package de.kleinabersmart.doorbellintercom

import android.view.ViewGroup
import de.kleinabersmart.doorbellintercom.databinding.SettingsCameraBinding

/** Einstellungen: Kamera (Adresse, Zugangsdaten, Video, Mikrofon). */
class CameraSettingsActivity : SettingsSectionActivity() {

    private lateinit var binding: SettingsCameraBinding

    override val titleRes = R.string.section_camera

    override fun onCreateContent(container: ViewGroup) {
        binding = SettingsCameraBinding.inflate(layoutInflater, container)
        binding.sliderMicGain.addOnChangeListener { _, value, _ -> showMicGain(value.toInt()) }
        binding.sliderVideoStartBuffer.addOnChangeListener { _, value, _ -> showVideoStartBuffer(value.toInt()) }
        binding.sliderVideoRebuffer.addOnChangeListener { _, value, _ -> showVideoRebuffer(value.toInt()) }

        binding.editCameraHost.setText(config.cameraHost)
        binding.editCameraBcPort.setText(config.cameraBcPort.toString())
        binding.editCameraUser.setText(config.cameraUsername)
        binding.editCameraPass.setText(config.cameraPassword)
        binding.editCameraRtspPath.setText(config.cameraRtspPath)
        binding.checkKeepAspectRatio.isChecked = config.keepAspectRatio
        // Auf die Schrittweite des Reglers runden, sonst wirft der Slider eine Exception.
        val micGain = ((config.micGainPercent + 50) / 100 * 100).coerceIn(100, 2000)
        binding.sliderMicGain.value = micGain.toFloat()
        showMicGain(micGain)
        // Auf die Schrittweiten der Regler (50 bzw. 100 ms) runden.
        val startBuffer = ((config.videoStartBufferMs + 25) / 50 * 50)
            .coerceIn(AppConfig.VIDEO_START_BUFFER_MIN_MS, AppConfig.VIDEO_START_BUFFER_MAX_MS)
        binding.sliderVideoStartBuffer.value = startBuffer.toFloat()
        showVideoStartBuffer(startBuffer)
        val rebuffer = ((config.videoRebufferMs + 50) / 100 * 100)
            .coerceIn(AppConfig.VIDEO_REBUFFER_MIN_MS, AppConfig.VIDEO_REBUFFER_MAX_MS)
        binding.sliderVideoRebuffer.value = rebuffer.toFloat()
        showVideoRebuffer(rebuffer)
    }

    override fun saveFieldsIntoConfig() {
        config.cameraHost = binding.editCameraHost.text.toString().trim()
        config.cameraBcPort = binding.editCameraBcPort.text.toString().toIntOrNull() ?: 9000
        config.cameraUsername = binding.editCameraUser.text.toString().trim()
        config.cameraPassword = binding.editCameraPass.text.toString()
        config.cameraRtspPath = binding.editCameraRtspPath.text.toString().trim().trimStart('/')
        config.keepAspectRatio = binding.checkKeepAspectRatio.isChecked
        config.micGainPercent = binding.sliderMicGain.value.toInt()
        config.videoStartBufferMs = binding.sliderVideoStartBuffer.value.toInt()
        config.videoRebufferMs = binding.sliderVideoRebuffer.value.toInt()
    }

    private fun showVideoStartBuffer(ms: Int) {
        binding.textVideoStartBuffer.text = getString(R.string.label_video_start_buffer, ms)
    }

    private fun showVideoRebuffer(ms: Int) {
        binding.textVideoRebuffer.text = getString(R.string.label_video_rebuffer, ms)
    }

    private fun showMicGain(percent: Int) {
        binding.textMicGain.text = getString(R.string.label_mic_gain, percent)
    }
}
