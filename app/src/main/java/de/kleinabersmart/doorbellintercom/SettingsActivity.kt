package de.kleinabersmart.doorbellintercom

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsEntry
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsScreen
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsUiState
import de.kleinabersmart.doorbellintercom.ui.settings.SystemCheckIssueUi
import de.kleinabersmart.doorbellintercom.ui.settings.createQrBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hauptseite der Einstellungen: Liste der Unterseiten (Kamera, Klingel, Push, weitere
 * Verbindungen, Klingel-Protokoll, Über / Lizenzen) und die Sicherung. Gespeichert wird in den Unterseiten
 * beim Verlassen; kehrt eine mit Änderungen zurück, erscheint hier ein Toast.
 *
 * Die Oberfläche ist Compose (SettingsScreen); die Activity schreibt nur in [uiState].
 */
class SettingsActivity : SettingsBaseActivity() {

    private val uiState = MutableStateFlow(SettingsUiState())

    override val titleRes = R.string.toolbar_settings_title

    private val sectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                showToast(getString(R.string.toast_settings_saved))
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportSettings(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSettings(uri)
        }

    // Je nach Build-Variante (play/free) eine andere Implementierung, siehe src/play und src/free.
    private val qrScanner = QrScanner(
        activity = this,
        onResult = ::confirmQrImport,
        onError = { showStatus(R.string.status_qr_failed) }
    )

    override fun createContent(): @Composable () -> Unit = {
        val state by uiState.collectAsStateWithLifecycle()
        SettingsScreen(
            state = state,
            onNavigateUp = ::finish,
            onEntryClick = ::openEntry,
            onIssueClick = ::openFix,
            onExportClick = { exportLauncher.launch(EXPORT_FILE_NAME) },
            onImportClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
            onShowQrClick = ::showQrCode,
            onScanQrClick = { qrScanner.scan() },
            onQrDialogDismiss = { uiState.update { it.copy(qrCode = null) } },
            onQrImportConfirm = ::confirmPendingQrImport,
            onQrImportDismiss = { uiState.update { it.copy(pendingQrImport = null) } },
        )
    }

    private fun openEntry(entry: SettingsEntry) {
        val target = when (entry) {
            SettingsEntry.Camera -> CameraSettingsActivity::class.java
            SettingsEntry.Ringing -> RingingSettingsActivity::class.java
            SettingsEntry.Push -> PushSettingsActivity::class.java
            SettingsEntry.Connections -> ConnectionsSettingsActivity::class.java
            SettingsEntry.RingLog -> RingLogActivity::class.java
            SettingsEntry.About -> AboutActivity::class.java
        }
        sectionLauncher.launch(Intent(this, target))
    }

    /**
     * Die Aktion hängt nur von der Art des Befunds ab (eindeutiger Titel), daher wird sie beim
     * Tippen frisch aus SystemCheck geholt statt im UI-Zustand gehalten. Ist der Befund inzwischen
     * behoben, passiert nichts; die Liste ist beim nächsten onResume aktuell.
     */
    private fun openFix(issue: SystemCheckIssueUi) {
        SystemCheck.issues(this).firstOrNull { it.title == issue.title }?.let { SystemCheck.openFix(this, it) }
    }

    private fun showQrCode() {
        val bitmap = try {
            createQrBitmap(config.exportToQrPayload())
        } catch (e: Exception) {
            Log.w(TAG, "QR-Code konnte nicht erzeugt werden", e)
            showStatus(R.string.status_qr_failed)
            return
        }
        uiState.update { it.copy(qrCode = bitmap.asImageBitmap()) }
    }

    private fun confirmQrImport(payload: String) {
        uiState.update { it.copy(pendingQrImport = payload) }
    }

    private fun confirmPendingQrImport() {
        val payload = uiState.value.pendingQrImport ?: return
        uiState.update { it.copy(pendingQrImport = null) }
        applyImportedQr(payload)
    }

    private fun applyImportedQr(payload: String) {
        try {
            config.importFromQrPayload(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Einstellungen konnten nicht aus dem QR-Code importiert werden", e)
            showStatus(R.string.status_settings_import_failed)
            return
        }
        onSettingsImported()
    }

    override fun onResume() {
        super.onResume()
        // Auch nach der Rückkehr aus den Systemeinstellungen, in denen etwas behoben wurde.
        showSystemCheck()
    }

    private fun showSystemCheck() {
        val issues = SystemCheck.issues(this).map { SystemCheckIssueUi(it.title, it.summary) }
        uiState.update { it.copy(issues = issues) }
    }

    private fun showStatus(@StringRes message: Int) {
        uiState.update { it.copy(statusMessage = message) }
    }

    private fun exportSettings(uri: Uri) {
        val json = config.exportToJson()
        // Dateizugriff im Hintergrund: der gewählte Speicherort kann auch ein Cloud-Anbieter sein.
        Thread {
            val success = try {
                val stream = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Kein Schreibzugriff auf $uri")
                stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Einstellungen konnten nicht exportiert werden", e)
                false
            }
            runOnUiThread {
                showStatus(if (success) R.string.status_settings_exported else R.string.status_settings_export_failed)
            }
        }.start()
    }

    private fun importSettings(uri: Uri) {
        Thread {
            val json = try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Kein Lesezugriff auf $uri")
                stream.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                Log.w(TAG, "Einstellungsdatei konnte nicht gelesen werden", e)
                null
            }
            runOnUiThread { applyImportedSettings(json) }
        }.start()
    }

    private fun applyImportedSettings(json: String?) {
        try {
            config.importFromJson(json ?: throw IllegalArgumentException("Datei nicht lesbar"))
        } catch (e: Exception) {
            Log.w(TAG, "Einstellungen konnten nicht importiert werden", e)
            showStatus(R.string.status_settings_import_failed)
            return
        }
        onSettingsImported()
    }

    private fun onSettingsImported() {
        DoorbellForegroundService.startOrStop(this)
        PushRegistrar.start(this)
        showStatus(R.string.status_settings_imported)
    }

    private companion object {
        const val TAG = "SettingsActivity"
        const val EXPORT_FILE_NAME = "doorbell-intercom-settings.json"

        // Manche Dateimanager melden .json-Dateien nicht als application/json.
        val IMPORT_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")
    }
}
