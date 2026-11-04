package de.kleinabersmart.doorbellintercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat

/**
 * Benachrichtigungen rund ums Klingeln: Klingel-Benachrichtigung mit Full-Screen-Intent,
 * „Verpasst“, „Angenommen von …“ und „Kamera nicht erreichbar“. Wann was erscheint,
 * entscheidet CallController; hier steht nur, wie es aussieht.
 */
object IncomingCallNotifier {

    // Neue ID, weil sich Ton und Vibration eines bestehenden Kanals nicht mehr ändern lassen.
    private const val CHANNEL_ID_CALL = "doorbell_call_watch"
    private val LEGACY_CHANNEL_IDS_CALL = listOf("doorbell_call", "doorbell_call_silent")
    // Kurzes Muster für die Uhr; das dauerhafte Klingeln auf dem Handy macht DoorbellRinger.
    private val CHANNEL_VIBRATION_PATTERN = longArrayOf(0, 500, 300, 500)
    private const val CALL_NOTIFICATION_ID = 2
    private const val CHANNEL_ID_ANSWERED = "doorbell_answered"
    private const val ANSWERED_NOTIFICATION_ID = 3
    private const val CHANNEL_ID_NOT_REACHABLE = "doorbell_not_reachable"
    private const val NOT_REACHABLE_NOTIFICATION_ID = 4
    private const val CHANNEL_ID_MISSED = "doorbell_missed"
    private const val MISSED_NOTIFICATION_ID = 5

    /**
     * Das Ende des Klingelns bestimmt CallController. Das System-Timeout ist nur der Rückfall,
     * falls der Prozess vorher beendet wird – etwas später, damit es dem Controller nicht
     * zuvorkommt (sonst fehlte die Meldung „Verpasst“).
     */
    private const val SYSTEM_TIMEOUT_GRACE_MS = 5_000L

    // Request-Codes der PendingIntents: Intents, die sich nur in den Extras unterscheiden,
    // gelten sonst als gleich und würden sich gegenseitig überschreiben.
    private const val REQUEST_OPEN = 0
    private const val REQUEST_ACCEPT = 1
    private const val REQUEST_MISSED = 2

    /** Zeigt bzw. aktualisiert die Klingel-Benachrichtigung (beim zweiten Aufruf mit Vorschaubild). */
    fun showRinging(context: Context, ringing: CallState.Ringing, picture: Bitmap?) {
        ensureCallChannel(context)
        val remainingMs = (ringing.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        context.getSystemService(NotificationManager::class.java)
            .notify(CALL_NOTIFICATION_ID, buildCallNotification(context, remainingMs + SYSTEM_TIMEOUT_GRACE_MS, picture))
    }

    fun cancelRinging(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
    }

    /**
     * Der Full-Screen-Intent ist der offizielle Weg, aus dem Hintergrund eine
     * Anrufansicht zu zeigen: bei gesperrtem/ausgeschaltetem Display öffnet Android
     * den Klingelbildschirm direkt, sonst erscheint die Benachrichtigung als Heads-up
     * mit Vorschaubild und den Buttons Annehmen, Ablehnen und Tür öffnen.
     */
    private fun buildCallNotification(context: Context, timeoutMs: Long, picture: Bitmap?): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            context, REQUEST_OPEN, callScreenIntent(context, accept = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val acceptPendingIntent = PendingIntent.getActivity(
            context, REQUEST_ACCEPT, callScreenIntent(context, accept = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(context.getString(R.string.incoming_call_title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(openPendingIntent, true)
            .setContentIntent(openPendingIntent)
            // Wegwischen (auch auf der gekoppelten Uhr) gilt als Ablehnen.
            .setDeleteIntent(CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_DECLINE))
            .setAutoCancel(true)
            // Das Nachreichen des Vorschaubilds soll weder erneut aufpoppen noch den Klingelbildschirm neu öffnen.
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(timeoutMs)
            .addAction(0, context.getString(R.string.btn_accept), acceptPendingIntent)
            .addAction(
                0, context.getString(R.string.btn_decline),
                CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_DECLINE)
            )

        if (AppConfig(context).doorOpenUrl.isNotBlank()) {
            val doorAction = NotificationCompat.Action.Builder(
                0, context.getString(R.string.btn_door_open),
                CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_OPEN_DOOR)
            )
                // Auf dem Sperrbildschirm erst entsperren, wie beim Annehmen im Klingelbildschirm.
                .setAuthenticationRequired(true)
                .build()
            builder.addAction(doorAction)
            // Auf der gekoppelten Uhr ersetzt diese Action die Handy-Actions: dort nur Bild und
            // "Tür öffnen", Annehmen und Ablehnen bleiben dem Handy vorbehalten.
            builder.extend(NotificationCompat.WearableExtender().addAction(doorAction))
        }

        if (picture != null) builder.setPicture(picture)
        return builder.build()
    }

    private fun NotificationCompat.Builder.setPicture(picture: Bitmap) {
        val style = NotificationCompat.BigPictureStyle().bigPicture(picture)
        // Bild auch im eingeklappten Zustand zeigen; vor Android 12 dort nur als kleines Vorschaubild.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            style.showBigPictureWhenCollapsed(true)
        } else {
            setLargeIcon(picture)
        }
        setStyle(style)
    }

    fun callScreenIntent(context: Context, accept: Boolean) =
        Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (accept) putExtra(IncomingCallActivity.EXTRA_ACCEPT, true)
        }

    /**
     * Niemand hat innerhalb der Klingeldauer reagiert: leise Meldung mit Uhrzeit und, falls
     * schon geladen, dem Vorschaubild. Tippen öffnet das Live-Bild.
     */
    fun showMissed(context: Context, rangAt: Long, picture: Bitmap?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_MISSED,
                context.getString(R.string.channel_missed),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val openMain = PendingIntent.getActivity(
            context, REQUEST_MISSED,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val time = DateFormat.getTimeFormat(context).format(rangAt)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_MISSED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(context.getString(R.string.missed_title, time))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setWhen(rangAt)
            .setShowWhen(true)
            .setContentIntent(openMain)
            .setAutoCancel(true)
        if (picture != null) builder.setPicture(picture)
        manager.notify(MISSED_NOTIFICATION_ID, builder.build())
    }

    fun showAnsweredNotice(context: Context, answeredBy: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Auch bei bestehendem Kanal aufrufen: aktualisiert den Namen nach einem Sprachwechsel.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ANSWERED,
                context.getString(R.string.channel_answered_elsewhere),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ANSWERED)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(context.getString(R.string.answered_elsewhere_title, answeredBy ?: "?"))
            .setAutoCancel(true)
            .build()
        manager.notify(ANSWERED_NOTIFICATION_ID, notification)
    }

    /** Leise Meldung statt Klingeln: es hat geklingelt, die Kamera ist aber nicht erreichbar. */
    fun showNotReachableNotice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_NOT_REACHABLE,
                context.getString(R.string.channel_not_reachable),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_NOT_REACHABLE)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(context.getString(R.string.not_reachable_title))
            .setAutoCancel(true)
            .build()
        manager.notify(NOT_REACHABLE_NOTIFICATION_ID, notification)
    }

    private fun ensureCallChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        LEGACY_CHANNEL_IDS_CALL.forEach(manager::deleteNotificationChannel)
        // Auch bei bestehendem Kanal aufrufen: aktualisiert den Namen nach einem Sprachwechsel,
        // Ton und Wichtigkeit eines bestehenden Kanals lässt Android dabei unverändert.
        // Ohne Ton: den spielt DoorbellRinger ab. Die kurze Kanal-Vibration dient der gekoppelten
        // Uhr, die sonst nur einen stillen Benachrichtigungspunkt zeigt.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_CALL,
                context.getString(R.string.channel_incoming_call),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = CHANNEL_VIBRATION_PATTERN
            }
        )
    }
}
