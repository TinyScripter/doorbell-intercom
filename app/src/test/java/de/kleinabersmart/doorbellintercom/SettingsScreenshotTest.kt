package de.kleinabersmart.doorbellintercom

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPowerManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Baseline screenshot tests of the settings screens (activity_settings.xml scaffold with
 * settings_main/camera/ringing/push/connections/ring_log/about.xml and item_settings_entry.xml).
 *
 * The real Activities are launched with `Robolectric.buildActivity(...).setup()` (create, start,
 * resume): under Robolectric their side effects are harmless. Section pages only write the
 * already normalized seed values back in onCreate (no change, so no service/push restart; persist
 * would only run on pause/finish, which the tests never trigger), the WireGuard permission is
 * granted beforehand so ConnectionsSettingsActivity requests nothing, and SystemCheck reads the
 * notification/power shadows that each test sets explicitly.
 *
 * Every screen is captured twice:
 *  - `_01_initial`: the window (decor view) at the regular device size, like the other screenshot tests.
 *  - `_02_fullContent`: the same screen relaunched in a tall window ([FULL_HEIGHT_QUALIFIER]) so the
 *    whole scroll content is visible without scrolling; the test asserts that nothing is left to scroll.
 * The QR dialog is captured as its own window (dialog decor view). Before each window capture the
 * main looper is idled and the ScrollView scrollbar is disabled (see [capture]).
 * The main settings page reads SystemCheck; its state is forced with [setSystemCheck].
 *
 * Device: Pixel 9 class phone (1080 x 2424 px at 420 dpi = 411 x 923 dp), light mode, values/ strings.
 * AppConfig is seeded with fixed values ([seedConfig]) before each launch. Screenshots go to
 * app/build/outputs/screenshots/SettingsScreenshotTest/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [36],
    qualifiers = "en-rUS-w411dp-h923dp-port-notnight-420dpi",
    shadows = [
        SettingsScreenshotTest.SystemCheckNotificationManagerShadow::class,
        SettingsScreenshotTest.SystemCheckPowerManagerShadow::class,
    ],
)
class SettingsScreenshotTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        seedConfig()
        // ConnectionsSettingsActivity would otherwise request it on create (auto VPN is on).
        shadowOf(app).grantPermissions(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS)
    }

    /** Deterministic settings for all screens (already on the slider steps, so nothing is normalized). */
    private fun seedConfig() {
        AppConfig(app).apply {
            cameraHost = "192.168.178.40"
            cameraBcPort = 9000
            cameraUsername = "admin"
            cameraPassword = "doorbell-secret"
            cameraRtspPath = "h264Preview_01_main"
            keepAspectRatio = true
            micGainPercent = 300
            duckWhileTalking = true
            videoStartBufferMs = 150
            videoRebufferMs = 400

            ringDurationSeconds = 45
            ringInSilentMode = true
            telecomCalls = false

            registrationUrl = "https://ha.example.com/api/webhook/doorbell_register"
            deviceName = "Pixel 9 (a1b2)"
            mqttFallbackEnabled = true
            mqttHost = "192.168.178.10"
            mqttPort = 1883
            mqttUsername = "doorbell"
            mqttPassword = "mqtt-secret"
            mqttTopic = "doorbell/ring"
            notifyAnsweredElsewhere = true

            doorOpenUrl = "https://ha.example.com/api/webhook/door_open"
            wireguardAutoVpn = true
            wireguardTunnel = "home"
            silentWhenUnreachable = true
        }
    }

    /**
     * Everything SystemCheck looks at is fine ([ok] = true) or all three checks fail. The built-in
     * shadow setters do not reach canUseFullScreenIntent / isIgnoringBatteryOptimizations on SDK 36,
     * hence the two small custom shadows below.
     */
    private fun setSystemCheck(ok: Boolean) {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(ok)
        SystemCheckNotificationManagerShadow.canUseFullScreenIntent = ok
        SystemCheckPowerManagerShadow.ignoringBatteryOptimizations = ok
        val issues = SystemCheck.issues(app).map { app.getString(it.title) }
        println("SYSTEM_CHECK ok=$ok: $issues")
        assertEquals(if (ok) 0 else 3, issues.size)
    }

    /** NotificationManager with a settable full-screen-intent permission (otherwise as Robolectric's shadow). */
    @Implements(NotificationManager::class)
    class SystemCheckNotificationManagerShadow : ShadowNotificationManager() {
        @Suppress("unused") // called by Robolectric instead of NotificationManager.canUseFullScreenIntent()
        @Implementation(minSdk = 34)
        fun canUseFullScreenIntent(): Boolean = canUseFullScreenIntent

        companion object {
            /** Set by every test that shows the main settings page ([setSystemCheck]). */
            var canUseFullScreenIntent = false
        }
    }

    /** PowerManager with a settable battery optimization state for this app (otherwise as Robolectric's shadow). */
    @Implements(PowerManager::class)
    class SystemCheckPowerManagerShadow : ShadowPowerManager() {
        @Implementation
        override fun isIgnoringBatteryOptimizations(packageName: String?): Boolean = ignoringBatteryOptimizations

        companion object {
            /** Set by every test that shows the main settings page ([setSystemCheck]). */
            var ignoringBatteryOptimizations = false
        }
    }

    // ---- Main settings -------------------------------------------------------------------------

    @Test
    fun settingsMain() {
        setSystemCheck(ok = true)
        captureScreen(SettingsActivity::class.java, "settingsMain") { activity ->
            assertTitle(activity, R.string.toolbar_settings_title)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.checkTitle).visibility)
            assertEquals(0, activity.findViewById<ViewGroup>(R.id.checkList).childCount)
            assertEntries(activity)
            assertBackupCard(activity)
            assertEquals("", activity.findViewById<TextView>(R.id.statusText).text.toString())
        }
    }

    @Test
    fun settingsMainSystemCheck() {
        setSystemCheck(ok = false)
        captureScreen(SettingsActivity::class.java, "settingsMainSystemCheck") { activity ->
            assertTitle(activity, R.string.toolbar_settings_title)
            val checkTitle = activity.findViewById<TextView>(R.id.checkTitle)
            assertTrue(checkTitle.isShown)
            assertEquals(activity.getString(R.string.check_title), checkTitle.text.toString())
            val issues = activity.findViewById<ViewGroup>(R.id.checkList)
            val expected = listOf(
                R.string.check_notifications_off to R.string.check_notifications_off_summary,
                R.string.check_full_screen_off to R.string.check_full_screen_off_summary,
                R.string.check_battery_optimized to R.string.check_battery_optimized_summary,
            )
            assertEquals(expected.size, issues.childCount)
            val entryTitleColor = activity.findViewById<ViewGroup>(R.id.entryList).getChildAt(0)
                .findViewById<TextView>(R.id.entryTitle).currentTextColor
            expected.forEachIndexed { index, (title, summary) ->
                val row = issues.getChildAt(index)
                assertTrue(row.isShown && row.isClickable)
                val titleView = row.findViewById<TextView>(R.id.entryTitle)
                assertEquals(activity.getString(title), titleView.text.toString())
                assertEquals(activity.getString(summary), row.findViewById<TextView>(R.id.entrySummary).text.toString())
                // Error color like the "Setup check" title, not the primary color of the entries.
                assertEquals(checkTitle.currentTextColor, titleView.currentTextColor)
                assertNotEquals(entryTitleColor, titleView.currentTextColor)
            }
            assertEntries(activity)
            assertBackupCard(activity)
        }
    }

    @Test
    fun settingsMainQrDialog() {
        setSystemCheck(ok = true)
        val activity = launch(SettingsActivity::class.java)
        capture(activity, "settingsMainQrDialog_01_initial")

        assertTrue(activity.findViewById<Button>(R.id.btnShowQr).performClick())
        settle()

        val dialog: Dialog = ShadowDialog.getLatestDialog()
        assertNotNull("no QR dialog", dialog)
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.title_qr_dialog),
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.message_qr_dialog),
            dialog.findViewById<TextView>(android.R.id.message).text.toString(),
        )
        val qrImage = findViews(dialog.window!!.decorView, ImageView::class.java).single { it.drawable != null }
        assertTrue("QR image not laid out", qrImage.isShown && qrImage.width > 0 && qrImage.height > 0)
        assertEquals(activity.getString(R.string.btn_close), dialog.findViewById<Button>(android.R.id.button1).text.toString())
        assertEquals("", activity.findViewById<TextView>(R.id.statusText).text.toString())

        // The dialog window alone (its own decor view), the same way for the Compose dialog.
        captureView(dialog.window!!.decorView, "settingsMainQrDialog_02_dialogShown")
    }

    // ---- Section pages -------------------------------------------------------------------------

    @Test
    fun settingsCamera() {
        captureScreen(CameraSettingsActivity::class.java, "settingsCamera") { activity ->
            assertTitle(activity, R.string.section_camera)
            assertField(activity, R.id.editCameraHost, "192.168.178.40")
            assertField(activity, R.id.editCameraBcPort, "9000")
            assertField(activity, R.id.editCameraUser, "admin")
            assertField(activity, R.id.editCameraPass, "doorbell-secret")
            assertField(activity, R.id.editCameraRtspPath, "h264Preview_01_main")
            assertTrue(activity.findViewById<CheckBox>(R.id.checkKeepAspectRatio).isChecked)
            assertTrue(activity.findViewById<CheckBox>(R.id.checkDuckWhileTalking).isChecked)
            assertSlider(activity, R.id.sliderMicGain, 300f, R.id.textMicGain, activity.getString(R.string.label_mic_gain, 300))
            assertSlider(
                activity, R.id.sliderVideoStartBuffer, 150f,
                R.id.textVideoStartBuffer, activity.getString(R.string.label_video_start_buffer, 150),
            )
            assertSlider(
                activity, R.id.sliderVideoRebuffer, 400f,
                R.id.textVideoRebuffer, activity.getString(R.string.label_video_rebuffer, 400),
            )
        }
    }

    @Test
    fun settingsRinging() {
        captureScreen(RingingSettingsActivity::class.java, "settingsRinging") { activity ->
            assertTitle(activity, R.string.section_ringing)
            assertSlider(
                activity, R.id.sliderRingDuration, 45f,
                R.id.textRingDuration, activity.getString(R.string.label_ring_duration, 45),
            )
            // Title of the default ringtone as Robolectric resolves it (deterministic, no media provider).
            val ringtone = activity.findViewById<TextView>(R.id.textRingtone).text.toString()
            val prefix = activity.getString(R.string.label_ringtone, "")
            assertTrue("ringtone label: $ringtone", ringtone.startsWith(prefix) && ringtone.length > prefix.length)
            println("RINGTONE_LABEL: $ringtone")
            assertTrue(activity.findViewById<Button>(R.id.btnChooseRingtone).isShown)
            assertTrue(activity.findViewById<MaterialSwitch>(R.id.switchRingInSilentMode).isChecked)
            assertFalse(activity.findViewById<MaterialSwitch>(R.id.switchTelecomCalls).isChecked)
            assertNotNull(findTextView(activity, activity.getString(R.string.switch_telecom_calls_summary)))
        }
    }

    @Test
    fun settingsPush() {
        captureScreen(PushSettingsActivity::class.java, "settingsPush") { activity ->
            assertTitle(activity, R.string.section_push_settings)
            assertField(activity, R.id.editRegistrationUrl, "https://ha.example.com/api/webhook/doorbell_register")
            assertField(activity, R.id.editDeviceName, "Pixel 9 (a1b2)")
            assertTrue(activity.findViewById<Button>(R.id.btnRegisterDevice).isEnabled)
            assertTrue(activity.findViewById<Button>(R.id.btnUnregisterDevice).isEnabled)
            assertTrue(activity.findViewById<MaterialSwitch>(R.id.switchMqttFallback).isChecked)
            assertField(activity, R.id.editMqttHost, "192.168.178.10")
            assertField(activity, R.id.editMqttPort, "1883")
            assertField(activity, R.id.editMqttUser, "doorbell")
            assertField(activity, R.id.editMqttPass, "mqtt-secret")
            assertField(activity, R.id.editMqttTopic, "doorbell/ring")
            assertTrue(activity.findViewById<CheckBox>(R.id.checkNotifyAnsweredElsewhere).isChecked)
        }
    }

    @Test
    fun settingsConnections() {
        captureScreen(ConnectionsSettingsActivity::class.java, "settingsConnections") { activity ->
            assertTitle(activity, R.string.section_connections)
            assertField(activity, R.id.editDoorOpenUrl, "https://ha.example.com/api/webhook/door_open")
            assertTrue(activity.findViewById<MaterialSwitch>(R.id.switchWireguardAutoVpn).isChecked)
            assertField(activity, R.id.editWireguardTunnel, "home")
            assertTrue(activity.findViewById<MaterialSwitch>(R.id.switchSilentWhenUnreachable).isChecked)
            // Permission was granted beforehand: no permission request on create.
            assertNull(shadowOf(activity).lastRequestedPermission)
        }
    }

    @Test
    fun settingsRingLogEmpty() {
        captureScreen(RingLogActivity::class.java, "settingsRingLogEmpty") { activity ->
            assertTitle(activity, R.string.section_ring_log)
            assertEquals(activity.getString(R.string.ring_log_empty), activity.findViewById<TextView>(R.id.textRingLog).text.toString())
            val copy = activity.findViewById<Button>(R.id.btnCopyRingLog)
            assertTrue(copy.isShown)
            assertFalse(copy.isEnabled)
        }
    }

    @Test
    fun settingsRingLogEntries() {
        // Oldest first: appendRingLogEntry puts the newest entry on top.
        val config = AppConfig(app)
        RING_LOG_ENTRIES.reversed().forEach(config::appendRingLogEntry)
        captureScreen(RingLogActivity::class.java, "settingsRingLogEntries") { activity ->
            assertTitle(activity, R.string.section_ring_log)
            assertEquals(RING_LOG_ENTRIES.joinToString("\n"), activity.findViewById<TextView>(R.id.textRingLog).text.toString())
            val copy = activity.findViewById<Button>(R.id.btnCopyRingLog)
            assertTrue(copy.isShown)
            assertTrue(copy.isEnabled)
        }
    }

    @Test
    fun settingsAbout() {
        captureScreen(AboutActivity::class.java, "settingsAbout") { activity ->
            assertTitle(activity, R.string.section_about)
            val versionName = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
            println("VERSION_NAME: $versionName")
            assertEquals(activity.getString(R.string.about_version, versionName), activity.findViewById<TextView>(R.id.textVersion).text.toString())
            for (text in listOf(
                R.string.about_third_party_title,
                R.string.about_libraries_title,
                R.string.about_libraries_variant,
            )) {
                assertNotNull("missing ${activity.resources.getResourceEntryName(text)}", findTextView(activity, activity.getString(text)))
            }
        }
    }

    // ---- Shared checks -------------------------------------------------------------------------

    private fun assertTitle(activity: Activity, titleRes: Int) {
        assertEquals(activity.getString(titleRes), activity.title.toString())
        assertNotNull(findTextView(activity, activity.getString(titleRes)))
    }

    /** The six navigation entries of the main settings page, in order. */
    private fun assertEntries(activity: Activity) {
        val entries = activity.findViewById<ViewGroup>(R.id.entryList)
        val expected = listOf(
            R.string.section_camera to R.string.section_camera_summary,
            R.string.section_ringing to R.string.section_ringing_summary,
            R.string.section_push_settings to R.string.section_push_settings_summary,
            R.string.section_connections to R.string.section_connections_summary,
            R.string.section_ring_log to R.string.section_ring_log_summary,
            R.string.section_about to R.string.section_about_summary,
        )
        assertEquals(expected.size, entries.childCount)
        expected.forEachIndexed { index, (title, summary) ->
            val row = entries.getChildAt(index)
            assertTrue(row.isClickable)
            assertEquals(activity.getString(title), row.findViewById<TextView>(R.id.entryTitle).text.toString())
            assertEquals(activity.getString(summary), row.findViewById<TextView>(R.id.entrySummary).text.toString())
        }
    }

    private fun assertBackupCard(activity: Activity) {
        for ((id, text) in listOf(
            R.id.btnExportSettings to R.string.btn_export_settings,
            R.id.btnImportSettings to R.string.btn_import_settings,
            R.id.btnShowQr to R.string.btn_show_qr,
            R.id.btnScanQr to R.string.btn_scan_qr,
        )) {
            val button = activity.findViewById<Button>(id)
            assertTrue(button.isEnabled)
            assertEquals(activity.getString(text), button.text.toString())
        }
    }

    private fun assertField(activity: Activity, id: Int, expected: String) {
        assertEquals(expected, activity.findViewById<EditText>(id).text.toString())
    }

    private fun assertSlider(activity: Activity, sliderId: Int, value: Float, labelId: Int, label: String) {
        assertEquals(value, activity.findViewById<Slider>(sliderId).value, 0f)
        assertEquals(label, activity.findViewById<TextView>(labelId).text.toString())
    }

    private fun findTextView(activity: Activity, text: String): TextView? =
        findViews(activity.window.decorView, TextView::class.java).firstOrNull { it.text.toString() == text && it.isShown }

    private fun <T : View> findViews(root: View, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        fun visit(view: View) {
            if (type.isInstance(view)) result += type.cast(view)!!
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return result
    }

    // ---- Launch & capture ----------------------------------------------------------------------

    private fun <T : Activity> launch(type: Class<T>): T {
        val activity = Robolectric.buildActivity(type).setup().get()
        settle()
        // Touch mode like a phone: no EditText gets initial focus, so no cursor is drawn.
        assertTrue(activity.window.decorView.isInTouchMode)
        assertFalse("unexpected focus: ${activity.currentFocus}", activity.currentFocus is EditText)
        return activity
    }

    /**
     * `<method>_01_initial`: window at the regular size. `<method>_02_fullContent`: relaunched in a
     * window of [FULL_HEIGHT_QUALIFIER] so the whole scroll content is visible (asserted).
     */
    private fun <T : Activity> captureScreen(type: Class<T>, method: String, checks: (T) -> Unit) {
        val activity = launch(type)
        checks(activity)
        capture(activity, "${method}_01_initial")

        RuntimeEnvironment.setQualifiers(FULL_HEIGHT_QUALIFIER)
        val tall = launch(type)
        checks(tall)
        val scroll = tall.findViewById<View>(R.id.settingsContent).parent as ScrollView
        val content = scroll.getChildAt(0)
        val density = tall.resources.displayMetrics.density
        println("CONTENT_HEIGHT $method: ${content.height / density} dp in viewport ${scroll.height / density} dp")
        assertTrue("content of $method does not fit into the tall window", content.height <= scroll.height)
        assertFalse(scroll.canScrollVertically(1))
        capture(tall, "${method}_02_fullContent")
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Draws the whole window (decor view) onto an opaque bitmap, as the Compose counterpart will.
     * The ScrollView scrollbar is switched off first: on a device it fades out shortly after launch,
     * under Robolectric it stays visible (even with the clock advanced), and Compose's
     * verticalScroll draws none.
     */
    private fun capture(activity: Activity, fileName: String) {
        settle()
        findViews(activity.window.decorView, ScrollView::class.java).forEach { it.isVerticalScrollBarEnabled = false }
        captureView(activity.window.decorView, fileName)
    }

    private fun captureView(root: View, fileName: String) {
        settle()
        assertTrue("view not laid out", root.width > 0 && root.height > 0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        root.draw(canvas)
        saveScreenshot(bitmap, fileName)
    }

    private fun saveScreenshot(bitmap: Bitmap, fileName: String) {
        val localDir = screenshotDir()
        if (!localDir.exists()) {
            localDir.mkdirs()
        }
        val file = File(localDir, "$fileName.png")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("SCREENSHOT_DIR: ${localDir.absolutePath}")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    companion object {
        /**
         * Tall window for the full-content captures (same width and density, only the height grows).
         * The longest page (About) needs about 1280 dp of scroll content plus the 64 dp top bar.
         */
        // Full string, not "+h1600dp": the relative form re-derives the width from the rounded
        // 410 dp of the running config and renders 1076 instead of 1078 px.
        private const val FULL_HEIGHT_QUALIFIER = "en-rUS-w411dp-h1600dp-port-notnight-420dpi"

        /** Newest first, as RingLogActivity shows them (format of CallController). */
        private val RING_LOG_ENTRIES = listOf(
            "16.11.2026 18:42:07  Push  Anruf angezeigt",
            "16.11.2026 18:42:15  Bildschirm  angenommen",
            "16.11.2026 12:03:51  MQTT  nur leise Benachrichtigung (Kamera nicht erreichbar)",
            "15.11.2026 09:30:12  Push  verpasst",
            "14.11.2026 20:11:45  Push  angenommen von: Pixel 8 (c3d4)",
        )

        private fun screenshotDir() = File("build/outputs/screenshots/${SettingsScreenshotTest::class.java.simpleName}")

        /** Once per class, never per method: otherwise earlier captures of this run would be lost. */
        @JvmStatic
        @BeforeClass
        fun clearScreenshots() {
            screenshotDir().listFiles { file -> file.extension == "png" }?.forEach { it.delete() }
        }
    }
}
