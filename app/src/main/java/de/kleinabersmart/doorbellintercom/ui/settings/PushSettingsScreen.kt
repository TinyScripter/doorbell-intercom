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

/** Werte der Push-Seite, wie sie in den Feldern stehen (Port als Text, geparst wird beim Speichern). */
@Immutable
data class PushSettingsUiState(
    val registrationUrl: String = "",
    val deviceName: String = "",
    val mqttFallbackEnabled: Boolean = false,
    val mqttHost: String = "",
    val mqttPort: String = "",
    val mqttUser: String = "",
    val mqttPassword: String = "",
    val mqttTopic: String = "",
    val notifyAnsweredElsewhere: Boolean = false,
)

/**
 * Einstellungen: Push (vorher settings_push.xml): UnifiedPush mit Registrierung bei Home
 * Assistant, MQTT als Notweg und die Benachrichtigung bei Annahme durch ein anderes Telefon.
 * Zustandslos; [onRegisterClick]/[onUnregisterClick] arbeiten in der Activity mit den aktuellen,
 * noch nicht gespeicherten Feldwerten.
 *
 * "Weiter" auf der Tastatur führt wie bei den EditTexts über alle sieben Textfelder hinweg (auch
 * von der ersten in die zweite Karte), "Fertig" im MQTT-Topic schließt die Tastatur.
 */
@Composable
fun PushSettingsScreen(
    state: PushSettingsUiState,
    onStateChange: (PushSettingsUiState) -> Unit,
    onRegisterClick: () -> Unit,
    onUnregisterClick: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusChain = rememberFocusChain(PUSH_FIELD_COUNT)
    SettingsScaffold(
        title = stringResource(R.string.section_push_settings),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) {
        // UnifiedPush
        SettingsCard {
            SettingsSectionTitle(text = stringResource(R.string.section_push))
            SettingsSummary(
                text = stringResource(R.string.section_push_summary),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsTextField(
                value = state.registrationUrl,
                onValueChange = { onStateChange(state.copy(registrationUrl = it)) },
                hint = stringResource(R.string.hint_registration_url),
                type = SettingsFieldType.Uri,
                onImeAction = { focusChain[1].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[0])
                    .testTag(SettingsTestTags.FIELD_REGISTRATION_URL),
            )
            SettingsTextField(
                value = state.deviceName,
                onValueChange = { onStateChange(state.copy(deviceName = it)) },
                hint = stringResource(R.string.hint_device_name),
                onImeAction = { focusChain[2].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[1])
                    .testTag(SettingsTestTags.FIELD_DEVICE_NAME),
            )
            SettingsButtonRow(modifier = Modifier.padding(top = 8.dp)) {
                SettingsOutlinedButton(
                    text = stringResource(R.string.btn_register_device),
                    onClick = onRegisterClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SettingsTestTags.BTN_REGISTER_DEVICE),
                )
                SettingsOutlinedButton(
                    text = stringResource(R.string.btn_unregister_device),
                    onClick = onUnregisterClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SettingsTestTags.BTN_UNREGISTER_DEVICE),
                )
            }
        }

        // MQTT
        SettingsCard {
            SettingsSectionTitle(text = stringResource(R.string.section_mqtt))
            SettingsSummary(
                text = stringResource(R.string.section_mqtt_summary),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsSwitchRow(
                text = stringResource(R.string.switch_mqtt_fallback),
                checked = state.mqttFallbackEnabled,
                onCheckedChange = { onStateChange(state.copy(mqttFallbackEnabled = it)) },
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag(SettingsTestTags.SWITCH_MQTT_FALLBACK),
            )
            SettingsTextField(
                value = state.mqttHost,
                onValueChange = { onStateChange(state.copy(mqttHost = it)) },
                hint = stringResource(R.string.hint_mqtt_host),
                type = SettingsFieldType.Uri,
                onImeAction = { focusChain[3].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[2])
                    .testTag(SettingsTestTags.FIELD_MQTT_HOST),
            )
            SettingsTextField(
                value = state.mqttPort,
                onValueChange = { onStateChange(state.copy(mqttPort = it)) },
                hint = stringResource(R.string.hint_mqtt_port),
                type = SettingsFieldType.Number,
                onImeAction = { focusChain[4].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[3])
                    .testTag(SettingsTestTags.FIELD_MQTT_PORT),
            )
            SettingsTextField(
                value = state.mqttUser,
                onValueChange = { onStateChange(state.copy(mqttUser = it)) },
                hint = stringResource(R.string.hint_mqtt_user),
                onImeAction = { focusChain[5].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[4])
                    .testTag(SettingsTestTags.FIELD_MQTT_USER),
            )
            SettingsTextField(
                value = state.mqttPassword,
                onValueChange = { onStateChange(state.copy(mqttPassword = it)) },
                hint = stringResource(R.string.hint_mqtt_pass),
                type = SettingsFieldType.Password,
                onImeAction = { focusChain[6].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[5])
                    .testTag(SettingsTestTags.FIELD_MQTT_PASS),
            )
            SettingsTextField(
                value = state.mqttTopic,
                onValueChange = { onStateChange(state.copy(mqttTopic = it)) },
                hint = stringResource(R.string.hint_mqtt_topic),
                imeAction = ImeAction.Done,
                modifier = Modifier
                    .focusRequester(focusChain[6])
                    .testTag(SettingsTestTags.FIELD_MQTT_TOPIC),
            )
        }

        // Benachrichtigungen
        SettingsCard {
            SettingsSectionTitle(
                text = stringResource(R.string.section_notifications),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsCheckboxRow(
                text = stringResource(R.string.check_notify_answered_elsewhere),
                checked = state.notifyAnsweredElsewhere,
                onCheckedChange = { onStateChange(state.copy(notifyAnsweredElsewhere = it)) },
                modifier = Modifier.testTag(SettingsTestTags.CHECK_NOTIFY_ANSWERED_ELSEWHERE),
            )
        }
    }
}

/** Registrierungs-URL, Gerätename, MQTT-Host, -Port, -Benutzer, -Passwort, -Topic. */
private const val PUSH_FIELD_COUNT = 7

private val PreviewPushState = PushSettingsUiState(
    registrationUrl = "https://ha.example.com/api/webhook/doorbell_register",
    deviceName = "Pixel 9 (a1b2)",
    mqttFallbackEnabled = true,
    mqttHost = "192.168.178.10",
    mqttPort = "1883",
    mqttUser = "doorbell",
    mqttPassword = "mqtt-secret",
    mqttTopic = "doorbell/ring",
    notifyAnsweredElsewhere = true,
)

@Composable
private fun PushSettingsPreviewHost(initial: PushSettingsUiState) {
    var state by remember { mutableStateOf(initial) }
    DoorbellTheme {
        PushSettingsScreen(
            state = state,
            onStateChange = { state = it },
            onRegisterClick = {},
            onUnregisterClick = {},
            onNavigateUp = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun PushSettingsScreenPreview() {
    PushSettingsPreviewHost(PreviewPushState)
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun PushSettingsScreenEmptyPreview() {
    // Frisch installiert: MQTT aus, nur Standard-Port und Gerätename.
    PushSettingsPreviewHost(PushSettingsUiState(deviceName = "Pixel 9 (a1b2)", mqttPort = "1883"))
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PushSettingsScreenDarkPreview() {
    PushSettingsPreviewHost(PreviewPushState)
}
