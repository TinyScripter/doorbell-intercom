package de.kleinabersmart.doorbellintercom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
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
    private lateinit var liveView: LiveViewController
    private lateinit var intercom: IntercomControls
    private var openingSettings = false
    private var systemCheckSnackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.topBar.applyTopSystemInset()
        binding.bottomBar.applyBottomSystemInset()
        PinchZoomController.attach(binding.videoView)

        liveView = LiveViewController(
            activity = this,
            playerView = binding.videoView,
            onStatus = { message -> binding.statusText.text = message },
            reportConnection = true
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
            openSettings()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openSettings() {
        // Der Tunnel bleibt beim Wechsel in die Einstellungen bestehen.
        openingSettings = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        // In onResume, damit auch die Antwort auf die Berechtigungsabfrage berücksichtigt wird.
        showSystemCheckHint()
    }

    /** Fehlt eine Voraussetzung fürs Klingeln, ein Hinweis mit Weg in die Einstellungen (siehe SystemCheck). */
    private fun showSystemCheckHint() {
        if (SystemCheck.issues(this).isEmpty()) {
            systemCheckSnackbar?.dismiss()
            systemCheckSnackbar = null
            return
        }
        if (systemCheckSnackbar?.isShownOrQueued == true) return
        // Nicht dauerhaft: es soll das Live-Bild nicht verdecken, kommt aber bei jedem Öffnen wieder.
        systemCheckSnackbar = Snackbar.make(binding.root, R.string.check_snackbar, SYSTEM_CHECK_HINT_MS)
            .setAnchorView(binding.bottomBar)
            .setAction(R.string.btn_check) { openSettings() }
            .also { it.show() }
    }

    override fun onStart() {
        super.onStart()
        openingSettings = false
        binding.btnTalk.showTalkState(false)
        liveView.start()
    }

    override fun onStop() {
        super.onStop()
        intercom.stopTalk()
        liveView.stop()
        if (!openingSettings) liveView.releaseVpn()
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
            // Läuft das Live-Bild schon, passiert nichts.
            liveView.start()
        }
    }

    private companion object {
        const val SYSTEM_CHECK_HINT_MS = 8000
    }
}
