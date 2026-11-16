package de.kleinabersmart.doorbellintercom

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import de.kleinabersmart.doorbellintercom.ui.settings.RingLogScreen
import de.kleinabersmart.doorbellintercom.ui.settings.RingLogUiState

/**
 * Klingel-Protokoll (AppConfig.ringLog): die letzten Klingel-Ereignisse mit Weg und Ergebnis,
 * für die Fehlersuche bei "hat nicht geklingelt". Kopieren, um es z. B. einem Fehlerbericht
 * anzuhängen.
 *
 * Die Oberfläche ist Compose (RingLogScreen); die Einträge werden in onResume neu gelesen.
 */
class RingLogActivity : SettingsBaseActivity() {

    private val uiState = mutableStateOf(RingLogUiState())

    override val titleRes = R.string.section_ring_log

    override fun createContent(): @Composable () -> Unit {
        uiState.value = RingLogUiState(config.ringLog)
        return {
            RingLogScreen(
                state = uiState.value,
                onCopyClick = ::copyRingLog,
                onNavigateUp = ::finish,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Auch nach einem Klingeln, während die Seite im Hintergrund lag.
        uiState.value = RingLogUiState(config.ringLog)
    }

    private fun copyRingLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.section_ring_log), config.ringLog.joinToString("\n")))
        // Ab Android 13 bestätigt das System das Kopieren selbst.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showToast(getString(R.string.toast_ring_log_copied))
        }
    }
}
