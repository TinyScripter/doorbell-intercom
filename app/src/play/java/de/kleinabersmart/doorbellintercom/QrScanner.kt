package de.kleinabersmart.doorbellintercom

import android.util.Log
import androidx.activity.ComponentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * QR-Scanner der Variante "play": ML Kit Code Scanner über die Play Services. Die Kamera
 * gehört dabei den Play Services, die App braucht keine eigene Kamera-Berechtigung.
 * Gegenstück der Variante "free" liegt in src/free (gleiche Schnittstelle).
 *
 * Muss während der Initialisierung der Activity erzeugt werden (wie ein ActivityResult-Launcher).
 */
class QrScanner(
    private val activity: ComponentActivity,
    private val onResult: (String) -> Unit,
    private val onError: () -> Unit
) {
    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode ->
                val payload = barcode.rawValue
                if (payload != null) onResult(payload) else onError()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "QR-Code konnte nicht gescannt werden", e)
                onError()
            }
    }

    private companion object {
        const val TAG = "QrScanner"
    }
}
