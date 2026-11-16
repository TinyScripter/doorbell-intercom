package de.kleinabersmart.doorbellintercom.ui.settings

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.kleinabersmart.doorbellintercom.AboutActivity
import de.kleinabersmart.doorbellintercom.AppConfig
import de.kleinabersmart.doorbellintercom.CameraSettingsActivity
import de.kleinabersmart.doorbellintercom.ConnectionsSettingsActivity
import de.kleinabersmart.doorbellintercom.PushSettingsActivity
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.RingLogActivity
import de.kleinabersmart.doorbellintercom.RingingSettingsActivity
import de.kleinabersmart.doorbellintercom.SettingsActivity
import de.kleinabersmart.doorbellintercom.SettingsScreenshotTest
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags as Tags
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream

/**
 * The Activities hosting the non-section pages, driven through Compose:
 *  - SettingsActivity: entries open their page for a result and "Settings saved" appears only
 *    for RESULT_OK; system-check hints open their system setting and are re-read in onResume;
 *    export/import launch the document pickers, an imported file is applied and reported in the
 *    status line; the QR dialog closes.
 *  - RingLogActivity: copy puts the joined log on the clipboard; entries are re-read in onResume.
 *
 * SystemCheck reads the notification/power shadows of SettingsScreenshotTest (set per test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    qualifiers = SETTINGS_TEST_QUALIFIERS,
    shadows = [
        SettingsScreenshotTest.SystemCheckNotificationManagerShadow::class,
        SettingsScreenshotTest.SystemCheckPowerManagerShadow::class,
    ],
)
class SettingsHostActivitiesTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val harness = SettingsActivityHarness(composeTestRule)

    private val config get() = AppConfig(testApp)

    @Before
    fun setUp() {
        // Blank registration URL: an import restarts PushRegistrar, which then does nothing.
        config.registrationUrl = ""
        config.cameraHost = "192.168.178.40"
        setSystemCheck(ok = true)
    }

    @After
    fun tearDown() {
        harness.closeAll()
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    private fun setSystemCheck(ok: Boolean) {
        shadowOf(testApp.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(ok)
        SettingsScreenshotTest.SystemCheckNotificationManagerShadow.canUseFullScreenIntent = ok
        SettingsScreenshotTest.SystemCheckPowerManagerShadow.ignoringBatteryOptimizations = ok
    }

    // ---- SettingsActivity ----------------------------------------------------------------------

    @Test
    fun entries_openTheirPage_savedToastOnlyForResultOk() {
        val activity = harness.launch(SettingsActivity::class.java).get()
        val targets = mapOf(
            SettingsEntry.Camera to CameraSettingsActivity::class.java,
            SettingsEntry.Ringing to RingingSettingsActivity::class.java,
            SettingsEntry.Push to PushSettingsActivity::class.java,
            SettingsEntry.Connections to ConnectionsSettingsActivity::class.java,
            SettingsEntry.RingLog to RingLogActivity::class.java,
            SettingsEntry.About to AboutActivity::class.java,
        )
        assertEquals(SettingsEntry.entries.toSet(), targets.keys)

        for ((entry, target) in targets) {
            node(entry.testTag).performScrollTo().performClick()
            val request = shadowOf(activity).nextStartedActivityForResult
            assertNotNull("$entry opened nothing", request)
            assertEquals(entry.name, target.name, request.intent.component?.className)
            // Returned without changes: no toast.
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
            harness.settle()
        }
        assertEquals(0, ShadowToast.shownToastCount())

        node(SettingsEntry.Camera.testTag).performScrollTo().performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, null)
        harness.settle()

        assertEquals(str(R.string.toast_settings_saved), ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun issueClicks_openTheirSystemSetting() {
        setSystemCheck(ok = false)
        val activity = harness.launch(SettingsActivity::class.java).get()

        node(Tags.checkIssue(0)).performClick()
        val notifications = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(testApp.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        node(Tags.checkIssue(1)).performClick()
        val fullScreen = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, fullScreen.action)
        assertEquals(Uri.fromParts("package", testApp.packageName, null), fullScreen.data)

        node(Tags.checkIssue(2)).performClick()
        val battery = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, battery.action)
        assertEquals(Uri.fromParts("package", testApp.packageName, null), battery.data)
    }

    @Test
    fun issues_reReadOnResume() {
        setSystemCheck(ok = false)
        val controller = harness.launch(SettingsActivity::class.java)
        node(Tags.checkIssue(2)).assertExists()

        // Fixed in the system settings, then back to the page.
        controller.pause()
        setSystemCheck(ok = true)
        controller.resume()
        harness.settle()

        node(Tags.CHECK_TITLE).assertDoesNotExist()
        node(Tags.checkIssue(0)).assertDoesNotExist()
    }

    @Test
    fun exportAndImport_launchDocumentPickers() {
        val activity = harness.launch(SettingsActivity::class.java).get()

        node(Tags.BTN_EXPORT).performScrollTo().performClick()
        val export = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.action)
        assertEquals("application/json", export.type)
        assertEquals("doorbell-intercom-settings.json", export.getStringExtra(Intent.EXTRA_TITLE))

        node(Tags.BTN_IMPORT).performScrollTo().performClick()
        val import = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, import.action)
        assertArrayEquals(
            arrayOf("application/json", "application/octet-stream", "text/plain"),
            import.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
    }

    @Test
    fun importedFile_appliedAndReportedInStatusLine() {
        config.cameraHost = "imported.example"
        val json = config.exportToJson()
        config.cameraHost = "192.168.178.40"
        val activity = harness.launch(SettingsActivity::class.java).get()

        assertEquals(str(R.string.status_settings_imported), importFile(activity, json))
        assertEquals("imported.example", config.cameraHost)
    }

    @Test
    fun importedBrokenFile_reportedAsFailed() {
        val activity = harness.launch(SettingsActivity::class.java).get()

        assertEquals(str(R.string.status_settings_import_failed), importFile(activity, "{ not json"))
        assertEquals("192.168.178.40", config.cameraHost)
    }

    /** Picks [content] in the import picker and waits for the status line (read on a background thread). */
    private fun importFile(activity: Activity, content: String): String {
        val uri = Uri.parse("content://de.kleinabersmart.test/settings.json")
        shadowOf(testApp.contentResolver).registerInputStream(uri, ByteArrayInputStream(content.toByteArray()))
        node(Tags.BTN_IMPORT).performScrollTo().performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))

        val deadline = System.currentTimeMillis() + 5_000
        var status = ""
        while (status.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
            harness.settle()
            status = node(Tags.STATUS_TEXT).text()
        }
        return status
    }

    @Test
    fun qrDialog_closeButtonClosesIt() {
        harness.launch(SettingsActivity::class.java)

        node(Tags.BTN_SHOW_QR).performScrollTo().performClick()
        harness.settle()
        node(Tags.QR_DIALOG).assertExists()

        node(Tags.BTN_QR_CLOSE).performClick()
        harness.settle()

        node(Tags.QR_DIALOG).assertDoesNotExist()
        node(Tags.STATUS_TEXT).assertTextEquals("")
    }

    // ---- RingLogActivity -----------------------------------------------------------------------

    @Test
    fun ringLog_copyPutsJoinedLogOnClipboard() {
        ENTRIES.reversed().forEach(config::appendRingLogEntry)
        val activity = harness.launch(RingLogActivity::class.java).get()

        node(Tags.BTN_COPY_RING_LOG).assertIsEnabled().performClick()

        val clip = testApp.getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull(clip)
        assertEquals(str(R.string.section_ring_log), clip!!.description.label)
        assertEquals(ENTRIES.joinToString("\n"), clip.getItemAt(0).text.toString())
        // API 33+: the system confirms copying itself, no own toast.
        assertNull(ShadowToast.getTextOfLatestToast())
        activity.finish()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun ringLog_entriesReReadOnResume() {
        val controller = harness.launch(RingLogActivity::class.java)
        node(Tags.RING_LOG_TEXT).assertTextEquals(str(R.string.ring_log_empty))

        // A ring arrives while the page is in the background.
        controller.pause()
        config.appendRingLogEntry(ENTRIES.first())
        controller.resume()
        harness.settle()

        node(Tags.RING_LOG_TEXT).assertTextEquals(ENTRIES.first())
        node(Tags.BTN_COPY_RING_LOG).assertIsEnabled()
    }

    private companion object {
        /** Newest first, as RingLogActivity shows them. */
        val ENTRIES = listOf(
            "16.11.2026 18:42:07  Push  Anruf angezeigt",
            "15.11.2026 09:30:12  MQTT  verpasst",
        )
    }
}
