package de.kleinabersmart.doorbellintercom

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import de.kleinabersmart.doorbellintercom.databinding.ItemSettingsEntryBinding
import de.kleinabersmart.doorbellintercom.databinding.SettingsMainBinding

/**
 * Hauptseite der Einstellungen: Liste der Unterseiten (Kamera, Klingel, Push, weitere
 * Verbindungen, Über / Lizenzen) und die Sicherung. Gespeichert wird in den Unterseiten
 * beim Verlassen; kehrt eine mit Änderungen zurück, erscheint hier ein Toast.
 */
class SettingsActivity : SettingsBaseActivity() {

    private lateinit var binding: SettingsMainBinding

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
        onError = { binding.statusText.text = getString(R.string.status_qr_failed) }
    )

    override fun onCreateContent(container: ViewGroup) {
        binding = SettingsMainBinding.inflate(layoutInflater, container)

        addEntry(R.string.section_camera, R.string.section_camera_summary, CameraSettingsActivity::class.java)
        addEntry(R.string.section_ringing, R.string.section_ringing_summary, RingingSettingsActivity::class.java)
        addEntry(R.string.section_push_settings, R.string.section_push_settings_summary, PushSettingsActivity::class.java)
        addEntry(R.string.section_connections, R.string.section_connections_summary, ConnectionsSettingsActivity::class.java)
        addEntry(R.string.section_about, R.string.section_about_summary, AboutActivity::class.java)

        binding.btnExportSettings.setOnClickListener { exportLauncher.launch(EXPORT_FILE_NAME) }
        binding.btnImportSettings.setOnClickListener { importLauncher.launch(IMPORT_MIME_TYPES) }
        binding.btnShowQr.setOnClickListener { showQrCode() }
        binding.btnScanQr.setOnClickListener { qrScanner.scan() }
    }

    private fun showQrCode() {
        val bitmap = try {
            createQrBitmap(config.exportToQrPayload())
        } catch (e: Exception) {
            Log.w(TAG, "QR-Code konnte nicht erzeugt werden", e)
            binding.statusText.text = getString(R.string.status_qr_failed)
            return
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            setPadding(padding, padding, padding, padding)
            adjustViewBounds = true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_qr_dialog)
            .setMessage(R.string.message_qr_dialog)
            .setView(image)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    /** Schwarz auf weiß mit Ruhezone, unabhängig vom App-Theme – sonst scannt er schlecht. */
    private fun createQrBitmap(content: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        // Ein Pixel pro Modul; ImageView skaliert ohne Weichzeichnen hoch.
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    private fun confirmQrImport(payload: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_qr_import_confirm)
            .setMessage(R.string.message_qr_import_confirm)
            .setPositiveButton(R.string.btn_import_confirm) { _, _ -> applyImportedQr(payload) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun applyImportedQr(payload: String) {
        try {
            config.importFromQrPayload(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Einstellungen konnten nicht aus dem QR-Code importiert werden", e)
            binding.statusText.text = getString(R.string.status_settings_import_failed)
            return
        }
        onSettingsImported()
    }

    private fun addEntry(@StringRes title: Int, @StringRes summary: Int, target: Class<*>) {
        val entry = ItemSettingsEntryBinding.inflate(layoutInflater, binding.entryList, false)
        entry.entryTitle.setText(title)
        entry.entrySummary.setText(summary)
        entry.root.setOnClickListener { sectionLauncher.launch(Intent(this, target)) }
        binding.entryList.addView(entry.root)
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
                binding.statusText.text = getString(
                    if (success) R.string.status_settings_exported else R.string.status_settings_export_failed
                )
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
            binding.statusText.text = getString(R.string.status_settings_import_failed)
            return
        }
        onSettingsImported()
    }

    private fun onSettingsImported() {
        DoorbellForegroundService.startOrStop(this)
        PushRegistrar.start(this)
        binding.statusText.text = getString(R.string.status_settings_imported)
    }

    private companion object {
        const val TAG = "SettingsActivity"
        const val EXPORT_FILE_NAME = "doorbell-intercom-settings.json"

        // Manche Dateimanager melden .json-Dateien nicht als application/json.
        val IMPORT_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")
    }
}
