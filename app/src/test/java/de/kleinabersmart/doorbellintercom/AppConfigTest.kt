package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppConfigTest {

    private lateinit var context: Context
    private lateinit var config: AppConfig

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        config = AppConfig(context)
    }

    private fun settingsPrefs() = context.getSharedPreferences("doorbell_config", Context.MODE_PRIVATE)
    private fun devicePrefs() = context.getSharedPreferences("doorbell_device", Context.MODE_PRIVATE)

    @Test
    fun export_containsSettingsWithTypesButNoDeviceData() {
        config.cameraHost = "192.168.1.10"
        config.ringDurationSeconds = 45
        config.ringInSilentMode = true
        config.deviceName = "Pixel"
        config.pushEndpoint = "https://ntfy.example/up123"
        config.appendRingLogEntry("12:00  MQTT  Anruf angezeigt")

        val root = JSONObject(config.exportToJson())
        val settings = root.getJSONObject("settings")
        val types = root.getJSONObject("types")

        assertEquals("doorbell-intercom-settings", root.getString("format"))
        assertEquals("192.168.1.10", settings.getString("camera_host"))
        assertEquals("string", types.getString("camera_host"))
        assertEquals(45, settings.getInt("ring_duration_seconds"))
        assertEquals("int", types.getString("ring_duration_seconds"))
        assertEquals("boolean", types.getString("ring_in_silent_mode"))
        for (deviceKey in listOf("device_name", "push_endpoint", "ring_log", "ringtone_uri")) {
            assertFalse(deviceKey, settings.has(deviceKey))
        }
    }

    /** JSON kennt nur „Zahl“; die Typangabe stellt Long und Float wieder exakt her. */
    @Test
    fun exportThenImport_restoresAllTypes() {
        settingsPrefs().edit {
            putString("s", "text")
            putInt("i", 7)
            putLong("l", 1L shl 40)
            putFloat("f", 1.5f)
            putBoolean("b", true)
            putStringSet("set", setOf("a", "b"))
        }
        val json = config.exportToJson()
        settingsPrefs().edit { clear() }

        config.importFromJson(json)

        val prefs = settingsPrefs()
        assertEquals("text", prefs.getString("s", null))
        assertEquals(7, prefs.getInt("i", 0))
        assertEquals(1L shl 40, prefs.getLong("l", 0))
        assertEquals(1.5f, prefs.getFloat("f", 0f))
        assertTrue(prefs.getBoolean("b", false))
        assertEquals(setOf("a", "b"), prefs.getStringSet("set", null))
    }

    @Test
    fun import_resetsSettingsMissingInFileToDefault() {
        config.doorOpenUrl = "http://ha/webhook"
        val json = config.exportToJson()
        config.cameraHost = "10.0.0.5"

        config.importFromJson(json)

        assertEquals("", config.cameraHost)
        assertEquals("http://ha/webhook", config.doorOpenUrl)
    }

    @Test
    fun import_keepsDeviceDataEvenIfFileContainsIt() {
        config.deviceName = "Dieses Handy"
        val json = JSONObject(config.exportToJson()).apply {
            getJSONObject("settings").put("device_name", "Altes Handy")
            getJSONObject("types").put("device_name", "string")
        }.toString()

        config.importFromJson(json)

        assertEquals("Dieses Handy", config.deviceName)
        assertFalse(settingsPrefs().contains("device_name"))
    }

    @Test
    fun import_rejectsForeignFileWithoutChangingAnything() {
        config.cameraHost = "192.168.1.10"
        assertThrows(IllegalArgumentException::class.java) {
            config.importFromJson("""{"format":"other","settings":{},"types":{}}""")
        }
        assertEquals("192.168.1.10", config.cameraHost)
    }

    @Test
    fun import_rejectsUnknownType() {
        val json = """{"format":"doorbell-intercom-settings","version":1,
            "settings":{"x":1},"types":{"x":"double"}}"""
        assertThrows(IllegalArgumentException::class.java) { config.importFromJson(json) }
    }

    @Test
    fun qrPayload_roundTrip() {
        config.cameraHost = "192.168.1.10"
        config.micGainPercent = 250
        val payload = config.exportToQrPayload()
        settingsPrefs().edit { clear() }

        config.importFromQrPayload(payload)

        assertTrue(payload.startsWith("dbi1:"))
        assertEquals("192.168.1.10", config.cameraHost)
        assertEquals(250, config.micGainPercent)
    }

    @Test
    fun qrPayload_rejectsForeignCode() {
        assertThrows(IllegalArgumentException::class.java) { config.importFromQrPayload("https://example.com") }
    }

    /** Bis 1.0.1 lagen die Gerätedaten in der Einstellungs-Datei. */
    @Test
    fun migration_movesDeviceKeysOutOfSettings() {
        settingsPrefs().edit {
            putString("device_name", "Altgerät")
            putString("push_endpoint", "https://ntfy.example/old")
            putString("camera_host", "192.168.1.10")
        }
        devicePrefs().edit { putString("push_endpoint", "https://ntfy.example/new") }

        val migrated = AppConfig(context)

        assertEquals("Altgerät", migrated.deviceName)
        // Ein schon vorhandener Wert in der neuen Datei gewinnt.
        assertEquals("https://ntfy.example/new", migrated.pushEndpoint)
        assertFalse(settingsPrefs().contains("device_name"))
        assertFalse(settingsPrefs().contains("push_endpoint"))
        assertEquals("192.168.1.10", migrated.cameraHost)
    }

    @Test
    fun snapshot_seesRingtoneChangeButNotRingLog() {
        val before = config.snapshot()
        config.appendRingLogEntry("12:00  Push  Anruf angezeigt")
        config.pushEndpoint = "https://ntfy.example/up"
        assertEquals(before, config.snapshot())

        config.ringtoneUri = Uri.parse("content://media/internal/audio/media/42")
        assertNotEquals(before, config.snapshot())
    }

    @Test
    fun ringLog_keepsNewestFirstAndAtMost20Entries() {
        repeat(25) { config.appendRingLogEntry("Eintrag $it") }
        assertEquals(20, config.ringLog.size)
        assertEquals("Eintrag 24", config.ringLog.first())
    }

    @Test
    fun ringDuration_isClampedToAllowedRange() {
        config.ringDurationSeconds = 5
        assertEquals(AppConfig.RING_DURATION_MIN_SECONDS, config.ringDurationSeconds)
        config.ringDurationSeconds = 500
        assertEquals(AppConfig.RING_DURATION_MAX_SECONDS, config.ringDurationSeconds)
    }

    @Test
    fun rtspUrl_encodesCredentials() {
        config.cameraHost = "192.168.1.10"
        config.cameraUsername = "admin"
        config.cameraPassword = "p@ss:wort"
        assertEquals("rtsp://admin:p%40ss%3Awort@192.168.1.10:554/h264Preview_01_main", config.rtspUrl())
    }
}
