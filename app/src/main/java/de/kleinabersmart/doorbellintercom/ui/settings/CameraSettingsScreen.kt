package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * Werte der Kamera-Seite, wie sie in den Feldern stehen (Port als Text, geparst wird erst beim
 * Speichern). Die Regler-Werte liegen schon auf ihren Schritten; das Runden übernimmt die
 * Activity beim Laden.
 */
@Immutable
data class CameraSettingsUiState(
    val host: String = "",
    val bcPort: String = "",
    val user: String = "",
    val password: String = "",
    val rtspPath: String = "",
    val keepAspectRatio: Boolean = false,
    val micGainPercent: Int = MIC_GAIN_RANGE.first,
    val duckWhileTalking: Boolean = false,
    val videoStartBufferMs: Int = VIDEO_START_BUFFER_RANGE.first,
    val videoRebufferMs: Int = VIDEO_REBUFFER_RANGE.first,
) {
    companion object {
        // Bereiche und Schritte der früheren Slider (settings_camera.xml).
        val MIC_GAIN_RANGE = 100..2000
        const val MIC_GAIN_STEP = 100
        val VIDEO_START_BUFFER_RANGE = 50..1000
        const val VIDEO_START_BUFFER_STEP = 50
        val VIDEO_REBUFFER_RANGE = 100..2000
        const val VIDEO_REBUFFER_STEP = 100
    }
}

/**
 * Einstellungen: Kamera (vorher settings_camera.xml): Adresse, Zugangsdaten und RTSP-Pfad,
 * Seitenverhältnis, Mikrofon-Lautstärke und Video-Puffer. Zustandslos; jede Änderung geht als
 * neuer Zustand an [onStateChange], gespeichert wird beim Verlassen der Seite in der Activity.
 *
 * "Weiter" auf der Tastatur springt der Reihe nach durch die fünf Textfelder, "Fertig" im
 * letzten schließt die Tastatur (wie bisher bei den EditTexts).
 */
@Composable
fun CameraSettingsScreen(
    state: CameraSettingsUiState,
    onStateChange: (CameraSettingsUiState) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusChain = rememberFocusChain(CAMERA_FIELD_COUNT)
    SettingsScaffold(
        title = stringResource(R.string.section_camera),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) {
        SettingsCard {
            SettingsTextField(
                value = state.host,
                onValueChange = { onStateChange(state.copy(host = it)) },
                hint = stringResource(R.string.hint_camera_host),
                type = SettingsFieldType.Uri,
                onImeAction = { focusChain[1].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[0])
                    .testTag(SettingsTestTags.FIELD_CAMERA_HOST),
            )
            SettingsTextField(
                value = state.bcPort,
                onValueChange = { onStateChange(state.copy(bcPort = it)) },
                hint = stringResource(R.string.hint_camera_bc_port),
                type = SettingsFieldType.Number,
                onImeAction = { focusChain[2].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[1])
                    .testTag(SettingsTestTags.FIELD_CAMERA_BC_PORT),
            )
            SettingsTextField(
                value = state.user,
                onValueChange = { onStateChange(state.copy(user = it)) },
                hint = stringResource(R.string.hint_camera_user),
                onImeAction = { focusChain[3].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[2])
                    .testTag(SettingsTestTags.FIELD_CAMERA_USER),
            )
            SettingsTextField(
                value = state.password,
                onValueChange = { onStateChange(state.copy(password = it)) },
                hint = stringResource(R.string.hint_camera_pass),
                type = SettingsFieldType.Password,
                onImeAction = { focusChain[4].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[3])
                    .testTag(SettingsTestTags.FIELD_CAMERA_PASS),
            )
            SettingsTextField(
                value = state.rtspPath,
                onValueChange = { onStateChange(state.copy(rtspPath = it)) },
                hint = stringResource(R.string.hint_camera_rtsp_path),
                imeAction = ImeAction.Done,
                modifier = Modifier
                    .focusRequester(focusChain[4])
                    .testTag(SettingsTestTags.FIELD_CAMERA_RTSP_PATH),
            )
            SettingsCheckboxRow(
                text = stringResource(R.string.check_keep_aspect_ratio),
                checked = state.keepAspectRatio,
                onCheckedChange = { onStateChange(state.copy(keepAspectRatio = it)) },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag(SettingsTestTags.CHECK_KEEP_ASPECT_RATIO),
            )
            SettingsSliderRow(
                label = stringResource(R.string.label_mic_gain, state.micGainPercent),
                value = state.micGainPercent,
                valueRange = CameraSettingsUiState.MIC_GAIN_RANGE,
                step = CameraSettingsUiState.MIC_GAIN_STEP,
                onValueChange = { onStateChange(state.copy(micGainPercent = it)) },
                modifier = Modifier.padding(top = 12.dp),
                labelModifier = Modifier.testTag(SettingsTestTags.LABEL_MIC_GAIN),
                sliderModifier = Modifier.testTag(SettingsTestTags.SLIDER_MIC_GAIN),
            )
            SettingsCheckboxRow(
                text = stringResource(R.string.check_duck_while_talking),
                checked = state.duckWhileTalking,
                onCheckedChange = { onStateChange(state.copy(duckWhileTalking = it)) },
                modifier = Modifier.testTag(SettingsTestTags.CHECK_DUCK_WHILE_TALKING),
            )
            SettingsSliderRow(
                label = stringResource(R.string.label_video_start_buffer, state.videoStartBufferMs),
                value = state.videoStartBufferMs,
                valueRange = CameraSettingsUiState.VIDEO_START_BUFFER_RANGE,
                step = CameraSettingsUiState.VIDEO_START_BUFFER_STEP,
                onValueChange = { onStateChange(state.copy(videoStartBufferMs = it)) },
                modifier = Modifier.padding(top = 12.dp),
                labelModifier = Modifier.testTag(SettingsTestTags.LABEL_VIDEO_START_BUFFER),
                sliderModifier = Modifier.testTag(SettingsTestTags.SLIDER_VIDEO_START_BUFFER),
            )
            SettingsSliderRow(
                label = stringResource(R.string.label_video_rebuffer, state.videoRebufferMs),
                value = state.videoRebufferMs,
                valueRange = CameraSettingsUiState.VIDEO_REBUFFER_RANGE,
                step = CameraSettingsUiState.VIDEO_REBUFFER_STEP,
                onValueChange = { onStateChange(state.copy(videoRebufferMs = it)) },
                modifier = Modifier.padding(top = 12.dp),
                labelModifier = Modifier.testTag(SettingsTestTags.LABEL_VIDEO_REBUFFER),
                sliderModifier = Modifier.testTag(SettingsTestTags.SLIDER_VIDEO_REBUFFER),
            )
        }
    }
}

/** Host, Port, Benutzer, Passwort, RTSP-Pfad. */
private const val CAMERA_FIELD_COUNT = 5

private val PreviewCameraState = CameraSettingsUiState(
    host = "192.168.178.40",
    bcPort = "9000",
    user = "admin",
    password = "doorbell-secret",
    rtspPath = "h264Preview_01_main",
    keepAspectRatio = true,
    micGainPercent = 300,
    duckWhileTalking = true,
    videoStartBufferMs = 150,
    videoRebufferMs = 400,
)

@Composable
private fun CameraSettingsPreviewHost(initial: CameraSettingsUiState) {
    var state by remember { mutableStateOf(initial) }
    DoorbellTheme {
        CameraSettingsScreen(state = state, onStateChange = { state = it }, onNavigateUp = {})
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun CameraSettingsScreenPreview() {
    CameraSettingsPreviewHost(PreviewCameraState)
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun CameraSettingsScreenEmptyPreview() {
    // Frisch installiert: nur der Standard-Port, alle Hinweise sichtbar.
    CameraSettingsPreviewHost(CameraSettingsUiState(bcPort = "9000"))
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CameraSettingsScreenDarkPreview() {
    CameraSettingsPreviewHost(PreviewCameraState)
}
