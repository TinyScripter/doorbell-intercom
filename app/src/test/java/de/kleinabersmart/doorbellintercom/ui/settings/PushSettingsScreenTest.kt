package de.kleinabersmart.doorbellintercom.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
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
 * Interaction tests of the stateless [PushSettingsScreen] (replaces settings_push.xml): field
 * mapping, digits-only MQTT port, masked MQTT password, the keyboard order across the UnifiedPush
 * and MQTT cards (skipping buttons and the switch), switch/checkbox and the register buttons.
 *
 * Every callback appends to [events], so a test also proves that no other callback fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class PushSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val keyboard = FakeKeyboardController()
    private var state by mutableStateOf(INITIAL)
    private val events = mutableListOf<Any>()

    @Before
    fun setUp() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DoorbellTheme {
                    PushSettingsScreen(
                        state = state,
                        onStateChange = {
                            events += it
                            state = it
                        },
                        onRegisterClick = { events += "register" },
                        onUnregisterClick = { events += "unregister" },
                        onNavigateUp = { events += "up" },
                    )
                }
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    @Test
    fun textFields_reportOnlyTheirOwnField() {
        val edits = listOf<Triple<String, String, (PushSettingsUiState) -> PushSettingsUiState>>(
            Triple(Tags.FIELD_REGISTRATION_URL, "https://ha.local/api/webhook/x", { it.copy(registrationUrl = "https://ha.local/api/webhook/x") }),
            Triple(Tags.FIELD_DEVICE_NAME, " Hallway tablet ", { it.copy(deviceName = " Hallway tablet ") }),
            Triple(Tags.FIELD_MQTT_HOST, "mqtt.local", { it.copy(mqttHost = "mqtt.local") }),
            Triple(Tags.FIELD_MQTT_PORT, "8883", { it.copy(mqttPort = "8883") }),
            Triple(Tags.FIELD_MQTT_USER, "bell", { it.copy(mqttUser = "bell") }),
            Triple(Tags.FIELD_MQTT_PASS, " p w ", { it.copy(mqttPassword = " p w ") }),
            Triple(Tags.FIELD_MQTT_TOPIC, "door/ring", { it.copy(mqttTopic = "door/ring") }),
        )
        for ((index, edit) in edits.withIndex()) {
            val (tag, input, expected) = edit
            val before = state
            node(tag).performTextReplacement(input)
            composeTestRule.waitForIdle()
            assertEquals("events after editing $tag", index + 1, events.size)
            assertEquals(expected(before), events.last())
        }
    }

    @Test
    fun deviceNameField_canBeCleared() {
        // Blank is allowed here (the Activity then stores "" and AppConfig regenerates the default name).
        node(Tags.FIELD_DEVICE_NAME).performTextClearance()

        assertEquals(INITIAL.copy(deviceName = ""), events.single())
    }

    @Test
    fun mqttPortField_dropsNonDigits() {
        node(Tags.FIELD_MQTT_PORT).performTextReplacement(":18 83x")

        assertEquals("1883", state.mqttPort)
        assertEquals("1883", node(Tags.FIELD_MQTT_PORT).editableText())
    }

    @Test
    fun onlyMqttPasswordIsMasked() {
        assertTrue(node(Tags.FIELD_MQTT_PASS).isPassword())
        assertEquals(PASSWORD_MASK.toString().repeat(INITIAL.mqttPassword.length), node(Tags.FIELD_MQTT_PASS).editableText())
        for (tag in listOf(
            Tags.FIELD_REGISTRATION_URL, Tags.FIELD_DEVICE_NAME, Tags.FIELD_MQTT_HOST,
            Tags.FIELD_MQTT_PORT, Tags.FIELD_MQTT_USER, Tags.FIELD_MQTT_TOPIC,
        )) {
            assertFalse("$tag masked", node(tag).isPassword())
        }
    }

    @Test
    fun imeNext_walksThroughAllSevenFields_doneOnMqttTopic() {
        composeTestRule.assertImeChain(
            listOf(
                Tags.FIELD_REGISTRATION_URL,
                Tags.FIELD_DEVICE_NAME,
                Tags.FIELD_MQTT_HOST,
                Tags.FIELD_MQTT_PORT,
                Tags.FIELD_MQTT_USER,
                Tags.FIELD_MQTT_PASS,
                Tags.FIELD_MQTT_TOPIC,
            ),
            keyboard,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun mqttFallbackSwitch_toggles() {
        node(Tags.SWITCH_MQTT_FALLBACK).assert(hasRole(Role.Switch)).assertIsOn().performScrollTo().performClick()

        node(Tags.SWITCH_MQTT_FALLBACK).assertIsOff()
        assertEquals(INITIAL.copy(mqttFallbackEnabled = false), events.single())
    }

    @Test
    fun notifyAnsweredElsewhereCheckbox_toggles() {
        node(Tags.CHECK_NOTIFY_ANSWERED_ELSEWHERE).assert(hasRole(Role.Checkbox)).assertIsOff()
            .performScrollTo().performClick()

        node(Tags.CHECK_NOTIFY_ANSWERED_ELSEWHERE).assertIsOn()
        assertEquals(INITIAL.copy(notifyAnsweredElsewhere = true), events.single())
    }

    @Test
    fun registerButtons_reportTheirClick() {
        node(Tags.BTN_REGISTER_DEVICE).performScrollTo().performClick()
        node(Tags.BTN_UNREGISTER_DEVICE).performScrollTo().performClick()

        assertEquals(listOf("register", "unregister"), events)
    }

    @Test
    fun upButton_navigatesUp() {
        node(Tags.UP).performClick()

        assertEquals(listOf("up"), events)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private companion object {
        val INITIAL = PushSettingsUiState(
            registrationUrl = "https://ha.example.com/api/webhook/doorbell_register",
            deviceName = "Pixel 9 (a1b2)",
            mqttFallbackEnabled = true,
            mqttHost = "192.168.178.10",
            mqttPort = "1883",
            mqttUser = "doorbell",
            mqttPassword = "mqtt-secret",
            mqttTopic = "doorbell/ring",
            notifyAnsweredElsewhere = false,
        )
    }
}
