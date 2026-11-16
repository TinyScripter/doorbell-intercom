package de.kleinabersmart.doorbellintercom.ui.components

import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Die ursprünglichen Fälle des Tür-Sliders (früher SlideToConfirmViewTest) gegen [SlideToConfirm]:
 * eingebettet über eine [ComposeView] in ein View-Layout mit fester Pixelgröße und bedient mit
 * echten MotionEvents statt der Gesten der Compose-Testregel (siehe [SlideToConfirmTest]). Was ein Bestätigen auslöst und das Ausblenden
 * (`request = null`) entscheidet wie in beiden Activities der Host; [toggle] entspricht dem
 * Tippen auf den Tür-Button. Die Zeit läuft über die Uhr der Compose-Testregel.
 */
@RunWith(RobolectricTestRunner::class)
class SlideToConfirmTouchTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var view: ComposeView
    private var request by mutableStateOf<SlideConfirmRequest?>(null)
    private var action: () -> Unit = {}
    private var confirmed = 0
    private var eventTime = 0L

    @Before
    fun setUp() {
        val activity = composeTestRule.activity
        view = ComposeView(activity).apply {
            setContent {
                DoorbellTheme {
                    SlideToConfirm(
                        request = request,
                        onConfirmed = { action() },
                        onDismiss = { request = null },
                        modifier = Modifier.testTag(TAG),
                    )
                }
            }
        }
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(WIDTH, HEIGHT)) })
        toggle("Zum Öffnen schieben", "Tür öffnen") { confirmed++ }
        // Layout-Durchlauf. Robolectric rechnet mit Dichte 1: Rand 4 px, Knopf 52 px, Weg 300 - 8 - 52 = 240 px.
        composeTestRule.waitForIdle()
        assertEquals(WIDTH, view.width)
        slider().assertIsDisplayed()
    }

    /** Wie das Tippen auf einen Tür-Button: blendet ein, mit demselben Label wieder aus. */
    private fun toggle(label: String, actionLabel: String, onConfirmed: () -> Unit) {
        request = request.toggled(label, actionLabel)
        action = onConfirmed
        composeTestRule.waitForIdle()
    }

    private fun slider() = composeTestRule.onNodeWithTag(TAG)

    private fun touch(action: Int, x: Float) {
        eventTime += 10
        val event = MotionEvent.obtain(0L, eventTime, action, x, HEIGHT / 2f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Greift den Knopf in der Mitte und zieht ihn um [distance] Pixel nach rechts. */
    private fun drag(distance: Float) {
        touch(MotionEvent.ACTION_DOWN, THUMB_CENTER)
        touch(MotionEvent.ACTION_MOVE, THUMB_CENTER + distance / 2)
        touch(MotionEvent.ACTION_MOVE, THUMB_CENTER + distance)
        touch(MotionEvent.ACTION_UP, THUMB_CENTER + distance)
        composeTestRule.waitForIdle()
    }

    @Test
    fun dragToEnd_confirmsAndHides() {
        drag(240f)

        assertEquals(1, confirmed)
        slider().assertDoesNotExist()
    }

    @Test
    fun dragHalfway_doesNotConfirm() {
        drag(120f)

        assertEquals(0, confirmed)
        slider().assertIsDisplayed()
    }

    @Test
    fun tapOnTrack_isIgnored() {
        // Auf der Leiste statt am Knopf gegriffen: auch bis ans Ende gezogen löst nichts aus.
        touch(MotionEvent.ACTION_DOWN, 200f)
        touch(MotionEvent.ACTION_MOVE, WIDTH - 4f)
        touch(MotionEvent.ACTION_UP, WIDTH - 4f)
        composeTestRule.waitForIdle()

        assertEquals(0, confirmed)
        slider().assertIsDisplayed()
    }

    @Test
    fun accessibilityClick_confirms() {
        slider().performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals(1, confirmed)
        slider().assertDoesNotExist()
    }

    @Test
    fun secondToggleWithSameLabel_cancels() {
        toggle("Zum Öffnen schieben", "Tür öffnen") { confirmed++ }

        slider().assertDoesNotExist()
        assertEquals(0, confirmed)
    }

    @Test
    fun toggleWithOtherLabel_switchesAction() {
        var other = 0
        toggle("Zum Öffnen & Auflegen schieben", "Tür öffnen und auflegen") { other++ }

        slider().assertIsDisplayed().assertContentDescriptionEquals("Zum Öffnen & Auflegen schieben")
        slider().performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertEquals(0, confirmed)
        assertEquals(1, other)
    }

    @Test
    fun hidesAutomaticallyWithoutInteraction() {
        // Plus ein paar Frames: der Timer startet erst mit der ersten Komposition des Sliders.
        composeTestRule.mainClock.advanceTimeBy(SlideToConfirmDefaults.AUTO_HIDE_MS + FRAME_MARGIN_MS)
        composeTestRule.waitForIdle()

        slider().assertDoesNotExist()
        assertEquals(0, confirmed)
    }

    private companion object {
        const val TAG = "slider"
        const val WIDTH = 300
        const val HEIGHT = 60
        const val THUMB_CENTER = 30f
        const val FRAME_MARGIN_MS = 100L
    }
}
