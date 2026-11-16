package de.kleinabersmart.doorbellintercom.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Feste Signalfarben der runden Buttons über dem Video, unabhängig von Hell/Dunkel (vorher
// die Shape-Drawables bg_btn_*). Das Material-Farbschema kommt weiter aus colors.xml
// (md_theme_*, siehe DoorbellTheme), die Video-Farben (on_video, video_scrim, …) ebenfalls.

val DoorGradientStart = Color(0xFFFFD45C)
val DoorGradientEnd = Color(0xFFF59E0B)
val HangUpGradientStart = Color(0xFFFF6B5E)
val HangUpGradientEnd = Color(0xFFDC2626)
val AcceptGradientStart = Color(0xFF4ADE80)
val AcceptGradientEnd = Color(0xFF16A34A)

/** Mikro aus: muss zum Freischnitt-Strich in ic_mic_off passen. */
val MicOff = Color(0xFF282A30)
val MicOffBorder = Color(0x33FFFFFF)
val MicOn = Color(0xFFF4F5F7)

/** Ripple auf dunklen bzw. farbigen Buttons (Annehmen, Auflegen, Mikro aus). */
val RippleOnDark = Color(0x40FFFFFF)

/** Ripple auf hellen Buttons (Tür, Mikro an). */
val RippleOnLight = Color(0x33000000)

/**
 * Zurück-Pfeil in der blauen Leiste der Einstellungen, in Hell und Dunkel gleich: die frühere
 * Toolbar lief mit dem Dark-ActionBar-Overlay von Material 3, das den Pfeil in dessen heller
 * Textfarbe (m3_sys_color_dark_on_surface) tönte – nicht in onPrimaryContainer wie den Titel.
 */
val TopBarOnContainerIcon = Color(0xFFE6E0E9)

// Verlauf wie android:angle="315": von links oben nach rechts unten, das ist die
// Standardrichtung von Brush.linearGradient.
val DoorBrush = Brush.linearGradient(listOf(DoorGradientStart, DoorGradientEnd))
val HangUpBrush = Brush.linearGradient(listOf(HangUpGradientStart, HangUpGradientEnd))
val AcceptBrush = Brush.linearGradient(listOf(AcceptGradientStart, AcceptGradientEnd))
