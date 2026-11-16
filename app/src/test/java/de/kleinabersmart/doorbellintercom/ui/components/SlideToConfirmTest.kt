package de.kleinabersmart.doorbellintercom.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose counterpart of SlideToConfirmViewTest: the same seven cases against [SlideToConfirm],
 * plus the threshold edges and the auto-hide re-arm after a failed drag.
 *
 * Like the View, the host decides what a confirm does: here [action] (switched by a toggle with
 * another label) and hiding = `request = null` in onDismiss, as both activities do.
 * Timing runs on the compose test main clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h923dp-port-notnight-420dpi")
class SlideToConfirmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var request by mutableStateOf<SlideConfirmRequest?>(null)
    private var action: () -> Unit = {}
    private var confirmed = 0
    private var dismissed = 0

    @Before
    fun setUp() {
        request = request.toggled(LABEL, ACTION_LABEL)
        action = { confirmed++ }
        composeTestRule.setContent {
            DoorbellTheme {
                Box(Modifier.width(WIDTH)) {
                    SlideToConfirm(
                        request = request,
                        onConfirmed = { action() },
                        onDismiss = {
                            dismissed++
                            request = null
                        },
                        modifier = Modifier.testTag(TAG),
                    )
                }
            }
        }
        slider().assertIsDisplayed().assertContentDescriptionEquals(LABEL)
    }

    private fun slider() = composeTestRule.onNodeWithTag(TAG)

    /** Track inset in px; the thumb is as high as the track minus both insets. */
    private val TouchInjectionScope.inset get() = SlideToConfirmDefaults.TrackInset.toPx()

    /** Thumb center at rest: inset + (height - 2 * inset) / 2. */
    private val TouchInjectionScope.thumbCenter get() = Offset(height / 2f, height / 2f)

    /** Travel of the thumb: width - 2 * inset - thumb = width - height. */
    private val TouchInjectionScope.maxOffset get() = (width - height).toFloat()

    /** Grabs the thumb in its center and drags it right by [fraction] of its travel, in two moves. */
    private fun drag(fraction: Float) {
        slider().performTouchInput {
            val distance = maxOffset * fraction
            down(thumbCenter)
            moveBy(Offset(distance / 2, 0f))
            moveBy(Offset(distance / 2, 0f))
            up()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun dragToEnd_confirmsAndHides() {
        drag(1f)

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
        assertNull(request)
        slider().assertDoesNotExist()
    }

    @Test
    fun dragHalfway_doesNotConfirm() {
        drag(0.5f)

        assertEquals(0, confirmed)
        assertEquals(0, dismissed)
        slider().assertIsDisplayed()
    }

    @Test
    fun dragJustBelowThreshold_doesNotConfirm() {
        drag(SlideToConfirmDefaults.CONFIRM_THRESHOLD - 0.05f)

        assertEquals(0, confirmed)
        slider().assertIsDisplayed()
    }

    @Test
    fun dragJustPastThreshold_confirms() {
        drag(SlideToConfirmDefaults.CONFIRM_THRESHOLD + 0.05f)

        assertEquals(1, confirmed)
        slider().assertDoesNotExist()
    }

    @Test
    fun tapOnTrack_isIgnored() {
        // A touch on the track (not the thumb) does not grab: not even dragging it to the end confirms.
        slider().performTouchInput {
            down(Offset(width * 2f / 3f, height / 2f))
            moveBy(Offset(width / 3f - inset, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(0, confirmed)
        assertEquals(0, dismissed)
        slider().assertIsDisplayed()
    }

    @Test
    fun accessibilityClick_confirms() {
        // TalkBack announces the action label for the double tap.
        slider().assert(hasOnClickLabel(ACTION_LABEL))

        slider().performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
        slider().assertDoesNotExist()
    }

    @Test
    fun secondToggleWithSameLabel_cancels() {
        request = request.toggled(LABEL, ACTION_LABEL)
        composeTestRule.waitForIdle()

        assertNull(request)
        slider().assertDoesNotExist()
        assertEquals(0, confirmed)
    }

    @Test
    fun toggleWithOtherLabel_switchesAction() {
        var other = 0
        val before = request!!.token
        request = request.toggled(OTHER_LABEL, OTHER_ACTION_LABEL)
        action = { other++ }
        composeTestRule.waitForIdle()

        assertEquals(before + 1, request!!.token)
        slider().assertIsDisplayed()
            .assertContentDescriptionEquals(OTHER_LABEL)
            .assert(hasOnClickLabel(OTHER_ACTION_LABEL))
        slider().performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals(0, confirmed)
        assertEquals(1, other)
    }

    @Test
    fun hidesAutomaticallyWithoutInteraction() {
        composeTestRule.mainClock.advanceTimeBy(SlideToConfirmDefaults.AUTO_HIDE_MS - MARGIN_MS)
        slider().assertIsDisplayed()
        assertEquals(0, dismissed)

        composeTestRule.mainClock.advanceTimeBy(2 * MARGIN_MS)
        composeTestRule.waitForIdle()

        slider().assertDoesNotExist()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun failedDrag_restartsAutoHide() {
        composeTestRule.mainClock.advanceTimeBy(SlideToConfirmDefaults.AUTO_HIDE_MS - 2 * MARGIN_MS)
        drag(0.5f)

        // The first timer would have fired by now; the drag re-armed the full 6 s.
        composeTestRule.mainClock.advanceTimeBy(SlideToConfirmDefaults.AUTO_HIDE_MS - MARGIN_MS)
        slider().assertIsDisplayed()
        assertEquals(0, dismissed)

        composeTestRule.mainClock.advanceTimeBy(2 * MARGIN_MS)
        composeTestRule.waitForIdle()
        slider().assertDoesNotExist()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun toggled_showsFromNothingAndCountsTokens() {
        val first = null.toggled(LABEL, ACTION_LABEL)
        assertEquals(SlideConfirmRequest(LABEL, ACTION_LABEL, token = 1), first)
        assertEquals(SlideConfirmRequest(OTHER_LABEL, OTHER_ACTION_LABEL, token = 2), first.toggled(OTHER_LABEL, OTHER_ACTION_LABEL))
        assertNull(first.toggled(LABEL, OTHER_ACTION_LABEL))
    }

    private fun hasOnClickLabel(label: String) = SemanticsMatcher("onClick label = $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private companion object {
        const val TAG = "slider"
        const val LABEL = "Zum Öffnen schieben"
        const val ACTION_LABEL = "Tür öffnen"
        const val OTHER_LABEL = "Zum Öffnen & Auflegen schieben"
        const val OTHER_ACTION_LABEL = "Tür öffnen und auflegen"
        const val MARGIN_MS = 500L
        val WIDTH = 300.dp
    }
}
