package de.kleinabersmart.doorbellintercom.ui.incomingcall

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.components.SlideConfirmRequest
import de.kleinabersmart.doorbellintercom.ui.components.SlideToConfirmDefaults
import de.kleinabersmart.doorbellintercom.ui.components.toggled
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Interaction tests of [IncomingCallScreen] (replaces activity_incoming_call.xml): which buttons
 * each phase offers and which callback they report, the talk button state, the texts and the
 * door slider with the [DoorAction] it confirms.
 *
 * Every callback appends to [events], so a test also proves that no other callback fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h923dp-port-notnight-420dpi")
class IncomingCallScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(@StringRes id: Int) = context.getString(id)

    private var state by mutableStateOf(IncomingCallUiState())
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        state = IncomingCallUiState(phase = IncomingCallPhase.Ringing, statusText = str(R.string.incoming_call_title))
        composeTestRule.setContent {
            DoorbellTheme {
                IncomingCallScreen(
                    state = state,
                    onAccept = { events += "accept" },
                    onDecline = { events += "decline" },
                    onDoorOpenAndHangUpClick = { events += "doorOpenAndHangUpClick" },
                    onTalk = { events += "talk" },
                    onDoorOpenClick = { events += "doorOpenClick" },
                    onHangUp = { events += "hangUp" },
                    onDoorConfirmed = { events += "doorConfirmed:$it" },
                    onDoorDismiss = {
                        events += "doorDismiss"
                        state = state.copy(doorConfirm = null)
                    },
                )
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    private fun setState(transform: (IncomingCallUiState) -> IncomingCallUiState) {
        state = transform(state)
        composeTestRule.waitForIdle()
    }

    private fun assertRingingButtonsGone() {
        node(IncomingCallTestTags.BTN_DECLINE).assertDoesNotExist()
        node(IncomingCallTestTags.BTN_ACCEPT).assertDoesNotExist()
        node(IncomingCallTestTags.BTN_DOOR_OPEN_AND_HANG_UP).assertDoesNotExist()
    }

    private fun assertActiveButtonsGone() {
        node(IncomingCallTestTags.BTN_TALK).assertDoesNotExist()
        node(IncomingCallTestTags.BTN_DOOR_OPEN).assertDoesNotExist()
        node(IncomingCallTestTags.BTN_HANG_UP).assertDoesNotExist()
    }

    @Test
    fun ringing_showsDeclineAcceptDoorOpenAndHangUp_withLegacyContentDescriptions() {
        node(IncomingCallTestTags.STATUS_TEXT).assertIsDisplayed().assertTextEquals(str(R.string.incoming_call_title))
        // Same contentDescription as android:contentDescription in activity_incoming_call.xml.
        node(IncomingCallTestTags.BTN_DECLINE).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_decline))
        node(IncomingCallTestTags.BTN_ACCEPT).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_accept))
        node(IncomingCallTestTags.BTN_DOOR_OPEN_AND_HANG_UP).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_door_open_and_hang_up))
        assertActiveButtonsGone()
        node(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()
    }

    @Test
    fun ringing_buttonsReportTheirCallbacks() {
        node(IncomingCallTestTags.BTN_DECLINE).performClick()
        node(IncomingCallTestTags.BTN_ACCEPT).performClick()
        node(IncomingCallTestTags.BTN_DOOR_OPEN_AND_HANG_UP).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("decline", "accept", "doorOpenAndHangUpClick"), events)
    }

    @Test
    fun active_showsTalkDoorOpenHangUp_withLegacyContentDescriptions() {
        setState { it.copy(phase = IncomingCallPhase.Active, statusText = str(R.string.status_connected)) }

        node(IncomingCallTestTags.STATUS_TEXT).assertTextEquals(str(R.string.status_connected))
        node(IncomingCallTestTags.BTN_TALK).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_talk_start))
        node(IncomingCallTestTags.BTN_DOOR_OPEN).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_door_open))
        node(IncomingCallTestTags.BTN_HANG_UP).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_hang_up))
        assertRingingButtonsGone()
    }

    @Test
    fun active_buttonsReportTheirCallbacks() {
        setState { it.copy(phase = IncomingCallPhase.Active) }

        node(IncomingCallTestTags.BTN_TALK).performClick()
        node(IncomingCallTestTags.BTN_DOOR_OPEN).performClick()
        node(IncomingCallTestTags.BTN_HANG_UP).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("talk", "doorOpenClick", "hangUp"), events)
    }

    @Test
    fun talkButton_contentDescriptionFollowsTalking() {
        setState { it.copy(phase = IncomingCallPhase.Active, talking = false) }
        node(IncomingCallTestTags.BTN_TALK).assertContentDescriptionEquals(str(R.string.btn_talk_start))

        setState { it.copy(talking = true, talkStatusText = str(R.string.status_talk_active)) }
        node(IncomingCallTestTags.BTN_TALK).assertContentDescriptionEquals(str(R.string.btn_talk_stop))
        node(IncomingCallTestTags.TALK_STATUS_TEXT).assertIsDisplayed().assertTextEquals(str(R.string.status_talk_active))

        setState { it.copy(talking = false, talkStatusText = "") }
        node(IncomingCallTestTags.BTN_TALK).assertContentDescriptionEquals(str(R.string.btn_talk_start))
        node(IncomingCallTestTags.TALK_STATUS_TEXT).assertTextEquals("")
    }

    @Test
    fun ringingSlider_confirmsDoorOpenAndHangUp() {
        // As IncomingCallActivity.confirmDoor for "Open & hang up".
        setState {
            it.copy(
                doorConfirm = it.doorConfirm.toggled(
                    str(R.string.slide_to_open_and_hang_up),
                    str(R.string.btn_door_open_and_hang_up),
                ),
                doorAction = DoorAction.DoorOpenAndHangUp,
            )
        }
        node(IncomingCallTestTags.DOOR_SLIDER).assertIsDisplayed()
            .assertContentDescriptionEquals(str(R.string.slide_to_open_and_hang_up))
            .assert(hasOnClickLabel(str(R.string.btn_door_open_and_hang_up)))

        node(IncomingCallTestTags.DOOR_SLIDER).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        // Like the View: hide first, then the action (which still knows the confirmed door action).
        assertEquals(listOf("doorDismiss", "doorConfirmed:DoorOpenAndHangUp"), events)
        node(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()
    }

    @Test
    fun activeSlider_dragToEnd_confirmsDoorOpen() {
        setState {
            it.copy(
                phase = IncomingCallPhase.Active,
                doorConfirm = it.doorConfirm.toggled(str(R.string.slide_to_open), str(R.string.btn_door_open)),
                doorAction = DoorAction.DoorOpen,
            )
        }
        node(IncomingCallTestTags.DOOR_SLIDER).assertIsDisplayed()
            .assertContentDescriptionEquals(str(R.string.slide_to_open))
            .assert(hasOnClickLabel(str(R.string.btn_door_open)))

        node(IncomingCallTestTags.DOOR_SLIDER).performTouchInput {
            down(Offset(height / 2f, height / 2f))
            moveBy(Offset((width - height) / 2f, 0f))
            moveBy(Offset((width - height) / 2f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("doorDismiss", "doorConfirmed:DoorOpen"), events)
        node(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()
    }

    @Test
    fun slider_hidesAfterTimeoutWithoutConfirming() {
        setState {
            it.copy(
                phase = IncomingCallPhase.Active,
                doorConfirm = SlideConfirmRequest(str(R.string.slide_to_open), str(R.string.btn_door_open), token = 1),
            )
        }

        composeTestRule.mainClock.advanceTimeBy(SlideToConfirmDefaults.AUTO_HIDE_MS + 500)
        composeTestRule.waitForIdle()

        assertEquals(listOf("doorDismiss"), events)
        node(IncomingCallTestTags.DOOR_SLIDER).assertDoesNotExist()
    }

    @Test
    fun phaseSwitch_replacesRingingButtonsByCallButtons() {
        node(IncomingCallTestTags.BTN_ACCEPT).assertIsDisplayed()

        // What showCall writes after CallController reports Active.
        setState { it.copy(phase = IncomingCallPhase.Active, statusText = str(R.string.status_connected), doorConfirm = null) }

        assertRingingButtonsGone()
        node(IncomingCallTestTags.BTN_HANG_UP).assertIsDisplayed()
        node(IncomingCallTestTags.STATUS_TEXT).assertTextEquals(str(R.string.status_connected))
    }

    private fun hasOnClickLabel(label: String) = SemanticsMatcher("onClick label = $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }
}
