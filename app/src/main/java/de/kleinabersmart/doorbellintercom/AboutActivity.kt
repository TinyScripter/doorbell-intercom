package de.kleinabersmart.doorbellintercom

import androidx.compose.runtime.Composable
import de.kleinabersmart.doorbellintercom.ui.settings.AboutScreen

/**
 * Über / Lizenzen: eigene Lizenz (GPLv3) und Hinweise auf verwendete Fremdkomponenten (reolink_aio, MIT).
 * Die Oberfläche ist Compose (AboutScreen); die Activity liefert nur die Versionsbezeichnung.
 */
class AboutActivity : SettingsBaseActivity() {

    override val titleRes = R.string.section_about

    override fun createContent(): @Composable () -> Unit {
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        return { AboutScreen(versionName = versionName, onNavigateUp = ::finish) }
    }
}
