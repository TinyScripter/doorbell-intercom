package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dünner Wrapper um den HiveMQ-MQTT-Client (reines Java/Kotlin, kein
 * veralteter Android-Service-Wrapper wie bei Eclipse Paho nötig).
 * Läuft rein im lokalen Netz – TLS ist optional, aber empfehlenswert,
 * falls dein Broker das unterstützt.
 *
 * Verbindungsversuche (erster Aufbau wie auch nach einem Abbruch) werden
 * selbst wiederholt statt über HiveMQs automaticReconnect, damit nach
 * MAX_FAILED_ATTEMPTS Fehlversuchen in Folge Schluss ist – etwa in einem
 * fremden WLAN, in dem der Broker nie erreichbar ist. Eine neue Instanz
 * (Netzwerkwechsel, App-Start, Speichern der Einstellungen) versucht es erneut.
 */
class MqttClientWrapper(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val onMessage: (topic: String, payload: String) -> Unit,
    private val onConnectionChanged: (connected: Boolean) -> Unit,
    private val onConnectFailed: (reason: String, willRetry: Boolean) -> Unit
) {
    @Volatile
    private var client: Mqtt5AsyncClient? = null
    private val retryExecutor = Executors.newSingleThreadScheduledExecutor()
    private val failedAttempts = AtomicInteger(0)

    @Volatile
    private var stopped = false

    @Volatile
    private var connected = false

    fun connect(topic: String) {
        val builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier("doorbell-intercom-${System.currentTimeMillis()}")
            .serverHost(host)
            .serverPort(port)
        val authedBuilder = if (username.isNotBlank()) {
            builder.simpleAuth()
                .username(username)
                .password(password.toByteArray())
                .applySimpleAuth()
        } else {
            builder
        }
        client = authedBuilder
            // Bei jeder (Wieder-)Verbindung neu abonnieren: eine neue MQTT-Sitzung
            // kennt das alte Abo nicht mehr, sonst kommen nach einem
            // Verbindungsabbruch keine Nachrichten mehr an.
            .addConnectedListener {
                if (stopped) return@addConnectedListener
                Log.i(TAG, "MQTT verbunden mit $host:$port, abonniere $topic")
                connected = true
                failedAttempts.set(0)
                onConnectionChanged(true)
                subscribe(topic)
            }
            // Fehlgeschlagene connect()-Aufrufe behandelt attemptConnect; hier nur
            // Abbrüche einer bereits bestehenden Verbindung.
            .addDisconnectedListener { disconnectedContext ->
                if (stopped || !connected) return@addDisconnectedListener
                connected = false
                Log.w(TAG, "MQTT-Verbindung getrennt", disconnectedContext.cause)
                onConnectionChanged(false)
                scheduleAttempt(topic)
            }
            .buildAsync()

        attemptConnect(topic)
    }

    private fun attemptConnect(topic: String) {
        val c = client ?: return
        if (stopped) return

        c.connect().whenComplete { _, throwable ->
            if (stopped) {
                // disconnect() kam, während der Aufbau noch lief – die inzwischen
                // doch zustande gekommene Verbindung sauber schließen.
                if (throwable == null) c.disconnect()
                return@whenComplete
            }
            if (throwable != null) {
                val failures = failedAttempts.incrementAndGet()
                val willRetry = failures < MAX_FAILED_ATTEMPTS
                Log.w(TAG, "MQTT-Verbindung zu $host:$port fehlgeschlagen ($failures/$MAX_FAILED_ATTEMPTS)", throwable)
                onConnectionChanged(false)
                onConnectFailed(throwable.message ?: throwable.javaClass.simpleName, willRetry)
                if (willRetry) scheduleAttempt(topic)
            }
        }
    }

    private fun scheduleAttempt(topic: String) {
        if (stopped) return
        retryExecutor.schedule(
            {
                if (!stopped && client?.state == MqttClientState.DISCONNECTED) {
                    attemptConnect(topic)
                }
            },
            RETRY_DELAY_SECONDS,
            TimeUnit.SECONDS
        )
    }

    private fun subscribe(topic: String) {
        client?.subscribeWith()
            ?.topicFilter(topic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes, Charsets.UTF_8)
                onMessage(publish.topic.toString(), payload)
            }
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.w(TAG, "Abonnieren von $topic fehlgeschlagen", throwable)
                    onConnectFailed(
                        context.getString(R.string.service_mqtt_subscribe_failed, throwable.message),
                        false
                    )
                }
            }
    }

    fun disconnect() {
        stopped = true
        retryExecutor.shutdownNow()
        client?.disconnect()
        client = null
    }

    private companion object {
        const val TAG = "MqttClientWrapper"
        const val RETRY_DELAY_SECONDS = 10L
        const val MAX_FAILED_ATTEMPTS = 3
    }
}
