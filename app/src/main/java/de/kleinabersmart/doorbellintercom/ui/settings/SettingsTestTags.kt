package de.kleinabersmart.doorbellintercom.ui.settings

/**
 * Test-Tags aller Einstellungsseiten an einer Stelle, angelehnt an die früheren View-IDs
 * (settings_*.xml), damit Screenshot- und UI-Tests dieselben Namen weiterverwenden können.
 */
object SettingsTestTags {
    // Gerüst (activity_settings.xml)
    const val TOOLBAR = "settings_toolbar"
    const val UP = "settings_up"

    /** Scrollbarer Inhaltsbereich (vorher ScrollView + settingsContent). */
    const val CONTENT = "settings_content"
}
