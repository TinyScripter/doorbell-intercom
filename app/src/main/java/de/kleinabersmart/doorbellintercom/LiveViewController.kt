package de.kleinabersmart.doorbellintercom

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Live-Bild für Haupt- und Klingelbildschirm: baut außerhalb des Heimnetzes zuerst den
 * VPN-Tunnel auf (siehe WireGuardTunnel), danach den RTSP-Stream (DirectRtspClient).
 *
 * [start] gehört in onStart, [stop] in onStop. Den Tunnel gibt erst [releaseVpn] frei – der
 * Hauptbildschirm behält ihn beim Wechsel in die Einstellungen, der Klingelbildschirm bis
 * zum Schließen.
 */
class LiveViewController(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val onStatus: (String) -> Unit,
    /** Ob „Verbinde…“, „Verbunden“ und „Kamera nicht eingerichtet“ gemeldet werden; der Klingelbildschirm zeigt stattdessen „Es klingelt“. */
    private val reportConnection: Boolean,
    muted: Boolean = false
) {
    /** Eigenes Token statt der Activity: WireGuardTunnel hält es, solange der Tunnel gebraucht wird. */
    private val vpnOwner = Any()
    private var client: DirectRtspClient? = null
    private var job: Job? = null

    /** Ton des Live-Bilds; gilt auch für einen erst später gestarteten Stream. */
    var muted: Boolean = muted
        set(value) {
            field = value
            client?.setMuted(value)
        }

    /** Ton als Sprachanruf (siehe DirectRtspClient.setVoiceCall); gilt auch für einen später gestarteten Stream. */
    var voiceCall: Boolean = false
        set(value) {
            field = value
            client?.setVoiceCall(value)
        }

    fun start() {
        if (job?.isActive == true || client != null) return
        // Abbruch in stop(): Wer den Bildschirm während des Tunnel-Aufbaus verlässt, startet keinen Stream mehr.
        job = activity.lifecycleScope.launch {
            WireGuardTunnel.prepare(activity.applicationContext, vpnOwner) {
                onStatus(activity.getString(R.string.status_vpn_connecting))
            }
            startStream()
        }
    }

    private fun startStream() {
        val config = AppConfig(activity)
        if (config.cameraHost.isBlank()) {
            if (reportConnection) onStatus(activity.getString(R.string.status_camera_not_configured))
            return
        }
        if (reportConnection) onStatus(activity.getString(R.string.status_connecting))
        client = DirectRtspClient(
            context = activity,
            rtspUrl = config.rtspUrl(),
            playerView = playerView,
            keepAspectRatio = config.keepAspectRatio,
            muted = muted,
            startBufferMs = config.videoStartBufferMs,
            rebufferMs = config.videoRebufferMs,
            onConnected = { if (reportConnection) onStatus(activity.getString(R.string.status_connected)) },
            onError = onStatus
        ).also {
            it.start()
            it.setVoiceCall(voiceCall)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        client?.stop()
        client = null
    }

    /** Dämpft den Ton, während man ins Mikrofon spricht (siehe IntercomControls). */
    fun setDucked(ducked: Boolean) {
        client?.setDucked(ducked)
    }

    fun releaseVpn() {
        WireGuardTunnel.release(activity.applicationContext, vpnOwner)
    }
}
