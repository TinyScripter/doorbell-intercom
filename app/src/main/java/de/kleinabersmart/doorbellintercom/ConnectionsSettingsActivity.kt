package de.kleinabersmart.doorbellintercom

import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import de.kleinabersmart.doorbellintercom.ui.settings.ConnectionsSettingsScreen
import de.kleinabersmart.doorbellintercom.ui.settings.ConnectionsSettingsUiState

/**
 * Einstellungen: weitere Verbindungen (Türöffner-Webhook, WireGuard-VPN).
 *
 * Die Oberfläche ist Compose (ConnectionsSettingsScreen); der Zustand liegt als Snapshot-State in
 * der Activity, damit [saveFieldsIntoConfig] und die Berechtigungsabfrage ihn synchron lesen können.
 */
class ConnectionsSettingsActivity : SettingsSectionActivity() {

    private val uiState = mutableStateOf(ConnectionsSettingsUiState())

    override val titleRes = R.string.section_connections

    private val wireguardPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            showToast(getString(
                if (granted) R.string.status_vpn_permission_granted else R.string.status_vpn_permission_denied
            ))
        }

    override fun createContent(): @Composable () -> Unit {
        uiState.value = ConnectionsSettingsUiState(
            doorOpenUrl = config.doorOpenUrl,
            wireguardAutoVpn = config.wireguardAutoVpn,
            wireguardTunnel = config.wireguardTunnel,
            silentWhenUnreachable = config.silentWhenUnreachable,
        )
        // Auto-VPN kann per Import an sein, ohne dass die Berechtigung je erfragt wurde.
        requestWireguardPermissionIfNeeded()
        return {
            ConnectionsSettingsScreen(
                state = uiState.value,
                onStateChange = { uiState.value = it },
                onWireguardAutoVpnChange = { checked ->
                    uiState.value = uiState.value.copy(wireguardAutoVpn = checked)
                    requestWireguardPermissionIfNeeded()
                },
                onNavigateUp = ::finish,
            )
        }
    }

    override fun saveFieldsIntoConfig() {
        val state = uiState.value
        config.doorOpenUrl = state.doorOpenUrl.trim()
        config.wireguardAutoVpn = state.wireguardAutoVpn
        config.wireguardTunnel = state.wireguardTunnel.trim()
        config.silentWhenUnreachable = state.silentWhenUnreachable
    }

    /** WireGuard stuft CONTROL_TUNNELS als "gefährlich" ein, die Berechtigung muss daher zur Laufzeit erteilt werden. */
    private fun requestWireguardPermissionIfNeeded() {
        if (!uiState.value.wireguardAutoVpn) return
        if (checkSelfPermission(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS) == PackageManager.PERMISSION_GRANTED) return
        wireguardPermissionLauncher.launch(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS)
    }
}
