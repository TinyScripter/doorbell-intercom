package de.kleinabersmart.doorbellintercom

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import de.kleinabersmart.doorbellintercom.ui.components.VideoLayer
import de.kleinabersmart.doorbellintercom.ui.components.inflateLivePlayerView
import de.kleinabersmart.doorbellintercom.ui.components.toggled
import de.kleinabersmart.doorbellintercom.ui.incomingcall.DoorAction
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallPhase
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallScreen
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallUiState
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Vollbild-Anrufansicht beim Klingeln: zeigt sofort das Live-Bild, aber
 * stumm. Nach "Annehmen" bleibt der Stream einfach weiterlaufen und wird nur
 * laut geschaltet (kein Neuaufbau, daher nahtlos); statt Annehmen/Ablehnen
 * erscheinen Sprechen, Tür öffnen und Auflegen.
 *
 * Die Ansicht folgt CallController.state: Klingeln, Gespräch oder – bei Idle – schließen
 * (abgelaufen, anderswo angenommen, in der Benachrichtigung abgelehnt). Die Buttons melden
 * nur an den Controller; was daraufhin zu sehen ist, ergibt sich aus dem neuen Zustand.
 *
 * Bei gesperrtem Handy verlangen "Annehmen" und "Tür öffnen + Auflegen" zuerst
 * die Entsperrung, damit die Tür nicht vom Sperrbildschirm aus geöffnet werden kann.
 * Beide Tür-Buttons blenden zunächst nur den Slider "Zum Öffnen schieben" ein
 * (SlideToConfirm); entsperrt wird erst nach dem Schieben.
 *
 * Die Oberfläche ist Compose (IncomingCallScreen); die Activity schreibt nur noch in
 * [uiState] statt in Views. Das Live-Bild bleibt ein PlayerView (siehe VideoLayer).
 */
class IncomingCallActivity : AppCompatActivity() {

    private val uiState = MutableStateFlow(IncomingCallUiState())
    private lateinit var intercom: IntercomControls
    private lateinit var liveView: LiveViewController
    private lateinit var calls: CallController

    /** Zuletzt angezeigter Zustand: nur ein Wechsel schaltet die Ansicht um (z. B. Sprechen beim Annehmen). */
    private var shown: CallState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        calls = appContainer.callController
        showStatus(getString(R.string.incoming_call_title))

        // Vor den Controllern erzeugt, die sofort eine echte Instanz brauchen; Compose zeigt ihn nur an.
        val playerView = inflateLivePlayerView(this)
        PinchZoomController.attach(playerView)

        // Display einschalten; über dem Sperrbildschirm nur bis zum Annehmen (siehe showRinging/showCall).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        }

        liveView = LiveViewController(
            activity = this,
            playerView = playerView,
            onStatus = ::showStatus,
            reportConnection = false,
            muted = true
        )
        intercom = IntercomControls(
            context = this,
            onStatus = ::showStatus,
            onTalkStatus = { message -> uiState.update { it.copy(talkStatusText = message) } },
            onTalkStateChanged = { talking ->
                uiState.update { it.copy(talking = talking, talkStatusText = if (talking) it.talkStatusText else "") }
            },
            onSpeakingChanged = liveView::setDucked
        )

        setContent {
            val state by uiState.collectAsStateWithLifecycle()
            DoorbellTheme {
                IncomingCallScreen(
                    state = state,
                    onAccept = ::acceptCall,
                    onDecline = { calls.decline(CallController.SOURCE_SCREEN) },
                    onDoorOpenAndHangUpClick = {
                        confirmDoor(
                            R.string.slide_to_open_and_hang_up,
                            R.string.btn_door_open_and_hang_up,
                            DoorAction.DoorOpenAndHangUp
                        )
                    },
                    onTalk = ::onTalkClick,
                    onDoorOpenClick = {
                        confirmDoor(R.string.slide_to_open, R.string.btn_door_open, DoorAction.DoorOpen)
                    },
                    onHangUp = { calls.hangUp() },
                    onDoorConfirmed = ::onDoorConfirmed,
                    onDoorDismiss = ::hideDoorSlider,
                    video = { VideoLayer(playerView) },
                )
            }
        }

        // Ab CREATED statt STARTED: auch ein Klingelbildschirm im Hintergrund soll sich nach
        // Ablauf schließen und damit den VPN-Tunnel freigeben. Der erste Wert kommt sofort.
        lifecycleScope.launch { calls.state.collect(::render) }

        acceptIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIfRequested(intent)
    }

    /** "Annehmen" in der Klingel-Benachrichtigung öffnet diesen Bildschirm mit EXTRA_ACCEPT. */
    private fun acceptIfRequested(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_ACCEPT, false) && calls.state.value is CallState.Ringing) acceptCall()
    }

    override fun onStart() {
        super.onStart()
        calls.onCallScreenStarted()
        liveView.start()
    }

    override fun onStop() {
        super.onStop()
        calls.onCallScreenStopped()
        intercom.stopTalk()
        hideDoorSlider()
        liveView.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Zurück oder Schließen aus anderem Grund: gilt als Ablehnen bzw. Auflegen.
        if (isFinishing) calls.leave(shown?.seq)
        liveView.releaseVpn()
    }

    private fun render(state: CallState) {
        val previous = shown
        shown = state
        when (state) {
            CallState.Idle -> finishAndRemoveTask()
            is CallState.Ringing -> if (previous !is CallState.Ringing || previous.seq != state.seq) showRinging()
            is CallState.Active -> {
                if (previous !is CallState.Active) showCall()
                liveView.voiceCall = state.systemCall
                if (state.held != ((previous as? CallState.Active)?.held ?: false)) showHeld(state.held)
                if (state.rangAgain) showStatus(getString(R.string.status_rang_again))
            }
        }
    }

    /** Auch nach einem liegen gelassenen Gespräch, wenn es erneut klingelt (siehe CallController.onCallScreenStarted). */
    private fun showRinging() {
        intercom.stopTalk()
        liveView.muted = true
        uiState.update {
            it.copy(
                phase = IncomingCallPhase.Ringing,
                statusText = getString(R.string.incoming_call_title),
                doorConfirm = null
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
    }

    private fun showCall() {
        liveView.muted = false
        // Ein noch offener Slider gehörte zu "Öffnen & Ende" vor dem Annehmen.
        uiState.update {
            it.copy(
                phase = IncomingCallPhase.Active,
                statusText = getString(R.string.status_connected),
                doorConfirm = null
            )
        }
        // Wie beim Telefon: nach dem Annehmen ist man sofort zu hören.
        intercom.startTalk()

        // Ab jetzt nicht mehr über dem Sperrbildschirm anzeigen: sperrt man
        // das Handy während des Gesprächs, ist "Tür öffnen" nicht mehr ohne
        // Entsperren erreichbar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
        }
    }

    private val isHeld: Boolean get() = (shown as? CallState.Active)?.held == true

    private fun onTalkClick() {
        if (isHeld) {
            // Gehalten (Telefonat): Tippen setzt fort und startet danach das Sprechen.
            talkAfterResume = true
            calls.requestResume()
        } else {
            intercom.toggleTalk()
        }
    }

    /** Nach dem Fortsetzen sofort sprechen: das Fortsetzen kam vom Tippen aufs Mikrofon. */
    private var talkAfterResume = false

    /** Das System hält den Anruf während eines Telefonats (TelecomBridge): Ton und Mikrofon aus. */
    private fun showHeld(held: Boolean) {
        liveView.muted = held
        if (held) {
            talkAfterResume = false
            intercom.stopTalk()
            showStatus(getString(R.string.status_call_held))
        } else {
            showStatus(getString(R.string.status_connected))
            if (talkAfterResume) intercom.startTalk()
            talkAfterResume = false
        }
    }

    /** Öffnet die Tür und legt auf; die Rückmeldung kommt als Toast, da die Ansicht gleich schließt. */
    private fun openDoorAndHangUp() {
        val appContext = applicationContext
        val url = AppConfig(appContext).doorOpenUrl
        if (url.isBlank()) {
            // Ohne Türöffner-URL nicht auflegen, sonst ginge die Fehlermeldung unter.
            showStatus(getString(R.string.status_door_open_not_configured))
            return
        }
        DoorOpenClient.trigger(appContext, url) { _, message ->
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
        // Gilt wie in der Benachrichtigung als angenommen: die anderen Handys hören auf zu klingeln.
        calls.doorOpened(CallController.SOURCE_SCREEN)
    }

    private fun acceptCall() = runUnlocked { calls.accept() }

    /** Tür-Aktionen erst nach dem Schieben; ein zweiter Tap auf denselben Button blendet den Slider wieder aus. */
    private fun confirmDoor(@StringRes label: Int, @StringRes action: Int, door: DoorAction) {
        val labelText = getString(label)
        val actionText = getString(action)
        uiState.update { it.copy(doorConfirm = it.doorConfirm.toggled(labelText, actionText), doorAction = door) }
    }

    /** Nach dem Annehmen ist das Handy entsperrt; runUnlocked sichert trotzdem ab. */
    private fun onDoorConfirmed(door: DoorAction) {
        when (door) {
            DoorAction.DoorOpen -> runUnlocked(intercom::openDoor)
            DoorAction.DoorOpenAndHangUp -> runUnlocked(::openDoorAndHangUp)
        }
    }

    private fun hideDoorSlider() {
        uiState.update { it.copy(doorConfirm = null) }
    }

    private fun showStatus(message: String) {
        uiState.update { it.copy(statusText = message) }
    }

    /**
     * Führt [action] erst aus, wenn das Handy entsperrt ist. Der Bildschirm erscheint auch
     * über dem Sperrbildschirm – Annehmen und Tür öffnen sollen dort nicht ohne Entsperren gehen.
     */
    private fun runUnlocked(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            action()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                action()
            }

            override fun onDismissCancelled() {
                showStatus(getString(R.string.status_unlock_cancelled))
            }
        })
    }

    companion object {
        /** Direkt annehmen (Button "Annehmen" in der Klingel-Benachrichtigung). */
        const val EXTRA_ACCEPT = "de.kleinabersmart.doorbellintercom.extra.ACCEPT"
    }
}
