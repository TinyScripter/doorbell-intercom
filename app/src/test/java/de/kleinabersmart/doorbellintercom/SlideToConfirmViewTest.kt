package de.kleinabersmart.doorbellintercom

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.view.isVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SlideToConfirmViewTest {

    private lateinit var view: SlideToConfirmView
    private var confirmed = 0

    @Before
    fun setUp() {
        // In einer Activity, damit postDelayed (automatisches Ausblenden) wirklich läuft.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = SlideToConfirmView(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(WIDTH, HEIGHT)) })
        view.toggle("Zum Öffnen schieben", "Tür öffnen") { confirmed++ }
        // Layout-Durchlauf. Robolectric rechnet mit Dichte 1: Rand 4 px, Knopf 52 px, Weg 300 - 8 - 52 = 240 px.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WIDTH, view.width)
    }

    private fun touch(action: Int, x: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, HEIGHT / 2f, 0)
        return view.onTouchEvent(event).also { event.recycle() }
    }

    /** Greift den Knopf in der Mitte und zieht ihn um [distance] Pixel nach rechts. */
    private fun drag(distance: Float) {
        assertTrue(touch(MotionEvent.ACTION_DOWN, THUMB_CENTER))
        touch(MotionEvent.ACTION_MOVE, THUMB_CENTER + distance / 2)
        touch(MotionEvent.ACTION_MOVE, THUMB_CENTER + distance)
        touch(MotionEvent.ACTION_UP, THUMB_CENTER + distance)
    }

    @Test
    fun dragToEnd_confirmsAndHides() {
        drag(240f)

        assertEquals(1, confirmed)
        assertFalse(view.isVisible)
    }

    @Test
    fun dragHalfway_doesNotConfirm() {
        drag(120f)

        assertEquals(0, confirmed)
        assertTrue(view.isVisible)
    }

    @Test
    fun tapOnTrack_isIgnored() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 200f))
        assertEquals(0, confirmed)
    }

    @Test
    fun accessibilityClick_confirms() {
        view.performClick()

        assertEquals(1, confirmed)
        assertFalse(view.isVisible)
    }

    @Test
    fun secondToggleWithSameLabel_cancels() {
        view.toggle("Zum Öffnen schieben", "Tür öffnen") { confirmed++ }

        assertFalse(view.isVisible)
        view.performClick()
        assertEquals(0, confirmed)
    }

    @Test
    fun toggleWithOtherLabel_switchesAction() {
        var other = 0
        view.toggle("Zum Öffnen & Auflegen schieben", "Tür öffnen und auflegen") { other++ }

        assertTrue(view.isVisible)
        view.performClick()
        assertEquals(0, confirmed)
        assertEquals(1, other)
    }

    @Test
    fun hidesAutomaticallyWithoutInteraction() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SlideToConfirmView.AUTO_HIDE_MS))

        assertFalse(view.isVisible)
        assertEquals(0, confirmed)
    }

    private companion object {
        const val WIDTH = 300
        const val HEIGHT = 60
        const val THUMB_CENTER = 30f
    }
}
