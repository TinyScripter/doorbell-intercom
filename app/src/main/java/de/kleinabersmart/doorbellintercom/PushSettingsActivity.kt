package de.kleinabersmart.doorbellintercom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import de.kleinabersmart.doorbellintercom.ui.settings.PushSettingsScreen
import de.kleinabersmart.doorbellintercom.ui.settings.PushSettingsUiState

/**
 * Einstellungen: Push (UnifiedPush), MQTT-Fallback und Benachrichtigung bei Annahme durch ein anderes Telefon.
 *
 * Die Oberfläche ist Compose (PushSettingsScreen); der Zustand liegt als Snapshot-State in der
 * Activity, damit [saveFieldsIntoConfig] und die Registrierung ihn synchron lesen können.
 */
class PushSettingsActivity : SettingsSectionActivity() {

    private val uiState = mutableStateOf(PushSettingsUiState())

    override val titleRes = R.string.section_push_settings

    override fun createContent(): @Composable () -> Unit {
        uiState.value = PushSettingsUiState(
            registrationUrl = config.registrationUrl,
            deviceName = config.deviceName,
            mqttFallbackEnabled = config.mqttFallbackEnabled,
            mqttHost = config.mqttHost,
            mqttPort = config.mqttPort.toString(),
            mqttUser = config.mqttUsername,
            mqttPassword = config.mqttPassword,
            mqttTopic = config.mqttTopic,
            notifyAnsweredElsewhere = config.notifyAnsweredElsewhere,
        )
        return {
            PushSettingsScreen(
                state = uiState.value,
                onStateChange = { uiState.value = it },
                onRegisterClick = ::registerDevice,
                onUnregisterClick = ::unregisterDevice,
                onNavigateUp = ::finish,
            )
        }
    }

    override fun saveFieldsIntoConfig() {
        val state = uiState.value
        config.registrationUrl = state.registrationUrl.trim()
        // Leer lassen gilt als "Standardname": der Getter erzeugt dann wieder einen.
        config.deviceName = state.deviceName.trim()
        config.mqttHost = state.mqttHost.trim()
        config.mqttPort = state.mqttPort.toIntOrNull() ?: 1883
        config.mqttUsername = state.mqttUser.trim()
        config.mqttPassword = state.mqttPassword
        config.mqttTopic = state.mqttTopic.trim()
        config.mqttFallbackEnabled = state.mqttFallbackEnabled
        config.notifyAnsweredElsewhere = state.notifyAnsweredElsewhere
    }

    /** Nutzt die Werte aus den Feldern, auch wenn sie noch nicht gespeichert sind. */
    private fun registerDevice() {
        val url = uiState.value.registrationUrl.trim()
        val device = uiState.value.deviceName.trim()
        if (!checkRegistrationFields(url, device)) return
        // Ohne Endpunkt zuerst beim Push-Verteiler anmelden; der Endpunkt kommt asynchron
        // und wird dann von DoorbellPushService gemeldet.
        if (config.pushEndpoint.isBlank()) {
            showToast(getString(R.string.status_push_setting_up))
            PushRegistrar.ensureRegistered(this) {
                showToast(getString(R.string.status_no_push_distributor))
            }
            return
        }
        PushRegistrar.registerCurrentEndpoint(this, url, device) { result ->
            runOnUiThread {
                showToast(if (result.success) {
                    // PushRegistrar hat die Antwort schon ausgewertet.
                    getString(
                        if (config.pushEncryptionConfirmed) R.string.status_device_registered
                        else R.string.status_device_registered_unencrypted,
                        result.code
                    )
                } else {
                    getString(R.string.status_device_register_failed, describe(result))
                })
            }
        }
    }

    private fun unregisterDevice() {
        val url = uiState.value.registrationUrl.trim()
        val device = uiState.value.deviceName.trim()
        if (!checkRegistrationFields(url, device)) return
        PushRegistrar.unregister(url, device) { result ->
            runOnUiThread {
                showToast(if (result.success) {
                    getString(R.string.status_device_unregistered)
                } else {
                    getString(R.string.status_device_unregister_failed, describe(result))
                })
            }
        }
    }

    private fun checkRegistrationFields(url: String, device: String): Boolean {
        val messageId = when {
            url.isBlank() -> R.string.status_register_url_missing
            device.isBlank() -> R.string.status_register_device_blank
            else -> return true
        }
        showToast(getString(messageId))
        return false
    }

    private fun describe(result: PushRegistrar.Result): String =
        result.error ?: getString(R.string.error_http_code, result.code)
}
