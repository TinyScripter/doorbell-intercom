package de.kleinabersmart.doorbellintercom.ui.main

import androidx.compose.runtime.Immutable
import de.kleinabersmart.doorbellintercom.ui.components.SlideConfirmRequest

/**
 * Anzeige des Startbildschirms; die Activity füllt sie aus den Rückmeldungen von
 * IntercomControls/LiveViewController und SystemCheck.
 *
 * Der Hinweis auf eine unvollständige Einrichtung erscheint bei jedem Öffnen erneut:
 * [systemCheckHintRequest] zählt die Anfragen (onResume), [showSystemCheckHint] sagt, ob es
 * überhaupt etwas zu melden gibt (sonst wird ein sichtbarer Hinweis geschlossen).
 */
@Immutable
data class MainUiState(
    val statusText: String = "",
    val talkStatusText: String = "",
    val talking: Boolean = false,
    val doorConfirm: SlideConfirmRequest? = null,
    val showSystemCheckHint: Boolean = false,
    val systemCheckHintRequest: Int = 0,
)
