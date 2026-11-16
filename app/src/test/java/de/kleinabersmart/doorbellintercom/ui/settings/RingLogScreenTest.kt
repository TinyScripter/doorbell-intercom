package de.kleinabersmart.doorbellintercom.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags as Tags
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Interaction tests of the stateless [RingLogScreen] (replaces settings_ring_log.xml): one text
 * block with one entry per line (duplicates kept), the empty hint, and "Copy" only with entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class RingLogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var state by mutableStateOf(RingLogUiState())
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        composeTestRule.setContent {
            DoorbellTheme {
                RingLogScreen(
                    state = state,
                    onCopyClick = { events += "copy" },
                    onNavigateUp = { events += "up" },
                )
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    @Test
    fun empty_showsHintAndCopyIsDisabled() {
        node(Tags.RING_LOG_TEXT).assertTextEquals(str(R.string.ring_log_empty))
        node(Tags.BTN_COPY_RING_LOG).assertIsNotEnabled().performClick()

        assertTrue(events.isEmpty())
    }

    @Test
    fun entries_shownOnePerLine_copyEnabled() {
        state = RingLogUiState(ENTRIES)
        composeTestRule.waitForIdle()

        node(Tags.RING_LOG_TEXT).assertTextEquals(ENTRIES.joinToString("\n"))
        node(Tags.BTN_COPY_RING_LOG).assertIsEnabled().performClick()
        assertEquals(listOf("copy"), events)

        // Cleared again (e.g. after a refresh in onResume): back to the hint, copy disabled.
        state = RingLogUiState()
        composeTestRule.waitForIdle()
        node(Tags.RING_LOG_TEXT).assertTextEquals(str(R.string.ring_log_empty))
        node(Tags.BTN_COPY_RING_LOG).assertIsNotEnabled()
    }

    @Test
    fun upButton_navigatesUp() {
        node(Tags.UP).performClick()

        assertEquals(listOf("up"), events)
    }

    private companion object {
        val ENTRIES = listOf(
            "16.11.2026 18:42:07  Push  Anruf angezeigt",
            "16.11.2026 18:42:07  Push  Anruf angezeigt",
            "15.11.2026 09:30:12  MQTT  verpasst",
        )
    }
}
