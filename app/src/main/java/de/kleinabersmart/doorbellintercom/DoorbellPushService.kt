package de.kleinabersmart.doorbellintercom

import android.util.Log
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Hauptweg fürs Klingeln über UnifiedPush, zusätzlich zu MQTT: funktioniert auch,
 * wenn der MQTT-Dienst nicht läuft (z. B. vom System beendet) oder das Handy nicht
 * im WLAN ist.
 *
 * Gesendet wird von der AppDaemon-App in Home Assistant
 * (tools/homeassistant/appdaemon/doorbell_push.py) als einfaches HTTP-POST an die
 * Endpoint-URL dieses Geräts, die PushRegistrar vorher bei ihr registriert hat.
 * Der Body ist JSON mit "type" = "doorbell_ring" bzw. "doorbell_answered".
 * Der Push-Verteiler (z. B. die ntfy-App) reicht ihn an diesen Dienst weiter.
 *
 * Achtung: notify.mobile_app aus Home Assistant reicht dafür nicht – das geht
 * an die Companion-App, nicht an diese App.
 */
class DoorbellPushService : PushService() {

    override fun onMessage(message: PushMessage, instance: String) {
        val text = String(message.content, Charsets.UTF_8)
        val data = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Push-Nachricht ist kein JSON: $text")
            return
        }
        val type = data.optString("type")
        Log.i(TAG, "Push-Nachricht empfangen (type=$type)")

        when (type) {
            "doorbell_ring" -> IncomingCallNotifier.ring(this, source = "Push")
            // Ein anderes Handy hat angenommen.
            "doorbell_answered" -> IncomingCallNotifier.answeredElsewhere(this, data.optString("answered_by").ifBlank { null })
        }
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val config = AppConfig(this)
        config.pushEndpoint = endpoint.url
        PushRegistrar.send(config.registrationUrl, config.deviceName, endpoint.url)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "Anmeldung beim Push-Verteiler fehlgeschlagen: $reason")
    }

    override fun onUnregistered(instance: String) {
        AppConfig(this).pushEndpoint = ""
    }

    private companion object {
        const val TAG = "DoorbellPushService"
    }
}
