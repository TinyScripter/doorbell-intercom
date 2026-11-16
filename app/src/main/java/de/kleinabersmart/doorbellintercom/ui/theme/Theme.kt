package de.kleinabersmart.doorbellintercom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import de.kleinabersmart.doorbellintercom.R
import kotlin.math.roundToInt

/**
 * Compose-Gegenstück zu Theme.DoorbellIntercom: Material 3 mit festem Farbschema statt
 * Dynamic Color. Die Farben kommen aus colors.xml (md_theme_*); values-night liefert die
 * dunkle Variante, daher bleiben XML-Einstellungen und Compose-Bildschirme gleichfarbig.
 * Typografie und Formen sind die Material-Standards, wie im XML-Theme.
 */
@Composable
fun DoorbellTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = doorbellColorScheme(isSystemInDarkTheme()), content = content)
}

@Composable
private fun doorbellColorScheme(dark: Boolean): ColorScheme {
    // Die Basis liefert nur die Slots, die das XML-Theme nicht setzt (z. B. scrim).
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val primary = colorResource(R.color.md_theme_primary)
    return base.copy(
        primary = primary,
        onPrimary = colorResource(R.color.md_theme_onPrimary),
        primaryContainer = colorResource(R.color.md_theme_primaryContainer),
        onPrimaryContainer = colorResource(R.color.md_theme_onPrimaryContainer),
        inversePrimary = colorResource(R.color.md_theme_inversePrimary),
        secondary = colorResource(R.color.md_theme_secondary),
        onSecondary = colorResource(R.color.md_theme_onSecondary),
        secondaryContainer = colorResource(R.color.md_theme_secondaryContainer),
        onSecondaryContainer = colorResource(R.color.md_theme_onSecondaryContainer),
        tertiary = colorResource(R.color.md_theme_tertiary),
        onTertiary = colorResource(R.color.md_theme_onTertiary),
        tertiaryContainer = colorResource(R.color.md_theme_tertiaryContainer),
        onTertiaryContainer = colorResource(R.color.md_theme_onTertiaryContainer),
        background = colorResource(R.color.md_theme_background),
        onBackground = colorResource(R.color.md_theme_onBackground),
        surface = colorResource(R.color.md_theme_surface),
        onSurface = colorResource(R.color.md_theme_onSurface),
        surfaceVariant = colorResource(R.color.md_theme_surfaceVariant),
        onSurfaceVariant = colorResource(R.color.md_theme_onSurfaceVariant),
        surfaceTint = primary,
        inverseSurface = colorResource(R.color.md_theme_inverseSurface),
        inverseOnSurface = colorResource(R.color.md_theme_inverseOnSurface),
        error = colorResource(R.color.md_theme_error),
        onError = colorResource(R.color.md_theme_onError),
        errorContainer = colorResource(R.color.md_theme_errorContainer),
        onErrorContainer = colorResource(R.color.md_theme_onErrorContainer),
        outline = colorResource(R.color.md_theme_outline),
        outlineVariant = colorResource(R.color.md_theme_outlineVariant),
        surfaceBright = colorResource(R.color.md_theme_surfaceBright),
        surfaceDim = colorResource(R.color.md_theme_surfaceDim),
        surfaceContainer = colorResource(R.color.md_theme_surfaceContainer),
        surfaceContainerHigh = colorResource(R.color.md_theme_surfaceContainerHigh),
        surfaceContainerHighest = colorResource(R.color.md_theme_surfaceContainerHighest),
        surfaceContainerLow = colorResource(R.color.md_theme_surfaceContainerLow),
        surfaceContainerLowest = colorResource(R.color.md_theme_surfaceContainerLowest),
    )
}

/**
 * Text direkt über dem Video (Button-Beschriftungen, Mikro-Status): weiß mit schwarzem
 * Schatten, damit er auch über hellem Bild lesbar bleibt. Der Schattenradius ist wie
 * android:shadowRadius in Pixeln angegeben. Bewusst ohne Material-Typografie (bodyLarge
 * hätte Zeilenhöhe und Laufweite), so wie die TextViews mit Plattform-Schrift.
 *
 * Mit Font-Padding wie TextView (includeFontPadding ist dort Standard), damit die Zeilen
 * gleich hoch sind und die Buttons nicht tiefer rutschen.
 */
@Composable
fun overlayTextStyle(fontSize: TextUnit): TextStyle = TextStyle(
    color = colorResource(R.color.on_video),
    fontSize = pixelRoundedFontSize(fontSize),
    shadow = Shadow(color = colorResource(R.color.on_video_shadow), blurRadius = 4f),
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)

/**
 * Wie [overlayTextStyle], aber ohne Schatten (Statuszeile oben, Slider-Beschriftung).
 * [includeFontPadding] für Texte, die frühere TextViews ersetzen (gleiche Zeilenhöhe); die
 * Slider-Beschriftung zeichnete der View selbst, dort ohne.
 */
@Composable
fun onVideoTextStyle(fontSize: TextUnit, includeFontPadding: Boolean = false): TextStyle = TextStyle(
    color = colorResource(R.color.on_video),
    fontSize = pixelRoundedFontSize(fontSize),
    platformStyle = PlatformTextStyle(includeFontPadding = includeFontPadding),
)

/**
 * TextView rundet die Schriftgröße aus XML auf ganze Pixel (getDimensionPixelSize), Compose
 * rechnet mit Bruchteilen – ohne Runden wären Zeilen und Titel ein bis zwei Pixel kleiner.
 */
@Composable
fun pixelRoundedFontSize(fontSize: TextUnit): TextUnit =
    with(LocalDensity.current) { fontSize.toPx().roundToInt().toSp() }
