package de.kleinabersmart.doorbellintercom.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import de.kleinabersmart.doorbellintercom.R

/**
 * Zustand der Einstellungs-Hauptseite (vorher settings_main.xml). Die Activity schreibt hinein,
 * die Seite zeigt nur an.
 *
 * @property issues Befunde des Systemchecks, bei jedem onResume neu ermittelt; leer = alles in
 *   Ordnung, dann entfallen Überschrift und Hinweise.
 * @property statusMessage Ergebnis von Export/Import/QR (vorher statusText); null = leer. Wird wie
 *   der TextView-Inhalt nicht über eine Neuerstellung der Activity gerettet.
 * @property qrCode Ungleich null: der Dialog mit dem QR-Code ist offen.
 * @property pendingQrImport Ungleich null: eingelesener QR-Inhalt, der Bestätigungsdialog ist offen.
 */
@Immutable
data class SettingsUiState(
    val issues: List<SystemCheckIssueUi> = emptyList(),
    @param:StringRes val statusMessage: Int? = null,
    val qrCode: ImageBitmap? = null,
    val pendingQrImport: String? = null,
)

/** Ein Befund des Systemchecks (SystemCheck.Issue ohne die Aktion); [title] ist eindeutig. */
@Immutable
data class SystemCheckIssueUi(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
)

/**
 * Die Unterseiten der Hauptseite in der angezeigten Reihenfolge; welche Activity dazugehört,
 * entscheidet die SettingsActivity.
 */
enum class SettingsEntry(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val testTag: String,
) {
    Camera(R.string.section_camera, R.string.section_camera_summary, "entry_camera"),
    Ringing(R.string.section_ringing, R.string.section_ringing_summary, "entry_ringing"),
    Push(R.string.section_push_settings, R.string.section_push_settings_summary, "entry_push"),
    Connections(R.string.section_connections, R.string.section_connections_summary, "entry_connections"),
    RingLog(R.string.section_ring_log, R.string.section_ring_log_summary, "entry_ring_log"),
    About(R.string.section_about, R.string.section_about_summary, "entry_about"),
}
