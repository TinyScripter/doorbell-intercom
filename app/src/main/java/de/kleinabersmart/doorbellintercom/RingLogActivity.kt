package de.kleinabersmart.doorbellintercom

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.ViewGroup
import de.kleinabersmart.doorbellintercom.databinding.SettingsRingLogBinding

/**
 * Klingel-Protokoll (AppConfig.ringLog): die letzten Klingel-Ereignisse mit Weg und Ergebnis,
 * für die Fehlersuche bei "hat nicht geklingelt". Kopieren, um es z. B. einem Fehlerbericht
 * anzuhängen.
 */
class RingLogActivity : SettingsBaseActivity() {

    private lateinit var binding: SettingsRingLogBinding

    override val titleRes = R.string.section_ring_log

    override fun onCreateContent(container: ViewGroup) {
        binding = SettingsRingLogBinding.inflate(layoutInflater, container)
        binding.btnCopyRingLog.setOnClickListener { copyRingLog() }
    }

    override fun onResume() {
        super.onResume()
        // Auch nach einem Klingeln, während die Seite im Hintergrund lag.
        val entries = config.ringLog
        binding.textRingLog.text =
            if (entries.isEmpty()) getString(R.string.ring_log_empty) else entries.joinToString("\n")
        binding.btnCopyRingLog.isEnabled = entries.isNotEmpty()
    }

    private fun copyRingLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.section_ring_log), config.ringLog.joinToString("\n")))
        // Ab Android 13 bestätigt das System das Kopieren selbst.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showToast(getString(R.string.toast_ring_log_copied))
        }
    }
}
