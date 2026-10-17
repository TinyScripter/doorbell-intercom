package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * Löst den Türöffner über einen einfachen HTTP-POST an eine frei
 * konfigurierbare Webhook-URL aus (z. B. eine Home-Assistant-Webhook-
 * Automation). Läuft auf einem eigenen Thread, ruft das Ergebnis auf dem
 * Main-Thread ab.
 */
object DoorOpenClient {

    fun trigger(context: Context, url: String, onResult: (success: Boolean, message: String) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val code = connection.responseCode
                connection.disconnect()
                val success = code in 200..299
                mainHandler.post {
                    onResult(
                        success,
                        if (success) context.getString(R.string.status_door_opened)
                        else context.getString(R.string.status_door_open_http_error, code)
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult(false, context.getString(R.string.status_door_open_failed, e.message))
                }
            }
        }.start()
    }
}
