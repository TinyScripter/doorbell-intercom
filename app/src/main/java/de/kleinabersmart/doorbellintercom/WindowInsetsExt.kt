package de.kleinabersmart.doorbellintercom

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Ab targetSdk 35 zeichnet Android auf API-35-Geräten Inhalte standardmäßig
 * edge-to-edge, also auch unter Statusleiste und Kamera-Aussparung (Notch/
 * Punch-Hole) – ohne diese manuelle Insets-Behandlung würden dort platzierte
 * Texte/Buttons abgeschnitten.
 */
fun View.applyTopSystemInset() {
    val basePaddingTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(view.paddingLeft, basePaddingTop + bars.top, view.paddingRight, view.paddingBottom)
        insets
    }
}

fun View.applyBottomSystemInset() {
    val basePaddingBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePaddingBottom + bars.bottom)
        insets
    }
}

/**
 * Wie applyBottomSystemInset, berücksichtigt aber zusätzlich die Tastatur
 * (IME): sonst überdeckt eine eingeblendete Tastatur Eingabefelder am Ende
 * eines scrollbaren Formulars, da windowSoftInputMode="adjustResize" allein
 * im Edge-to-Edge-Modus (ab targetSdk 35) nicht mehr zuverlässig greift.
 * Navigationsleiste und Tastatur überlappen sich am unteren Rand, daher
 * das Maximum statt der Summe beider Insets.
 */
fun View.applyBottomSystemAndImeInset() {
    val basePaddingBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = maxOf(bars.bottom, ime.bottom)
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePaddingBottom + bottomInset)
        insets
    }
}
