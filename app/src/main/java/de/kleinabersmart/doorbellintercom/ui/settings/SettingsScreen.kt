package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * Hauptseite der Einstellungen (vorher settings_main.xml in activity_settings.xml): oben die
 * Befunde des Systemchecks (nur wenn etwas fehlt), dann die Einträge der Unterseiten, die Karte
 * "Sicherung" und darunter die Statuszeile. Zustandslos; Starten der Unterseiten, Dateiauswahl,
 * QR-Scanner und Import übernimmt die SettingsActivity über die Callbacks.
 *
 * Die beiden Dialoge (QR-Code anzeigen, QR-Import bestätigen) hängen nur am Zustand: [onQrDialogDismiss]
 * und [onQrImportDismiss] schließen sie (auch Zurück oder Tippen daneben), [onQrImportConfirm]
 * übernimmt den eingelesenen Inhalt.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onNavigateUp: () -> Unit,
    onEntryClick: (SettingsEntry) -> Unit,
    onIssueClick: (SystemCheckIssueUi) -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onShowQrClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onQrDialogDismiss: () -> Unit,
    onQrImportConfirm: () -> Unit,
    onQrImportDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.toolbar_settings_title),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) {
        if (state.issues.isNotEmpty()) {
            SettingsSectionTitle(
                text = stringResource(R.string.check_title),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag(SettingsTestTags.CHECK_TITLE),
            )
            state.issues.forEachIndexed { index, issue ->
                key(issue.title) {
                    SettingsEntryRow(
                        title = stringResource(issue.title),
                        summary = stringResource(issue.summary),
                        onClick = { onIssueClick(issue) },
                        titleColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(SettingsTestTags.checkIssue(index)),
                    )
                }
            }
        }
        for (entry in SettingsEntry.entries) {
            SettingsEntryRow(
                title = stringResource(entry.title),
                summary = stringResource(entry.summary),
                onClick = { onEntryClick(entry) },
                modifier = Modifier.testTag(entry.testTag),
            )
        }
        BackupCard(
            onExportClick = onExportClick,
            onImportClick = onImportClick,
            onShowQrClick = onShowQrClick,
            onScanQrClick = onScanQrClick,
        )
        // Wie die leere TextView belegt die Zeile auch ohne Meldung ihre Höhe.
        SettingsText(
            text = state.statusMessage?.let { stringResource(it) }.orEmpty(),
            textAlign = TextAlign.Center,
            minLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag(SettingsTestTags.STATUS_TEXT),
        )
    }

    state.qrCode?.let { QrCodeDialog(bitmap = it, onDismiss = onQrDialogDismiss) }
    if (state.pendingQrImport != null) {
        QrImportConfirmDialog(onConfirm = onQrImportConfirm, onDismiss = onQrImportDismiss)
    }
}

/** Karte "Sicherung": Export/Import als Datei und Übertragung per QR-Code, je zwei Buttons pro Reihe. */
@Composable
private fun BackupCard(
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onShowQrClick: () -> Unit,
    onScanQrClick: () -> Unit,
) {
    SettingsCard {
        SettingsSectionTitle(text = stringResource(R.string.section_backup))
        SettingsSummary(
            text = stringResource(R.string.section_backup_summary),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SettingsButtonRow {
            SettingsOutlinedButton(
                text = stringResource(R.string.btn_export_settings),
                onClick = onExportClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTestTags.BTN_EXPORT),
            )
            SettingsOutlinedButton(
                text = stringResource(R.string.btn_import_settings),
                onClick = onImportClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTestTags.BTN_IMPORT),
            )
        }
        SettingsButtonRow {
            SettingsOutlinedButton(
                text = stringResource(R.string.btn_show_qr),
                onClick = onShowQrClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTestTags.BTN_SHOW_QR),
            )
            SettingsOutlinedButton(
                text = stringResource(R.string.btn_scan_qr),
                onClick = onScanQrClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTestTags.BTN_SCAN_QR),
            )
        }
    }
}

/**
 * QR-Code zum Übertragen der Einstellungen (vorher MaterialAlertDialog mit ImageView): Titel,
 * Warnhinweis, darunter der Code über die volle Breite mit 16dp Rand und "Schließen".
 */
@Composable
private fun QrCodeDialog(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.title_qr_dialog)) },
        text = {
            Column {
                Text(text = stringResource(R.string.message_qr_dialog))
                QrCodeImage(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.title_qr_dialog),
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .testTag(SettingsTestTags.QR_IMAGE),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SettingsTestTags.BTN_QR_CLOSE)) {
                Text(text = stringResource(R.string.btn_close))
            }
        },
        modifier = Modifier.testTag(SettingsTestTags.QR_DIALOG),
    )
}

/** Rückfrage vor dem Übernehmen eines eingelesenen QR-Codes; überschreibt alle Einstellungen. */
@Composable
private fun QrImportConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.title_qr_import_confirm)) },
        text = { Text(text = stringResource(R.string.message_qr_import_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(SettingsTestTags.BTN_QR_IMPORT_CONFIRM)) {
                Text(text = stringResource(R.string.btn_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SettingsTestTags.BTN_QR_IMPORT_CANCEL)) {
                Text(text = stringResource(R.string.btn_cancel))
            }
        },
        modifier = Modifier.testTag(SettingsTestTags.QR_IMPORT_DIALOG),
    )
}

/** Alle drei Befunde, wie auf einem frisch installierten Gerät ohne Berechtigungen. */
private val PreviewIssues = listOf(
    SystemCheckIssueUi(R.string.check_notifications_off, R.string.check_notifications_off_summary),
    SystemCheckIssueUi(R.string.check_full_screen_off, R.string.check_full_screen_off_summary),
    SystemCheckIssueUi(R.string.check_battery_optimized, R.string.check_battery_optimized_summary),
)

@Composable
private fun SettingsScreenPreviewHost(state: SettingsUiState) {
    DoorbellTheme {
        SettingsScreen(
            state = state,
            onNavigateUp = {},
            onEntryClick = {},
            onIssueClick = {},
            onExportClick = {},
            onImportClick = {},
            onShowQrClick = {},
            onScanQrClick = {},
            onQrDialogDismiss = {},
            onQrImportConfirm = {},
            onQrImportDismiss = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun SettingsScreenPreview() {
    SettingsScreenPreviewHost(SettingsUiState(statusMessage = R.string.status_settings_exported))
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun SettingsScreenSystemCheckPreview() {
    SettingsScreenPreviewHost(SettingsUiState(issues = PreviewIssues))
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenSystemCheckDarkPreview() {
    SettingsScreenPreviewHost(SettingsUiState(issues = PreviewIssues))
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun SettingsScreenQrDialogPreview() {
    val bitmap = remember { createQrBitmap("""{"v":1,"cameraHost":"192.168.178.40","cameraBcPort":9000}""").asImageBitmap() }
    SettingsScreenPreviewHost(SettingsUiState(qrCode = bitmap))
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun SettingsScreenQrImportPreview() {
    SettingsScreenPreviewHost(SettingsUiState(pendingQrImport = """{"v":1}"""))
}
