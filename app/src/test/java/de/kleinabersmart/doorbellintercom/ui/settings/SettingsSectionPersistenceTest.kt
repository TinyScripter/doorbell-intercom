package de.kleinabersmart.doorbellintercom.ui.settings

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import de.kleinabersmart.doorbellintercom.AppConfig
import de.kleinabersmart.doorbellintercom.CameraSettingsActivity
import de.kleinabersmart.doorbellintercom.ConnectionsSettingsActivity
import de.kleinabersmart.doorbellintercom.DoorbellForegroundService
import de.kleinabersmart.doorbellintercom.PushSettingsActivity
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.RingingSettingsActivity
import de.kleinabersmart.doorbellintercom.WireGuardTunnel
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags as Tags
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Persistence of the section pages (SettingsSectionActivity: Camera, Ringing, Push, Connections)
 * after the move to Compose, driven through the real Activities and Compose input:
 *  - nothing is written while editing; leaving the page (finish(), up arrow, back) or onPause
 *    writes the fields with the same normalization as the EditText version (trim except for
 *    passwords, port defaults 9000/1883, RTSP path without leading slashes, slider steps);
 *  - normalizing on open (slider rounding) is no change: an untouched page returns
 *    RESULT_CANCELED and restarts nothing;
 *  - a real change returns RESULT_OK and restarts the background service once
 *    (DoorbellForegroundService.startOrStop: started with MQTT fallback on, stopped with it off);
 *  - "changed in this session" survives a recreation of the Activity;
 *  - Ringing: ringtone picker intent and result; Connections: the WireGuard permission request;
 *    Push: register/unregister checks use the current, unsaved field values.
 *
 * The registration URL is kept blank, so PushRegistrar.start (part of the restart) does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class SettingsSectionPersistenceTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val harness = SettingsActivityHarness(composeTestRule)

    private val config get() = AppConfig(testApp)

    @Before
    fun setUp() {
        config.apply {
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

            registrationUrl = ""
            deviceName = "Pixel 9 (a1b2)"
            mqttFallbackEnabled = true
            mqttHost = "192.168.178.10"
            mqttPort = 1883
            mqttUsername = "doorbell"
            mqttPassword = "mqtt-secret"
            mqttTopic = "doorbell/ring"
            notifyAnsweredElsewhere = true

            doorOpenUrl = "https://ha.example.com/api/webhook/door_open"
            wireguardAutoVpn = false
            wireguardTunnel = "home"
            silentWhenUnreachable = true
        }
    }

    @After
    fun tearDown() {
        harness.closeAll()
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    private fun resultCode(activity: Activity) = shadowOf(activity).resultCode

    /** The background service was (re)started exactly once (MQTT fallback on). */
    private fun assertServiceStartedOnce() {
        val started = shadowOf(testApp).nextStartedService
        assertNotNull("service not restarted", started)
        assertEquals(DoorbellForegroundService::class.java.name, started.component?.className)
        assertNull("service restarted twice", shadowOf(testApp).nextStartedService)
    }

    private fun assertNoServiceRestart() {
        assertNull("service started", shadowOf(testApp).nextStartedService)
        assertNull("service stopped", shadowOf(testApp).nextStoppedService)
    }

    // ---- Camera --------------------------------------------------------------------------------

    @Test
    fun camera_untouchedPageClosedByUpArrow_resultCanceledNothingChanged() {
        val before = config.snapshot()
        val activity = harness.launch(CameraSettingsActivity::class.java).get()

        node(Tags.UP).performClick()

        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, resultCode(activity))
        assertEquals(before, config.snapshot())
        assertNoServiceRestart()
    }

    @Test
    fun camera_sliderValuesRoundedOnOpen_notCountedAsChange() {
        config.micGainPercent = 333
        config.videoStartBufferMs = 160
        config.videoRebufferMs = 449
        val activity = harness.launch(CameraSettingsActivity::class.java).get()

        // Written back rounded right in onCreate, shown on the step.
        assertEquals(300, config.micGainPercent)
        assertEquals(150, config.videoStartBufferMs)
        assertEquals(400, config.videoRebufferMs)
        node(Tags.SLIDER_MIC_GAIN).assertRangeInfoEquals(ProgressBarRangeInfo(300f, 100f..2000f, 18))
        node(Tags.LABEL_MIC_GAIN).assertTextEquals(str(R.string.label_mic_gain, 300))

        activity.finish()

        assertEquals(Activity.RESULT_CANCELED, resultCode(activity))
        assertNoServiceRestart()
    }

    @Test
    fun camera_editsSavedNormalizedOnlyWhenLeaving() {
        config.cameraBcPort = 1234
        val activity = harness.launch(CameraSettingsActivity::class.java).get()

        node(Tags.FIELD_CAMERA_HOST).performTextReplacement("  10.0.0.7  ")
        node(Tags.FIELD_CAMERA_BC_PORT).performTextClearance()
        node(Tags.FIELD_CAMERA_USER).performTextReplacement(" viewer ")
        node(Tags.FIELD_CAMERA_PASS).performTextReplacement("  pass word  ")
        node(Tags.FIELD_CAMERA_RTSP_PATH).performTextReplacement(" //h265Preview_01_sub ")
        node(Tags.CHECK_KEEP_ASPECT_RATIO).performScrollTo().performClick()
        node(Tags.CHECK_DUCK_WHILE_TALKING).performScrollTo().performClick()
        node(Tags.SLIDER_MIC_GAIN).setSliderValue(1240f)
        node(Tags.SLIDER_VIDEO_START_BUFFER).setSliderValue(990f)
        node(Tags.SLIDER_VIDEO_REBUFFER).setSliderValue(160f)
        harness.settle()

        // No save button and no saving while editing.
        assertEquals("192.168.178.40", config.cameraHost)
        assertEquals(1234, config.cameraBcPort)

        activity.finish()

        with(config) {
            assertEquals("10.0.0.7", cameraHost)
            assertEquals("empty port falls back to 9000", 9000, cameraBcPort)
            assertEquals("viewer", cameraUsername)
            assertEquals("password is not trimmed", "  pass word  ", cameraPassword)
            assertEquals("h265Preview_01_sub", cameraRtspPath)
            assertFalse(keepAspectRatio)
            assertFalse(duckWhileTalking)
            assertEquals(1200, micGainPercent)
            assertEquals(1000, videoStartBufferMs)
            assertEquals(200, videoRebufferMs)
        }
        assertEquals(Activity.RESULT_OK, resultCode(activity))
        assertServiceStartedOnce()
    }

    @Test
    fun camera_portWithNonDigits_savedAsDigits() {
        val activity = harness.launch(CameraSettingsActivity::class.java).get()

        node(Tags.FIELD_CAMERA_BC_PORT).performTextReplacement("90a01")
        activity.finish()

        assertEquals(9001, config.cameraBcPort)
        assertEquals(Activity.RESULT_OK, resultCode(activity))
    }

    @Test
    fun camera_onPause_persistsAndLaterFinishReturnsOk() {
        val controller = harness.launch(CameraSettingsActivity::class.java)

        node(Tags.FIELD_CAMERA_HOST).performTextReplacement("cam.local")
        // E.g. the app goes to the background.
        controller.pause()

        assertEquals("cam.local", config.cameraHost)
        assertServiceStartedOnce()

        controller.resume()
        harness.settle()
        // Leaving later without further changes still reports the change of this session.
        node(Tags.UP).performClick()

        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_OK, resultCode(controller.get()))
        assertNoServiceRestart()
    }

    @Test
    fun camera_changeSurvivesRecreation_resultStillOk() {
        val controller = harness.launch(CameraSettingsActivity::class.java)
        node(Tags.FIELD_CAMERA_HOST).performTextReplacement("cam.local")

        controller.recreate()
        harness.settle()

        // Reloaded from AppConfig (persisted in onPause of the old instance).
        assertEquals("cam.local", node(Tags.FIELD_CAMERA_HOST).editableText())
        assertServiceStartedOnce()

        controller.get().finish()

        assertEquals(Activity.RESULT_OK, resultCode(controller.get()))
        assertNoServiceRestart()
    }

    // ---- Ringing -------------------------------------------------------------------------------

    @Test
    fun ringing_durationRoundedOnOpen_notCountedAsChange() {
        config.ringDurationSeconds = 47
        val activity = harness.launch(RingingSettingsActivity::class.java).get()

        assertEquals(45, config.ringDurationSeconds)
        node(Tags.LABEL_RING_DURATION).assertTextEquals(str(R.string.label_ring_duration, 45))

        activity.finish()

        assertEquals(Activity.RESULT_CANCELED, resultCode(activity))
        assertNoServiceRestart()
    }

    @Test
    fun ringing_sliderAndSwitchesSavedOnFinish() {
        val activity = harness.launch(RingingSettingsActivity::class.java).get()

        node(Tags.SLIDER_RING_DURATION).setSliderValue(62f)
        node(Tags.SWITCH_RING_IN_SILENT_MODE).performClick()
        node(Tags.SWITCH_TELECOM_CALLS).performScrollTo().performClick()
        harness.settle()
        activity.finish()

        assertEquals(60, config.ringDurationSeconds)
        assertFalse(config.ringInSilentMode)
        assertTrue(config.telecomCalls)
        assertEquals(Activity.RESULT_OK, resultCode(activity))
        assertServiceStartedOnce()
    }

    @Test
    fun ringing_pickedRingtone_shownAndSavedOnFinish() {
        val activity = harness.launch(RingingSettingsActivity::class.java).get()
        val labelBefore = node(Tags.LABEL_RINGTONE).text()

        node(Tags.BTN_CHOOSE_RINGTONE).performClick()

        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("ringtone picker not opened", request)
        with(request.intent) {
            assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, action)
            assertEquals(RingtoneManager.TYPE_RINGTONE, getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
            assertTrue(getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false))
            assertFalse(getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
            assertEquals(Settings.System.DEFAULT_RINGTONE_URI, existingRingtone())
            assertEquals(str(R.string.btn_choose_ringtone), getStringExtra(RingtoneManager.EXTRA_RINGTONE_TITLE))
        }

        shadowOf(activity).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, PICKED_RINGTONE),
        )
        harness.settle()

        // No media provider under Robolectric: the title falls back to the last path segment.
        node(Tags.LABEL_RINGTONE).assertTextEquals(str(R.string.label_ringtone, "42"))
        assertNotEquals(labelBefore, node(Tags.LABEL_RINGTONE).text())

        activity.finish()

        assertEquals(PICKED_RINGTONE, config.ringtoneUri)
        assertEquals(Activity.RESULT_OK, resultCode(activity))
    }

    @Test
    fun ringing_cancelledPicker_changesNothing() {
        val activity = harness.launch(RingingSettingsActivity::class.java).get()
        val labelBefore = node(Tags.LABEL_RINGTONE).text()

        node(Tags.BTN_CHOOSE_RINGTONE).performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        harness.settle()

        node(Tags.LABEL_RINGTONE).assertTextEquals(labelBefore)
        activity.finish()

        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, config.ringtoneUri)
        assertEquals(Activity.RESULT_CANCELED, resultCode(activity))
    }

    @Suppress("DEPRECATION") // Uri extra, read like the system picker does
    private fun Intent.existingRingtone(): Uri? = getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI)

    // ---- Push ----------------------------------------------------------------------------------

    @Test
    fun push_editsSavedNormalizedOnFinish() {
        config.mqttPort = 1884
        val activity = harness.launch(PushSettingsActivity::class.java).get()

        node(Tags.FIELD_DEVICE_NAME).performTextReplacement("  Hallway tablet ")
        node(Tags.FIELD_MQTT_HOST).performTextReplacement(" mqtt.local ")
        node(Tags.FIELD_MQTT_PORT).performTextClearance()
        node(Tags.FIELD_MQTT_USER).performTextReplacement(" bell ")
        node(Tags.FIELD_MQTT_PASS).performTextReplacement(" p w ")
        node(Tags.FIELD_MQTT_TOPIC).performTextReplacement(" door/ring ")
        node(Tags.CHECK_NOTIFY_ANSWERED_ELSEWHERE).performScrollTo().performClick()
        harness.settle()
        assertEquals("nothing saved while editing", "mqtt-secret", config.mqttPassword)

        activity.finish()

        with(config) {
            assertEquals("Hallway tablet", deviceName)
            assertEquals("mqtt.local", mqttHost)
            assertEquals("empty port falls back to 1883", 1883, mqttPort)
            assertEquals("bell", mqttUsername)
            assertEquals("password is not trimmed", " p w ", mqttPassword)
            assertEquals("door/ring", mqttTopic)
            assertFalse(notifyAnsweredElsewhere)
            assertTrue(mqttFallbackEnabled)
        }
        assertEquals(Activity.RESULT_OK, resultCode(activity))
        assertServiceStartedOnce()
    }

    @Test
    fun push_mqttFallbackSwitchedOff_stopsService() {
        val activity = harness.launch(PushSettingsActivity::class.java).get()

        node(Tags.SWITCH_MQTT_FALLBACK).performScrollTo().performClick()
        activity.finish()

        assertFalse(config.mqttFallbackEnabled)
        assertEquals(Activity.RESULT_OK, resultCode(activity))
        val stopped = shadowOf(testApp).nextStoppedService
        assertEquals(DoorbellForegroundService::class.java.name, stopped?.component?.className)
        assertNull(shadowOf(testApp).nextStartedService)
    }

    @Test
    fun push_blankDeviceName_regeneratesDefaultName() {
        val activity = harness.launch(PushSettingsActivity::class.java).get()

        node(Tags.FIELD_DEVICE_NAME).performTextClearance()
        activity.finish()

        val generated = config.deviceName
        assertTrue("generated name: $generated", generated.startsWith("${Build.MODEL} ("))
        assertNotEquals("Pixel 9 (a1b2)", generated)
        assertEquals(Activity.RESULT_OK, resultCode(activity))
    }

    @Test
    fun push_registerAndUnregister_checkCurrentUnsavedFields() {
        val activity = harness.launch(PushSettingsActivity::class.java).get()

        node(Tags.BTN_REGISTER_DEVICE).performScrollTo().performClick()
        assertEquals(str(R.string.status_register_url_missing), ShadowToast.getTextOfLatestToast())
        node(Tags.BTN_UNREGISTER_DEVICE).performScrollTo().performClick()
        assertEquals(str(R.string.status_register_url_missing), ShadowToast.getTextOfLatestToast())

        // Typed but not yet saved: the URL check passes, the cleared device name does not.
        node(Tags.FIELD_REGISTRATION_URL).performTextReplacement("https://ha.local/api/webhook/x")
        node(Tags.FIELD_DEVICE_NAME).performTextReplacement("   ")
        node(Tags.BTN_REGISTER_DEVICE).performScrollTo().performClick()
        assertEquals(str(R.string.status_register_device_blank), ShadowToast.getTextOfLatestToast())
        node(Tags.BTN_UNREGISTER_DEVICE).performScrollTo().performClick()
        assertEquals(str(R.string.status_register_device_blank), ShadowToast.getTextOfLatestToast())
        assertEquals("", config.registrationUrl)

        // Back to the stored values: leaving the page is no change (and contacts no server).
        node(Tags.FIELD_REGISTRATION_URL).performTextClearance()
        node(Tags.FIELD_DEVICE_NAME).performTextReplacement("Pixel 9 (a1b2)")
        activity.finish()
        assertEquals(Activity.RESULT_CANCELED, resultCode(activity))
        assertNoServiceRestart()
    }

    // ---- Connections ---------------------------------------------------------------------------

    @Test
    fun connections_editsSavedTrimmedOnFinish() {
        val activity = harness.launch(ConnectionsSettingsActivity::class.java).get()

        node(Tags.FIELD_DOOR_OPEN_URL).performTextReplacement("  https://ha.local/api/webhook/open ")
        node(Tags.FIELD_WIREGUARD_TUNNEL).performTextReplacement(" office ")
        node(Tags.SWITCH_SILENT_WHEN_UNREACHABLE).performScrollTo().performClick()
        activity.finish()

        assertEquals("https://ha.local/api/webhook/open", config.doorOpenUrl)
        assertEquals("office", config.wireguardTunnel)
        assertFalse(config.silentWhenUnreachable)
        assertFalse(config.wireguardAutoVpn)
        assertEquals(Activity.RESULT_OK, resultCode(activity))
        assertServiceStartedOnce()
        assertNull("no permission needed without auto-VPN", shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun connections_autoVpnSwitchedOnWithoutPermission_requestsIt_denialKeepsSwitch() {
        val activity = harness.launch(ConnectionsSettingsActivity::class.java).get()
        assertNull(shadowOf(activity).lastRequestedPermission)

        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).performClick()

        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull("WireGuard permission not requested", request)
        assertArrayEquals(arrayOf(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS), request.requestedPermissions)

        @Suppress("DEPRECATION") // how the platform delivers the answer to the ActivityResultRegistry
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        harness.settle()

        assertEquals(str(R.string.status_vpn_permission_denied), ShadowToast.getTextOfLatestToast())
        // Not reverted on denial (as before).
        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).assertIsOn()
        activity.finish()
        assertTrue(config.wireguardAutoVpn)
        assertEquals(Activity.RESULT_OK, resultCode(activity))
    }

    @Test
    fun connections_permissionGrantedAnswer_showsToast() {
        val activity = harness.launch(ConnectionsSettingsActivity::class.java).get()

        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).performClick()
        val request = shadowOf(activity).lastRequestedPermission
        shadowOf(testApp).grantPermissions(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        harness.settle()

        assertEquals(str(R.string.status_vpn_permission_granted), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun connections_autoVpnWithPermissionGranted_requestsNothing() {
        shadowOf(testApp).grantPermissions(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS)
        val activity = harness.launch(ConnectionsSettingsActivity::class.java).get()

        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).performClick()

        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).assertIsOn()
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun connections_autoVpnAlreadyOnWithoutPermission_requestsItOnOpen() {
        // E.g. switched on by an import on another phone.
        config.wireguardAutoVpn = true
        val activity = harness.launch(ConnectionsSettingsActivity::class.java).get()

        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull("WireGuard permission not requested on open", request)
        assertArrayEquals(arrayOf(WireGuardTunnel.PERMISSION_CONTROL_TUNNELS), request.requestedPermissions)
    }

    private companion object {
        val PICKED_RINGTONE: Uri = Uri.parse("content://media/internal/audio/media/42")
    }
}
