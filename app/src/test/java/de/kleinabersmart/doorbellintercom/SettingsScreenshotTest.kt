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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsEntry
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPowerManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Screenshot tests of the settings screens. Written as the baseline of the XML layouts
 * (activity_settings.xml scaffold with settings_main/camera/ringing/push/connections/ring_log/about.xml
 * and item_settings_entry.xml); since the pages were replaced by Compose (SettingsScreen,
 * CameraSettingsScreen, ...) the same test methods drive the same scenarios on the Compose pages,
 * with the former View checks expressed as Compose semantics (test tags from SettingsTestTags),
 * and keep their capture names.
 *
 * The real Activities are launched with `Robolectric.buildActivity(...).setup()` (create, start,
 * resume): under Robolectric their side effects are harmless. Section pages only write the
 * already normalized seed values back in onCreate (no change, so no service/push restart; closing
 * a page persists the same values again), the WireGuard permission is granted beforehand so
 * ConnectionsSettingsActivity requests nothing, and SystemCheck reads the notification/power
 * shadows that each test sets explicitly.
 *
 * Every screen is captured twice:
 *  - `_01_initial`: the window (decor view) at the regular device size, like the other screenshot tests.
 *  - `_02_fullContent`: the same screen relaunched in a tall window ([FULL_HEIGHT_QUALIFIER]) so the
 *    whole scroll content is visible without scrolling; the test asserts that the scroll range is 0.
 * The first Activity is closed before the tall relaunch (and every Activity after each test), so
 * that only one Compose hierarchy is attached. The QR dialog is captured as its own window (the
 * decor view of the Compose AlertDialog). Before each capture the main looper and Compose are idled.
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

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Activities of the running test, closed before the tall relaunch ([closeActivities]). */
    private val controllers = mutableListOf<ActivityController<out Activity>>()

    @Before
    fun setUp() {
        seedConfig()
        // ConnectionsSettingsActivity would otherwise request it on create (auto VPN is on).
        shadowOf(app).grantPermissions(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS)
    }

    /**
     * Leaves no Activity (and no Compose dialog) behind: their Compose hierarchies would stay
     * registered with the compose rule and block the idle sync of the next test.
     */
    @After
    fun tearDown() {
        closeActivities()
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
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.toolbar_settings_title)
            // No setup check: neither the title nor any hint is composed (formerly GONE / empty checkList).
            composeTestRule.onNodeWithTag(SettingsTestTags.CHECK_TITLE).assertDoesNotExist()
            composeTestRule.onAllNodes(hasTestTagPrefix(CHECK_ISSUE_TAG_PREFIX)).assertCountEquals(0)
            assertEntries(activity)
            assertBackupCard(activity)
            composeTestRule.onNodeWithTag(SettingsTestTags.STATUS_TEXT).assertTextEquals("")
        }
    }

    @Test
    fun settingsMainSystemCheck() {
        setSystemCheck(ok = false)
        captureScreen(SettingsActivity::class.java, "settingsMainSystemCheck") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.toolbar_settings_title)
            composeTestRule.onNodeWithTag(SettingsTestTags.CHECK_TITLE).assertIsDisplayed()
                .assertTextEquals(activity.getString(R.string.check_title))
            val expected = listOf(
                R.string.check_notifications_off to R.string.check_notifications_off_summary,
                R.string.check_full_screen_off to R.string.check_full_screen_off_summary,
                R.string.check_battery_optimized to R.string.check_battery_optimized_summary,
            )
            composeTestRule.onAllNodes(hasTestTagPrefix(CHECK_ISSUE_TAG_PREFIX)).assertCountEquals(expected.size)
            val checkTitleColor = textColor(SettingsTestTags.CHECK_TITLE, activity.getString(R.string.check_title))
            val entryTitleColor = textColor(SettingsEntry.Camera.testTag, activity.getString(R.string.section_camera))
            expected.forEachIndexed { index, (title, summary) ->
                val tag = SettingsTestTags.checkIssue(index)
                composeTestRule.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                    .assertTextEquals(activity.getString(title), activity.getString(summary))
                // Error color like the "Setup check" title, not the primary color of the entries.
                val issueTitleColor = textColor(tag, activity.getString(title))
                assertEquals(checkTitleColor, issueTitleColor)
                assertNotEquals(entryTitleColor, issueTitleColor)
            }
            assertEntries(activity)
            assertBackupCard(activity)
        }
    }

    @Test
    fun settingsMainQrDialog() {
        setSystemCheck(ok = true)
        val activity = launch(SettingsActivity::class.java)
        assertComposeHosted(activity)
        capture(activity, "settingsMainQrDialog_01_initial")

        composeTestRule.onNodeWithTag(SettingsTestTags.BTN_SHOW_QR).assertHasClickAction().performClick()
        settle()

        val dialog: Dialog = ShadowDialog.getLatestDialog()
        assertNotNull("no QR dialog", dialog)
        assertTrue(dialog.isShowing)
        composeTestRule.onNodeWithTag(SettingsTestTags.QR_DIALOG).assertIsDisplayed()
        composeTestRule.onNode(inQrDialog(activity.getString(R.string.title_qr_dialog))).assertIsDisplayed()
        composeTestRule.onNode(inQrDialog(activity.getString(R.string.message_qr_dialog))).assertIsDisplayed()
        val qrImage = composeTestRule.onNodeWithTag(SettingsTestTags.QR_IMAGE).assertIsDisplayed()
        val qrBounds = qrImage.getUnclippedBoundsInRoot()
        assertTrue("QR image not laid out", qrBounds.right > qrBounds.left && qrBounds.bottom > qrBounds.top)
        composeTestRule.onNodeWithTag(SettingsTestTags.BTN_QR_CLOSE).assertTextEquals(activity.getString(R.string.btn_close))
        composeTestRule.onNodeWithTag(SettingsTestTags.STATUS_TEXT).assertTextEquals("")

        // The dialog window alone (its own decor view), the same way as for the former View dialog.
        captureView(checkNotNull(dialog.window).decorView, "settingsMainQrDialog_02_dialogShown")
    }

    // ---- Section pages -------------------------------------------------------------------------

    @Test
    fun settingsCamera() {
        captureScreen(CameraSettingsActivity::class.java, "settingsCamera") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.section_camera)
            assertComposeField(SettingsTestTags.FIELD_CAMERA_HOST, "192.168.178.40")
            assertComposeField(SettingsTestTags.FIELD_CAMERA_BC_PORT, "9000")
            assertComposeField(SettingsTestTags.FIELD_CAMERA_USER, "admin")
            assertComposeField(SettingsTestTags.FIELD_CAMERA_PASS, "doorbell-secret", password = true)
            // Raw password as onCreate wrote it back from the field state (saveFieldsIntoConfig).
            assertEquals("doorbell-secret", AppConfig(activity).cameraPassword)
            assertComposeField(SettingsTestTags.FIELD_CAMERA_RTSP_PATH, "h264Preview_01_main")
            composeTestRule.onNodeWithTag(SettingsTestTags.CHECK_KEEP_ASPECT_RATIO).assertIsOn()
            composeTestRule.onNodeWithTag(SettingsTestTags.CHECK_DUCK_WHILE_TALKING).assertIsOn()
            assertComposeSlider(
                SettingsTestTags.SLIDER_MIC_GAIN, 300f, 100f..2000f, steps = 18,
                SettingsTestTags.LABEL_MIC_GAIN, activity.getString(R.string.label_mic_gain, 300),
            )
            assertComposeSlider(
                SettingsTestTags.SLIDER_VIDEO_START_BUFFER, 150f, 50f..1000f, steps = 18,
                SettingsTestTags.LABEL_VIDEO_START_BUFFER, activity.getString(R.string.label_video_start_buffer, 150),
            )
            assertComposeSlider(
                SettingsTestTags.SLIDER_VIDEO_REBUFFER, 400f, 100f..2000f, steps = 18,
                SettingsTestTags.LABEL_VIDEO_REBUFFER, activity.getString(R.string.label_video_rebuffer, 400),
            )
        }
    }

    @Test
    fun settingsRinging() {
        captureScreen(RingingSettingsActivity::class.java, "settingsRinging") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.section_ringing)
            assertComposeSlider(
                SettingsTestTags.SLIDER_RING_DURATION, 45f, 10f..90f, steps = 15,
                SettingsTestTags.LABEL_RING_DURATION, activity.getString(R.string.label_ring_duration, 45),
            )
            // Title of the default ringtone as Robolectric resolves it (deterministic, no media provider).
            val ringtone = composeText(SettingsTestTags.LABEL_RINGTONE)
            val prefix = activity.getString(R.string.label_ringtone, "")
            assertTrue("ringtone label: $ringtone", ringtone.startsWith(prefix) && ringtone.length > prefix.length)
            println("RINGTONE_LABEL: $ringtone")
            composeTestRule.onNodeWithTag(SettingsTestTags.BTN_CHOOSE_RINGTONE).assertIsDisplayed().assertHasClickAction()
                .assertTextEquals(activity.getString(R.string.btn_choose_ringtone))
            composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_RING_IN_SILENT_MODE).assertIsOn()
            composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_TELECOM_CALLS).assertIsOff()
            composeTestRule.onNodeWithTag(SettingsTestTags.TELECOM_CALLS_SUMMARY).assertIsDisplayed()
                .assertTextEquals(activity.getString(R.string.switch_telecom_calls_summary))
        }
    }

    @Test
    fun settingsPush() {
        captureScreen(PushSettingsActivity::class.java, "settingsPush") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.section_push_settings)
            assertComposeField(SettingsTestTags.FIELD_REGISTRATION_URL, "https://ha.example.com/api/webhook/doorbell_register")
            assertComposeField(SettingsTestTags.FIELD_DEVICE_NAME, "Pixel 9 (a1b2)")
            composeTestRule.onNodeWithTag(SettingsTestTags.BTN_REGISTER_DEVICE).assertIsEnabled()
                .assertTextEquals(activity.getString(R.string.btn_register_device))
            composeTestRule.onNodeWithTag(SettingsTestTags.BTN_UNREGISTER_DEVICE).assertIsEnabled()
                .assertTextEquals(activity.getString(R.string.btn_unregister_device))
            composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_MQTT_FALLBACK).assertIsOn()
            assertComposeField(SettingsTestTags.FIELD_MQTT_HOST, "192.168.178.10")
            assertComposeField(SettingsTestTags.FIELD_MQTT_PORT, "1883")
            assertComposeField(SettingsTestTags.FIELD_MQTT_USER, "doorbell")
            assertComposeField(SettingsTestTags.FIELD_MQTT_PASS, "mqtt-secret", password = true)
            // Raw password as onCreate wrote it back from the field state (saveFieldsIntoConfig).
            assertEquals("mqtt-secret", AppConfig(activity).mqttPassword)
            assertComposeField(SettingsTestTags.FIELD_MQTT_TOPIC, "doorbell/ring")
            composeTestRule.onNodeWithTag(SettingsTestTags.CHECK_NOTIFY_ANSWERED_ELSEWHERE).assertIsOn()
        }
    }

    @Test
    fun settingsConnections() {
        captureScreen(ConnectionsSettingsActivity::class.java, "settingsConnections") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.section_connections)
            assertComposeField(SettingsTestTags.FIELD_DOOR_OPEN_URL, "https://ha.example.com/api/webhook/door_open")
            composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_WIREGUARD_AUTO_VPN).assertIsOn()
            assertComposeField(SettingsTestTags.FIELD_WIREGUARD_TUNNEL, "home")
            composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_SILENT_WHEN_UNREACHABLE).assertIsOn()
            // Permission was granted beforehand: no permission request on create.
            assertNull(shadowOf(activity).lastRequestedPermission)
        }
    }

    @Test
    fun settingsRingLogEmpty() {
        captureScreen(RingLogActivity::class.java, "settingsRingLogEmpty") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.section_ring_log)
            composeTestRule.onNodeWithTag(SettingsTestTags.RING_LOG_TEXT)
                .assertTextEquals(activity.getString(R.string.ring_log_empty))
            composeTestRule.onNodeWithTag(SettingsTestTags.BTN_COPY_RING_LOG).assertIsDisplayed().assertIsNotEnabled()
                .assertTextEquals(activity.getString(R.string.btn_copy))
        }
    }

    @Test
    fun settingsRingLogEntries() {
        // Oldest first: appendRingLogEntry puts the newest entry on top.
        val config = AppConfig(app)
        RING_LOG_ENTRIES.reversed().forEach(config::appendRingLogEntry)
        captureScreen(RingLogActivity::class.java, "settingsRingLogEntries") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.section_ring_log)
            composeTestRule.onNodeWithTag(SettingsTestTags.RING_LOG_TEXT).assertTextEquals(RING_LOG_ENTRIES.joinToString("\n"))
            composeTestRule.onNodeWithTag(SettingsTestTags.BTN_COPY_RING_LOG).assertIsDisplayed().assertIsEnabled()
                .assertTextEquals(activity.getString(R.string.btn_copy))
        }
    }

    @Test
    fun settingsAbout() {
        captureScreen(AboutActivity::class.java, "settingsAbout") { activity ->
            assertComposeHosted(activity)
            assertComposeTitle(activity, R.string.section_about)
            val versionName = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
            println("VERSION_NAME: $versionName")
            composeTestRule.onNodeWithTag(SettingsTestTags.ABOUT_VERSION)
                .assertTextEquals(activity.getString(R.string.about_version, versionName))
            for (text in listOf(
                R.string.about_third_party_title,
                R.string.about_libraries_title,
                R.string.about_libraries_variant,
            )) {
                // Composed (formerly: a shown TextView with this text), not necessarily on screen.
                composeTestRule.onNode(hasText(activity.getString(text)), useUnmergedTree = true)
                    .assertExists("missing ${activity.resources.getResourceEntryName(text)}")
            }
        }
    }

    // ---- Shared checks -------------------------------------------------------------------------

    /** The Compose path really ran: the content view is the ComposeView of setContent. */
    private fun assertComposeHosted(activity: Activity) {
        assertTrue("no ComposeView attached", isComposeHosted(activity))
    }

    private fun isComposeHosted(activity: Activity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return content.childCount == 1 && content.getChildAt(0) is ComposeView && content.getChildAt(0).isShown
    }

    /** Window title (announced by TalkBack) and the top bar with title and up button. */
    private fun assertComposeTitle(activity: Activity, titleRes: Int) {
        val title = activity.getString(titleRes)
        assertEquals(title, activity.title.toString())
        composeTestRule.onNodeWithTag(SettingsTestTags.TOOLBAR).assertIsDisplayed()
        composeTestRule.onNode(hasText(title) and hasAnyAncestor(hasTestTag(SettingsTestTags.TOOLBAR))).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsTestTags.UP).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(activity.getString(R.string.nav_up))
    }

    /** The six navigation entries of the main settings page, in order (top to bottom). */
    private fun assertEntries(activity: Activity) {
        val expected = listOf(
            Triple(SettingsEntry.Camera.testTag, R.string.section_camera, R.string.section_camera_summary),
            Triple(SettingsEntry.Ringing.testTag, R.string.section_ringing, R.string.section_ringing_summary),
            Triple(SettingsEntry.Push.testTag, R.string.section_push_settings, R.string.section_push_settings_summary),
            Triple(SettingsEntry.Connections.testTag, R.string.section_connections, R.string.section_connections_summary),
            Triple(SettingsEntry.RingLog.testTag, R.string.section_ring_log, R.string.section_ring_log_summary),
            Triple(SettingsEntry.About.testTag, R.string.section_about, R.string.section_about_summary),
        )
        composeTestRule.onAllNodes(hasTestTagPrefix(ENTRY_TAG_PREFIX)).assertCountEquals(expected.size)
        val tops = expected.map { (tag, title, summary) ->
            val row = composeTestRule.onNodeWithTag(tag).assertHasClickAction()
                .assertTextEquals(activity.getString(title), activity.getString(summary))
            row.getUnclippedBoundsInRoot().top
        }
        assertEquals("entries out of order", tops.sorted(), tops)
    }

    private fun assertBackupCard(activity: Activity) {
        for ((tag, text) in listOf(
            SettingsTestTags.BTN_EXPORT to R.string.btn_export_settings,
            SettingsTestTags.BTN_IMPORT to R.string.btn_import_settings,
            SettingsTestTags.BTN_SHOW_QR to R.string.btn_show_qr,
            SettingsTestTags.BTN_SCAN_QR to R.string.btn_scan_qr,
        )) {
            composeTestRule.onNodeWithTag(tag).assertIsEnabled().assertTextEquals(activity.getString(text))
        }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun inQrDialog(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(SettingsTestTags.QR_DIALOG))

    /**
     * Color the text [text] is drawn with, below the node tagged [tag] (or tagged itself): from the
     * text layout, which is what TextView.currentTextColor was before.
     */
    private fun textColor(tag: String, text: String): ComposeColor {
        val node = composeTestRule.onNode(
            hasText(text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    /**
     * Text field content (formerly EditText.text). Password fields only expose the masked text (one
     * bullet per character, like the screen) and must be marked as password; their raw value is
     * checked by the caller via AppConfig.
     */
    private fun assertComposeField(tag: String, expected: String, password: Boolean = false) {
        val config = composeTestRule.onNodeWithTag(tag).assertExists().fetchSemanticsNode().config
        assertEquals("password semantics of $tag", password, SemanticsProperties.Password in config)
        val shown = if (password) PASSWORD_MASK.toString().repeat(expected.length) else expected
        assertEquals(shown, config[SemanticsProperties.EditableText].text)
    }

    /** Slider value, range and steps (formerly Slider.value) plus its live label above. */
    private fun assertComposeSlider(
        sliderTag: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        labelTag: String,
        label: String,
    ) {
        composeTestRule.onNodeWithTag(sliderTag).assertRangeInfoEquals(ProgressBarRangeInfo(value, range, steps))
        composeTestRule.onNodeWithTag(labelTag).assertTextEquals(label)
    }

    private fun composeText(tag: String): String =
        composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    // ---- Launch & capture ----------------------------------------------------------------------

    private fun <T : Activity> launch(type: Class<T>): T {
        val controller = Robolectric.buildActivity(type).setup()
        controllers += controller
        val activity = controller.get()
        settle()
        // Touch mode like a phone: no text field gets initial focus, so no cursor is drawn.
        assertTrue(activity.window.decorView.isInTouchMode)
        composeTestRule.onAllNodes(isFocused()).assertCountEquals(0)
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
        // Only one window with content at a time (otherwise every Compose tag would match twice).
        closeActivities()

        RuntimeEnvironment.setQualifiers(FULL_HEIGHT_QUALIFIER)
        val tall = launch(type)
        checks(tall)
        assertFullContentFits(tall, method)
        capture(tall, "${method}_02_fullContent")
    }

    /** Nothing left to scroll in the tall window (formerly: ScrollView content <= viewport, cannot scroll). */
    private fun assertFullContentFits(activity: Activity, method: String) {
        val density = activity.resources.displayMetrics.density
        val content = composeTestRule.onNodeWithTag(SettingsTestTags.CONTENT).fetchSemanticsNode()
        val range = content.config[SemanticsProperties.VerticalScrollAxisRange]
        println("CONTENT_SCROLL $method: max ${range.maxValue() / density} dp in viewport ${content.size.height / density} dp")
        assertEquals("content of $method does not fit into the tall window", 0f, range.maxValue(), 0f)
        assertEquals(0f, range.value(), 0f)
    }

    /** Pauses, stops and destroys the Activities launched so far (like leaving the page). */
    private fun closeActivities() {
        controllers.forEach { it.pause().stop().destroy() }
        controllers.clear()
        settle()
    }

    /**
     * Idles the main looper and, if a page is open, waits for Compose to be idle. Without any
     * Compose hierarchy (after [closeActivities]) waitForIdle would first wait 2 s for one to
     * appear, hence only with an open page.
     */
    private fun settle() {
        if (controllers.isNotEmpty()) composeTestRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Draws the whole window (decor view) onto an opaque bitmap. (The XML baseline switched the
     * ScrollView scrollbar off first; Compose's verticalScroll draws none.)
     */
    private fun capture(activity: Activity, fileName: String) {
        settle()
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

        /** Tags of the navigation entries (SettingsEntry.testTag) and setup-check hints (SettingsTestTags.checkIssue). */
        private const val ENTRY_TAG_PREFIX = "entry_"

        /** Mask character of PasswordVisualTransformation (as PasswordTransformationMethod). */
        private const val PASSWORD_MASK = '•'
        private const val CHECK_ISSUE_TAG_PREFIX = "check_issue_"

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
