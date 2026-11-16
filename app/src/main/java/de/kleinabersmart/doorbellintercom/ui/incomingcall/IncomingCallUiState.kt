package de.kleinabersmart.doorbellintercom.ui.incomingcall

import androidx.compose.runtime.Immutable
import de.kleinabersmart.doorbellintercom.ui.components.SlideConfirmRequest

/** Vor dem Annehmen: Ablehnen/Annehmen/Öffnen & Ende; danach: Sprechen/Tür öffnen/Auflegen. */
enum class IncomingCallPhase { Ringing, Active }

/** Was nach dem Schieben passiert; der Slider selbst kennt nur Label und Aktionstext. */
enum class DoorAction { DoorOpen, DoorOpenAndHangUp }

/**
 * Anzeige des Klingelbildschirms. Die Activity leitet sie aus CallController.state und den
 * Rückmeldungen von IntercomControls/LiveViewController ab.
 *
 * [doorAction] gehört zur zuletzt eingeblendeten [doorConfirm] und bleibt beim Ausblenden
 * stehen: der Slider blendet beim Bestätigen erst aus und meldet dann die Bestätigung.
 */
@Immutable
data class IncomingCallUiState(
    val phase: IncomingCallPhase = IncomingCallPhase.Ringing,
    val statusText: String = "",
    val talkStatusText: String = "",
    val talking: Boolean = false,
    val doorConfirm: SlideConfirmRequest? = null,
    val doorAction: DoorAction = DoorAction.DoorOpen,
)
