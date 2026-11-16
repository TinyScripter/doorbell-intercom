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

    // Kamera (settings_camera.xml)
    const val FIELD_CAMERA_HOST = "field_camera_host"
    const val FIELD_CAMERA_BC_PORT = "field_camera_bc_port"
    const val FIELD_CAMERA_USER = "field_camera_user"
    const val FIELD_CAMERA_PASS = "field_camera_pass"
    const val FIELD_CAMERA_RTSP_PATH = "field_camera_rtsp_path"
    const val CHECK_KEEP_ASPECT_RATIO = "check_keep_aspect_ratio"
    const val CHECK_DUCK_WHILE_TALKING = "check_duck_while_talking"
    const val LABEL_MIC_GAIN = "label_mic_gain"
    const val SLIDER_MIC_GAIN = "slider_mic_gain"
    const val LABEL_VIDEO_START_BUFFER = "label_video_start_buffer"
    const val SLIDER_VIDEO_START_BUFFER = "slider_video_start_buffer"
    const val LABEL_VIDEO_REBUFFER = "label_video_rebuffer"
    const val SLIDER_VIDEO_REBUFFER = "slider_video_rebuffer"

    // Klingel (settings_ringing.xml)
    const val LABEL_RING_DURATION = "label_ring_duration"
    const val SLIDER_RING_DURATION = "slider_ring_duration"
    const val LABEL_RINGTONE = "label_ringtone"
    const val BTN_CHOOSE_RINGTONE = "btn_choose_ringtone"
    const val SWITCH_RING_IN_SILENT_MODE = "switch_ring_in_silent_mode"
    const val SWITCH_TELECOM_CALLS = "switch_telecom_calls"
    const val TELECOM_CALLS_SUMMARY = "telecom_calls_summary"

    // Push (settings_push.xml)
    const val FIELD_REGISTRATION_URL = "field_registration_url"
    const val FIELD_DEVICE_NAME = "field_device_name"
    const val BTN_REGISTER_DEVICE = "btn_register_device"
    const val BTN_UNREGISTER_DEVICE = "btn_unregister_device"
    const val SWITCH_MQTT_FALLBACK = "switch_mqtt_fallback"
    const val FIELD_MQTT_HOST = "field_mqtt_host"
    const val FIELD_MQTT_PORT = "field_mqtt_port"
    const val FIELD_MQTT_USER = "field_mqtt_user"
    const val FIELD_MQTT_PASS = "field_mqtt_pass"
    const val FIELD_MQTT_TOPIC = "field_mqtt_topic"
    const val CHECK_NOTIFY_ANSWERED_ELSEWHERE = "check_notify_answered_elsewhere"

    // Weitere Verbindungen (settings_connections.xml)
    const val FIELD_DOOR_OPEN_URL = "field_door_open_url"
    const val SWITCH_WIREGUARD_AUTO_VPN = "switch_wireguard_auto_vpn"
    const val FIELD_WIREGUARD_TUNNEL = "field_wireguard_tunnel"
    const val SWITCH_SILENT_WHEN_UNREACHABLE = "switch_silent_when_unreachable"

    /** Systemcheck-Hinweis an Position [index] (vorher Kind [index] von checkList). */
    fun checkIssue(index: Int) = "check_issue_$index"
}
