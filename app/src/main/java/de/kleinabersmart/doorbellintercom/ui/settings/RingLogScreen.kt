package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/** Klingel-Protokoll, neuester Eintrag zuerst (AppConfig.ringLog, höchstens 20). */
@Immutable
data class RingLogUiState(
    val entries: List<String> = emptyList(),
)

/** Zusätzlicher Zeilenabstand des Protokolls (vorher android:lineSpacingExtra). */
private val RingLogLineSpacingExtra = 4.dp

/**
 * Klingel-Protokoll (vorher settings_ring_log.xml): Hinweis, die Einträge als ein markierbarer
 * Block in Festbreitenschrift (eine Zeile pro Klingeln, Duplikate möglich, daher keine Liste mit
 * Schlüsseln) und "Kopieren", das nur mit Einträgen aktiv ist. Ohne Einträge steht dort
 * ring_log_empty. Kopieren übernimmt die Activity ([onCopyClick]).
 */
@Composable
fun RingLogScreen(
    state: RingLogUiState,
    onCopyClick: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.section_ring_log),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) {
        SettingsCard {
            SettingsSummary(
                text = stringResource(R.string.ring_log_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )
            // Ein Eintrag pro Zeile: Zeitpunkt, Weg, Ergebnis (siehe CallController).
            SelectionContainer {
                Text(
                    text = if (state.entries.isEmpty()) {
                        stringResource(R.string.ring_log_empty)
                    } else {
                        state.entries.joinToString("\n")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = ringLogTextStyle(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.RING_LOG_TEXT),
                )
            }
            SettingsOutlinedButton(
                text = stringResource(R.string.btn_copy),
                onClick = onCopyClick,
                enabled = state.entries.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
                    .testTag(SettingsTestTags.BTN_COPY_RING_LOG),
            )
        }
    }
}

/**
 * Festbreitenschrift 13sp wie die TextView mit lineSpacingExtra 4dp: der Zeilenabstand ist die
 * natürliche Zeilenhöhe der Schrift (Ascent bis Descent, wie StaticLayout sie nimmt) plus 4dp,
 * der zusätzliche Raum liegt unter jeder Zeile außer der letzten.
 */
@Composable
private fun ringLogTextStyle(): TextStyle {
    val base = textViewStyle(13.sp)
    val density = LocalDensity.current
    val lineHeight = remember(base.fontSize, density) {
        with(density) {
            val metrics = Paint().apply {
                typeface = Typeface.MONOSPACE
                textSize = base.fontSize.toPx()
            }.fontMetricsInt
            (metrics.descent - metrics.ascent + RingLogLineSpacingExtra.roundToPx()).toSp()
        }
    }
    return base.copy(
        fontFamily = FontFamily.Monospace,
        lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Top,
            trim = LineHeightStyle.Trim.LastLineBottom,
        ),
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun RingLogScreenPreview() {
    DoorbellTheme {
        RingLogScreen(
            state = RingLogUiState(
                listOf(
                    "16.11.2026 18:42:07  Push  Anruf angezeigt",
                    "16.11.2026 18:42:15  Bildschirm  angenommen",
                    "16.11.2026 12:03:51  MQTT  nur leise Benachrichtigung (Kamera nicht erreichbar)",
                    "15.11.2026 09:30:12  Push  verpasst",
                ),
            ),
            onCopyClick = {},
            onNavigateUp = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun RingLogScreenEmptyPreview() {
    DoorbellTheme {
        RingLogScreen(state = RingLogUiState(), onCopyClick = {}, onNavigateUp = {})
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RingLogScreenDarkPreview() {
    DoorbellTheme {
        RingLogScreen(
            state = RingLogUiState(listOf("16.11.2026 18:42:07  Push  Anruf angezeigt")),
            onCopyClick = {},
            onNavigateUp = {},
        )
    }
}
