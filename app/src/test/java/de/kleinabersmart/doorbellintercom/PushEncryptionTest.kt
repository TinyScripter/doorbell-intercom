package de.kleinabersmart.doorbellintercom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.unifiedpush.android.connector.data.PushMessage
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Web-Push-Verschlüsselung auf der App-Seite: Registrierung und Ablehnen unverschlüsselter Pushes. */
@RunWith(RobolectricTestRunner::class)
class PushEncryptionTest {

    private lateinit var context: Context
    private lateinit var config: AppConfig
    private val calls get() = context.appContainer.callController
    private var server: ServerSocket? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        config = AppConfig(context)
    }

    @After
    fun tearDown() {
        server?.close()
        calls.hangUp()
        calls.decline(CallController.SOURCE_SCREEN)
    }

    private fun receive(json: String, decrypted: Boolean) {
        val service = Robolectric.buildService(DoorbellPushService::class.java).create().get()
        service.onMessage(PushMessage(json.toByteArray(), decrypted), "default")
    }

    @Test
    fun plainPush_isRejected_onceEncryptionIsConfirmed() {
        config.pushEncryptionConfirmed = true

        receive("""{"type":"doorbell_ring","id":"A"}""", decrypted = false)

        assertEquals(CallState.Idle, calls.state.value)
        assertTrue(config.ringLog.first().endsWith("Push  unverschlüsselt, verworfen"))
    }

    @Test
    fun decryptedPush_rings_whenEncryptionIsConfirmed() {
        config.pushEncryptionConfirmed = true

        receive("""{"type":"doorbell_ring","id":"B"}""", decrypted = true)

        assertTrue(calls.state.value is CallState.Ringing)
    }

    @Test
    fun plainPush_stillRings_withoutConfirmedEncryption() {
        config.pushEncryptionConfirmed = false

        receive("""{"type":"doorbell_ring","id":"C"}""", decrypted = false)

        assertTrue(calls.state.value is CallState.Ringing)
    }

    /**
     * Minimaler Registrierungs-Endpunkt für genau eine Anfrage, der [response] antwortet;
     * liefert dessen URL und den empfangenen Body.
     */
    private fun startServer(response: String): Pair<String, () -> JSONObject> {
        var received = ""
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server = socket
        Thread {
            socket.accept().use { client ->
                val input = client.getInputStream().bufferedReader()
                val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val length = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }
                    .substringAfter(':').trim().toInt()
                // Der Body ist ASCII-JSON: Zeichen = Bytes.
                received = CharArray(length).also { input.read(it, 0, length) }.concatToString()
                val bytes = response.toByteArray()
                client.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes
                )
            }
        }.start()
        return "http://127.0.0.1:${socket.localPort}/api/appdaemon/doorbell_devices?key=x" to { JSONObject(received) }
    }

    private fun register(url: String): PushRegistrar.Result {
        val done = CountDownLatch(1)
        var result: PushRegistrar.Result? = null
        PushRegistrar.send(context, url, "Pixel") {
            result = it
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result!!
    }

    @Test
    fun registration_sendsKeys_andStoresConfirmedEncryption() {
        config.pushEndpoint = "https://ntfy.example/upX"
        config.pushPublicKey = "BCVxsr7N"
        config.pushAuthSecret = "BTBZMqHH"
        val (url, received) = startServer("""{"status": "ok", "encryption": true}""")

        assertTrue(register(url).success)

        val body = received()
        assertEquals("Pixel", body.getString("device"))
        assertEquals("https://ntfy.example/upX", body.getString("endpoint"))
        assertEquals("BCVxsr7N", body.getString("p256dh"))
        assertEquals("BTBZMqHH", body.getString("auth"))
        assertTrue(config.pushEncryptionConfirmed)
    }

    @Test
    fun registrationWithOldAppDaemon_turnsEncryptionOff() {
        config.pushEndpoint = "https://ntfy.example/upX"
        config.pushEncryptionConfirmed = true
        // Ältere Versionen antworten ohne "encryption".
        val (url, received) = startServer("""{"status": "ok"}""")

        assertTrue(register(url).success)

        assertFalse(received().has("p256dh"))
        assertFalse(config.pushEncryptionConfirmed)
    }
}
