package de.kleinabersmart.doorbellintercom

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Buttons und Wegwischen der Klingel-Benachrichtigung (siehe IncomingCallNotifier). */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> IncomingCallNotifier.declineFromNotification(context)
            ACTION_OPEN_DOOR -> openDoor(context)
        }
    }

    private fun openDoor(context: Context) {
        val appContext = context.applicationContext
        IncomingCallNotifier.doorOpenedFromNotification(appContext)
        val url = AppConfig(appContext).doorOpenUrl
        if (url.isBlank()) return
        // Hält den Prozess am Leben, bis der Webhook geantwortet hat.
        val pendingResult = goAsync()
        DoorOpenClient.trigger(appContext, url) { _, message ->
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_DECLINE = "de.kleinabersmart.doorbellintercom.action.DECLINE"
        const val ACTION_OPEN_DOOR = "de.kleinabersmart.doorbellintercom.action.OPEN_DOOR"

        fun pendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0, Intent(context, CallActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
