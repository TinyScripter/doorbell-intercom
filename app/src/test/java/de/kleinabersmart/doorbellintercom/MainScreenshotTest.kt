package de.kleinabersmart.doorbellintercom

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import de.kleinabersmart.doorbellintercom.databinding.ActivityMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Screenshot tests of the main screen (activity_main.xml).
 *
 * The real MainActivity starts VPN, RTSP, push registration and services, so [HostActivity]
 * only repeats its view setup: same theme, layout, toolbar as support action bar with
 * menu_main (settings action) and the system insets. The PlayerView has no player and renders black.
 *
 * Device: Pixel 9 class phone (1080 x 2424 px at 420 dpi = 411 x 923 dp), light mode, values/ strings.
 * Screenshots go to app/build/outputs/screenshots/MainScreenshotTest/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h923dp-port-notnight-420dpi")
class MainScreenshotTest {

    /** Minimal host with the UI setup of MainActivity, without its services. */
    class HostActivity : AppCompatActivity() {
        lateinit var binding: ActivityMainBinding
        var menu: Menu? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_DoorbellIntercom)
            super.onCreate(savedInstanceState)
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            binding.topBar.applyTopSystemInset()
            binding.bottomBar.applyBottomSystemInset()
        }

        override fun onCreateOptionsMenu(menu: Menu): Boolean {
            menuInflater.inflate(R.menu.menu_main, menu)
            this.menu = menu
            return true
        }

        override fun onStart() {
            super.onStart()
            binding.btnTalk.showTalkState(false)
        }
    }

    private fun launch(): HostActivity =
        Robolectric.buildActivity(HostActivity::class.java).setup().get()

    /** Toolbar with title and settings action, as in every main screen state. */
    private fun assertToolbar(activity: HostActivity) {
        val binding = activity.binding
        assertTrue(binding.toolbar.isShown)
        assertEquals(activity.getString(R.string.app_name), binding.toolbar.title.toString())
        val settings = activity.menu?.findItem(R.id.action_settings)
        assertNotNull(settings)
        assertTrue(settings!!.isVisible)
        assertEquals(activity.getString(R.string.menu_settings), settings.title.toString())
    }

    @Test
    fun mainIdle() {
        val activity = launch()
        // Status of LiveViewController once the stream is running.
        activity.binding.statusText.text = activity.getString(R.string.status_connected)
        settle()

        val binding = activity.binding
        assertToolbar(activity)
        assertEquals(activity.getString(R.string.status_connected), binding.statusText.text.toString())
        assertTrue(binding.btnTalk.isShown && binding.btnTalk.isEnabled)
        assertTrue(binding.btnDoorOpen.isShown && binding.btnDoorOpen.isEnabled)
        assertEquals(activity.getString(R.string.btn_talk_start), binding.btnTalk.contentDescription)
        assertEquals(activity.getString(R.string.btn_door_open), binding.btnDoorOpen.contentDescription)
        assertEquals("", binding.talkStatusText.text.toString())
        assertFalse(binding.doorSlider.isVisible)

        capture(activity, "mainIdle_01_initial")
    }

    @Test
    fun mainTalking() {
        val activity = launch()
        activity.binding.statusText.text = activity.getString(R.string.status_connected)
        // As IntercomControls reports it: talking on, talk status from the Baichuan session.
        activity.binding.btnTalk.showTalkState(true)
        activity.binding.talkStatusText.text = activity.getString(R.string.status_talk_active)
        settle()

        val binding = activity.binding
        assertToolbar(activity)
        assertEquals(activity.getString(R.string.btn_talk_stop), binding.btnTalk.contentDescription)
        assertTrue(binding.talkStatusText.isShown)
        assertEquals(activity.getString(R.string.status_talk_active), binding.talkStatusText.text.toString())
        assertFalse(binding.doorSlider.isVisible)

        capture(activity, "mainTalking_01_talkStatusShown")
    }

    @Test
    fun mainDoorSlider() {
        val activity = launch()
        activity.binding.statusText.text = activity.getString(R.string.status_connected)
        // Like tapping "Open door" in MainActivity.
        activity.binding.doorSlider.toggle(
            activity.getString(R.string.slide_to_open),
            activity.getString(R.string.btn_door_open)
        ) {}
        settle()

        val binding = activity.binding
        assertToolbar(activity)
        assertTrue(binding.doorSlider.isShown)
        assertTrue(binding.doorSlider.width > 0 && binding.doorSlider.height > 0)
        assertEquals(activity.getString(R.string.slide_to_open), binding.doorSlider.contentDescription)

        capture(activity, "mainDoorSlider_01_sliderShown")
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
        private fun screenshotDir() = File("build/outputs/screenshots/${MainScreenshotTest::class.java.simpleName}")

        /** Once per class, never per method: otherwise earlier captures of this run would be lost. */
        @JvmStatic
        @BeforeClass
        fun clearScreenshots() {
            screenshotDir().listFiles { file -> file.extension == "png" }?.forEach { it.delete() }
        }
    }
}
