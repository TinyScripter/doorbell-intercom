package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Zentrale Ablage für die Verbindungsdaten. Alles läuft rein lokal im LAN,
 * daher genügt eine simple SharedPreferences-Ablage ohne Verschlüsselung.
 * Für den produktiven Einsatz auf einem nicht rein privaten Gerät:
 * EncryptedSharedPreferences (androidx.security) verwenden.
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("doorbell_config", Context.MODE_PRIVATE)

    var mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, value).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, 1883)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    var mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USER, value).apply()

    var mqttPassword: String
        get() = prefs.getString(KEY_MQTT_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_PASS, value).apply()

    var mqttTopic: String
        get() = prefs.getString(KEY_MQTT_TOPIC, "doorbell/ring") ?: "doorbell/ring"
        set(value) = prefs.edit().putString(KEY_MQTT_TOPIC, value).apply()

    /** Aus: kein Hintergrunddienst und keine Dauer-Benachrichtigung, Klingeln nur per Push. */
    var mqttFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_MQTT_FALLBACK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_FALLBACK_ENABLED, value).apply()

    // Kamera-Zugangsdaten: werden für beides verwendet – Video per direktem
    // RTSP (Port 554) und Gegensprechen per Baichuan-Protokoll (Port 9000).
    var cameraHost: String
        get() = prefs.getString(KEY_CAMERA_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CAMERA_HOST, value).apply()

    var cameraBcPort: Int
        get() = prefs.getInt(KEY_CAMERA_BC_PORT, 9000)
        set(value) = prefs.edit().putInt(KEY_CAMERA_BC_PORT, value).apply()

    var cameraUsername: String
        get() = prefs.getString(KEY_CAMERA_USER, "admin") ?: "admin"
        set(value) = prefs.edit().putString(KEY_CAMERA_USER, value).apply()

    var cameraPassword: String
        get() = prefs.getString(KEY_CAMERA_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CAMERA_PASS, value).apply()

    var cameraRtspPath: String
        get() = prefs.getString(KEY_CAMERA_RTSP_PATH, "h264Preview_01_main") ?: "h264Preview_01_main"
        set(value) = prefs.edit().putString(KEY_CAMERA_RTSP_PATH, value).apply()

    /** Seitenverhältnis beim Video beibehalten (Letterboxing) statt zu verzerren. */
    var keepAspectRatio: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ASPECT_RATIO, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ASPECT_RATIO, value).apply()

    /**
     * Software-Verstärkung des Smartphone-Mikrofons beim Gegensprechen in Prozent
     * (100 = unverändert). Hilft, wenn der Besucher einen an der Klingel zu leise hört.
     */
    var micGainPercent: Int
        get() = prefs.getInt(KEY_MIC_GAIN_PERCENT, 100)
        set(value) = prefs.edit().putInt(KEY_MIC_GAIN_PERCENT, value).apply()

    /**
     * Ton der Klingel im Handy leiser stellen, solange man spricht. Die Klingel
     * nimmt die eigene Stimme aus ihrem Lautsprecher wieder auf und schickt sie
     * zurück; ohne Dämpfung hört man sich verzögert selbst.
     */
    var duckWhileTalking: Boolean
        get() = prefs.getBoolean(KEY_DUCK_WHILE_TALKING, true)
        set(value) = prefs.edit().putBoolean(KEY_DUCK_WHILE_TALKING, value).apply()

    /**
     * Wie viel Video vor dem Start der Wiedergabe gepuffert wird, in Millisekunden. Bestimmt die
     * Verzögerung zum Live-Bild; zu klein führt bei schwachem Netz zu Rucklern.
     */
    var videoStartBufferMs: Int
        get() = prefs.getInt(KEY_VIDEO_START_BUFFER_MS, VIDEO_START_BUFFER_DEFAULT_MS)
            .coerceIn(VIDEO_START_BUFFER_MIN_MS, VIDEO_START_BUFFER_MAX_MS)
        set(value) = prefs.edit().putInt(KEY_VIDEO_START_BUFFER_MS, value).apply()

    /** Wie viel Video nach einem Aussetzer gepuffert wird, bevor es weitergeht, in Millisekunden. */
    var videoRebufferMs: Int
        get() = prefs.getInt(KEY_VIDEO_REBUFFER_MS, VIDEO_REBUFFER_DEFAULT_MS)
            .coerceIn(VIDEO_REBUFFER_MIN_MS, VIDEO_REBUFFER_MAX_MS)
        set(value) = prefs.edit().putInt(KEY_VIDEO_REBUFFER_MS, value).apply()

    /** Wie lange es beim Klingeln klingelt, in Sekunden (RING_DURATION_MIN_SECONDS..RING_DURATION_MAX_SECONDS). */
    var ringDurationSeconds: Int
        get() = prefs.getInt(KEY_RING_DURATION_SECONDS, RING_DURATION_DEFAULT_SECONDS)
            .coerceIn(RING_DURATION_MIN_SECONDS, RING_DURATION_MAX_SECONDS)
        set(value) = prefs.edit().putInt(KEY_RING_DURATION_SECONDS, value).apply()

    /** Klingelton beim Klingeln; Standard ist der Klingelton des Telefons. */
    var ringtoneUri: Uri
        get() = prefs.getString(KEY_RINGTONE_URI, null)?.let(Uri::parse) ?: Settings.System.DEFAULT_RINGTONE_URI
        set(value) = prefs.edit().putString(KEY_RINGTONE_URI, value.toString()).apply()

    /** Auch bei lautlos oder auf Vibration gestelltem Telefon klingeln (über die Wecker-Lautstärke). */
    var ringInSilentMode: Boolean
        get() = prefs.getBoolean(KEY_RING_IN_SILENT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_RING_IN_SILENT_MODE, value).apply()

    /** Meldung "Türklingel beantwortet von: ...", wenn ein anderes Telefon den Anruf angenommen hat. */
    var notifyAnsweredElsewhere: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ANSWERED_ELSEWHERE, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ANSWERED_ELSEWHERE, value).apply()

    /** Webhook-URL für den Türöffner (einfacher HTTP-POST, z. B. Home-Assistant-Webhook). */
    var doorOpenUrl: String
        get() = prefs.getString(KEY_DOOR_OPEN_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOOR_OPEN_URL, value).apply()

    /**
     * Name dieses Geräts bei der Registrierung in Home Assistant. Standardwert ist das Modell
     * mit einer zufälligen Endung, damit zwei gleiche Handys sich nicht überschreiben. Beim
     * ersten Lesen erzeugt und dauerhaft gespeichert. Nicht Teil von Export/Import (siehe
     * NON_SETTING_KEYS): sonst würde ein importiertes Backup den Namen des alten Handys auf
     * das neue übertragen und die beiden würden sich in Home Assistant überschreiben.
     */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName().also { prefs.edit().putString(KEY_DEVICE_NAME, it).apply() }
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    private fun defaultDeviceName(): String =
        "${Build.MODEL} (${UUID.randomUUID().toString().take(4)})"

    /** URL des Registrierungs-Endpunkts, an den der Push-Endpunkt dieses Geräts gemeldet wird (siehe PushRegistrar). */
    var registrationUrl: String
        get() = prefs.getString(KEY_REGISTRATION_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REGISTRATION_URL, value).apply()

    /**
     * UnifiedPush-Endpoint-URL dieses Geräts, die der Push-Verteiler (z. B. ntfy) vergeben hat.
     * Gerätespezifisch und daher nicht Teil von Export/Import (siehe NON_SETTING_KEYS).
     */
    var pushEndpoint: String
        get() = prefs.getString(KEY_PUSH_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PUSH_ENDPOINT, value).apply()

    /** Beim Klingeln außerhalb des Heimnetzes den WireGuard-Tunnel automatisch auf- und danach abbauen. */
    var wireguardAutoVpn: Boolean
        get() = prefs.getBoolean(KEY_WIREGUARD_AUTO_VPN, false)
        set(value) = prefs.edit().putBoolean(KEY_WIREGUARD_AUTO_VPN, value).apply()

    /** Name des Tunnels in der WireGuard-App. */
    var wireguardTunnel: String
        get() = prefs.getString(KEY_WIREGUARD_TUNNEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WIREGUARD_TUNNEL, value).apply()

    /**
     * Ist die Kamera nicht erreichbar (nicht im Heimnetz) und kein Auto-VPN eingerichtet, gibt es nur
     * eine leise Benachrichtigung statt zu klingeln. Aus = klingeln wie bisher.
     */
    var silentWhenUnreachable: Boolean
        get() = prefs.getBoolean(KEY_SILENT_WHEN_UNREACHABLE, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_WHEN_UNREACHABLE, value).apply()

    /**
     * Merkt sich die letzten Klingel-Ereignisse (Zeitpunkt, Weg, Ergebnis) dauerhaft,
     * damit sie sich auch nach einer Verzögerung noch nachvollziehen lassen – der
     * normale Logcat-Puffer rotiert bei viel Systemaktivität oft schon nach wenigen
     * Minuten und ist dann nicht mehr auswertbar (siehe RING_LOG_MAX_ENTRIES).
     */
    fun appendRingLogEntry(entry: String) {
        val updated = (listOf(entry) + ringLog).take(RING_LOG_MAX_ENTRIES)
        prefs.edit().putString(KEY_RING_LOG, updated.joinToString("\n")).apply()
    }

    /** Neuestes Ereignis zuerst. */
    val ringLog: List<String>
        get() = prefs.getString(KEY_RING_LOG, "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * Alle gespeicherten Einstellungen als JSON. Liest generisch sämtliche Einträge der
     * SharedPreferences, statt die Felder einzeln aufzuzählen – neue Einstellungen landen
     * damit automatisch im Export. Außen vor bleibt nur, was keine Einstellung ist
     * (siehe NON_SETTING_KEYS). Nie gespeicherte Einstellungen fehlen in der Datei und
     * erhalten beim Import ihren Standardwert.
     *
     * Die Typen stehen getrennt daneben, weil JSON Int, Long und Float nicht
     * unterscheidet, SharedPreferences beim Lesen aber auf dem exakten Typ besteht.
     */
    fun exportToJson(indent: Int = 2): String {
        val settings = JSONObject()
        val types = JSONObject()
        for ((key, value) in prefs.all.toSortedMap()) {
            if (key in NON_SETTING_KEYS) continue
            when (value) {
                is String -> types.put(key, TYPE_STRING)
                is Int -> types.put(key, TYPE_INT)
                is Long -> types.put(key, TYPE_LONG)
                is Float -> types.put(key, TYPE_FLOAT)
                is Boolean -> types.put(key, TYPE_BOOLEAN)
                is Set<*> -> types.put(key, TYPE_STRING_SET)
                else -> continue
            }
            settings.put(key, if (value is Set<*>) JSONArray(value.map { it.toString() }.sorted()) else value)
        }
        return JSONObject()
            .put("format", EXPORT_FORMAT)
            .put("version", EXPORT_VERSION)
            .put("settings", settings)
            .put("types", types)
            .toString(indent)
    }

    /**
     * Die Einstellungen in kompakter Form für einen QR-Code: gzip-komprimiertes, kompaktes
     * JSON als Base64 mit Präfix, damit fremde QR-Codes sicher abgelehnt werden können.
     */
    fun exportToQrPayload(): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(exportToJson(indent = 0).toByteArray(Charsets.UTF_8)) }
        return QR_PREFIX + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    /** Gegenstück zu [exportToQrPayload]; wirft bei fremdem oder ungültigem Inhalt. */
    fun importFromQrPayload(payload: String) {
        require(payload.startsWith(QR_PREFIX)) { "Kein Einstellungs-QR-Code dieser App" }
        val compressed = Base64.decode(payload.removePrefix(QR_PREFIX), Base64.NO_WRAP)
        val json = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes().toString(Charsets.UTF_8) }
        importFromJson(json)
    }

    /**
     * Ersetzt die Einstellungen durch den Inhalt eines Exports. Einstellungen, die in der
     * Datei fehlen, fallen auf ihren Standardwert zurück. Wirft bei ungültigem Inhalt eine
     * Exception, ohne dass etwas geändert wurde.
     */
    fun importFromJson(json: String) {
        val root = JSONObject(json)
        require(root.optString("format") == EXPORT_FORMAT) { "Keine Einstellungsdatei dieser App" }
        val settings = root.getJSONObject("settings")
        val types = root.getJSONObject("types")

        val editor = prefs.edit()
        prefs.all.keys.filterNot { it in NON_SETTING_KEYS }.forEach { editor.remove(it) }
        for (key in settings.keys()) {
            if (key in NON_SETTING_KEYS) continue
            when (val type = types.optString(key)) {
                TYPE_STRING -> editor.putString(key, settings.getString(key))
                TYPE_INT -> editor.putInt(key, settings.getInt(key))
                TYPE_LONG -> editor.putLong(key, settings.getLong(key))
                TYPE_FLOAT -> editor.putFloat(key, settings.getDouble(key).toFloat())
                TYPE_BOOLEAN -> editor.putBoolean(key, settings.getBoolean(key))
                TYPE_STRING_SET -> {
                    val array = settings.getJSONArray(key)
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
                else -> throw IllegalArgumentException("Unbekannter Typ \"$type\" für $key")
            }
        }
        editor.apply()
    }

    companion object {
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_USER = "mqtt_user"
        private const val KEY_MQTT_PASS = "mqtt_pass"
        private const val KEY_MQTT_TOPIC = "mqtt_topic"
        private const val KEY_MQTT_FALLBACK_ENABLED = "mqtt_fallback_enabled"
        private const val KEY_CAMERA_HOST = "camera_host"
        private const val KEY_CAMERA_BC_PORT = "camera_bc_port"
        private const val KEY_CAMERA_USER = "camera_user"
        private const val KEY_CAMERA_PASS = "camera_pass"
        private const val KEY_CAMERA_RTSP_PATH = "camera_rtsp_path"
        private const val KEY_KEEP_ASPECT_RATIO = "keep_aspect_ratio"
        private const val KEY_MIC_GAIN_PERCENT = "mic_gain_percent"
        private const val KEY_DUCK_WHILE_TALKING = "duck_while_talking"
        private const val KEY_VIDEO_START_BUFFER_MS = "video_start_buffer_ms"
        const val VIDEO_START_BUFFER_MIN_MS = 50
        const val VIDEO_START_BUFFER_MAX_MS = 1000
        const val VIDEO_START_BUFFER_DEFAULT_MS = 100
        private const val KEY_VIDEO_REBUFFER_MS = "video_rebuffer_ms"
        const val VIDEO_REBUFFER_MIN_MS = 100
        const val VIDEO_REBUFFER_MAX_MS = 2000
        const val VIDEO_REBUFFER_DEFAULT_MS = 300
        private const val KEY_RING_DURATION_SECONDS = "ring_duration_seconds"
        const val RING_DURATION_MIN_SECONDS = 10
        const val RING_DURATION_MAX_SECONDS = 90
        const val RING_DURATION_DEFAULT_SECONDS = 30
        private const val KEY_RINGTONE_URI = "ringtone_uri"
        private const val KEY_RING_IN_SILENT_MODE = "ring_in_silent_mode"
        private const val KEY_NOTIFY_ANSWERED_ELSEWHERE = "notify_answered_elsewhere"
        private const val KEY_DOOR_OPEN_URL = "door_open_url"
        // Schlüsselname stammt aus der FCM-Zeit; beibehalten, damit gespeicherte Einstellungen weiter gelten.
        private const val KEY_REGISTRATION_URL = "fcm_token_url"
        private const val KEY_PUSH_ENDPOINT = "push_endpoint"
        private const val KEY_WIREGUARD_AUTO_VPN = "wireguard_auto_vpn"
        private const val KEY_WIREGUARD_TUNNEL = "wireguard_tunnel"
        private const val KEY_SILENT_WHEN_UNREACHABLE = "silent_when_unreachable"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_RING_LOG = "ring_log"
        private const val RING_LOG_MAX_ENTRIES = 20

        /** Einträge, die keine Einstellung sind und weder exportiert noch beim Import angefasst werden. */
        // Der Klingelton ist ein content://-URI, der nur auf dem Quellgerät gilt.
        private val NON_SETTING_KEYS = setOf(KEY_RING_LOG, KEY_DEVICE_NAME, KEY_PUSH_ENDPOINT, KEY_RINGTONE_URI)

        private const val QR_PREFIX = "dbi1:"

        private const val EXPORT_FORMAT = "doorbell-intercom-settings"
        private const val EXPORT_VERSION = 1
        private const val TYPE_STRING = "string"
        private const val TYPE_INT = "int"
        private const val TYPE_LONG = "long"
        private const val TYPE_FLOAT = "float"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_STRING_SET = "string_set"
    }
}
