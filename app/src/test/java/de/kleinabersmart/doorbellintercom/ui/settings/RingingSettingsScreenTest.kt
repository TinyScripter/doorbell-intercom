package de.kleinabersmart.doorbellintercom.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags as Tags
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Interaction tests of the stateless [RingingSettingsScreen] (replaces settings_ringing.xml): ring
 * duration slider (10..90 s in 5 s steps, live label), ringtone label and picker button, switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class RingingSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var state by mutableStateOf(INITIAL)
    private val events = mutableListOf<Any>()

    @Before
    fun setUp() {
        composeTestRule.setContent {
            DoorbellTheme {
                RingingSettingsScreen(
                    state = state,
                    onStateChange = {
                        events += it
                        state = it
                    },
                    onChooseRingtone = { events += "chooseRingtone" },
                    onNavigateUp = { events += "up" },
                )
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    @Test
    fun ringDurationSlider_snapsToStepsOf5Within10To90() {
        for ((input, expected) in listOf(47f to 45f, 48f to 50f, 200f to 90f, 0f to 10f)) {
            val before = state
            node(Tags.SLIDER_RING_DURATION).setSliderValue(input)
            composeTestRule.waitForIdle()
            assertEquals("ring duration set to $input", before.copy(ringDurationSeconds = expected.toInt()), state)
            node(Tags.SLIDER_RING_DURATION).assertRangeInfoEquals(ProgressBarRangeInfo(expected, 10f..90f, 15))
            node(Tags.LABEL_RING_DURATION).assertTextEquals(str(R.string.label_ring_duration, expected.toInt()))
        }
    }

    @Test
    fun ringtoneLabel_showsTitleFromState_buttonOpensPicker() {
        node(Tags.LABEL_RINGTONE).assertTextEquals(str(R.string.label_ringtone, "Galaxy Bells"))

        node(Tags.BTN_CHOOSE_RINGTONE).performClick()
        assertEquals(listOf("chooseRingtone"), events)

        // The Activity puts the picked title into the state.
        state = state.copy(ringtoneTitle = "Old Phone")
        composeTestRule.waitForIdle()
        node(Tags.LABEL_RINGTONE).assertTextEquals(str(R.string.label_ringtone, "Old Phone"))
    }

    @Test
    fun switches_toggleTheirOwnValue() {
        node(Tags.SWITCH_RING_IN_SILENT_MODE).assert(hasRole(Role.Switch)).assertIsOn().performClick()
        node(Tags.SWITCH_RING_IN_SILENT_MODE).assertIsOff()
        assertEquals(INITIAL.copy(ringInSilentMode = false), events.single())

        node(Tags.SWITCH_TELECOM_CALLS).assert(hasRole(Role.Switch)).assertIsOff().performClick()
        node(Tags.SWITCH_TELECOM_CALLS).assertIsOn()
        assertEquals(INITIAL.copy(ringInSilentMode = false, telecomCalls = true), events.last())
        assertEquals(2, events.size)
    }

    @Test
    fun upButton_navigatesUp() {
        node(Tags.UP).performClick()

        assertEquals(listOf("up"), events)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private companion object {
        val INITIAL = RingingSettingsUiState(
            ringDurationSeconds = 30,
            ringtoneTitle = "Galaxy Bells",
            ringInSilentMode = true,
            telecomCalls = false,
        )
    }
}
