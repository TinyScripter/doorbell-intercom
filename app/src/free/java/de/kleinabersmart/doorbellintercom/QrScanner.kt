package de.kleinabersmart.doorbellintercom

import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * QR-Scanner der Variante "free": zxing-android-embedded, ganz ohne Play Services. Die
 * Bibliothek bringt die Kamera-Berechtigung mit und fragt sie vor dem Scan selbst an.
 * Gegenstück der Variante "play" liegt in src/play (gleiche Schnittstelle).
 *
 * Muss während der Initialisierung der Activity erzeugt werden (wie ein ActivityResult-Launcher).
 */
class QrScanner(
    activity: ComponentActivity,
    private val onResult: (String) -> Unit,
    private val onError: () -> Unit
) {
    private val launcher = activity.registerForActivityResult(ScanContract()) { result ->
        // contents ist null, wenn der Nutzer den Scan abbricht – kein Fehler.
        result.contents?.let(onResult)
    }

    fun scan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .setPrompt("")
        try {
            launcher.launch(options)
        } catch (e: Exception) {
            onError()
        }
    }
}
