package de.kleinabersmart.doorbellintercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Startet den Hintergrunddienst nach einem Neustart und nach einem Update der App wieder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> try {
                DoorbellForegroundService.startOrStop(context)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: Ab Android 15 kommt BOOT_COMPLETED auch,
                // wenn die App nach "Beenden erzwingen" wieder geöffnet wird – dann ist der Start aus
                // dem Hintergrund noch nicht erlaubt. Den Dienst startet gleich danach MainActivity;
                // ein Absturz hier würde nur den Prozess neu starten.
                Log.w(TAG, "Hintergrunddienst nach ${intent.action} nicht gestartet", e)
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
