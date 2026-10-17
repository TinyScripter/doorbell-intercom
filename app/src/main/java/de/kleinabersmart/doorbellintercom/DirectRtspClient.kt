package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Video (und, falls vorhanden, Ton) direkt per RTSP von der Kamera – nutzt
 * Googles offizielles ExoPlayer-RTSP-Modul (offener Standard, kein
 * Reverse-Engineering wie beim Baichuan-Protokoll). Kein go2rtc mehr nötig.
 *
 * Bei einem Live-Stream entspricht der Pufferinhalt der Verzögerung zum Live-Bild.
 * Wächst er (z. B. nach einem Aussetzer), spielt der Player etwas schneller ab,
 * bis er wieder beim Startpuffer angekommen ist (siehe catchUp).
 */
@OptIn(UnstableApi::class) // DefaultLoadControl, RtspMediaSource, PlayerView-Einstellungen
class DirectRtspClient(
    private val context: Context,
    private val rtspUrl: String,
    private val playerView: PlayerView,
    private val keepAspectRatio: Boolean = true,
    private val muted: Boolean = false,
    private val startBufferMs: Int = AppConfig.VIDEO_START_BUFFER_DEFAULT_MS,
    private val rebufferMs: Int = AppConfig.VIDEO_REBUFFER_DEFAULT_MS,
    private val onConnected: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSpeed = 1f

    private val catchUpRunnable = object : Runnable {
        override fun run() {
            catchUp()
            mainHandler.postDelayed(this, CATCH_UP_INTERVAL_MS)
        }
    }

    fun start() {
        // Standard-LoadControl puffert 2,5 s vor dem Start – bei RTSP gibt es
        // keinen Live-Offset, der das begrenzt, und der Rückstand bleibt dauerhaft.
        // Min- und Max-Puffer sind bewusst groß: Sie begrenzen nur das Nachladen, und
        // ein pausiertes Nachladen würde die Daten im Socket stauen statt im Player.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ LOAD_BUFFER_MS,
                /* maxBufferMs = */ LOAD_BUFFER_MS,
                /* bufferForPlaybackMs = */ startBufferMs,
                /* bufferForPlaybackAfterRebufferMs = */ rebufferMs
            )
            .build()
        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
        exoPlayer = player
        // Stumm: Ton wird zwar mitgeladen, aber nicht ausgegeben (z. B. im
        // Klingelbildschirm, bevor der Anruf angenommen wurde).
        if (muted) player.volume = 0f

        playerView.useController = false
        playerView.resizeMode = if (keepAspectRatio) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    mainHandler.post { onConnected() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                mainHandler.post {
                    onError(context.getString(R.string.status_rtsp_error, error.errorCodeName, error.message))
                }
            }
        })

        val mediaSource = RtspMediaSource.Factory()
            // TCP-interleaved statt UDP: robuster im LAN, kein separates Öffnen
            // von RTP-Ports/UDP-Firewall-Themen nötig.
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(rtspUrl))

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        mainHandler.postDelayed(catchUpRunnable, CATCH_UP_INTERVAL_MS)
    }

    /**
     * Holt Rückstand zum Live-Bild durch leicht beschleunigte Wiedergabe auf (Tonhöhe
     * bleibt gleich). Hysterese, damit nicht ständig zwischen den Geschwindigkeiten
     * gewechselt wird: beschleunigen ab CATCH_UP_START_MS über dem Ziel, zurück auf
     * normal erst, wenn das Ziel bis auf CATCH_UP_STOP_MS erreicht ist.
     */
    private fun catchUp() {
        val player = exoPlayer ?: return
        if (player.playbackState != Player.STATE_READY || !player.isPlaying) return
        val target = maxOf(startBufferMs.toLong(), MIN_TARGET_BUFFER_MS)
        val lag = player.totalBufferedDuration - target
        val speed = when {
            lag > CATCH_UP_FAST_MS -> CATCH_UP_SPEED_FAST
            lag > CATCH_UP_START_MS -> CATCH_UP_SPEED
            lag <= CATCH_UP_STOP_MS -> 1f
            else -> currentSpeed
        }
        if (speed != currentSpeed) {
            Log.d(TAG, "Puffer ${player.totalBufferedDuration} ms (Ziel $target ms) → Geschwindigkeit $speed")
            currentSpeed = speed
            player.playbackParameters = PlaybackParameters(speed)
        }
    }

    /** Ton ein-/ausschalten, ohne die Verbindung neu aufzubauen. */
    fun setMuted(muted: Boolean) {
        exoPlayer?.volume = if (muted) 0f else 1f
    }

    fun stop() {
        mainHandler.removeCallbacks(catchUpRunnable)
        playerView.player = null
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        private const val TAG = "DirectRtspClient"
        private const val LOAD_BUFFER_MS = 5000
        private const val CATCH_UP_INTERVAL_MS = 500L
        // Etwas Restpuffer gegen Ruckler, auch wenn der Startpuffer sehr klein ist.
        private const val MIN_TARGET_BUFFER_MS = 100L
        private const val CATCH_UP_START_MS = 250L
        private const val CATCH_UP_STOP_MS = 50L
        private const val CATCH_UP_FAST_MS = 1000L
        private const val CATCH_UP_SPEED = 1.1f
        private const val CATCH_UP_SPEED_FAST = 1.25f
    }
}
