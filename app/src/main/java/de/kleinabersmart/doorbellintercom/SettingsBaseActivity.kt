package de.kleinabersmart.doorbellintercom

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import de.kleinabersmart.doorbellintercom.databinding.ActivitySettingsBinding
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * Gemeinsame Basis der Einstellungs-Hauptseite und aller Unterseiten: Titel, [config] und
 * Toast-Anzeige. Die Oberfläche kommt aus Compose ([createContent], Gerüst mit Leiste und
 * Scrollbereich in SettingsScaffold); Seiten, die noch nicht umgestellt sind, hängen ihr
 * XML-Layout (settings_*.xml) über [onCreateContent] in activity_settings.xml ein.
 */
abstract class SettingsBaseActivity : AppCompatActivity() {

    protected lateinit var config: AppConfig
    private var toast: Toast? = null
    private val toastHandler = Handler(Looper.getMainLooper())

    @get:StringRes
    protected abstract val titleRes: Int

    /**
     * Legt den Zustand der Seite an und liefert ihren Inhalt. Läuft in onCreate, nachdem [config]
     * gesetzt ist, und damit vor dem ersten Speichern in SettingsSectionActivity: der Zustand muss
     * hier schon vollständig (aus [config]) vorliegen, nicht erst beim ersten Zeichnen.
     * null = die Seite nutzt noch ihr XML-Layout ([onCreateContent]).
     */
    protected open fun createContent(): (@Composable () -> Unit)? = null

    /** XML-Weg bis zur Umstellung aller Seiten: hängt den Inhalt in [container] ein und füllt die Felder. */
    protected open fun onCreateContent(container: ViewGroup) = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fenstertitel weiterhin setzen: TalkBack sagt ihn beim Öffnen der Seite an.
        setTitle(titleRes)
        config = AppConfig(this)

        val content = createContent()
        if (content != null) {
            setContent { DoorbellTheme { content() } }
            return
        }

        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(titleRes)

        binding.topBar.applyTopSystemInset()
        binding.settingsContent.applyBottomSystemAndImeInset()

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
