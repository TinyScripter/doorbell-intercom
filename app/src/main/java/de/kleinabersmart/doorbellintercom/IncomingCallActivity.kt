package de.kleinabersmart.doorbellintercom

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import de.kleinabersmart.doorbellintercom.databinding.ActivityIncomingCallBinding

/**
 * Vollbild-Anrufansicht beim Klingeln: zeigt sofort das Live-Bild, aber
 * stumm. Nach "Annehmen" bleibt der Stream einfach weiterlaufen und wird nur
 * laut geschaltet (kein Neuaufbau, daher nahtlos); statt Annehmen/Ablehnen
 * erscheinen Sprechen, Tür öffnen und Auflegen.
 *
 * Bei gesperrtem Handy verlangt "Annehmen" zuerst die Entsperrung, damit
 * die Tür nicht vom Sperrbildschirm aus geöffnet werden kann.
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private lateinit var intercom: IntercomControls
    private var rtspClient: DirectRtspClient? = null
    private var accepted = false
    private var preparingConnection = false

    /**
     * Ein anderes Handy hat angenommen oder es wurde in der Benachrichtigung abgelehnt bzw. die
     * Tür geöffnet: nur schließen, solange man selbst noch nicht angenommen hat.
     */
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!accepted) finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.statusText.applyTopSystemInset()
        binding.bottomArea.applyBottomSystemInset()
        PinchZoomController.attach(binding.videoView)

        // Auch bei gesperrtem Bildschirm anzeigen und Display einschalten
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        intercom = IntercomControls(
            context = this,
            onStatus = { message -> binding.statusText.text = message },
            onTalkStatus = { message -> binding.talkStatusText.text = message },
            onTalkStateChanged = { talking ->
                binding.btnTalk.showTalkState(talking)
                if (!talking) binding.talkStatusText.text = ""
            }
        )

        ContextCompat.registerReceiver(this, closeReceiver,
            IntentFilter(IncomingCallNotifier.ACTION_CLOSE_INCOMING_CALL), ContextCompat.RECEIVER_NOT_EXPORTED)

        binding.btnAccept.setOnClickListener { acceptCall() }
        binding.btnDecline.setOnClickListener { finishAndRemoveTask() }
        binding.btnTalk.setOnClickListener { intercom.toggleTalk() }
        binding.btnDoorOpen.setOnClickListener { intercom.openDoor() }
        binding.btnHangUp.setOnClickListener { finishAndRemoveTask() }
        binding.btnDoorOpenAndHangUp.setOnClickListener { openDoorAndHangUp() }

        acceptIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIfRequested(intent)
    }

    /** "Annehmen" in der Klingel-Benachrichtigung öffnet diesen Bildschirm mit EXTRA_ACCEPT. */
    private fun acceptIfRequested(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_ACCEPT, false) && !accepted) acceptCall()
    }

    override fun onStart() {
        super.onStart()
        prepareConnectionAndStartLiveView()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(closeReceiver)
        IncomingCallNotifier.stopRinging(applicationContext)
        WireGuardTunnel.release(applicationContext, this)
    }

    /**
     * Baut außerhalb des Heimnetzes zuerst den VPN-Tunnel auf (siehe WireGuardTunnel),
     * bevor das Live-Bild startet – der Test auf die Kamera blockiert, daher im Hintergrund.
     */
    private fun prepareConnectionAndStartLiveView() {
        if (preparingConnection || rtspClient != null) return
        preparingConnection = true
        WireGuardTunnel.prepareAsync(
            owner = this,
            onStartingTunnel = { binding.statusText.text = getString(R.string.status_vpn_connecting) },
            onDone = {
                preparingConnection = false
                if (!isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startLiveView()
            }
        )
    }

    override fun onStop() {
        super.onStop()
        intercom.stopTalk()
        rtspClient?.stop()
        rtspClient = null
    }

    private fun startLiveView() {
        val config = AppConfig(this)
        if (config.cameraHost.isBlank() || rtspClient != null) return

        val rtspUrl = "rtsp://${Uri.encode(config.cameraUsername)}:${Uri.encode(config.cameraPassword)}" +
            "@${config.cameraHost}:554/${config.cameraRtspPath}"

        val client = DirectRtspClient(
            context = this,
            rtspUrl = rtspUrl,
            playerView = binding.videoView,
            keepAspectRatio = config.keepAspectRatio,
            // Nach dem Annehmen (z. B. wenn die Activity kurz im Hintergrund war)
            // mit Ton neu starten, vorher stumm.
            muted = !accepted,
            startBufferMs = config.videoStartBufferMs,
            rebufferMs = config.videoRebufferMs,
            onError = { message -> binding.statusText.text = message }
        )
        client.start()
        rtspClient = client
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
        finishAndRemoveTask()
    }

    private fun acceptCall() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    onCallAccepted()
                }

                override fun onDismissCancelled() {
                    binding.statusText.text = getString(R.string.status_unlock_cancelled)
                }
            })
        } else {
            onCallAccepted()
        }
    }

    private fun onCallAccepted() {
        accepted = true
        IncomingCallNotifier.stopRinging(applicationContext)
        PushRegistrar.reportAnswered(applicationContext)
        rtspClient?.setMuted(false)
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

    companion object {
        /** Direkt annehmen (Button "Annehmen" in der Klingel-Benachrichtigung). */
        const val EXTRA_ACCEPT = "de.kleinabersmart.doorbellintercom.extra.ACCEPT"
    }
}
