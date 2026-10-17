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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zeigt beim Klingeln den Klingelbildschirm – gemeinsam genutzt vom
 * MQTT-Weg (DoorbellForegroundService) und vom Push-Weg
 * (DoorbellPushService).
 *
 * Home Assistant schickt ein Klingeln über beide Wege; was innerhalb von
 * DEDUP_WINDOW_MS nach dem ersten Eintreffen noch ankommt, wird ignoriert,
 * damit der Klingelbildschirm nur einmal erscheint.
 */
object IncomingCallNotifier {

    private const val TAG = "IncomingCallNotifier"
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
    private const val DEDUP_WINDOW_MS = 15_000L

    // Request-Codes der PendingIntents: Intents, die sich nur in den Extras unterscheiden,
    // gelten sonst als gleich und würden sich gegenseitig überschreiben.
    private const val REQUEST_OPEN = 0
    private const val REQUEST_ACCEPT = 1

    private var lastRingAt = 0L

    /** Zählt die Klingel-Ereignisse; ein spät eintreffendes Vorschaubild gehört nur zum aktuellen. */
    private var ringGeneration = 0

    @Synchronized
    fun ring(context: Context, source: String) {
        val now = SystemClock.elapsedRealtime()
        val timestamp = currentTimestamp()
        val config = AppConfig(context)
        if (lastRingAt != 0L && now - lastRingAt < DEDUP_WINDOW_MS) {
            val ageMs = now - lastRingAt
            Log.i(TAG, "Klingeln über $source ignoriert (bereits vor $ageMs ms gemeldet)")
            config.appendRingLogEntry("$timestamp  $source  ignoriert (Duplikat, $ageMs ms nach erstem Eintreffen)")
            return
        }
        lastRingAt = now
        Log.i(TAG, "Klingeln über $source um $timestamp")

        // Ohne Auto-VPN ist die Kamera nur im Heimnetz erreichbar: sonst nur leise melden.
        // Der TCP-Test blockiert kurz und darf nicht auf dem Haupt-Thread laufen (Push-Empfänger).
        if (config.silentWhenUnreachable && config.cameraHost.isNotBlank() && !WireGuardTunnel.isAutoVpnConfigured(config)) {
            val appContext = context.applicationContext
            Thread {
                if (WireGuardTunnel.isReachable(config.cameraHost)) {
                    startRinging(appContext, source, timestamp)
                } else {
                    config.appendRingLogEntry("$timestamp  $source  nur leise Benachrichtigung (Kamera nicht erreichbar)")
                    showNotReachableNotice(appContext)
                }
            }.start()
            return
        }
        startRinging(context, source, timestamp)
    }

    @Synchronized
    private fun startRinging(context: Context, source: String, timestamp: String) {
        val config = AppConfig(context)
        config.appendRingLogEntry("$timestamp  $source  Anruf angezeigt")

        ensureChannel(context)
        val generation = ++ringGeneration
        val now = SystemClock.elapsedRealtime()
        val endsAt = now + config.ringDurationSeconds * 1000L

        // Erst ohne Vorschaubild, damit das Klingeln nicht auf die Kamera wartet.
        context.getSystemService(NotificationManager::class.java)
            .notify(CALL_NOTIFICATION_ID, buildCallNotification(context, endsAt - now, picture = null))
        // Ton und Vibration kommen von der App selbst, siehe DoorbellRinger.
        DoorbellRinger.start(context)

        // Falls die App gerade im Vordergrund ist, zusätzlich direkt öffnen.
        // Aus dem Hintergrund blockiert Android das – dann greift die
        // Benachrichtigung oben.
        context.startActivity(callScreenIntent(context, accept = false))

        loadPreviewAsync(context.applicationContext, generation, endsAt)
    }

    /** Holt das Standbild der Kamera und ergänzt es in der noch klingelnden Benachrichtigung. */
    private fun loadPreviewAsync(context: Context, generation: Int, endsAt: Long) {
        Thread {
            val picture = CameraSnapshot.fetch(AppConfig(context)) ?: return@Thread
            synchronized(this) {
                // Inzwischen angenommen, abgelehnt, abgelaufen oder neu geklingelt: nichts mehr ändern.
                val remainingMs = endsAt - SystemClock.elapsedRealtime()
                if (generation != ringGeneration || remainingMs <= 0 || !isCallNotificationShown(context)) {
                    return@Thread
                }
                context.getSystemService(NotificationManager::class.java)
                    .notify(CALL_NOTIFICATION_ID, buildCallNotification(context, remainingMs, picture))
            }
        }.start()
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
            // Das System beendet die Benachrichtigung nach der eingestellten Klingeldauer.
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

        if (picture != null) {
            val style = NotificationCompat.BigPictureStyle().bigPicture(picture)
            // Bild auch im eingeklappten Heads-up zeigen; vor Android 12 dort nur als kleines Vorschaubild.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                style.showBigPictureWhenCollapsed(true)
            } else {
                builder.setLargeIcon(picture)
            }
            builder.setStyle(style)
        }
        return builder.build()
    }

    private fun callScreenIntent(context: Context, accept: Boolean) =
        Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (accept) putExtra(IncomingCallActivity.EXTRA_ACCEPT, true)
        }

    private fun isCallNotificationShown(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == CALL_NOTIFICATION_ID }

    /** Intent-Action, mit der eine offene, noch nicht angenommene IncomingCallActivity geschlossen wird. */
    const val ACTION_CLOSE_INCOMING_CALL = "de.kleinabersmart.doorbellintercom.CLOSE_INCOMING_CALL"

    private fun closeIncomingCallScreen(context: Context) {
        context.sendBroadcast(Intent(ACTION_CLOSE_INCOMING_CALL).setPackage(context.packageName))
    }

    /** Ein anderes Handy hat angenommen: Klingeln beenden und den Klingelbildschirm schließen. */
    fun answeredElsewhere(context: Context, answeredBy: String?) {
        Log.i(TAG, "Anruf wurde auf einem anderen Gerät angenommen: $answeredBy")
        val config = AppConfig(context)
        config.appendRingLogEntry("${currentTimestamp()}  Push  angenommen von: ${answeredBy ?: "unbekannt"}")
        stopRinging(context)
        closeIncomingCallScreen(context)
        if (config.notifyAnsweredElsewhere) showAnsweredNotice(context, answeredBy)
    }

    /** "Ablehnen" in der Benachrichtigung: wie Ablehnen im Klingelbildschirm, betrifft nur dieses Gerät. */
    fun declineFromNotification(context: Context) {
        AppConfig(context).appendRingLogEntry("${currentTimestamp()}  Benachrichtigung  abgelehnt")
        stopRinging(context)
        closeIncomingCallScreen(context)
    }

    /**
     * "Tür öffnen" in der Benachrichtigung: beendet das Klingeln und gilt als angenommen,
     * damit die anderen Handys aufhören zu klingeln. Den Webhook schickt CallActionReceiver.
     */
    fun doorOpenedFromNotification(context: Context) {
        AppConfig(context).appendRingLogEntry("${currentTimestamp()}  Benachrichtigung  Tür geöffnet")
        stopRinging(context)
        closeIncomingCallScreen(context)
        PushRegistrar.reportAnswered(context)
    }

    private fun showAnsweredNotice(context: Context, answeredBy: String?) {
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
    private fun showNotReachableNotice(context: Context) {
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

    /** Beendet das Klingeln (z. B. bei Annehmen oder Auflegen). */
    @Synchronized
    fun stopRinging(context: Context) {
        DoorbellRinger.stop()
        context.getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
    }

    /**
     * Uhrzeit im Klartext (statt nur SystemClock.elapsedRealtime) - so lassen sich
     * Einträge direkt mit Zeitstempeln aus Home-Assistant-/AppDaemon-Logs vergleichen.
     */
    private fun currentTimestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY).format(Date())

    private fun ensureChannel(context: Context) {
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
