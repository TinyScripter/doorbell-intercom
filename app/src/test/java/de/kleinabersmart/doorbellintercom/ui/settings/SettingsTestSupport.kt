package de.kleinabersmart.doorbellintercom.ui.settings

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** Device of all settings tests (Pixel 9 class phone, light mode), as in SettingsScreenshotTest. */
internal const val SETTINGS_TEST_QUALIFIERS = "en-rUS-w411dp-h923dp-port-notnight-420dpi"

/** Mask character of PasswordVisualTransformation (as PasswordTransformationMethod before). */
internal const val PASSWORD_MASK = '•'

internal val testApp: Application get() = ApplicationProvider.getApplicationContext()

internal fun str(@StringRes id: Int, vararg args: Any): String = testApp.getString(id, *args)

/** Content of a text field as the user sees it (masked for password fields). */
internal fun SemanticsNodeInteraction.editableText(): String =
    fetchSemanticsNode().config[SemanticsProperties.EditableText].text

internal fun SemanticsNodeInteraction.isPassword(): Boolean =
    SemanticsProperties.Password in fetchSemanticsNode().config

/** Text of a (non-editable) text node, all parts joined. */
internal fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

/** Like dragging the slider to [value]: the slider itself snaps and clamps (SetProgress semantics). */
internal fun SemanticsNodeInteraction.setSliderValue(value: Float): SemanticsNodeInteraction =
    performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

/** Focus without a touch (works for fields scrolled out of view, too). */
internal fun SemanticsNodeInteraction.focus(): SemanticsNodeInteraction =
    performSemanticsAction(SemanticsActions.RequestFocus)

/** Records hide() calls of the "Done" key (provided as LocalSoftwareKeyboardController). */
internal class FakeKeyboardController : SoftwareKeyboardController {
    var hideCount = 0
        private set

    override fun show() = Unit

    override fun hide() {
        hideCount++
    }
}

/**
 * The keyboard order of a page, like the EditTexts before: "Next" on field i focuses field i + 1
 * (skipping switches, checkboxes and buttons in between), the last field shows "Done", which
 * only hides the keyboard (TextView.onEditorAction for IME_ACTION_DONE kept the focus as well).
 */
internal fun SemanticsNodeInteractionsProvider.assertImeChain(fieldTags: List<String>, keyboard: FakeKeyboardController) {
    onNodeWithTag(fieldTags.first()).focus()
    for ((from, to) in fieldTags.zipWithNext()) {
        onNodeWithTag(from).assertIsFocused().assert(hasImeAction(ImeAction.Next)).performImeAction()
        onNodeWithTag(to).assertIsFocused()
        onNodeWithTag(from).assertIsNotFocused()
    }
    val last = onNodeWithTag(fieldTags.last()).assert(hasImeAction(ImeAction.Done))
    val hiddenBefore = keyboard.hideCount
    last.performImeAction()
    assertEquals("Done must hide the keyboard once", hiddenBefore + 1, keyboard.hideCount)
    last.assertIsFocused()
    fieldTags.dropLast(1).forEach { onNodeWithTag(it).assertIsNotFocused() }
    assertEquals("Done must not hide the keyboard on the other fields", hiddenBefore + 1, keyboard.hideCount)
}

/**
 * Launches the real settings Activities (create, start, resume) for a test with an empty compose
 * rule and closes all of them again in [closeAll] (otherwise their Compose hierarchies stay
 * registered and block the idle sync of the next test).
 */
internal class SettingsActivityHarness(private val composeTestRule: ComposeTestRule) {

    private val controllers = mutableListOf<ActivityController<out Activity>>()

    fun <T : Activity> launch(type: Class<T>): ActivityController<T> {
        val controller = Robolectric.buildActivity(type).setup()
        controllers += controller
        settle()
        return controller
    }

    /** Idles the main looper and Compose (only with an open page, see SettingsScreenshotTest.settle). */
    fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        if (controllers.isNotEmpty()) composeTestRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Leaves every launched page through the remaining lifecycle steps (pause/stop/destroy). */
    fun closeAll() {
        for (controller in controllers) {
            val activity = controller.get() as ComponentActivity
            if (activity.lifecycle.currentState == Lifecycle.State.RESUMED) controller.pause()
            if (activity.lifecycle.currentState == Lifecycle.State.STARTED) controller.stop()
            if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
        }
        controllers.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
