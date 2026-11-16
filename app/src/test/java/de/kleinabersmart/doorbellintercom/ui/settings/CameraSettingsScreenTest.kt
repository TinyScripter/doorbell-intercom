package de.kleinabersmart.doorbellintercom.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags as Tags
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Interaction tests of the stateless [CameraSettingsScreen] (replaces settings_camera.xml): every
 * input reports a new state with exactly its own field changed, the port only takes digits, the
 * password is masked, the keyboard walks host -> port -> user -> password -> RTSP path, the
 * checkboxes toggle and the sliders snap to their steps within their ranges with a live label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class CameraSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val keyboard = FakeKeyboardController()
    private var state by mutableStateOf(INITIAL)
    private val changes = mutableListOf<CameraSettingsUiState>()
    private var navigateUpCount = 0

    @Before
    fun setUp() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DoorbellTheme {
                    CameraSettingsScreen(
                        state = state,
                        onStateChange = {
                            changes += it
                            state = it
                        },
                        onNavigateUp = { navigateUpCount++ },
                    )
                }
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    @Test
    fun textFields_reportOnlyTheirOwnField() {
        val edits = listOf<Triple<String, String, (CameraSettingsUiState) -> CameraSettingsUiState>>(
            Triple(Tags.FIELD_CAMERA_HOST, " 10.0.0.7 ", { it.copy(host = " 10.0.0.7 ") }),
            Triple(Tags.FIELD_CAMERA_BC_PORT, "9001", { it.copy(bcPort = "9001") }),
            Triple(Tags.FIELD_CAMERA_USER, "viewer", { it.copy(user = "viewer") }),
            Triple(Tags.FIELD_CAMERA_PASS, " new pw ", { it.copy(password = " new pw ") }),
            Triple(Tags.FIELD_CAMERA_RTSP_PATH, "/h265Preview_01_sub", { it.copy(rtspPath = "/h265Preview_01_sub") }),
        )
        for ((index, edit) in edits.withIndex()) {
            val (tag, input, expected) = edit
            val before = state
            node(tag).performTextReplacement(input)
            composeTestRule.waitForIdle()
            assertEquals("changes after editing $tag", index + 1, changes.size)
            // The screen does not trim or normalize: that happens on saving in the Activity.
            assertEquals(expected(before), changes.last())
        }
        assertEquals(0, navigateUpCount)
    }

    @Test
    fun portField_dropsNonDigits() {
        node(Tags.FIELD_CAMERA_BC_PORT).performTextReplacement("80a8-0")

        assertEquals("8080", state.bcPort)
        assertEquals("8080", node(Tags.FIELD_CAMERA_BC_PORT).editableText())
        assertTrue(changes.all { it.bcPort.all(Char::isDigit) })
    }

    @Test
    fun passwordField_isMaskedOtherFieldsAreNot() {
        assertTrue(node(Tags.FIELD_CAMERA_PASS).isPassword())
        assertEquals(PASSWORD_MASK.toString().repeat(INITIAL.password.length), node(Tags.FIELD_CAMERA_PASS).editableText())
        for (tag in listOf(Tags.FIELD_CAMERA_HOST, Tags.FIELD_CAMERA_BC_PORT, Tags.FIELD_CAMERA_USER, Tags.FIELD_CAMERA_RTSP_PATH)) {
            assertFalse("$tag masked", node(tag).isPassword())
        }
    }

    @Test
    fun imeNext_walksThroughTextFields_doneOnRtspPath() {
        composeTestRule.assertImeChain(
            listOf(
                Tags.FIELD_CAMERA_HOST,
                Tags.FIELD_CAMERA_BC_PORT,
                Tags.FIELD_CAMERA_USER,
                Tags.FIELD_CAMERA_PASS,
                Tags.FIELD_CAMERA_RTSP_PATH,
            ),
            keyboard,
        )
        assertTrue("focus changes must not change values", changes.isEmpty())
    }

    @Test
    fun checkboxes_toggleTheirOwnValue() {
        node(Tags.CHECK_KEEP_ASPECT_RATIO).assert(hasRole(Role.Checkbox)).assertIsOn().performScrollTo().performClick()
        node(Tags.CHECK_KEEP_ASPECT_RATIO).assertIsOff()
        assertEquals(INITIAL.copy(keepAspectRatio = false), changes.single())

        node(Tags.CHECK_DUCK_WHILE_TALKING).assert(hasRole(Role.Checkbox)).assertIsOn().performScrollTo().performClick()
        node(Tags.CHECK_DUCK_WHILE_TALKING).assertIsOff()
        assertEquals(INITIAL.copy(keepAspectRatio = false, duckWhileTalking = false), changes.last())

        node(Tags.CHECK_KEEP_ASPECT_RATIO).performClick()
        node(Tags.CHECK_KEEP_ASPECT_RATIO).assertIsOn()
        assertEquals(INITIAL.copy(duckWhileTalking = false), state)
    }

    @Test
    fun micGainSlider_snapsToStepsOf100Within100To2000() {
        assertSlider(
            Tags.SLIDER_MIC_GAIN, Tags.LABEL_MIC_GAIN, R.string.label_mic_gain, 100f..2000f,
            inputs = listOf(733f to 700, 1260f to 1300, 5000f to 2000, -50f to 100),
            update = { s, v -> s.copy(micGainPercent = v) },
        )
    }

    @Test
    fun videoStartBufferSlider_snapsToStepsOf50Within50To1000() {
        assertSlider(
            Tags.SLIDER_VIDEO_START_BUFFER, Tags.LABEL_VIDEO_START_BUFFER, R.string.label_video_start_buffer, 50f..1000f,
            inputs = listOf(333f to 350, 990f to 1000, 2000f to 1000, 0f to 50),
            update = { s, v -> s.copy(videoStartBufferMs = v) },
        )
    }

    @Test
    fun videoRebufferSlider_snapsToStepsOf100Within100To2000() {
        assertSlider(
            Tags.SLIDER_VIDEO_REBUFFER, Tags.LABEL_VIDEO_REBUFFER, R.string.label_video_rebuffer, 100f..2000f,
            inputs = listOf(1249f to 1200, 160f to 200, 9999f to 2000, 1f to 100),
            update = { s, v -> s.copy(videoRebufferMs = v) },
        )
    }

    @Test
    fun upButton_navigatesUpWithoutChangingState() {
        node(Tags.UP).performClick()

        assertEquals(1, navigateUpCount)
        assertTrue(changes.isEmpty())
    }

    /**
     * Each input (raw drag position -> expected snapped value) changes only this slider's value
     * ([update]) and its label. All three sliders have 18 intermediate steps (19 positions).
     */
    private fun assertSlider(
        sliderTag: String,
        labelTag: String,
        labelRes: Int,
        range: ClosedFloatingPointRange<Float>,
        inputs: List<Pair<Float, Int>>,
        update: (CameraSettingsUiState, Int) -> CameraSettingsUiState,
    ) {
        for ((input, expected) in inputs) {
            val before = state
            node(sliderTag).setSliderValue(input)
            composeTestRule.waitForIdle()
            assertEquals("slider $sliderTag set to $input", update(before, expected), state)
            node(sliderTag).assertRangeInfoEquals(ProgressBarRangeInfo(expected.toFloat(), range, 18))
            node(labelTag).assertTextEquals(str(labelRes, expected))
        }
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private companion object {
        val INITIAL = CameraSettingsUiState(
            host = "192.168.178.40",
            bcPort = "9000",
            user = "admin",
            password = "doorbell-secret",
            rtspPath = "h264Preview_01_main",
            keepAspectRatio = true,
            micGainPercent = 300,
            duckWhileTalking = true,
            videoStartBufferMs = 150,
            videoRebufferMs = 400,
        )
    }
}
