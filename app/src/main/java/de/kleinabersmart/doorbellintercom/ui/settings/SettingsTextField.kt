package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/** Art des Eingabefelds, entspricht den bisherigen android:inputType-Werten. */
enum class SettingsFieldType {
    /** inputType="text": ohne Autokorrektur und Großschreibung. */
    Text,

    /** inputType="textUri": Adressen und Hostnamen. */
    Uri,

    /** inputType="number": nur Ziffern, auch beim Einfügen. */
    Number,

    /** inputType="textPassword": verdeckt, ohne Anzeige-Umschalter. */
    Password,
}

// Geometrie des AppCompat-EditText-Hintergrunds (abc_edit_text_material): 4dp seitlich, oben 10dp,
// die Linie sitzt 7dp über der Unterkante; dazwischen liegt der Rand der 9-Patch-Grafik.
private val FieldHorizontalInset = 4.dp
private val FieldTopPadding = 10.dp
private val FieldBottomPadding = 11.dp
private val UnderlineBottomInset = 7.dp
private val UnderlineThickness = 1.dp
private val UnderlineThicknessFocused = 2.dp

/** Deckkraft des Hinweistexts (m3_hint_foreground: material_emphasis_disabled). */
private const val HintAlpha = 0.38f

/**
 * Eingabefeld im Stil der bisherigen EditTexts: nur Hinweistext (kein schwebendes Label, der
 * Hinweis verschwindet bei Inhalt), Unterstrich in der sekundären Textfarbe bzw. bei Fokus in
 * colorControlActivated (secondary), 18sp Text, einzeilig.
 *
 * [type] legt Tastatur, Ziffernfilter und Verdeckung fest. [imeAction] ersetzt das automatische
 * "Weiter"/"Fertig" der EditTexts: Mit [onImeAction] springt der Aufrufer gezielt ins nächste
 * Feld (siehe [rememberFocusChain]), denn das Standard-"Weiter" landete in Compose auch auf
 * Schaltern oder Buttons. Ohne [onImeAction] gilt das Standardverhalten (bei "Fertig" Tastatur
 * schließen, wie beim EditText).
 */
@Composable
fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    type: SettingsFieldType = SettingsFieldType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val lineColor = if (focused) colors.secondary else colors.onSurfaceVariant
    val lineThickness = if (focused) UnderlineThicknessFocused else UnderlineThickness
    // TextAppearance.AppCompat.Medium (18sp) in der primären Textfarbe.
    val textStyle = textViewStyle(18.sp).copy(color = colors.onSurface)

    BasicTextField(
        value = value,
        onValueChange = { newValue ->
            // Wie der DigitsKeyListener: andere Zeichen werden verworfen, auch beim Einfügen.
            onValueChange(if (type == SettingsFieldType.Number) newValue.filter(Char::isDigit) else newValue)
        },
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val inset = FieldHorizontalInset.toPx()
                val thickness = lineThickness.toPx()
                drawRect(
                    color = lineColor,
                    topLeft = Offset(inset, size.height - UnderlineBottomInset.toPx() - thickness),
                    size = Size(size.width - 2 * inset, thickness),
                )
            },
        textStyle = textStyle,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = when (type) {
                SettingsFieldType.Text -> KeyboardType.Text
                SettingsFieldType.Uri -> KeyboardType.Uri
                SettingsFieldType.Number -> KeyboardType.Number
                SettingsFieldType.Password -> KeyboardType.Password
            },
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions { onImeAction?.invoke() ?: defaultKeyboardAction(imeAction) },
        visualTransformation = if (type == SettingsFieldType.Password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        interactionSource = interactionSource,
        cursorBrush = SolidColor(colors.secondary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.padding(
                    start = FieldHorizontalInset,
                    end = FieldHorizontalInset,
                    top = FieldTopPadding,
                    bottom = FieldBottomPadding,
                ),
            ) {
                if (value.isEmpty()) {
                    // Wie der Hinweis eines einzeiligen EditTexts: eine Zeile ohne Umbruch, ein zu
                    // langer Hinweis wird am rechten Rand abgeschnitten (nicht am Wortende).
                    Text(
                        text = hint,
                        style = textStyle.copy(color = colors.onBackground.copy(alpha = HintAlpha)),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * Feste Reihenfolge der Textfelder einer Seite für "Weiter" auf der Tastatur: Feld i bekommt
 * `Modifier.focusRequester(chain[i])` und springt mit `chain[i + 1].requestFocus()` weiter.
 */
@Composable
fun rememberFocusChain(size: Int): List<FocusRequester> = remember(size) { List(size) { FocusRequester() } }

@Composable
private fun SettingsTextFieldSample() {
    var host by remember { mutableStateOf("192.168.178.40") }
    var port by remember { mutableStateOf("9000") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("secret-password") }
    DoorbellTheme {
        SettingsScaffold(title = stringResource(R.string.section_camera), onNavigateUp = {}) {
            SettingsCard {
                SettingsTextField(
                    value = host,
                    onValueChange = { host = it },
                    hint = stringResource(R.string.hint_camera_host),
                    type = SettingsFieldType.Uri,
                )
                SettingsTextField(
                    value = port,
                    onValueChange = { port = it },
                    hint = stringResource(R.string.hint_camera_bc_port),
                    type = SettingsFieldType.Number,
                )
                SettingsTextField(
                    value = user,
                    onValueChange = { user = it },
                    hint = stringResource(R.string.hint_camera_user),
                )
                SettingsTextField(
                    value = password,
                    onValueChange = { password = it },
                    hint = stringResource(R.string.hint_camera_pass),
                    type = SettingsFieldType.Password,
                    imeAction = ImeAction.Done,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun SettingsTextFieldPreview() {
    SettingsTextFieldSample()
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsTextFieldDarkPreview() {
    SettingsTextFieldSample()
}
