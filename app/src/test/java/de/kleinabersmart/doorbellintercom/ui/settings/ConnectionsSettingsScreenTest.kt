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
 * Interaction tests of the stateless [ConnectionsSettingsScreen] (replaces settings_connections.xml).
 * The auto-VPN switch reports through its own callback (the Activity asks for the WireGuard
 * permission afterwards, as the former click listener did), not through onStateChange.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class ConnectionsSettingsScreenTest {

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
                    ConnectionsSettingsScreen(
                        state = state,
                        onStateChange = {
                            events += it
                            state = it
                        },
                        onWireguardAutoVpnChange = { checked ->
                            events += "autoVpn=$checked"
                            // As ConnectionsSettingsActivity.
                            state = state.copy(wireguardAutoVpn = checked)
                        },
                        onNavigateUp = { events += "up" },
                    )
                }
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    @Test
    fun textFields_reportOnlyTheirOwnField() {
        node(Tags.FIELD_DOOR_OPEN_URL).performTextReplacement(" https://ha.local/api/webhook/open ")
        assertEquals(INITIAL.copy(doorOpenUrl = " https://ha.local/api/webhook/open "), events.single())

        node(Tags.FIELD_WIREGUARD_TUNNEL).performTextReplacement("office")
        assertEquals(
            INITIAL.copy(doorOpenUrl = " https://ha.local/api/webhook/open ", wireguardTunnel = "office"),
            events.last(),
        )
        assertEquals(2, events.size)
        assertFalse(node(Tags.FIELD_DOOR_OPEN_URL).isPassword())
        assertFalse(node(Tags.FIELD_WIREGUARD_TUNNEL).isPassword())
    }

    @Test
    fun imeNext_skipsAutoVpnSwitch_doneOnTunnel() {
        composeTestRule.assertImeChain(listOf(Tags.FIELD_DOOR_OPEN_URL, Tags.FIELD_WIREGUARD_TUNNEL), keyboard)
        assertTrue(events.isEmpty())
    }

    @Test
    fun autoVpnSwitch_reportsThroughItsOwnCallback() {
        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).assert(hasRole(Role.Switch)).assertIsOff().performClick()
        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).assertIsOn()

        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).performClick()
        node(Tags.SWITCH_WIREGUARD_AUTO_VPN).assertIsOff()

        assertEquals(listOf("autoVpn=true", "autoVpn=false"), events)
    }

    @Test
    fun silentWhenUnreachableSwitch_toggles() {
        node(Tags.SWITCH_SILENT_WHEN_UNREACHABLE).assert(hasRole(Role.Switch)).assertIsOn().performClick()

        node(Tags.SWITCH_SILENT_WHEN_UNREACHABLE).assertIsOff()
        assertEquals(INITIAL.copy(silentWhenUnreachable = false), events.single())
    }

    @Test
    fun upButton_navigatesUp() {
        node(Tags.UP).performClick()

        assertEquals(listOf("up"), events)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private companion object {
        val INITIAL = ConnectionsSettingsUiState(
            doorOpenUrl = "https://ha.example.com/api/webhook/door_open",
            wireguardAutoVpn = false,
            wireguardTunnel = "home",
            silentWhenUnreachable = true,
        )
    }
}
