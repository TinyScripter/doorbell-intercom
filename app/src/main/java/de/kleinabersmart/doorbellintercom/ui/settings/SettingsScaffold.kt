package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import de.kleinabersmart.doorbellintercom.ui.theme.TopBarOnContainerIcon
import de.kleinabersmart.doorbellintercom.ui.theme.pixelRoundedFontSize

/** Innenabstand des Inhaltsbereichs (vorher android:padding von settingsContent). */
private val ContentPadding = 16.dp

/** Zusätzlicher Abstand des Titels zum Zurück-Pfeil (MaterialToolbar: 56dp Button + 4dp). */
private val TitleStartOffset = 4.dp

/**
 * Gemeinsames Gerüst der Einstellungs-Hauptseite und aller Unterseiten (vorher
 * activity_settings.xml): blaue Leiste mit Zurück-Pfeil und Titel, darunter ein scrollbarer
 * Inhaltsbereich mit 16dp Rand, in den die Seite ihre Karten legt.
 *
 * Insets wie zuvor in WindowInsetsExt.kt: die Leiste reicht unter Statusleiste und
 * Kamera-Aussparung (Inset in der farbigen Fläche, nicht in der festen Toolbar-Höhe). Unten
 * gilt max(Navigationsleiste, Tastatur): imePadding() außerhalb des Scrollbereichs verkleinert
 * ihn bei offener Tastatur, sodass das fokussierte Feld sichtbar bleibt; die Navigationsleiste
 * innen wird dabei um das schon verbrauchte Tastatur-Inset gekürzt. Seitliche Insets gab es
 * vorher nicht (Hochformat). Ohne edge-to-edge (unter API 35) kommen keine Insets an, dann
 * verkleinert adjustResize das Fenster wie bisher.
 *
 * Zurück-Pfeil und Zurück-Geste enden beide in [onNavigateUp] bzw. finish() der Activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = {
                // Wie der Toolbar-Titel (TextView): Font-Padding statt fester Zeilenhöhe und auf
                // ganze Pixel gerundete Schrift, sonst sitzt der Titel einige Pixel höher. Hinter
                // dem 56dp breiten Navigations-Button der Toolbar begann er 4dp weiter rechts.
                Text(
                    text = title,
                    style = LocalTextStyle.current.copy(
                        fontSize = pixelRoundedFontSize(LocalTextStyle.current.fontSize),
                        lineHeight = TextUnit.Unspecified,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                    modifier = Modifier.padding(start = TitleStartOffset),
                )
            },
            navigationIcon = {
                // Eigener Pfeil in der Geometrie des früheren AppCompat-Pfeils (gespiegelt bei
                // RTL); das Projekt hat keine Material-Icons-Abhängigkeit.
                IconButton(onClick = onNavigateUp, modifier = Modifier.testTag(SettingsTestTags.UP)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.nav_up),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                navigationIconContentColor = TopBarOnContainerIcon,
            ),
            windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top),
            modifier = Modifier.testTag(SettingsTestTags.TOOLBAR),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(SettingsTestTags.CONTENT)
                .imePadding()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                .padding(ContentPadding),
            content = content,
        )
    }
}

@Composable
private fun SettingsScaffoldSample() {
    DoorbellTheme {
        SettingsScaffold(title = stringResource(R.string.toolbar_settings_title), onNavigateUp = {}) {
            SettingsSectionTitle(
                text = stringResource(R.string.check_title),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsEntryRow(
                title = stringResource(R.string.check_notifications_off),
                summary = stringResource(R.string.check_notifications_off_summary),
                onClick = {},
                titleColor = MaterialTheme.colorScheme.error,
            )
            SettingsEntryRow(
                title = stringResource(R.string.section_camera),
                summary = stringResource(R.string.section_camera_summary),
                onClick = {},
            )
            SettingsEntryRow(
                title = stringResource(R.string.section_ringing),
                summary = stringResource(R.string.section_ringing_summary),
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun SettingsScaffoldPreview() {
    SettingsScaffoldSample()
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScaffoldDarkPreview() {
    SettingsScaffoldSample()
}
