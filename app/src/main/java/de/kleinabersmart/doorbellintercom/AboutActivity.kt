package de.kleinabersmart.doorbellintercom

import android.view.ViewGroup
import de.kleinabersmart.doorbellintercom.databinding.SettingsAboutBinding

/** Über / Lizenzen: eigene Lizenz (GPLv3) und Hinweise auf verwendete Fremdkomponenten (reolink_aio, MIT). */
class AboutActivity : SettingsBaseActivity() {

    override val titleRes = R.string.section_about

    override fun onCreateContent(container: ViewGroup) {
        val binding = SettingsAboutBinding.inflate(layoutInflater, container)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        binding.textVersion.text = getString(R.string.about_version, versionName)
    }
}
