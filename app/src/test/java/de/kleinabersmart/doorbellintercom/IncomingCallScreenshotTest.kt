package de.kleinabersmart.doorbellintercom

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.isVisible
import de.kleinabersmart.doorbellintercom.databinding.ActivityIncomingCallBinding
import de.kleinabersmart.doorbellintercom.ui.components.SlideConfirmRequest
import de.kleinabersmart.doorbellintercom.ui.components.VideoLayer
import de.kleinabersmart.doorbellintercom.ui.components.inflateLivePlayerView
import de.kleinabersmart.doorbellintercom.ui.incomingcall.DoorAction
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallPhase
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallScreen
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallTestTags
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallUiState
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Screenshot tests of the call screen (activity_incoming_call.xml).
 *
 * The real IncomingCallActivity starts VPN, RTSP and the call controller, so [HostActivity]
 * only inflates the layout with the same theme and applies the same view setup as the
 * activity (insets, visibility per call phase). The PlayerView has no player and renders black.
 *
 * The `<method>_compose` tests render the migrated IncomingCallScreen the same way: [ComposeHostActivity]
 * hosts it like IncomingCallActivity (same theme, DoorbellTheme, PlayerView without player via VideoLayer)
 * and the capture draws the decor view identically.
 *
 * Device: Pixel 9 class phone (1080 x 2424 px at 420 dpi = 411 x 923 dp), light mode, values/ strings.
 * Screenshots go to app/build/outputs/screenshots/IncomingCallScreenshotTest/ (View) and its
 * compose/ subfolder (Compose, same file names for a 1:1 comparison).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h923dp-port-notnight-420dpi")
class IncomingCallScreenshotTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    /** Minimal host with the theme of IncomingCallActivity, without its services. */
    class HostActivity : AppCompatActivity() {
        lateinit var binding: ActivityIncomingCallBinding

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_DoorbellIntercom_FullScreen)
            super.onCreate(savedInstanceState)
            binding = ActivityIncomingCallBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.statusText.applyTopSystemInset()
            binding.bottomArea.applyBottomSystemInset()
        }

        /** Like IncomingCallActivity.showRinging(). */
        fun showRinging() {
            binding.doorSlider.hide()
            binding.statusText.text = getString(R.string.incoming_call_title)
            binding.buttonBar.visibility = View.VISIBLE
            binding.callControls.visibility = View.GONE
        }

        /** Like IncomingCallActivity.showCall(), talking not (yet) started. */
        fun showCall() {
            binding.doorSlider.hide()
            binding.statusText.text = getString(R.string.status_connected)
            binding.buttonBar.visibility = View.GONE
            binding.callControls.visibility = View.VISIBLE
        }
    }

    /** Compose host with the theme of IncomingCallActivity and its setContent, without its services. */
    class ComposeHostActivity : AppCompatActivity() {
        var state by mutableStateOf(IncomingCallUiState())

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_DoorbellIntercom_FullScreen)
            super.onCreate(savedInstanceState)
            // As IncomingCallActivity: the PlayerView (no player, renders black) is the bottom layer.
            val playerView = inflateLivePlayerView(this)
            setContent {
                DoorbellTheme {
                    IncomingCallScreen(
                        state = state,
                        onAccept = {},
                        onDecline = {},
                        onDoorOpenAndHangUpClick = {},
                        onTalk = {},
                        onDoorOpenClick = {},
                        onHangUp = {},
                        onDoorConfirmed = {},
                        onDoorDismiss = {},
                        video = { VideoLayer(playerView) },
                    )
                }
            }
        }

        /** Like IncomingCallActivity.showRinging(). */
        fun showRinging() {
            state = state.copy(
                phase = IncomingCallPhase.Ringing,
                statusText = getString(R.string.incoming_call_title),
                doorConfirm = null,
            )
        }

        /** Like IncomingCallActivity.showCall(), talking not (yet) started. */
        fun showCall() {
            state = state.copy(
                phase = IncomingCallPhase.Active,
                statusText = getString(R.string.status_connected),
                doorConfirm = null,
            )
        }
    }

    private fun launch(): HostActivity =
        Robolectric.buildActivity(HostActivity::class.java).setup().get()

    private fun launchCompose(): ComposeHostActivity =
        Robolectric.buildActivity(ComposeHostActivity::class.java).setup().get()

    /** The Compose path really ran: the content view is the ComposeView of setContent. */
    private fun assertComposeHosted(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("no ComposeView attached", content.getChildAt(0) is ComposeView && content.getChildAt(0).isShown)
    }

    @Test
    fun callRinging() {
        val activity = launch()
        activity.showRinging()
        settle()

        val binding = activity.binding
        assertEquals(activity.getString(R.string.incoming_call_title), binding.statusText.text.toString())
        assertTrue(binding.buttonBar.isShown)
        assertFalse(binding.callControls.isVisible)
        assertTrue(binding.btnAccept.isShown && binding.btnAccept.isEnabled)
        assertTrue(binding.btnDecline.isShown && binding.btnDecline.isEnabled)
        assertTrue(binding.btnDoorOpenAndHangUp.isShown)
        assertEquals(activity.getString(R.string.btn_accept), binding.btnAccept.contentDescription)
        assertEquals(activity.getString(R.string.btn_decline), binding.btnDecline.contentDescription)
        assertFalse(binding.doorSlider.isVisible)
        assertEquals("", binding.talkStatusText.text.toString())

        capture(activity, "callRinging_01_initial")
    }

    @Test
    fun callActive() {
        val activity = launch()
        activity.showRinging()
        activity.showCall()
        activity.binding.btnTalk.showTalkState(false)
        settle()

        val binding = activity.binding
        assertEquals(activity.getString(R.string.status_connected), binding.statusText.text.toString())
        assertFalse(binding.buttonBar.isVisible)
        assertTrue(binding.callControls.isShown)
        assertTrue(binding.btnTalk.isShown && binding.btnDoorOpen.isShown && binding.btnHangUp.isShown)
        assertEquals(activity.getString(R.string.btn_talk_start), binding.btnTalk.contentDescription)
        assertEquals(activity.getString(R.string.btn_door_open), binding.btnDoorOpen.contentDescription)
        assertEquals(activity.getString(R.string.btn_hang_up), binding.btnHangUp.contentDescription)
        assertFalse(binding.doorSlider.isVisible)

        capture(activity, "callActive_01_connected")
    }

    @Test
    fun callActiveTalking() {
        val activity = launch()
        activity.showRinging()
        activity.showCall()
        // As IntercomControls reports it: talking on, talk status from the Baichuan session.
        activity.binding.btnTalk.showTalkState(true)
        activity.binding.talkStatusText.text = activity.getString(R.string.status_talk_active)
        settle()

        val binding = activity.binding
        assertTrue(binding.callControls.isShown)
        assertEquals(activity.getString(R.string.btn_talk_stop), binding.btnTalk.contentDescription)
        assertTrue(binding.talkStatusText.isShown)
        assertEquals(activity.getString(R.string.status_talk_active), binding.talkStatusText.text.toString())
        assertEquals(activity.getString(R.string.status_connected), binding.statusText.text.toString())

        capture(activity, "callActiveTalking_01_talkStatusShown")
    }

    @Test
    fun callRingingDoorSlider() {
        val activity = launch()
        activity.showRinging()
        // Like tapping "Open & hang up" (IncomingCallActivity.confirmDoor).
        activity.binding.doorSlider.toggle(
            activity.getString(R.string.slide_to_open_and_hang_up),
            activity.getString(R.string.btn_door_open_and_hang_up)
        ) {}
        settle()

        val binding = activity.binding
        assertTrue(binding.buttonBar.isShown)
        assertTrue(binding.doorSlider.isShown)
        assertTrue(binding.doorSlider.width > 0 && binding.doorSlider.height > 0)
        assertEquals(activity.getString(R.string.slide_to_open_and_hang_up), binding.doorSlider.contentDescription)

        capture(activity, "callRingingDoorSlider_01_sliderShown")
    }

    @Test
    fun callRinging_compose() {
        val activity = launchCompose()
        activity.showRinging()
        settleCompose()

        assertComposeHosted(activity)
        composeTestRule.onNodeWithTag(IncomingCallTestTags.STATUS_TEXT)
            .assertTextEquals(activity.getString(R.string.incoming_call_title))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_ACCEPT).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_accept))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_DECLINE).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_decline))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_DOOR_OPEN_AND_HANG_UP).assertIsDisplayed()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_TALK).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.TALK_STATUS_TEXT).assertTextEquals("")

        capture(activity, "callRinging_01_initial", COMPOSE_SUBDIR)
    }

    @Test
    fun callActive_compose() {
        val activity = launchCompose()
        activity.showRinging()
        activity.showCall()
        activity.state = activity.state.copy(talking = false)
        settleCompose()

        assertComposeHosted(activity)
        composeTestRule.onNodeWithTag(IncomingCallTestTags.STATUS_TEXT)
            .assertTextEquals(activity.getString(R.string.status_connected))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_ACCEPT).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_TALK).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_talk_start))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_DOOR_OPEN).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_door_open))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_HANG_UP).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_hang_up))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()

        capture(activity, "callActive_01_connected", COMPOSE_SUBDIR)
    }

    @Test
    fun callActiveTalking_compose() {
        val activity = launchCompose()
        activity.showRinging()
        activity.showCall()
        // As IntercomControls reports it: talking on, talk status from the Baichuan session.
        activity.state = activity.state.copy(
            talking = true,
            talkStatusText = activity.getString(R.string.status_talk_active),
        )
        settleCompose()

        assertComposeHosted(activity)
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_TALK)
            .assertContentDescriptionEquals(activity.getString(R.string.btn_talk_stop))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.TALK_STATUS_TEXT).assertIsDisplayed()
            .assertTextEquals(activity.getString(R.string.status_talk_active))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.STATUS_TEXT)
            .assertTextEquals(activity.getString(R.string.status_connected))

        capture(activity, "callActiveTalking_01_talkStatusShown", COMPOSE_SUBDIR)
    }

    @Test
    fun callRingingDoorSlider_compose() {
        val activity = launchCompose()
        activity.showRinging()
        // Like tapping "Open & hang up" (IncomingCallActivity.confirmDoor).
        activity.state = activity.state.copy(
            doorConfirm = SlideConfirmRequest(
                label = activity.getString(R.string.slide_to_open_and_hang_up),
                actionLabel = activity.getString(R.string.btn_door_open_and_hang_up),
                token = 1,
            ),
            doorAction = DoorAction.DoorOpenAndHangUp,
        )
        settleCompose()

        assertComposeHosted(activity)
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_ACCEPT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.DOOR_SLIDER).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.slide_to_open_and_hang_up))

        capture(activity, "callRingingDoorSlider_01_sliderShown", COMPOSE_SUBDIR)
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Recomposition after a state change, then the main looper (layout pass) like [settle]. */
    private fun settleCompose() {
        composeTestRule.waitForIdle()
        settle()
    }

    /** Draws the whole window (decor view) onto an opaque bitmap, as the Compose counterpart will. */
    private fun capture(activity: Activity, fileName: String, subDir: String? = null) {
        settle()
        val root = activity.window.decorView
        assertTrue("window not laid out", root.width > 0 && root.height > 0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        root.draw(canvas)
        saveScreenshot(bitmap, fileName, subDir)
    }

    private fun saveScreenshot(bitmap: Bitmap, fileName: String, subDir: String? = null) {
        val localDir = if (subDir == null) screenshotDir() else File(screenshotDir(), subDir)
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
        /** Compose captures keep the View file names, so they live in their own folder. */
        private const val COMPOSE_SUBDIR = "compose"

        private fun screenshotDir() = File("build/outputs/screenshots/${IncomingCallScreenshotTest::class.java.simpleName}")

        /** Once per class, never per method: otherwise earlier captures of this run would be lost. */
        @JvmStatic
        @BeforeClass
        fun clearScreenshots() {
            screenshotDir().listFiles { file -> file.extension == "png" }?.forEach { it.delete() }
            File(screenshotDir(), COMPOSE_SUBDIR).listFiles { file -> file.extension == "png" }?.forEach { it.delete() }
        }
    }
}
