package de.kleinabersmart.doorbellintercom

import android.app.Activity
import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.net.HttpURLConnection
import java.net.URL

/**
 * Meldet den UnifiedPush-Endpunkt dieses Geräts per HTTP-POST an den Registrierungs-Endpunkt
 * der AppDaemon-App (tools/homeassistant/appdaemon/doorbell_push.py), die damit beim
 * Klingeln allen registrierten Handys ein HTTP-POST schicken kann. Body:
 * {"device": "<Gerätename>", "endpoint": "<Endpoint-URL>"}. Der Gerätename trennt
 * mehrere Handys voneinander; wer sich mit demselben Namen erneut meldet,
 * ersetzt seinen alten Eintrag.
 *
 * Den Endpunkt vergibt der Push-Verteiler (z. B. die ntfy-App) erst nach [ensureRegistered];
 * er kommt asynchron in DoorbellPushService.onNewEndpoint an und wird dort gemeldet.
 * Wird beim App-Start und nach dem Speichern der Einstellungen aufgerufen ([start]) –
 * so kommt der Endpunkt auch dann an, wenn der Endpunkt in Home Assistant erst später
 * eingerichtet wurde. Zum Entfernen gibt es [unregister]. Es nutzt ebenfalls POST, weil
 * AppDaemon für seine Endpunkte nur GET und POST zulässt (DELETE endet mit HTTP 405).
 */
object PushRegistrar {

    private const val TAG = "PushRegistrar"

    /** [code] ist der HTTP-Status oder -1, falls keine Antwort kam ([error] nennt dann den Grund). */
    data class Result(val code: Int, val error: String? = null) {
        val success: Boolean get() = code in 200..299
    }

    /**
     * Wählt den Push-Verteiler (bei mehreren installierten fragt die Bibliothek nach) und
     * meldet die App dort an. [onNoDistributor] wird aufgerufen, wenn keiner installiert ist.
     */
    fun ensureRegistered(activity: Activity, onNoDistributor: (() -> Unit)? = null) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
            if (success) UnifiedPush.register(activity) else onNoDistributor?.invoke()
        }
    }

    /** Anmelden beim Verteiler und den bekannten Endpunkt an Home Assistant melden – nur mit Registrierungs-URL. */
    fun start(activity: Activity) {
        if (AppConfig(activity).registrationUrl.isBlank()) return
        ensureRegistered(activity)
        registerCurrentEndpoint(activity)
    }

    /**
     * [onResult] wird auf einem Hintergrund-Thread aufgerufen, nicht auf dem UI-Thread.
     * Ohne URL passiert nichts und es gibt auch kein Ergebnis. Ohne bekannten Endpunkt
     * (der Verteiler hat noch keinen vergeben) endet es mit einem Fehler-Ergebnis.
     */
    fun registerCurrentEndpoint(
        context: Context,
        url: String = AppConfig(context).registrationUrl,
        device: String = AppConfig(context).deviceName,
        onResult: ((Result) -> Unit)? = null
    ) {
        if (url.isBlank()) return
        val endpoint = AppConfig(context).pushEndpoint
        if (endpoint.isBlank()) {
            onResult?.invoke(Result(-1, context.getString(R.string.error_no_push_endpoint)))
            return
        }
        send(url, device, endpoint, onResult)
    }

    fun send(url: String, device: String, endpoint: String, onResult: ((Result) -> Unit)? = null) {
        if (url.isBlank()) return
        val body = JSONObject().put("device", device).put("endpoint", endpoint).toString()
        post(url, body, onResult)
    }

    /** Entfernt dieses Gerät aus der Geräteliste in Home Assistant. */
    fun unregister(url: String, device: String, onResult: ((Result) -> Unit)? = null) {
        if (url.isBlank()) return
        val body = JSONObject().put("device", device).put("action", "unregister").toString()
        post(url, body, onResult)
    }

    /**
     * Meldet Home Assistant, dass dieses Gerät den Anruf angenommen hat – die anderen
     * Handys hören daraufhin auf zu klingeln (Push "doorbell_answered").
     */
    fun reportAnswered(context: Context) {
        val config = AppConfig(context)
        if (config.registrationUrl.isBlank()) return
        val body = JSONObject().put("device", config.deviceName).put("action", "answered").toString()
        post(config.registrationUrl, body, null)
    }

    private fun post(url: String, body: String, onResult: ((Result) -> Unit)?) {
        Thread {
            val result = try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                connection.disconnect()
                // Ohne Query-Teil loggen: dort kann das Geheimnis (?key=...) stehen.
                Log.i(TAG, "Registrierungs-Endpunkt ${url.substringBefore('?')}: HTTP $code")
                Result(code)
            } catch (e: Exception) {
                Log.w(TAG, "Registrierungs-Endpunkt nicht erreichbar", e)
                Result(-1, e.message ?: e.javaClass.simpleName)
            }
            onResult?.invoke(result)
        }.start()
    }
}
