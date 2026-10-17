package de.kleinabersmart.doorbellintercom

import android.os.Bundle

/**
 * Unterseite der Einstellungen ohne Speichern-Button: Änderungen werden beim Verlassen der
 * Seite übernommen (siehe [persist]). Hat sich dabei etwas geändert, liefert die Seite
 * RESULT_OK zurück, und die Hauptseite zeigt "gespeichert".
 */
abstract class SettingsSectionActivity : SettingsBaseActivity() {

    private var baseline = ""
    private var changedInSession = false

    /** Schreibt die Werte aus den Feldern in [config]. */
    protected abstract fun saveFieldsIntoConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        changedInSession = savedInstanceState?.getBoolean(STATE_CHANGED) ?: false
        // Beim Laden werden Werte auf die Schrittweite der Regler gerundet usw.; das soll
        // nicht als Änderung des Nutzers gelten, daher wird der Stand erst danach gemerkt.
        saveFieldsIntoConfig()
        baseline = snapshot()
    }

    /** Gerätename steht nicht im Export (siehe AppConfig), gehört aber zu den Einstellungen. */
    private fun snapshot() = config.exportToJson() + "\n" + config.deviceName

    /**
     * Auch bei onPause (z. B. beim Öffnen des Klingelton-Dialogs oder wenn die App im
     * Hintergrund beendet wird), nicht nur beim Schließen der Seite.
     */
    private fun persist() {
        saveFieldsIntoConfig()
        val current = snapshot()
        if (current == baseline) return
        baseline = current
        changedInSession = true
        DoorbellForegroundService.startOrStop(this)
        PushRegistrar.start(this)
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun finish() {
        persist()
        setResult(if (changedInSession) RESULT_OK else RESULT_CANCELED)
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CHANGED, changedInSession)
    }

    private companion object {
        const val STATE_CHANGED = "changed_in_session"
    }
}
