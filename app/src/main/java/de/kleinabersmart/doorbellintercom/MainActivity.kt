package de.kleinabersmart.doorbellintercom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import de.kleinabersmart.doorbellintercom.databinding.ActivityMainBinding

/**
 * Startbildschirm: zeigt direkt das Live-Bild und bietet die Gegensprechfunktion
 * an. Einstellungen sind über das Überlaufmenü (3 Punkte) oben rechts
 * erreichbar (siehe SettingsActivity).
 *
 * Video (inkl. Ton, falls die Kamera welchen über RTSP anbietet) kommt direkt
 * per RTSP von der Kamera (DirectRtspClient). Das Sprechen zur Klingel läuft
 * separat direkt per Baichuan-Protokoll (BaichuanTalkSession) – umgeht die
 * 3-5s-Latenz, die eine go2rtc/ffmpeg-Brücke dafür gehabt hätte.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var rtspClient: DirectRtspClient? = null
    private lateinit var intercom: IntercomControls
    private var preparingConnection = false
    private var openingSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.topBar.applyTopSystemInset()
        binding.bottomBar.applyBottomSystemInset()
        PinchZoomController.attach(binding.videoView)

        intercom = IntercomControls(
            context = this,
            onStatus = { message -> binding.statusText.text = message },
            onTalkStatus = { message -> binding.talkStatusText.text = message },
            onTalkStateChanged = { talking ->
                binding.btnTalk.showTalkState(talking)
                if (!talking) binding.talkStatusText.text = ""
            }
        )
        binding.btnTalk.setOnClickListener { intercom.toggleTalk() }
        binding.btnDoorOpen.setOnClickListener { intercom.openDoor() }

        requestRuntimePermissions()
        // Hintergrunddienst für den MQTT-Fallback, sofern in den Einstellungen aktiv.
        DoorbellForegroundService.startOrStop(this)
        // Beim Push-Verteiler anmelden und den Endpunkt an Home Assistant melden (falls Registrierungs-URL konfiguriert).
        PushRegistrar.start(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            // Der Tunnel bleibt beim Wechsel in die Einstellungen bestehen.
            openingSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        openingSettings = false
        prepareConnectionAndStartLiveView()
    }

    override fun onStop() {
        super.onStop()
        intercom.stopTalk()
        stopLiveView()
        if (!openingSettings) WireGuardTunnel.release(applicationContext, this)
    }

    /**
     * Baut außerhalb des Heimnetzes zuerst den VPN-Tunnel auf (siehe WireGuardTunnel),
     * bevor das Live-Bild startet.
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

    private fun startLiveView() {
        val config = AppConfig(this)
        binding.btnTalk.showTalkState(false)

        if (config.cameraHost.isBlank()) {
            binding.statusText.text = getString(R.string.status_camera_not_configured)
            return
        }
        if (rtspClient != null) return

        val rtspUrl = "rtsp://${Uri.encode(config.cameraUsername)}:${Uri.encode(config.cameraPassword)}" +
            "@${config.cameraHost}:554/${config.cameraRtspPath}"

        binding.statusText.text = getString(R.string.status_connecting)
        val client = DirectRtspClient(
            context = this,
            rtspUrl = rtspUrl,
            playerView = binding.videoView,
            keepAspectRatio = config.keepAspectRatio,
            startBufferMs = config.videoStartBufferMs,
            rebufferMs = config.videoRebufferMs,
            onConnected = { binding.statusText.text = getString(R.string.status_connected) },
            onError = { message -> binding.statusText.text = message }
        )
        client.start()
        rtspClient = client
    }

    private fun stopLiveView() {
        rtspClient?.stop()
        rtspClient = null
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Nach einem Einstellungs-Import (z. B. in eine neu installierte Variante) ist Auto-VPN
        // schon an, die Berechtigung aber nie erfragt worden – ohne sie bleibt der Tunnel still aus.
        if (WireGuardTunnel.isAutoVpnConfigured(AppConfig(this))) {
            needed.add(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 200)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            prepareConnectionAndStartLiveView()
        }
    }
}
