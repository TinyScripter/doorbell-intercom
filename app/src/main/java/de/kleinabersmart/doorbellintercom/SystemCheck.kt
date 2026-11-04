package de.kleinabersmart.doorbellintercom

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat

/**
 * Prüft Voraussetzungen, ohne die das Klingeln nicht (zuverlässig) ankommt, und kennt die
 * Systemeinstellung, in der sich das jeweils beheben lässt. Angezeigt in den Einstellungen
 * (Karte "Einrichtung prüfen") und als Hinweis auf dem Hauptbildschirm.
 */
object SystemCheck {

    private const val TAG = "SystemCheck"

    class Issue(@param:StringRes val title: Int, @param:StringRes val summary: Int, val fix: (Context) -> Intent)

    fun issues(context: Context): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            issues += Issue(R.string.check_notifications_off, R.string.check_notifications_off_summary) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)
            }
        }
        // Ab Android 14 eine Sonderberechtigung, die der Nutzer (oder Play) entziehen kann:
        // ohne sie öffnet sich der Klingelbildschirm bei gesperrtem Display nicht.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            issues += Issue(R.string.check_full_screen_off, R.string.check_full_screen_off_summary) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(it))
            }
        }
        if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
            // App-Info statt ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: Letzteres braucht eine
            // Berechtigung, die Google Play nur wenigen App-Arten erlaubt.
            issues += Issue(R.string.check_battery_optimized, R.string.check_battery_optimized_summary) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(it))
            }
        }
        return issues
    }

    /** Öffnet die passende Systemeinstellung; fehlt sie (Hersteller-Anpassung), die App-Info. */
    fun openFix(context: Context, issue: Issue) {
        try {
            context.startActivity(issue.fix(context))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Einstellung nicht vorhanden, öffne App-Info", e)
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
        }
    }

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
}
