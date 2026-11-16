package de.kleinabersmart.doorbellintercom.ui.settings

/**
 * Test-Tags aller Einstellungsseiten an einer Stelle, angelehnt an die früheren View-IDs
 * (settings_*.xml), damit Screenshot- und UI-Tests dieselben Namen weiterverwenden können.
 * Die Einträge der Hauptseite tragen [SettingsEntry.testTag].
 */
object SettingsTestTags {
    // Gerüst (activity_settings.xml)
    const val TOOLBAR = "settings_toolbar"
    const val UP = "settings_up"

    /** Scrollbarer Inhaltsbereich (vorher ScrollView + settingsContent). */
    const val CONTENT = "settings_content"

    // Hauptseite (settings_main.xml)
    const val CHECK_TITLE = "check_title"
    const val BTN_EXPORT = "btn_export"
    const val BTN_IMPORT = "btn_import"
    const val BTN_SHOW_QR = "btn_show_qr"
    const val BTN_SCAN_QR = "btn_scan_qr"
    const val STATUS_TEXT = "settings_status_text"

    // Dialoge der Hauptseite
    const val QR_DIALOG = "qr_dialog"
    const val QR_IMAGE = "qr_image"
    const val BTN_QR_CLOSE = "btn_qr_close"
    const val QR_IMPORT_DIALOG = "qr_import_dialog"
    const val BTN_QR_IMPORT_CONFIRM = "btn_qr_import_confirm"
    const val BTN_QR_IMPORT_CANCEL = "btn_qr_import_cancel"

    /** Systemcheck-Hinweis an Position [index] (vorher Kind [index] von checkList). */
    fun checkIssue(index: Int) = "check_issue_$index"
}
