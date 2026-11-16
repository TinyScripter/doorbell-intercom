package de.kleinabersmart.doorbellintercom.ui.main

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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.doorbellintercom.R
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
 * Interaction tests of [MainScreen] (replaces activity_main.xml + menu_main.xml): talk and door
 * buttons, the settings action, the door slider and the system-check hint (shown on request,
 * action opens the settings, closes itself after [SYSTEM_CHECK_HINT_MS]).
 *
 * Every callback appends to [events], so a test also proves that no other callback fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h923dp-port-notnight-420dpi")
class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(@StringRes id: Int) = context.getString(id)

    private var state by mutableStateOf(MainUiState())
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        state = MainUiState(statusText = str(R.string.status_connected))
        composeTestRule.setContent {
            DoorbellTheme {
                MainScreen(
                    state = state,
                    onToggleTalk = { events += "toggleTalk" },
                    onDoorOpenClick = {
                        events += "doorOpenClick"
                        // As MainActivity.toggleDoorSlider.
                        state = state.copy(
                            doorConfirm = state.doorConfirm.toggled(str(R.string.slide_to_open), str(R.string.btn_door_open))
                        )
                    },
                    onDoorConfirmed = { events += "doorConfirmed" },
                    onDoorDismiss = {
                        events += "doorDismiss"
                        state = state.copy(doorConfirm = null)
                    },
                    onOpenSettings = { events += "openSettings" },
                )
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    private fun setState(transform: (MainUiState) -> MainUiState) {
        state = transform(state)
        composeTestRule.waitForIdle()
    }

    /** As MainActivity.showSystemCheckHint on onResume with open issues. */
    private fun requestHint() =
        setState { it.copy(showSystemCheckHint = true, systemCheckHintRequest = it.systemCheckHintRequest + 1) }

    private fun hintMessage() = composeTestRule.onNodeWithText(str(R.string.check_snackbar))

    @Test
    fun initial_showsToolbarStatusAndButtons_withLegacyContentDescriptions() {
        node(MainTestTags.TOOLBAR).assertIsDisplayed()
        composeTestRule.onNode(hasText(str(R.string.app_name))).assertIsDisplayed()
        // menu_main.xml: action_settings with title menu_settings.
        node(MainTestTags.ACTION_SETTINGS).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.menu_settings))
        node(MainTestTags.STATUS_TEXT).assertIsDisplayed().assertTextEquals(str(R.string.status_connected))
        node(MainTestTags.TALK_STATUS_TEXT).assertTextEquals("")
        // Same contentDescription as android:contentDescription in activity_main.xml.
        node(MainTestTags.BTN_TALK).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_talk_start))
        node(MainTestTags.BTN_DOOR_OPEN).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(str(R.string.btn_door_open))
        node(MainTestTags.DOOR_SLIDER).assertDoesNotExist()
        hintMessage().assertDoesNotExist()
    }

    @Test
    fun talkButton_togglesTalk_andContentDescriptionFollowsTalking() {
        node(MainTestTags.BTN_TALK).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("toggleTalk"), events)

        setState { it.copy(talking = true, talkStatusText = str(R.string.status_talk_active)) }
        node(MainTestTags.BTN_TALK).assertContentDescriptionEquals(str(R.string.btn_talk_stop))
        node(MainTestTags.TALK_STATUS_TEXT).assertIsDisplayed().assertTextEquals(str(R.string.status_talk_active))

        setState { it.copy(talking = false, talkStatusText = "") }
        node(MainTestTags.BTN_TALK).assertContentDescriptionEquals(str(R.string.btn_talk_start))
    }

    @Test
    fun settingsAction_opensSettings() {
        node(MainTestTags.ACTION_SETTINGS).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("openSettings"), events)
    }

    @Test
    fun doorButton_showsSlider_secondTapHidesIt() {
        node(MainTestTags.BTN_DOOR_OPEN).performClick()
        composeTestRule.waitForIdle()

        node(MainTestTags.DOOR_SLIDER).assertIsDisplayed()
            .assertContentDescriptionEquals(str(R.string.slide_to_open))
            .assert(hasOnClickLabel(str(R.string.btn_door_open)))

        node(MainTestTags.BTN_DOOR_OPEN).performClick()
        composeTestRule.waitForIdle()

        node(MainTestTags.DOOR_SLIDER).assertDoesNotExist()
        // Only showing/hiding: the door itself is never opened by the button.
        assertEquals(listOf("doorOpenClick", "doorOpenClick"), events)
    }

    @Test
    fun slider_accessibilityClick_opensDoor() {
        node(MainTestTags.BTN_DOOR_OPEN).performClick()
        composeTestRule.waitForIdle()

        node(MainTestTags.DOOR_SLIDER).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals(listOf("doorOpenClick", "doorDismiss", "doorConfirmed"), events)
        node(MainTestTags.DOOR_SLIDER).assertDoesNotExist()
    }

    @Test
    fun slider_dragToEnd_opensDoor_halfwayDoesNot() {
        node(MainTestTags.BTN_DOOR_OPEN).performClick()
        composeTestRule.waitForIdle()

        node(MainTestTags.DOOR_SLIDER).performTouchInput {
            down(Offset(height / 2f, height / 2f))
            moveBy(Offset((width - height) / 2f, 0f))
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf("doorOpenClick"), events)
        node(MainTestTags.DOOR_SLIDER).assertIsDisplayed()

        node(MainTestTags.DOOR_SLIDER).performTouchInput {
            down(Offset(height / 2f, height / 2f))
            moveBy(Offset((width - height) / 2f, 0f))
            moveBy(Offset((width - height) / 2f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("doorOpenClick", "doorDismiss", "doorConfirmed"), events)
        node(MainTestTags.DOOR_SLIDER).assertDoesNotExist()
    }

    @Test
    fun systemCheckHint_shownOnRequest_actionOpensSettings() {
        requestHint()

        node(MainTestTags.SNACKBAR_HOST).assertIsDisplayed()
        hintMessage().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.btn_check)).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("openSettings"), events)
        hintMessage().assertDoesNotExist()
    }

    @Test
    fun systemCheckHint_closesItselfAfterEightSeconds() {
        requestHint()
        hintMessage().assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(SYSTEM_CHECK_HINT_MS - MARGIN_MS)
        hintMessage().assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(2 * MARGIN_MS)
        composeTestRule.waitForIdle()

        hintMessage().assertDoesNotExist()
        // Timing out is not the action: the settings stay closed.
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun systemCheckHint_repeatedRequestWhileShown_keepsOneHintAndItsTimer() {
        requestHint()
        composeTestRule.mainClock.advanceTimeBy(SYSTEM_CHECK_HINT_MS / 2)

        // Next onResume while the hint is still visible (legacy: no second Snackbar).
        requestHint()
        composeTestRule.onAllNodesWithText(str(R.string.check_snackbar)).assertCountEquals(1)

        composeTestRule.mainClock.advanceTimeBy(SYSTEM_CHECK_HINT_MS / 2 + MARGIN_MS)
        composeTestRule.waitForIdle()
        hintMessage().assertDoesNotExist()
    }

    @Test
    fun systemCheckHint_closedWhenIssuesAreResolved() {
        requestHint()
        hintMessage().assertIsDisplayed()

        // onResume without issues.
        setState { it.copy(showSystemCheckHint = false) }

        hintMessage().assertDoesNotExist()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun systemCheckHint_notShownWithoutIssues() {
        setState { it.copy(showSystemCheckHint = false, systemCheckHintRequest = 1) }
        composeTestRule.mainClock.advanceTimeBy(MARGIN_MS)

        hintMessage().assertDoesNotExist()
    }

    private fun hasOnClickLabel(label: String) = SemanticsMatcher("onClick label = $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private companion object {
        const val MARGIN_MS = 500L
    }
}
