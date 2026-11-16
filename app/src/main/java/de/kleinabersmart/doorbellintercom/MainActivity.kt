package de.kleinabersmart.doorbellintercom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.doorbellintercom.ui.components.VideoLayer
import de.kleinabersmart.doorbellintercom.ui.components.inflateLivePlayerView
import de.kleinabersmart.doorbellintercom.ui.components.toggled
import de.kleinabersmart.doorbellintercom.ui.main.MainScreen
import de.kleinabersmart.doorbellintercom.ui.main.MainUiState
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Startbildschirm: zeigt direkt das Live-Bild und bietet die Gegensprechfunktion
 * an. Einstellungen sind über das Symbol (3 Punkte) oben rechts erreichbar
 * (siehe SettingsActivity).
 *
 * Video (inkl. Ton, falls die Kamera welchen über RTSP anbietet) kommt direkt
 * per RTSP von der Kamera (DirectRtspClient). Das Sprechen zur Klingel läuft
 * separat direkt per Baichuan-Protokoll (BaichuanTalkSession) – umgeht die
 * 3-5s-Latenz, die eine go2rtc/ffmpeg-Brücke dafür gehabt hätte.
 *
 * Die Oberfläche ist Compose (MainScreen); die Activity schreibt nur noch in [uiState]
 * statt in Views. Das Live-Bild bleibt ein PlayerView (siehe VideoLayer).
 */
class MainActivity : AppCompatActivity() {

    private val uiState = MutableStateFlow(MainUiState())
    private lateinit var liveView: LiveViewController
    private lateinit var intercom: IntercomControls
    private var openingSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vor den Controllern erzeugt, die sofort eine echte Instanz brauchen; Compose zeigt ihn nur an.
        val playerView = inflateLivePlayerView(this)
        PinchZoomController.attach(playerView)

        liveView = LiveViewController(
            activity = this,
            playerView = playerView,
            onStatus = ::showStatus,
            reportConnection = true
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
                MainScreen(
                    state = state,
                    onToggleTalk = { intercom.toggleTalk() },
                    // Erst schieben, dann öffnen: ein versehentlicher Tap soll die Tür nicht öffnen.
                    onDoorOpenClick = ::toggleDoorSlider,
                    onDoorConfirmed = { intercom.openDoor() },
                    onDoorDismiss = ::hideDoorSlider,
                    onOpenSettings = ::openSettings,
                    video = { VideoLayer(playerView) },
                )
            }
        }

        requestRuntimePermissions()
        // Hintergrunddienst für den MQTT-Fallback, sofern in den Einstellungen aktiv.
        DoorbellForegroundService.startOrStop(this)
        // Beim Push-Verteiler anmelden und den Endpunkt an Home Assistant melden (falls Registrierungs-URL konfiguriert).
        PushRegistrar.start(this)
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
        val hasIssues = SystemCheck.issues(this).isNotEmpty()
        uiState.update {
            if (hasIssues) {
                it.copy(showSystemCheckHint = true, systemCheckHintRequest = it.systemCheckHintRequest + 1)
            } else {
                it.copy(showSystemCheckHint = false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        openingSettings = false
        uiState.update { it.copy(talking = false) }
        liveView.start()
    }

    override fun onStop() {
        super.onStop()
        intercom.stopTalk()
        hideDoorSlider()
        liveView.stop()
        if (!openingSettings) liveView.releaseVpn()
    }

    /** Ein zweiter Tap auf "Tür öffnen" blendet den Slider wieder aus. */
    private fun toggleDoorSlider() {
        val label = getString(R.string.slide_to_open)
        val action = getString(R.string.btn_door_open)
        uiState.update { it.copy(doorConfirm = it.doorConfirm.toggled(label, action)) }
    }

    private fun hideDoorSlider() {
        uiState.update { it.copy(doorConfirm = null) }
    }

    private fun showStatus(message: String) {
        uiState.update { it.copy(statusText = message) }
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
}
