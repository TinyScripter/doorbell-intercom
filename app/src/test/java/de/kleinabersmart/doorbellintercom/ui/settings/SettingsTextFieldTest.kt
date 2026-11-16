package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
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
 * Behavior of the shared [SettingsTextField] that replaced the EditTexts of settings_*.xml:
 * inputType="number" (DigitsKeyListener: non-digits are dropped when typed, set or pasted),
 * inputType="textPassword" (masked, raw value reported), hint only while empty, and the IME
 * action (custom handler for "Next", default "Done" = hide the keyboard, focus stays).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class SettingsTextFieldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val keyboard = FakeKeyboardController()
    private var number by mutableStateOf("")
    private var password by mutableStateOf("")
    private var text by mutableStateOf("")
    private val numberChanges = mutableListOf<String>()
    private var numberImeActions = 0

    @Before
    fun setUp() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DoorbellTheme {
                    Column {
                        SettingsTextField(
                            value = number,
                            onValueChange = {
                                numberChanges += it
                                number = it
                            },
                            hint = NUMBER_HINT,
                            type = SettingsFieldType.Number,
                            onImeAction = { numberImeActions++ },
                            modifier = Modifier.testTag(NUMBER),
                        )
                        SettingsTextField(
                            value = password,
                            onValueChange = { password = it },
                            hint = "Password",
                            type = SettingsFieldType.Password,
                            modifier = Modifier.testTag(PASSWORD),
                        )
                        SettingsTextField(
                            value = text,
                            onValueChange = { text = it },
                            hint = "Text",
                            imeAction = ImeAction.Done,
                            modifier = Modifier.testTag(TEXT),
                        )
                    }
                }
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    @Test
    fun numberField_typedText_keepsOnlyDigits() {
        node(NUMBER).performTextInput("8a0-8 0")

        assertEquals("8080", number)
        assertEquals("8080", node(NUMBER).editableText())
        assertTrue("non-digit reported: $numberChanges", numberChanges.all { it.all(Char::isDigit) })
    }

    @Test
    fun numberField_replacedText_keepsOnlyDigits() {
        number = "9000"
        node(NUMBER).performTextReplacement("port 1883!")

        assertEquals("1883", number)
        assertEquals("1883", node(NUMBER).editableText())
    }

    @Test
    fun numberField_pastedText_keepsOnlyDigits() {
        testApp.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("port", "12ab34"))
        node(NUMBER).focus().performSemanticsAction(SemanticsActions.PasteText)
        composeTestRule.waitForIdle()

        assertEquals("1234", number)
        assertEquals("1234", node(NUMBER).editableText())
    }

    @Test
    fun numberField_onlyNonDigitsTyped_valueUnchanged() {
        number = "9000"
        node(NUMBER).performTextInput("x")

        assertEquals("9000", number)
        assertEquals("9000", node(NUMBER).editableText())
    }

    @Test
    fun hint_shownOnlyWhileEmpty() {
        composeTestRule.onNodeWithText(NUMBER_HINT, useUnmergedTree = true).assertExists()

        node(NUMBER).performTextInput("1")

        composeTestRule.onNodeWithText(NUMBER_HINT, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun passwordField_masksTextButReportsRawValue() {
        node(PASSWORD).performTextInput(" s3cret pw ")

        assertEquals(" s3cret pw ", password)
        assertTrue(node(PASSWORD).isPassword())
        assertEquals(PASSWORD_MASK.toString().repeat(" s3cret pw ".length), node(PASSWORD).editableText())
        // The other types are shown in clear text.
        node(TEXT).performTextInput("visible")
        assertFalse(node(TEXT).isPassword())
        assertEquals("visible", node(TEXT).editableText())
    }

    @Test
    fun customImeAction_replacesDefaultAction() {
        node(NUMBER).focus().performImeAction()

        assertEquals(1, numberImeActions)
        assertEquals(0, keyboard.hideCount)
    }

    @Test
    fun doneWithoutHandler_hidesKeyboardAndKeepsFocus() {
        node(TEXT).focus().performImeAction()

        assertEquals(1, keyboard.hideCount)
        node(TEXT).assertIsFocused()
        assertEquals(0, numberImeActions)
    }

    private companion object {
        const val NUMBER = "number"
        const val PASSWORD = "password"
        const val TEXT = "text"
        const val NUMBER_HINT = "Port"
    }
}
