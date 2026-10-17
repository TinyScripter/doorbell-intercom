package de.kleinabersmart.doorbellintercom

import android.view.ViewGroup
import de.kleinabersmart.doorbellintercom.databinding.SettingsPushBinding

/** Einstellungen: Push (UnifiedPush), MQTT-Fallback und Benachrichtigung bei Annahme durch ein anderes Telefon. */
class PushSettingsActivity : SettingsSectionActivity() {

    private lateinit var binding: SettingsPushBinding

    override val titleRes = R.string.section_push_settings

    override fun onCreateContent(container: ViewGroup) {
        binding = SettingsPushBinding.inflate(layoutInflater, container)
        binding.btnRegisterDevice.setOnClickListener { registerDevice() }
        binding.btnUnregisterDevice.setOnClickListener { unregisterDevice() }

        binding.editRegistrationUrl.setText(config.registrationUrl)
        binding.editDeviceName.setText(config.deviceName)
        binding.editMqttHost.setText(config.mqttHost)
        binding.editMqttPort.setText(config.mqttPort.toString())
        binding.editMqttUser.setText(config.mqttUsername)
        binding.editMqttPass.setText(config.mqttPassword)
        binding.editMqttTopic.setText(config.mqttTopic)
        binding.switchMqttFallback.isChecked = config.mqttFallbackEnabled
        binding.checkNotifyAnsweredElsewhere.isChecked = config.notifyAnsweredElsewhere
    }

    override fun saveFieldsIntoConfig() {
        config.registrationUrl = binding.editRegistrationUrl.text.toString().trim()
        // Leer lassen gilt als "Standardname": der Getter erzeugt dann wieder einen.
        config.deviceName = binding.editDeviceName.text.toString().trim()
        config.mqttHost = binding.editMqttHost.text.toString().trim()
        config.mqttPort = binding.editMqttPort.text.toString().toIntOrNull() ?: 1883
        config.mqttUsername = binding.editMqttUser.text.toString().trim()
        config.mqttPassword = binding.editMqttPass.text.toString()
        config.mqttTopic = binding.editMqttTopic.text.toString().trim()
        config.mqttFallbackEnabled = binding.switchMqttFallback.isChecked
        config.notifyAnsweredElsewhere = binding.checkNotifyAnsweredElsewhere.isChecked
    }

    /** Nutzt die Werte aus den Feldern, auch wenn sie noch nicht gespeichert sind. */
    private fun registerDevice() {
        val url = binding.editRegistrationUrl.text.toString().trim()
        val device = binding.editDeviceName.text.toString().trim()
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
                    getString(R.string.status_device_registered, result.code)
                } else {
                    getString(R.string.status_device_register_failed, describe(result))
                })
            }
        }
    }

    private fun unregisterDevice() {
        val url = binding.editRegistrationUrl.text.toString().trim()
        val device = binding.editDeviceName.text.toString().trim()
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
