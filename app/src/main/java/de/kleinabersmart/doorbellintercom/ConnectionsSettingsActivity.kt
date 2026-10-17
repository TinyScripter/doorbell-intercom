package de.kleinabersmart.doorbellintercom

import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import de.kleinabersmart.doorbellintercom.databinding.SettingsConnectionsBinding

/** Einstellungen: weitere Verbindungen (Türöffner-Webhook, WireGuard-VPN). */
class ConnectionsSettingsActivity : SettingsSectionActivity() {

    private lateinit var binding: SettingsConnectionsBinding

    override val titleRes = R.string.section_connections

    private val wireguardPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            showToast(getString(
                if (granted) R.string.status_vpn_permission_granted else R.string.status_vpn_permission_denied
            ))
        }

    override fun onCreateContent(container: ViewGroup) {
        binding = SettingsConnectionsBinding.inflate(layoutInflater, container)
        binding.switchWireguardAutoVpn.setOnClickListener { requestWireguardPermissionIfNeeded() }

        binding.editDoorOpenUrl.setText(config.doorOpenUrl)
        binding.switchWireguardAutoVpn.isChecked = config.wireguardAutoVpn
        binding.editWireguardTunnel.setText(config.wireguardTunnel)
        binding.switchSilentWhenUnreachable.isChecked = config.silentWhenUnreachable
        // Auto-VPN kann per Import an sein, ohne dass die Berechtigung je erfragt wurde.
        requestWireguardPermissionIfNeeded()
    }

    override fun saveFieldsIntoConfig() {
        config.doorOpenUrl = binding.editDoorOpenUrl.text.toString().trim()
        config.wireguardAutoVpn = binding.switchWireguardAutoVpn.isChecked
        config.wireguardTunnel = binding.editWireguardTunnel.text.toString().trim()
        config.silentWhenUnreachable = binding.switchSilentWhenUnreachable.isChecked
    }

    /** WireGuard stuft CONTROL_TUNNELS als "gefährlich" ein, die Berechtigung muss daher zur Laufzeit erteilt werden. */
    private fun requestWireguardPermissionIfNeeded() {
        if (!binding.switchWireguardAutoVpn.isChecked) return
        if (checkSelfPermission(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS) == PackageManager.PERMISSION_GRANTED) return
        wireguardPermissionLauncher.launch(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS)
    }
}
