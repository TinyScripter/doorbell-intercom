package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * Über / Lizenzen (vorher settings_about.xml): eigene Lizenz mit Version, Fremdkomponenten (MIT-Text
 * markierbar) und verwendete Bibliotheken. Web-Adressen sind wie bei autoLink="web" antippbar
 * ([AutoLinkText]). about_libraries_variant kommt je nach Build-Variante (play/free) aus
 * src/<variante>/res.
 *
 * @param versionName versionName der App inklusive Varianten-Zusatz (z. B. 1.0.1-play-debug).
 */
@Composable
fun AboutScreen(
    versionName: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.section_about),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    ) {
        // Eigene Lizenz
        SettingsCard {
            SettingsSectionTitle(text = stringResource(R.string.app_name))
            SettingsSummary(
                text = stringResource(R.string.about_version, versionName),
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag(SettingsTestTags.ABOUT_VERSION),
            )
            AutoLinkText(text = stringResource(R.string.about_license), modifier = Modifier.fillMaxWidth())
        }

        // Fremde Komponenten
        SettingsCard {
            SettingsSectionTitle(
                text = stringResource(R.string.about_third_party_title),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AutoLinkText(text = stringResource(R.string.about_reolink_aio), modifier = Modifier.fillMaxWidth())
            // Lizenztext zum Kopieren markierbar (vorher textIsSelectable).
            SelectionContainer(modifier = Modifier.padding(top = 12.dp)) {
                SettingsSummary(
                    text = stringResource(R.string.about_mit_license_text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AutoLinkText(
                text = stringResource(R.string.about_feather_icons),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }

        // Bibliotheken
        SettingsCard {
            SettingsSectionTitle(
                text = stringResource(R.string.about_libraries_title),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AutoLinkText(text = stringResource(R.string.about_libraries), modifier = Modifier.fillMaxWidth())
            AutoLinkText(
                text = stringResource(R.string.about_libraries_variant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 1400)
@Composable
private fun AboutScreenPreview() {
    DoorbellTheme {
        AboutScreen(versionName = "1.0.1-play-debug", onNavigateUp = {})
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutScreenDarkPreview() {
    DoorbellTheme {
        AboutScreen(versionName = "1.0.1-play-debug", onNavigateUp = {})
    }
}
