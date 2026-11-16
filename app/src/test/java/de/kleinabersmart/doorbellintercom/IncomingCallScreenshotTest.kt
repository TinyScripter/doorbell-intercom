package de.kleinabersmart.doorbellintercom

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import de.kleinabersmart.doorbellintercom.databinding.ActivityIncomingCallBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
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
 * Device: Pixel 9 class phone (1080 x 2424 px at 420 dpi = 411 x 923 dp), light mode, values/ strings.
 * Screenshots go to app/build/outputs/screenshots/IncomingCallScreenshotTest/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h923dp-port-notnight-420dpi")
class IncomingCallScreenshotTest {

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

    private fun launch(): HostActivity =
        Robolectric.buildActivity(HostActivity::class.java).setup().get()

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

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Draws the whole window (decor view) onto an opaque bitmap, as the Compose counterpart will. */
    private fun capture(activity: Activity, fileName: String) {
        settle()
        val root = activity.window.decorView
        assertTrue("window not laid out", root.width > 0 && root.height > 0)
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
        private fun screenshotDir() = File("build/outputs/screenshots/${IncomingCallScreenshotTest::class.java.simpleName}")

        /** Once per class, never per method: otherwise earlier captures of this run would be lost. */
        @JvmStatic
        @BeforeClass
        fun clearScreenshots() {
            screenshotDir().listFiles { file -> file.extension == "png" }?.forEach { it.delete() }
        }
    }
}
