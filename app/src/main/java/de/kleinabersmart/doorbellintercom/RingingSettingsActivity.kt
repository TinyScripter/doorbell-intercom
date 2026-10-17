package de.kleinabersmart.doorbellintercom

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import de.kleinabersmart.doorbellintercom.databinding.SettingsRingingBinding

/** Einstellungen: Klingel (Klingeldauer, Klingelton, Verhalten im Lautlos-Modus). */
class RingingSettingsActivity : SettingsSectionActivity() {

    private lateinit var binding: SettingsRingingBinding

    /** Gewählter Klingelton; wird beim Verlassen der Seite (bzw. beim Öffnen des Dialogs) übernommen. */
    private lateinit var selectedRingtoneUri: Uri

    override val titleRes = R.string.section_ringing

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } ?: return@registerForActivityResult
            selectedRingtoneUri = uri
            showRingtone(uri)
        }

    override fun onCreateContent(container: ViewGroup) {
        binding = SettingsRingingBinding.inflate(layoutInflater, container)
        binding.sliderRingDuration.addOnChangeListener { _, value, _ -> showRingDuration(value.toInt()) }
        binding.btnChooseRingtone.setOnClickListener { chooseRingtone() }

        // Auf die Schrittweite des Reglers (5 s) runden.
        val ringDuration = ((config.ringDurationSeconds + 2) / 5 * 5)
            .coerceIn(AppConfig.RING_DURATION_MIN_SECONDS, AppConfig.RING_DURATION_MAX_SECONDS)
        binding.sliderRingDuration.value = ringDuration.toFloat()
        showRingDuration(ringDuration)
        selectedRingtoneUri = config.ringtoneUri
        showRingtone(selectedRingtoneUri)
        binding.switchRingInSilentMode.isChecked = config.ringInSilentMode
    }

    override fun saveFieldsIntoConfig() {
        config.ringDurationSeconds = binding.sliderRingDuration.value.toInt()
        config.ringtoneUri = selectedRingtoneUri
        config.ringInSilentMode = binding.switchRingInSilentMode.isChecked
    }

    private fun chooseRingtone() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedRingtoneUri)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.btn_choose_ringtone))
        ringtonePickerLauncher.launch(intent)
    }

    private fun showRingDuration(seconds: Int) {
        binding.textRingDuration.text = getString(R.string.label_ring_duration, seconds)
    }

    private fun showRingtone(uri: Uri) {
        // Der Titel kann fehlen, z. B. bei einem gelöschten oder nicht lesbaren Klingelton.
        val title = runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
        binding.textRingtone.text = getString(R.string.label_ringtone, title)
    }
}
