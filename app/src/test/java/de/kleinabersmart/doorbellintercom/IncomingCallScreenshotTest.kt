package de.kleinabersmart.doorbellintercom

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.kleinabersmart.doorbellintercom.ui.components.SlideConfirmRequest
import de.kleinabersmart.doorbellintercom.ui.components.VideoLayer
import de.kleinabersmart.doorbellintercom.ui.components.inflateLivePlayerView
import de.kleinabersmart.doorbellintercom.ui.incomingcall.DoorAction
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallPhase
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallScreen
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallTestTags
import de.kleinabersmart.doorbellintercom.ui.incomingcall.IncomingCallUiState
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
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
 * Screenshot tests of the call screen (IncomingCallScreen, formerly activity_incoming_call.xml).
 *
 * The real IncomingCallActivity starts VPN, RTSP and the call controller, so [ComposeHostActivity]
 * only hosts IncomingCallScreen like IncomingCallActivity (same theme, DoorbellTheme, PlayerView
 * without player via VideoLayer, which renders black) and the capture draws the whole decor view.
 *
 * The original tests (callRinging, ...) were written against the XML layout; since the layout was
 * replaced by Compose they drive the same scenarios on the Compose screen, with the original checks
 * expressed as semantics, and keep their capture names. The `<method>_compose` tests were added
 * during the migration for the side-by-side comparison.
 *
 * Device: Pixel 9 class phone (1080 x 2424 px at 420 dpi = 411 x 923 dp), light mode, values/ strings.
 * Screenshots go to app/build/outputs/screenshots/IncomingCallScreenshotTest/ (original tests) and
 * its compose/ subfolder (`_compose` tests, same file names).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h923dp-port-notnight-420dpi")
class IncomingCallScreenshotTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

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

    private fun launchCompose(): ComposeHostActivity =
        Robolectric.buildActivity(ComposeHostActivity::class.java).setup().get()

    /** The Compose path really ran: the content view is the ComposeView of setContent. */
    private fun assertComposeHosted(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("no ComposeView attached", content.getChildAt(0) is ComposeView && content.getChildAt(0).isShown)
    }

    @Test
    fun callRinging() {
        val activity = launchCompose()
        activity.showRinging()
        settleCompose()

        assertComposeHosted(activity)
        composeTestRule.onNodeWithTag(IncomingCallTestTags.STATUS_TEXT)
            .assertTextEquals(activity.getString(R.string.incoming_call_title))
        // Button bar shown, call controls not.
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_ACCEPT).assertIsDisplayed().assertIsEnabled()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_accept))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_DECLINE).assertIsDisplayed().assertIsEnabled()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_decline))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_DOOR_OPEN_AND_HANG_UP).assertIsDisplayed()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_TALK).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_HANG_UP).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.TALK_STATUS_TEXT).assertTextEquals("")

        capture(activity, "callRinging_01_initial")
    }

    @Test
    fun callActive() {
        val activity = launchCompose()
        activity.showRinging()
        activity.showCall()
        activity.state = activity.state.copy(talking = false)
        settleCompose()

        assertComposeHosted(activity)
        composeTestRule.onNodeWithTag(IncomingCallTestTags.STATUS_TEXT)
            .assertTextEquals(activity.getString(R.string.status_connected))
        // Button bar gone, call controls shown.
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_ACCEPT).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_DECLINE).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_TALK).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_talk_start))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_DOOR_OPEN).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_door_open))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_HANG_UP).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_hang_up))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()

        capture(activity, "callActive_01_connected")
    }

    @Test
    fun callActiveTalking() {
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
        composeTestRule.onNodeWithTag(IncomingCallTestTags.BTN_TALK).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.btn_talk_stop))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.TALK_STATUS_TEXT).assertIsDisplayed()
            .assertTextEquals(activity.getString(R.string.status_talk_active))
        composeTestRule.onNodeWithTag(IncomingCallTestTags.STATUS_TEXT)
            .assertTextEquals(activity.getString(R.string.status_connected))

        capture(activity, "callActiveTalking_01_talkStatusShown")
    }

    @Test
    fun callRingingDoorSlider() {
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
        val slider = composeTestRule.onNodeWithTag(IncomingCallTestTags.DOOR_SLIDER).assertIsDisplayed()
            .assertContentDescriptionEquals(activity.getString(R.string.slide_to_open_and_hang_up))
        val bounds = slider.getUnclippedBoundsInRoot()
        assertTrue("slider not laid out", bounds.right > bounds.left && bounds.bottom > bounds.top)

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
