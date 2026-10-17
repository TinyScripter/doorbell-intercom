package de.kleinabersmart.doorbellintercom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Holt ein Standbild der Kamera über die Reolink-HTTP-API (cmd=Snap) – für das
 * Vorschaubild in der Klingel-Benachrichtigung. Dafür muss HTTP in der Kamera
 * aktiviert sein. Blockiert, daher nur aus einem Hintergrund-Thread aufrufen.
 */
object CameraSnapshot {

    private const val TAG = "CameraSnapshot"
    private const val TIMEOUT_MS = 3000

    /** Breite, auf die das Bild höchstens verkleinert wird; mehr zeigt eine Benachrichtigung nicht an. */
    private const val MAX_WIDTH = 1024

    fun fetch(config: AppConfig): Bitmap? {
        if (config.cameraHost.isBlank()) return null
        val url = Uri.Builder()
            .scheme("http")
            .encodedAuthority(config.cameraHost)
            .path("cgi-bin/api.cgi")
            .appendQueryParameter("cmd", "Snap")
            .appendQueryParameter("channel", "0")
            // Zufallswert gegen zwischengespeicherte Bilder
            .appendQueryParameter("rs", System.currentTimeMillis().toString())
            .appendQueryParameter("user", config.cameraUsername)
            .appendQueryParameter("password", config.cameraPassword)
            .build()
            .toString()
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Standbild: HTTP $code")
                    return null
                }
                // Bei falschen Zugangsdaten antwortet die Kamera mit HTTP 200 und JSON – das dekodiert zu null.
                val bytes = connection.inputStream.use { it.readBytes() }
                val bitmap = decodeScaled(bytes)
                if (bitmap == null) Log.w(TAG, "Standbild: Antwort ist kein Bild")
                bitmap
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Standbild nicht abrufbar: ${e.message}")
            null
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_WIDTH) sampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }
}
