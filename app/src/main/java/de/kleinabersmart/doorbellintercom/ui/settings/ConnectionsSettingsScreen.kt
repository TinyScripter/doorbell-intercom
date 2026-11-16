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

/** Werte der Seite "Weitere Verbindungen". */
@Immutable
data class ConnectionsSettingsUiState(
    val doorOpenUrl: String = "",
    val wireguardAutoVpn: Boolean = false,
    val wireguardTunnel: String = "",
    val silentWhenUnreachable: Boolean = false,
)

/**
 * Einstellungen: weitere Verbindungen (vorher settings_connections.xml): Türöffner-Webhook und
 * WireGuard-VPN. Zustandslos. Das Umschalten von Auto-VPN meldet die Seite gesondert über
 * [onWireguardAutoVpnChange], weil die Activity danach die WireGuard-Berechtigung erfragt (wie
 * vorher der Klick-Listener des Schalters).
 */
@Composable
fun ConnectionsSettingsScreen(
    state: ConnectionsSettingsUiState,
    onStateChange: (ConnectionsSettingsUiState) -> Unit,
    onWireguardAutoVpnChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusChain = rememberFocusChain(CONNECTIONS_FIELD_COUNT)
    SettingsScaffold(
        title = stringResource(R.string.section_connections),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) {
        // Türöffner
        SettingsCard {
            SettingsSectionTitle(
                text = stringResource(R.string.section_door),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsTextField(
                value = state.doorOpenUrl,
                onValueChange = { onStateChange(state.copy(doorOpenUrl = it)) },
                hint = stringResource(R.string.hint_door_open_url),
                type = SettingsFieldType.Uri,
                onImeAction = { focusChain[1].requestFocus() },
                modifier = Modifier
                    .focusRequester(focusChain[0])
                    .testTag(SettingsTestTags.FIELD_DOOR_OPEN_URL),
            )
        }

        // VPN
        SettingsCard {
            SettingsSectionTitle(text = stringResource(R.string.section_vpn))
            SettingsSummary(
                text = stringResource(R.string.section_vpn_summary),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsSwitchRow(
                text = stringResource(R.string.switch_wireguard_auto_vpn),
                checked = state.wireguardAutoVpn,
                onCheckedChange = onWireguardAutoVpnChange,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag(SettingsTestTags.SWITCH_WIREGUARD_AUTO_VPN),
            )
            SettingsTextField(
                value = state.wireguardTunnel,
                onValueChange = { onStateChange(state.copy(wireguardTunnel = it)) },
                hint = stringResource(R.string.hint_wireguard_tunnel),
                imeAction = ImeAction.Done,
                modifier = Modifier
                    .focusRequester(focusChain[1])
                    .testTag(SettingsTestTags.FIELD_WIREGUARD_TUNNEL),
            )
            SettingsSwitchRow(
                text = stringResource(R.string.switch_silent_when_unreachable),
                checked = state.silentWhenUnreachable,
                onCheckedChange = { onStateChange(state.copy(silentWhenUnreachable = it)) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(SettingsTestTags.SWITCH_SILENT_WHEN_UNREACHABLE),
            )
        }
    }
}

/** Türöffner-URL, WireGuard-Tunnel. */
private const val CONNECTIONS_FIELD_COUNT = 2

private val PreviewConnectionsState = ConnectionsSettingsUiState(
    doorOpenUrl = "https://ha.example.com/api/webhook/door_open",
    wireguardAutoVpn = true,
    wireguardTunnel = "home",
    silentWhenUnreachable = true,
)

@Composable
private fun ConnectionsSettingsPreviewHost(initial: ConnectionsSettingsUiState) {
    var state by remember { mutableStateOf(initial) }
    DoorbellTheme {
        ConnectionsSettingsScreen(
            state = state,
            onStateChange = { state = it },
            onWireguardAutoVpnChange = { state = state.copy(wireguardAutoVpn = it) },
            onNavigateUp = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun ConnectionsSettingsScreenPreview() {
    ConnectionsSettingsPreviewHost(PreviewConnectionsState)
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun ConnectionsSettingsScreenEmptyPreview() {
    ConnectionsSettingsPreviewHost(ConnectionsSettingsUiState())
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConnectionsSettingsScreenDarkPreview() {
    ConnectionsSettingsPreviewHost(PreviewConnectionsState)
}
