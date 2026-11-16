package de.kleinabersmart.doorbellintercom

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import de.kleinabersmart.doorbellintercom.ui.settings.RingingSettingsScreen
import de.kleinabersmart.doorbellintercom.ui.settings.RingingSettingsUiState

/**
 * Einstellungen: Klingel (Klingeldauer, Klingelton, Verhalten im Lautlos-Modus, als Anruf einbinden).
 *
 * Die Oberfläche ist Compose (RingingSettingsScreen); der Zustand liegt als Snapshot-State in der
 * Activity, damit [saveFieldsIntoConfig] ihn synchron lesen kann.
 */
class RingingSettingsActivity : SettingsSectionActivity() {

    private val uiState = mutableStateOf(RingingSettingsUiState())

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
            uiState.value = uiState.value.copy(ringtoneTitle = ringtoneTitle(uri))
        }

    override fun createContent(): @Composable () -> Unit {
        selectedRingtoneUri = config.ringtoneUri
        // Auf die Schrittweite des Reglers (5 s) runden.
        val ringDuration = ((config.ringDurationSeconds + 2) / 5 * 5)
            .coerceIn(AppConfig.RING_DURATION_MIN_SECONDS, AppConfig.RING_DURATION_MAX_SECONDS)
        uiState.value = RingingSettingsUiState(
            ringDurationSeconds = ringDuration,
            ringtoneTitle = ringtoneTitle(selectedRingtoneUri),
            ringInSilentMode = config.ringInSilentMode,
            telecomCalls = config.telecomCalls,
        )
        return {
            RingingSettingsScreen(
                state = uiState.value,
                onStateChange = { uiState.value = it },
                onChooseRingtone = ::chooseRingtone,
                onNavigateUp = ::finish,
            )
        }
    }

    override fun saveFieldsIntoConfig() {
        val state = uiState.value
        config.ringDurationSeconds = state.ringDurationSeconds
        config.ringtoneUri = selectedRingtoneUri
        config.ringInSilentMode = state.ringInSilentMode
        config.telecomCalls = state.telecomCalls
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

    /** Der Titel kann fehlen, z. B. bei einem gelöschten oder nicht lesbaren Klingelton. */
    private fun ringtoneTitle(uri: Uri): String =
        runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
}
