package de.kleinabersmart.doorbellintercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Läuft dauerhaft im Vordergrund, hält die MQTT-Verbindung zum lokalen Broker
 * aber nur offen, wenn sie gebraucht wird:
 *
 * - Hängt das Handy in einem WLAN ohne Internet (Android konnte den Zugang
 *   nicht bestätigen), kann Home Assistant keinen Push schicken – dann
 *   übernimmt MQTT. Ob das WLAN das Heimnetz ist, entscheidet allein die
 *   Erreichbarkeit des Brokers, dafür ist kein SSID-Abgleich (und damit keine
 *   Standort-Berechtigung) nötig.
 * - Mit Internet kommt das Klingeln per Push, MQTT ist dann pausiert und der
 *   Dienst wartet nur ereignisgesteuert auf Netzwerkwechsel.
 * - Ist der Push gar nicht eingerichtet (keine Registrierungs-URL), läuft MQTT
 *   dauerhaft wie bisher.
 *
 * Wichtig: Für Android-Akku-Optimierung eine Ausnahme für diese App
 * einrichten, sonst killt das System den Dienst im Hintergrund.
 */
class DoorbellForegroundService : Service() {

    /** [bindTo]: WLAN ohne Internet, an das der Prozess gebunden wird – oder null. */
    private data class Mode(val mqtt: Boolean, val bindTo: Network?)

    private var mqttClient: MqttClientWrapper? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectivityManager: ConnectivityManager

    // Wird nur auf dem Main-Thread gelesen/geschrieben (Callback läuft über handler).
    private val wifiNetworks = mutableMapOf<Network, NetworkCapabilities>()

    @Volatile
    private var current = Mode(mqtt = false, bindTo = null)

    private val applyModeRunnable = Runnable { applyMode(force = false) }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            wifiNetworks[network] = caps
            scheduleModeUpdate()
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
            scheduleModeUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildServiceNotification(getString(R.string.service_checking_network)))

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        // INTERNET entfernen, damit auch WLANs gemeldet werden, die Android
        // bereits als "kein Internet" eingestuft hat.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, wifiCallback, handler)
    }

    // Wird bei jedem start() aufgerufen (auch nach "Speichern" in den Einstellungen),
    // onCreate dagegen nur einmal – daher hier neu verbinden, damit geänderte
    // Zugangsdaten sofort greifen.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(applyModeRunnable)
        applyMode(force = true)
        return START_STICKY
    }

    /** WLAN, über das kein Push ankommen kann; Captive Portals sind fremde Hotspots. */
    private fun offlineWifi(): Network? = wifiNetworks.entries.firstOrNull { (_, caps) ->
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }?.key

    private fun desiredMode(): Mode {
        val offline = offlineWifi()
        val pushConfigured = AppConfig(this).registrationUrl.isNotBlank()
        return Mode(mqtt = offline != null || !pushConfigured, bindTo = offline)
    }

    // Entprellt: Ein frisch verbundenes WLAN ist kurz "unbestätigt", und kurze
    // Internet-Aussetzer sollen kein ständiges Verbinden/Trennen auslösen. Beim
    // Zurückschalten länger warten – IncomingCallNotifier filtert Doppel-Klingeln
    // aus, eine Überlappung beider Wege schadet also nicht.
    private fun scheduleModeUpdate() {
        handler.removeCallbacks(applyModeRunnable)
        val target = desiredMode()
        if (target == current) return
        val boundNetworkGone = current.bindTo != null && current.bindTo !in wifiNetworks
        val delayMs = when {
            boundNetworkGone -> 0L
            target.bindTo != null -> ACTIVATE_DELAY_MS
            current.bindTo != null -> DEACTIVATE_DELAY_MS
            else -> 0L
        }
        handler.postDelayed(applyModeRunnable, delayMs)
    }

    private fun applyMode(force: Boolean) {
        val target = desiredMode()
        if (target == current && !force) return
        Log.i(TAG, "Modus: $current -> $target")

        mqttClient?.disconnect()
        mqttClient = null

        // Ohne Bindung schickt Android bei aktiven mobilen Daten auch Verbindungen
        // ins LAN (Broker, Kamera, Webhooks) über Mobilfunk, wo sie scheitern.
        if (target.bindTo != current.bindTo) {
            connectivityManager.bindProcessToNetwork(target.bindTo)
        }
        current = target

        if (target.mqtt) {
            connectMqtt()
        } else {
            updateNotification(getString(R.string.service_standby_push))
        }
    }

    private fun connectMqtt() {
        val config = AppConfig(this)
        if (config.mqttHost.isBlank()) {
            updateNotification(getString(R.string.service_mqtt_not_configured))
            return
        }
        updateNotification(withReason(getString(R.string.service_mqtt_connecting)))

        mqttClient = MqttClientWrapper(
            context = this,
            host = config.mqttHost,
            port = config.mqttPort,
            username = config.mqttUsername,
            password = config.mqttPassword,
            onMessage = { topic, payload ->
                Log.i(TAG, "MQTT-Nachricht auf $topic empfangen: \"$payload\"")
                IncomingCallNotifier.ring(this, source = "MQTT")
            },
            onConnectionChanged = { connected ->
                updateNotification(
                    withReason(
                        getString(
                            if (connected) R.string.service_mqtt_connected
                            else R.string.service_mqtt_disconnected
                        )
                    )
                )
            },
            onConnectFailed = { reason, willRetry ->
                updateNotification(
                    withReason(
                        getString(
                            if (willRetry) R.string.service_mqtt_failed_retry
                            else R.string.service_mqtt_gave_up,
                            reason
                        )
                    )
                )
            }
        )
        mqttClient?.connect(config.mqttTopic)
    }

    private fun withReason(text: String): String {
        val reason = if (current.bindTo != null) getString(R.string.service_reason_offline_wifi) else "MQTT"
        return "$reason – $text"
    }

    private fun buildServiceNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildServiceNotification(text))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_SERVICE,
                getString(R.string.channel_service_status),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(applyModeRunnable)
        connectivityManager.unregisterNetworkCallback(wifiCallback)
        mqttClient?.disconnect()
        mqttClient = null
        if (current.bindTo != null) connectivityManager.bindProcessToNetwork(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DoorbellForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID_SERVICE = "doorbell_service"
        private const val ACTIVATE_DELAY_MS = 10_000L
        private const val DEACTIVATE_DELAY_MS = 30_000L

        fun startOrStop(context: Context) {
            val intent = Intent(context, DoorbellForegroundService::class.java)
            if (!AppConfig(context).mqttFallbackEnabled) {
                context.stopService(intent)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
