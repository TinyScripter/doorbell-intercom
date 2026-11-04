package de.kleinabersmart.doorbellintercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Startet den Hintergrunddienst nach einem Neustart und nach einem Update der App wieder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> DoorbellForegroundService.startOrStop(context)
        }
    }
}
