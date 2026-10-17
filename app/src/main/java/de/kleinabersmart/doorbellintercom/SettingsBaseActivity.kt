package de.kleinabersmart.doorbellintercom

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import de.kleinabersmart.doorbellintercom.databinding.ActivitySettingsBinding

/**
 * Gemeinsames Gerüst der Einstellungs-Hauptseite und aller Unterseiten: Toolbar mit
 * Zurück-Pfeil, scrollbarer Inhaltsbereich und Toast-Anzeige. Der eigentliche Inhalt
 * kommt aus einem eigenen Layout (settings_*.xml), das die Unterklasse einhängt.
 */
abstract class SettingsBaseActivity : AppCompatActivity() {

    protected lateinit var config: AppConfig
    private var toast: Toast? = null
    private val toastHandler = Handler(Looper.getMainLooper())

    @get:StringRes
    protected abstract val titleRes: Int

    /** Hängt den Inhalt in [container] ein und füllt die Felder. */
    protected abstract fun onCreateContent(container: ViewGroup)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(titleRes)

        binding.topBar.applyTopSystemInset()
        binding.settingsContent.applyBottomSystemAndImeInset()

        config = AppConfig(this)
        onCreateContent(binding.settingsContent)
    }

    /** Der Zurück-Pfeil beendet die Seite wie die Zurück-Geste (und liefert so auch das Ergebnis). */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Kurze Einblendung, die nach 3 s wieder verschwindet. Toast.LENGTH_LONG dauert ca. 3,5 s und
     * lässt sich nicht einstellen, daher wird sie vorzeitig abgebrochen. Eine neue Meldung ersetzt die alte.
     */
    protected fun showToast(message: String) {
        toast?.cancel()
        toastHandler.removeCallbacksAndMessages(null)
        val newToast = Toast.makeText(this, message, Toast.LENGTH_LONG)
        toast = newToast
        newToast.show()
        toastHandler.postDelayed({ newToast.cancel() }, TOAST_DURATION_MS)
    }

    private companion object {
        const val TOAST_DURATION_MS = 3000L
    }
}
