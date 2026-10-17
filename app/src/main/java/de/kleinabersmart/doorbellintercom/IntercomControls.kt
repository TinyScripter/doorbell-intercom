package de.kleinabersmart.doorbellintercom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import de.kleinabersmart.doorbellintercom.baichuan.BaichuanTalkSession

/**
 * Gemeinsame Logik für "Sprechen" und "Tür öffnen", genutzt vom
 * Hauptbildschirm und vom Klingelbildschirm nach dem Annehmen.
 * Statusmeldungen und der Sprech-Zustand gehen über Callbacks an die
 * jeweilige Activity, die damit ihre eigenen Views aktualisiert. Meldungen
 * zum Sprechen/Mikrofon kommen getrennt über [onTalkStatus], damit sie
 * unabhängig vom Verbindungsstatus angezeigt werden können.
 */
class IntercomControls(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onTalkStatus: (String) -> Unit,
    private val onTalkStateChanged: (talking: Boolean) -> Unit
) {
    private var talkSession: BaichuanTalkSession? = null

    fun toggleTalk() {
        if (talkSession != null) {
            stopTalk()
        } else {
            startTalk()
        }
    }

    /** Startet das Sprechen; läuft es bereits, passiert nichts. */
    fun startTalk() {
        if (talkSession != null) return

        val config = AppConfig(context)
        if (config.cameraHost.isBlank()) {
            onTalkStatus(context.getString(R.string.status_camera_not_configured))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onTalkStatus(context.getString(R.string.status_mic_permission_needed))
            return
        }

        val session = BaichuanTalkSession(
            context = context,
            host = config.cameraHost,
            port = config.cameraBcPort,
            username = config.cameraUsername,
            password = config.cameraPassword,
            micGain = config.micGainPercent / 100f,
            onError = { message ->
                // Erst stoppen, dann melden – sonst leert die Activity beim
                // Stoppen die Anzeige und die Fehlermeldung wäre weg.
                stopTalk()
                onTalkStatus(message)
            },
            onStatus = onTalkStatus
        )
        session.start()
        talkSession = session
        onTalkStateChanged(true)
    }

    fun stopTalk() {
        val session = talkSession ?: return
        talkSession = null
        session.stop()
        onTalkStateChanged(false)
    }

    fun openDoor() {
        val config = AppConfig(context)
        if (config.doorOpenUrl.isBlank()) {
            onStatus(context.getString(R.string.status_door_open_not_configured))
            return
        }

        onStatus(context.getString(R.string.status_door_opening))
        DoorOpenClient.trigger(context, config.doorOpenUrl) { _, message -> onStatus(message) }
    }
}

/** Sprech-Button zeigt den Mikro-Zustand: heller Button = an, dunkler mit durchgestrichenem Mikrofon = aus. */
fun ImageButton.showTalkState(talking: Boolean) {
    setBackgroundResource(if (talking) R.drawable.bg_btn_mic_on else R.drawable.bg_btn_mic_off)
    setImageResource(if (talking) R.drawable.ic_mic else R.drawable.ic_mic_off)
    contentDescription = context.getString(
        if (talking) R.string.btn_talk_stop else R.string.btn_talk_start
    )
}
