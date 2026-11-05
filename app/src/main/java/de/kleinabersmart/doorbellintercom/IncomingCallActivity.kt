package de.kleinabersmart.doorbellintercom

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import de.kleinabersmart.doorbellintercom.databinding.ActivityIncomingCallBinding
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
 * (SlideToConfirmView); entsperrt wird erst nach dem Schieben.
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private lateinit var intercom: IntercomControls
    private lateinit var liveView: LiveViewController
    private lateinit var calls: CallController

    /** Zuletzt angezeigter Zustand: nur ein Wechsel schaltet die Ansicht um (z. B. Sprechen beim Annehmen). */
    private var shown: CallState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        calls = appContainer.callController

        binding.statusText.applyTopSystemInset()
        binding.bottomArea.applyBottomSystemInset()
        PinchZoomController.attach(binding.videoView)

        // Display einschalten; über dem Sperrbildschirm nur bis zum Annehmen (siehe showRinging/showCall).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        }

        liveView = LiveViewController(
            activity = this,
            playerView = binding.videoView,
            onStatus = { message -> binding.statusText.text = message },
            reportConnection = false,
            muted = true
        )
        intercom = IntercomControls(
            context = this,
            onStatus = { message -> binding.statusText.text = message },
            onTalkStatus = { message -> binding.talkStatusText.text = message },
            onTalkStateChanged = { talking ->
                binding.btnTalk.showTalkState(talking)
                if (!talking) binding.talkStatusText.text = ""
            },
            onSpeakingChanged = liveView::setDucked
        )

        binding.btnAccept.setOnClickListener { acceptCall() }
        binding.btnDecline.setOnClickListener { calls.decline(CallController.SOURCE_SCREEN) }
        binding.btnTalk.setOnClickListener {
            if (isHeld) {
                // Gehalten (Telefonat): Tippen setzt fort und startet danach das Sprechen.
                talkAfterResume = true
                calls.requestResume()
            } else {
                intercom.toggleTalk()
            }
        }
        // Nach dem Annehmen ist das Handy entsperrt; runUnlocked sichert trotzdem ab.
        binding.btnDoorOpen.setOnClickListener {
            confirmDoor(R.string.slide_to_open, R.string.btn_door_open) { runUnlocked(intercom::openDoor) }
        }
        binding.btnHangUp.setOnClickListener { calls.hangUp() }
        binding.btnDoorOpenAndHangUp.setOnClickListener {
            confirmDoor(R.string.slide_to_open_and_hang_up, R.string.btn_door_open_and_hang_up) {
                runUnlocked(::openDoorAndHangUp)
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
        binding.doorSlider.hide()
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
                if (state.rangAgain) binding.statusText.text = getString(R.string.status_rang_again)
            }
        }
    }

    /** Auch nach einem liegen gelassenen Gespräch, wenn es erneut klingelt (siehe CallController.onCallScreenStarted). */
    private fun showRinging() {
        intercom.stopTalk()
        binding.doorSlider.hide()
        liveView.muted = true
        binding.statusText.text = getString(R.string.incoming_call_title)
        binding.buttonBar.visibility = View.VISIBLE
        binding.callControls.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
    }

    private fun showCall() {
        // Ein noch offener Slider gehörte zu "Öffnen & Ende" vor dem Annehmen.
        binding.doorSlider.hide()
        liveView.muted = false
        binding.statusText.text = getString(R.string.status_connected)
        binding.buttonBar.visibility = View.GONE
        binding.callControls.visibility = View.VISIBLE
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

    /** Nach dem Fortsetzen sofort sprechen: das Fortsetzen kam vom Tippen aufs Mikrofon. */
    private var talkAfterResume = false

    /** Das System hält den Anruf während eines Telefonats (TelecomBridge): Ton und Mikrofon aus. */
    private fun showHeld(held: Boolean) {
        liveView.muted = held
        if (held) {
            talkAfterResume = false
            intercom.stopTalk()
            binding.statusText.text = getString(R.string.status_call_held)
        } else {
            binding.statusText.text = getString(R.string.status_connected)
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
            binding.statusText.text = getString(R.string.status_door_open_not_configured)
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
    private fun confirmDoor(@StringRes label: Int, @StringRes action: Int, onConfirmed: () -> Unit) {
        binding.doorSlider.toggle(getString(label), getString(action), onConfirmed)
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
                binding.statusText.text = getString(R.string.status_unlock_cancelled)
            }
        })
    }

    companion object {
        /** Direkt annehmen (Button "Annehmen" in der Klingel-Benachrichtigung). */
        const val EXTRA_ACCEPT = "de.kleinabersmart.doorbellintercom.extra.ACCEPT"
    }
}
