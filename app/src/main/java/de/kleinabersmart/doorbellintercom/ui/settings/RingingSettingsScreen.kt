package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * Werte der Klingel-Seite. Der gewählte Klingelton selbst (Uri) bleibt in der Activity, die
 * Seite zeigt nur seinen Titel ([ringtoneTitle], von der Activity über RingtoneManager ermittelt).
 */
@Immutable
data class RingingSettingsUiState(
    val ringDurationSeconds: Int = RING_DURATION_RANGE.first,
    val ringtoneTitle: String = "",
    val ringInSilentMode: Boolean = false,
    val telecomCalls: Boolean = false,
) {
    companion object {
        // Bereich und Schritt des früheren Sliders (settings_ringing.xml).
        val RING_DURATION_RANGE = 10..90
        const val RING_DURATION_STEP = 5
    }
}

/**
 * Einstellungen: Klingel (vorher settings_ringing.xml): Klingeldauer, Klingelton, Klingeln im
 * Lautlos-Modus und die experimentelle Einbindung als Anruf (TelecomBridge). Zustandslos; die
 * Klingelton-Auswahl des Systems öffnet die Activity ([onChooseRingtone]).
 */
@Composable
fun RingingSettingsScreen(
    state: RingingSettingsUiState,
    onStateChange: (RingingSettingsUiState) -> Unit,
    onChooseRingtone: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.section_ringing),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) {
        SettingsCard {
            SettingsSliderRow(
                label = stringResource(R.string.label_ring_duration, state.ringDurationSeconds),
                value = state.ringDurationSeconds,
                valueRange = RingingSettingsUiState.RING_DURATION_RANGE,
                step = RingingSettingsUiState.RING_DURATION_STEP,
                onValueChange = { onStateChange(state.copy(ringDurationSeconds = it)) },
                labelModifier = Modifier.testTag(SettingsTestTags.LABEL_RING_DURATION),
                sliderModifier = Modifier.testTag(SettingsTestTags.SLIDER_RING_DURATION),
            )
            SettingsText(
                text = stringResource(R.string.label_ringtone, state.ringtoneTitle),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(SettingsTestTags.LABEL_RINGTONE),
            )
            SettingsOutlinedButton(
                text = stringResource(R.string.btn_choose_ringtone),
                onClick = onChooseRingtone,
                modifier = Modifier.testTag(SettingsTestTags.BTN_CHOOSE_RINGTONE),
            )
            SettingsSwitchRow(
                text = stringResource(R.string.switch_ring_in_silent_mode),
                checked = state.ringInSilentMode,
                onCheckedChange = { onStateChange(state.copy(ringInSilentMode = it)) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(SettingsTestTags.SWITCH_RING_IN_SILENT_MODE),
            )
            // Experimentell: TelecomBridge
            SettingsSwitchRow(
                text = stringResource(R.string.switch_telecom_calls),
                checked = state.telecomCalls,
                onCheckedChange = { onStateChange(state.copy(telecomCalls = it)) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(SettingsTestTags.SWITCH_TELECOM_CALLS),
            )
            SettingsSummary(
                text = stringResource(R.string.switch_telecom_calls_summary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.TELECOM_CALLS_SUMMARY),
            )
        }
    }
}

@Composable
private fun RingingSettingsPreviewHost(initial: RingingSettingsUiState) {
    var state by remember { mutableStateOf(initial) }
    DoorbellTheme {
        RingingSettingsScreen(state = state, onStateChange = { state = it }, onChooseRingtone = {}, onNavigateUp = {})
    }
}

private val PreviewRingingState = RingingSettingsUiState(
    ringDurationSeconds = 45,
    ringtoneTitle = "Flutey Phone",
    ringInSilentMode = true,
    telecomCalls = false,
)

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun RingingSettingsScreenPreview() {
    RingingSettingsPreviewHost(PreviewRingingState)
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RingingSettingsScreenDarkPreview() {
    RingingSettingsPreviewHost(PreviewRingingState)
}
