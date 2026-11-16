package de.kleinabersmart.doorbellintercom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import de.kleinabersmart.doorbellintercom.ui.settings.CameraSettingsScreen
import de.kleinabersmart.doorbellintercom.ui.settings.CameraSettingsUiState

/**
 * Einstellungen: Kamera (Adresse, Zugangsdaten, Video, Mikrofon).
 *
 * Die Oberfläche ist Compose (CameraSettingsScreen). Der Zustand liegt als Snapshot-State in der
 * Activity, damit [saveFieldsIntoConfig] ihn jederzeit synchron lesen kann (direkt nach onCreate,
 * in onPause und in finish), unabhängig davon, ob schon gezeichnet wurde.
 */
class CameraSettingsActivity : SettingsSectionActivity() {

    private val uiState = mutableStateOf(CameraSettingsUiState())

    override val titleRes = R.string.section_camera

    override fun createContent(): @Composable () -> Unit {
        uiState.value = loadFromConfig()
        return {
            CameraSettingsScreen(
                state = uiState.value,
                onStateChange = { uiState.value = it },
                onNavigateUp = ::finish,
            )
        }
    }

    private fun loadFromConfig(): CameraSettingsUiState {
        // Auf die Schrittweiten der Regler (100 %, 50 bzw. 100 ms) runden und begrenzen.
        val micGain = ((config.micGainPercent + 50) / 100 * 100).coerceIn(100, 2000)
        val startBuffer = ((config.videoStartBufferMs + 25) / 50 * 50)
            .coerceIn(AppConfig.VIDEO_START_BUFFER_MIN_MS, AppConfig.VIDEO_START_BUFFER_MAX_MS)
        val rebuffer = ((config.videoRebufferMs + 50) / 100 * 100)
            .coerceIn(AppConfig.VIDEO_REBUFFER_MIN_MS, AppConfig.VIDEO_REBUFFER_MAX_MS)
        return CameraSettingsUiState(
            host = config.cameraHost,
            bcPort = config.cameraBcPort.toString(),
            user = config.cameraUsername,
            password = config.cameraPassword,
            rtspPath = config.cameraRtspPath,
            keepAspectRatio = config.keepAspectRatio,
            micGainPercent = micGain,
            duckWhileTalking = config.duckWhileTalking,
            videoStartBufferMs = startBuffer,
            videoRebufferMs = rebuffer,
        )
    }

    override fun saveFieldsIntoConfig() {
        val state = uiState.value
        config.cameraHost = state.host.trim()
        config.cameraBcPort = state.bcPort.toIntOrNull() ?: 9000
        config.cameraUsername = state.user.trim()
        config.cameraPassword = state.password
        config.cameraRtspPath = state.rtspPath.trim().trimStart('/')
        config.keepAspectRatio = state.keepAspectRatio
        config.micGainPercent = state.micGainPercent
        config.duckWhileTalking = state.duckWhileTalking
        config.videoStartBufferMs = state.videoStartBufferMs
        config.videoRebufferMs = state.videoRebufferMs
    }
}
