package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import de.kleinabersmart.doorbellintercom.ui.theme.pixelRoundedFontSize
import kotlin.math.roundToInt

// Maße aus Widget.DoorbellIntercom.SettingsCard (themes.xml).
private val CardCornerRadius = 12.dp
private val CardContentPadding = 16.dp
private val CardBottomMargin = 16.dp

/** Mindesthöhe von Schaltern und Checkboxen (?attr/minTouchTargetSize). */
private val MinTouchTarget = 48.dp

/** Abstand zwischen Text und Schalter (mtrl_switch_text_padding). */
private val SwitchTextPadding = 16.dp

/**
 * Breite des Checkbox-Symbols der Material-CheckBox: das Kästchen sitzt mittig in 32dp, der
 * Text beginnt direkt dahinter. Die Compose-Checkbox ist ohne eigenen Klick nur 24dp breit.
 */
private val CheckboxButtonWidth = 32.dp

/** Abstand zwischen zwei Buttons einer Reihe (je 4dp Außenrand in XML). */
private val ButtonSpacing = 8.dp

/** Rahmen der umrandeten Buttons (Widget.Material3.Button.OutlinedButton). */
private val ButtonBorderWidth = 1.dp

/** Deckkraft des Rahmens deaktivierter Buttons (Material 3: onSurface 12 %). */
private const val DisabledBorderAlpha = 0.12f

/**
 * Der Material-Slider lässt seitlich mehr Platz (trackSidePadding 22dp für die Daumenmitte) als
 * der Compose-Slider (ca. 10dp); der Ausgleich hält Spur und Daumen an derselben Stelle. Die
 * Höhe (48dp) stimmt bereits überein.
 */
private val SliderHorizontalPadding = 11.dp

/**
 * Schrift wie eine TextView ohne eigene Zeilenhöhe: ganze Pixel und Font-Padding, damit Zeilen
 * und Abstände wie in den bisherigen XML-Layouts ausfallen.
 */
@Composable
internal fun textViewStyle(fontSize: TextUnit): TextStyle = TextStyle(
    fontSize = pixelRoundedFontSize(fontSize),
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)

/**
 * Text von CheckBox und MaterialSwitch (textAppearanceBodyMedium): Größe und Laufweite von
 * bodyMedium, aber die natürliche Zeilenhöhe, da CompoundButtons die lineHeight der
 * TextAppearance nicht übernehmen (mehrzeilige Texte stehen enger als bei bodyMedium).
 */
@Composable
private fun compoundButtonTextStyle(): TextStyle = textViewStyle(MaterialTheme.typography.bodyMedium.fontSize)
    .copy(letterSpacing = MaterialTheme.typography.bodyMedium.letterSpacing)

/**
 * Karte für eine Kategorie (vorher Widget.DoorbellIntercom.SettingsCard: Elevated Card mit
 * 12dp Ecken, 16dp Innenabstand und 16dp Abstand nach unten).
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        // Der Außenabstand steht vor der Karte, damit Fläche und Schatten ihn nicht umfassen.
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = CardBottomMargin),
        shape = RoundedCornerShape(CardCornerRadius),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(CardContentPadding), content = content)
    }
}

/**
 * Anklickbare Karte (vorher MaterialCardView mit clickable/focusable): Ripple und Fokus über die
 * ganze Kartenfläche; TalkBack liest den Inhalt als einen Eintrag.
 */
@Composable
fun SettingsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = CardBottomMargin),
        shape = RoundedCornerShape(CardCornerRadius),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(CardContentPadding), content = content)
    }
}

/**
 * Überschrift einer Karte (vorher TextAppearance.DoorbellIntercom.SettingsSection: 18sp fett in
 * der Primärfarbe). Für die Systemcheck-Hinweise mit [color] = colorScheme.error.
 */
@Composable
fun SettingsSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        color = color,
        style = textViewStyle(18.sp).copy(fontWeight = FontWeight.Bold),
        modifier = modifier,
    )
}

/**
 * Kurze Erklärung unter einer Überschrift (vorher ?attr/textAppearanceBodySmall mit der
 * sekundären Textfarbe).
 */
@Composable
fun SettingsSummary(
    text: String,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodySmall
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = style.copy(fontSize = pixelRoundedFontSize(style.fontSize)),
        modifier = modifier,
    )
}

/**
 * Einfacher Text ohne eigene TextAppearance (Regler-Beschriftungen, Statuszeile, Klingelton):
 * die TextView-Vorgabe TextAppearance.AppCompat.Small, also 14sp in der sekundären Textfarbe.
 */
@Composable
fun SettingsText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    minLines: Int = 1,
) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = textViewStyle(14.sp),
        textAlign = textAlign,
        minLines = minLines,
        modifier = modifier,
    )
}

/**
 * Eintrag der Hauptseite (vorher item_settings_entry.xml): anklickbare Karte mit Titel,
 * Kurzbeschreibung und Pfeil nach rechts. Auch für die Systemcheck-Hinweise, dann mit
 * [titleColor] = colorScheme.error. Der Pfeil ist nur Dekoration (für TalkBack ausgeblendet).
 */
@Composable
fun SettingsEntryRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.primary,
) {
    SettingsCard(onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SettingsSectionTitle(text = title, color = titleColor)
                SettingsSummary(text = summary)
            }
            // Das Vektorbild war mit ?attr/colorControlNormal getönt (sekundäre Textfarbe).
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(24.dp),
            )
        }
    }
}

/**
 * Schalter mit Text davor (vorher MaterialSwitch): die ganze Zeile schaltet um, mindestens 48dp
 * hoch, der Text bricht vor dem Schalter um.
 */
@Composable
fun SettingsSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = MinTouchTarget),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = compoundButtonTextStyle(),
            modifier = Modifier
                .weight(1f)
                .padding(end = SwitchTextPadding),
        )
        // Ohne eigenen Klick: Umschalten, Ripple und Semantik liefert die ganze Zeile.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Checkbox mit Text dahinter (vorher CheckBox): die ganze Zeile schaltet um, mindestens 48dp hoch.
 */
@Composable
fun SettingsCheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = MinTouchTarget),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.width(CheckboxButtonWidth)) {
            Checkbox(checked = checked, onCheckedChange = null)
        }
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = compoundButtonTextStyle(),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Regler mit Beschriftung darüber (vorher TextView + Material-Slider ohne Werte-Label): [label]
 * zeigt den aktuellen Wert und wird vom Aufrufer bei jeder Änderung neu gebildet. Der Regler
 * rastet in [step]-Schritten im Bereich [valueRange] ein und meldet ganze Zahlen.
 */
@Composable
fun SettingsSliderRow(
    label: String,
    value: Int,
    valueRange: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sliderModifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsText(text = label)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            // steps zählt nur die Zwischenpunkte, ohne Anfang und Ende.
            steps = (valueRange.last - valueRange.first) / step - 1,
            modifier = sliderModifier
                .fillMaxWidth()
                .padding(horizontal = SliderHorizontalPadding),
        )
    }
}

/**
 * Umrandeter Button (vorher ?attr/materialButtonOutlinedStyle). Wie dort 40dp sichtbare Höhe in
 * einer 48dp hohen Touch-Fläche, daher ergeben gestapelte Reihen dieselben Abstände. Beschriftung
 * in colorPrimary und Rahmen in colorOutline wie beim Material-Button; die neueren
 * Compose-Vorgaben (onSurfaceVariant, outlineVariant) wären blasser.
 */
@Composable
fun SettingsOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
        border = BorderStroke(
            width = ButtonBorderWidth,
            color = if (enabled) colors.outline else colors.onSurface.copy(alpha = DisabledBorderAlpha),
        ),
        modifier = modifier,
    ) {
        val style = MaterialTheme.typography.labelLarge
        Text(text = text, style = style.copy(fontSize = pixelRoundedFontSize(style.fontSize)))
    }
}

/**
 * Reihe gleich breiter Buttons (vorher LinearLayout mit layout_weight 1 und je 4dp Rand);
 * die Buttons übergeben selbst Modifier.weight(1f).
 */
@Composable
fun SettingsButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonSpacing),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun SettingsControlsSample() {
    var keepAspectRatio by remember { mutableStateOf(true) }
    var micGain by remember { mutableIntStateOf(300) }
    var mqttFallback by remember { mutableStateOf(true) }
    DoorbellTheme {
        SettingsScaffold(title = stringResource(R.string.section_camera), onNavigateUp = {}) {
            SettingsCard {
                SettingsSectionTitle(text = stringResource(R.string.section_mqtt))
                SettingsSummary(
                    text = stringResource(R.string.section_mqtt_summary),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SettingsSwitchRow(
                    text = stringResource(R.string.switch_mqtt_fallback),
                    checked = mqttFallback,
                    onCheckedChange = { mqttFallback = it },
                )
                SettingsCheckboxRow(
                    text = stringResource(R.string.check_keep_aspect_ratio),
                    checked = keepAspectRatio,
                    onCheckedChange = { keepAspectRatio = it },
                    modifier = Modifier.padding(top = 12.dp),
                )
                SettingsSliderRow(
                    label = stringResource(R.string.label_mic_gain, micGain),
                    value = micGain,
                    valueRange = 100..2000,
                    step = 100,
                    onValueChange = { micGain = it },
                    modifier = Modifier.padding(top = 12.dp),
                )
                SettingsButtonRow(modifier = Modifier.padding(top = 8.dp)) {
                    SettingsOutlinedButton(
                        text = stringResource(R.string.btn_register_device),
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                    SettingsOutlinedButton(
                        text = stringResource(R.string.btn_unregister_device),
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            SettingsCard {
                SettingsSectionTitle(text = stringResource(R.string.section_ring_log))
                SettingsText(text = stringResource(R.string.ring_log_empty))
                SettingsOutlinedButton(
                    text = stringResource(R.string.btn_copy),
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun SettingsControlsPreview() {
    SettingsControlsSample()
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsControlsDarkPreview() {
    SettingsControlsSample()
}
