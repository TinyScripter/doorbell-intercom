package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** Zustand des Klingelns auf diesem Gerät, siehe [CallController]. */
sealed interface CallState {

    /** Laufende Nummer des Klingelns, zu dem der Zustand gehört; null bei [Idle]. */
    val seq: Int?

    data object Idle : CallState {
        override val seq: Int? get() = null
    }

    /**
     * [seq] unterscheidet aufeinanderfolgende Klingeln, auch wenn Home Assistant keine [id]
     * mitschickt (ältere Konfiguration). [endsAt] ist SystemClock.elapsedRealtime(),
     * [rangAt] die Uhrzeit für die Meldung „Verpasst“. [systemCall]: als Anruf beim System
     * angemeldet (TelecomBridge).
     */
    data class Ringing(
        override val seq: Int,
        val id: String?,
        val source: String,
        val rangAt: Long,
        val endsAt: Long,
        val systemCall: Boolean = false
    ) : CallState

    /**
     * [rangAgain]: Während des Gesprächs hat es erneut geklingelt; das wird nur angezeigt, nicht geläutet.
     * [systemCall]: als Anruf beim System angemeldet, der Ton läuft dann über den Anruf-Audiopfad.
     * [held]: vom System gehalten, z. B. weil ein Telefonat angenommen wurde – Ton und Mikrofon aus.
     */
    data class Active(
        override val seq: Int,
        val id: String?,
        val rangAgain: Boolean = false,
        val systemCall: Boolean = false,
        val held: Boolean = false
    ) : CallState
}

/**
 * Einzige Quelle der Wahrheit dafür, ob gerade geklingelt oder gesprochen wird. Beide Wege
 * (MQTT im DoorbellForegroundService, Push im DoorbellPushService) melden hier, ebenso
 * Klingelbildschirm und Benachrichtigung. Klingelton, Benachrichtigung und Timeout steuert
 * der Controller selbst; IncomingCallActivity beobachtet [state] und schließt sich bei [CallState.Idle].
 *
 * Home Assistant schickt ein Klingeln über beide Wege. Mit Ring-ID (last_changed der
 * Trigger-Entität, siehe README 4.1) wird exakt per ID dedupliziert; ohne ID gilt wie früher
 * ein Zeitfenster von DEDUP_WINDOW_MS.
 *
 * Die öffentlichen Methoden kommen von verschiedenen Threads (HiveMQ-Callbacks, Main-Thread)
 * und sind deshalb synchronisiert.
 */
class CallController(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /**
     * Wie viele Klingelbildschirme gerade zu sehen sind (onStart/onStop der IncomingCallActivity;
     * gezählt, weil das onStop einer schließenden Instanz nach dem onStart der neuen kommen kann).
     * Nur bei sichtbarem Bildschirm wird ein neues Klingeln während eines Gesprächs unterdrückt –
     * ein liegen gelassenes Gespräch im Hintergrund soll kein späteres Klingeln verschlucken.
     */
    private var visibleCallScreens = 0

    @Synchronized
    fun onCallScreenStarted() {
        visibleCallScreens++
    }

    @Synchronized
    fun onCallScreenStopped() {
        visibleCallScreens = (visibleCallScreens - 1).coerceAtLeast(0)
    }

    private var seq = 0
    private var lastRingAt = 0L
    private var lastRingId: String? = null
    private val seenIds = ArrayDeque<String>()
    private var timeoutJob: Job? = null
    private var preview: Bitmap? = null

    @Synchronized
    fun onRing(rawId: String?, source: String) {
        val id = rawId?.let(::normalizeRingId)
        val now = SystemClock.elapsedRealtime()
        val timestamp = currentTimestamp()
        val config = AppConfig(context)
        val duplicate = duplicateReason(id, now)
        if (id != null) remember(id)
        if (duplicate != null) {
            Log.i(TAG, "Klingeln über $source ignoriert ($duplicate)")
            config.appendRingLogEntry("$timestamp  $source  ignoriert ($duplicate)")
            return
        }
        lastRingAt = now
        lastRingId = id
        Log.i(TAG, "Klingeln über $source um $timestamp (ID ${id ?: "–"})")

        val current = _state.value
        if (current is CallState.Active && visibleCallScreens > 0) {
            config.appendRingLogEntry("$timestamp  $source  nur angezeigt (Gespräch läuft)")
            _state.value = current.copy(rangAgain = true)
            return
        }

        // Ohne Auto-VPN ist die Kamera nur im Heimnetz erreichbar: sonst nur leise melden.
        // Der TCP-Test blockiert kurz und darf nicht auf dem Haupt-Thread laufen (Push-Empfänger).
        if (config.silentWhenUnreachable && config.cameraHost.isNotBlank() && !WireGuardTunnel.isAutoVpnConfigured(config)) {
            scope.launch {
                if (WireGuardTunnel.isReachable(config.cameraHost)) {
                    startRinging(id, source, timestamp)
                } else {
                    config.appendRingLogEntry("$timestamp  $source  nur leise Benachrichtigung (Kamera nicht erreichbar)")
                    IncomingCallNotifier.showNotReachableNotice(context)
                }
            }
            return
        }
        startRinging(id, source, timestamp)
    }

    /**
     * Eine unverschlüsselte Push-Nachricht kam an, obwohl Home Assistant verschlüsselt (siehe
     * DoorbellPushService): nicht klingeln, nur für die Fehlersuche festhalten.
     */
    fun onRejectedPush() {
        AppConfig(context).appendRingLogEntry("${currentTimestamp()}  Push  unverschlüsselt, verworfen")
    }

    /** Grund, warum [id] als Duplikat gilt, oder null für ein neues Klingeln. */
    private fun duplicateReason(id: String?, now: Long): String? {
        if (id != null && id in seenIds) return "Duplikat, ID $id"
        // Beide mit ID: verschiedene IDs sind zwei echte Klingeln, auch kurz nacheinander.
        if (id != null && lastRingId != null) return null
        val ageMs = now - lastRingAt
        if (lastRingAt != 0L && ageMs < DEDUP_WINDOW_MS) {
            // Kam der erste Weg ohne ID, gilt die ID ab jetzt für dieses Klingeln.
            if (lastRingId == null) lastRingId = id
            return "Duplikat, $ageMs ms nach erstem Eintreffen"
        }
        return null
    }

    private fun remember(id: String) {
        if (id in seenIds) return
        seenIds.addLast(id)
        if (seenIds.size > MAX_SEEN_IDS) seenIds.removeFirst()
    }

    @Synchronized
    private fun startRinging(id: String?, source: String, timestamp: String) {
        val config = AppConfig(context)
        config.appendRingLogEntry("$timestamp  $source  Anruf angezeigt")

        val now = SystemClock.elapsedRealtime()
        val ringing = CallState.Ringing(
            seq = ++seq,
            id = id,
            source = source,
            rangAt = System.currentTimeMillis(),
            endsAt = now + config.ringDurationSeconds * 1000L
        )
        preview = null
        _state.value = ringing

        // Erst ohne Vorschaubild, damit das Klingeln nicht auf die Kamera wartet.
        IncomingCallNotifier.showRinging(context, ringing, picture = null)
        // Ton und Vibration kommen von der App selbst, siehe DoorbellRinger.
        DoorbellRinger.start(context)
        // Falls die App gerade im Vordergrund ist, zusätzlich direkt öffnen. Aus dem
        // Hintergrund blockiert Android das – dann greift der Full-Screen-Intent der Benachrichtigung.
        context.startActivity(IncomingCallNotifier.callScreenIntent(context, accept = false))

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay((ringing.endsAt - now).milliseconds)
            onTimeout(ringing.seq)
        }
        scope.launch { loadPreview(ringing) }
    }

    /** Holt das Standbild der Kamera und ergänzt es in der noch klingelnden Benachrichtigung. */
    private fun loadPreview(ringing: CallState.Ringing) {
        val picture = CameraSnapshot.fetch(AppConfig(context)) ?: return
        showPreview(ringing, picture)
    }

    @Synchronized
    private fun showPreview(ringing: CallState.Ringing, picture: Bitmap) {
        // Inzwischen angenommen, abgelehnt, abgelaufen oder neu geklingelt: nichts mehr ändern.
        if (_state.value != ringing) return
        preview = picture
        IncomingCallNotifier.showRinging(context, ringing, picture)
    }

    @Synchronized
    private fun onTimeout(seq: Int) {
        val ringing = _state.value as? CallState.Ringing ?: return
        if (ringing.seq != seq) return
        AppConfig(context).appendRingLogEntry("${currentTimestamp()}  ${ringing.source}  verpasst")
        val picture = preview
        end()
        IncomingCallNotifier.showMissed(context, ringing.rangAt, picture)
    }

    /** Annehmen im Klingelbildschirm. Gibt false zurück, wenn gerade nicht geklingelt wird. */
    @Synchronized
    fun accept(): Boolean {
        val ringing = _state.value as? CallState.Ringing ?: return false
        return acceptRinging(ringing, SOURCE_SCREEN)
    }

    private fun acceptRinging(ringing: CallState.Ringing, source: String): Boolean {
        AppConfig(context).appendRingLogEntry("${currentTimestamp()}  $source  angenommen")
        stopRingingEffects()
        _state.value = CallState.Active(ringing.seq, ringing.id, systemCall = ringing.systemCall)
        PushRegistrar.reportAnswered(context, ringing.id)
        return true
    }

    // --- Anruf im System (TelecomBridge) ------------------------------------

    /** Das System hat das Klingeln [seq] als Anruf angenommen (angemeldet, noch nicht angenommen). */
    @Synchronized
    fun onSystemCallAdded(seq: Int) {
        when (val current = _state.value) {
            is CallState.Ringing -> if (current.seq == seq) _state.value = current.copy(systemCall = true)
            is CallState.Active -> if (current.seq == seq) _state.value = current.copy(systemCall = true)
            CallState.Idle -> Unit
        }
    }

    /**
     * Annehmen über das System (Uhr, Headset-Taste, Android Auto). Gibt false zurück, wenn
     * [seq] nicht mehr klingelt. Den Bildschirm öffnet der Full-Screen-Intent; auf dem
     * Sperrbildschirm läuft der Ton erst nach dem Entsperren (siehe IncomingCallActivity).
     */
    @Synchronized
    fun acceptFromSystem(seq: Int): Boolean {
        val ringing = _state.value as? CallState.Ringing ?: return false
        if (ringing.seq != seq) return false
        return acceptRinging(ringing, SOURCE_SYSTEM)
    }

    /** Das System hat den Anruf [seq] beendet (z. B. Auflegen am Headset). */
    @Synchronized
    fun endFromSystem(seq: Int) {
        if (_state.value.seq != seq) return
        when (_state.value) {
            is CallState.Ringing -> decline(SOURCE_SYSTEM)
            is CallState.Active -> hangUp()
            CallState.Idle -> Unit
        }
    }

    private val _resumeRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /**
     * Bitten, den gehaltenen Anruf (seq) fortzusetzen. Telecom setzt ihn nach dem Telefonat
     * nicht von selbst fort; das macht TelecomBridge auf diese Bitte hin.
     */
    val resumeRequests: SharedFlow<Int> = _resumeRequests.asSharedFlow()

    /** Fortsetzen nach dem Halten (Tippen aufs Mikrofon im Klingelbildschirm). */
    fun requestResume() {
        val active = _state.value as? CallState.Active ?: return
        if (active.held) _resumeRequests.tryEmit(active.seq)
    }

    /** Das System hält den Anruf [seq] (z. B. Telefonat angenommen) bzw. setzt ihn fort. */
    @Synchronized
    fun setHeld(seq: Int, held: Boolean) {
        val active = _state.value as? CallState.Active ?: return
        if (active.seq != seq || active.held == held) return
        AppConfig(context).appendRingLogEntry(
            "${currentTimestamp()}  $SOURCE_SYSTEM  ${if (held) "gehalten" else "fortgesetzt"}"
        )
        _state.value = active.copy(held = held)
    }

    /** Ablehnen im Klingelbildschirm oder in der Benachrichtigung; betrifft nur dieses Gerät. */
    @Synchronized
    fun decline(source: String) {
        if (_state.value !is CallState.Ringing) return
        AppConfig(context).appendRingLogEntry("${currentTimestamp()}  $source  abgelehnt")
        end()
    }

    @Synchronized
    fun hangUp() {
        if (_state.value !is CallState.Active) return
        end()
    }

    /**
     * Der Klingelbildschirm wird geschlossen (z. B. Zurück): wie Ablehnen bzw. Auflegen.
     * Nur für das Klingeln [seq], das er gezeigt hat – eine noch schließende alte Instanz
     * darf ein inzwischen neues Klingeln nicht beenden.
     */
    @Synchronized
    fun leave(seq: Int?) {
        if (seq == null || _state.value.seq != seq) return
        when (_state.value) {
            is CallState.Ringing -> decline(SOURCE_SCREEN)
            is CallState.Active -> hangUp()
            CallState.Idle -> Unit
        }
    }

    /**
     * „Tür öffnen“ (Benachrichtigung oder Kombi-Button): beendet das Klingeln und gilt als
     * angenommen, damit die anderen Handys aufhören zu klingeln. Den Webhook schickt der Aufrufer.
     */
    @Synchronized
    fun doorOpened(source: String) {
        val current = _state.value
        AppConfig(context).appendRingLogEntry("${currentTimestamp()}  $source  Tür geöffnet")
        when (current) {
            is CallState.Ringing -> {
                end()
                PushRegistrar.reportAnswered(context, current.id)
            }
            is CallState.Active -> end()
            CallState.Idle -> Unit
        }
    }

    /** Ein anderes Handy hat angenommen: Klingeln beenden, sofern es um dasselbe Klingeln geht. */
    @Synchronized
    fun answeredElsewhere(rawId: String?, answeredBy: String?) {
        val id = rawId?.let(::normalizeRingId)
        val config = AppConfig(context)
        val timestamp = currentTimestamp()
        if (id != null && lastRingId != null && id != lastRingId) {
            // Verspätete Meldung zu einem früheren Klingeln: das aktuelle nicht beenden.
            config.appendRingLogEntry("$timestamp  Push  angenommen von ${answeredBy ?: "unbekannt"} ignoriert (anderes Klingeln)")
            return
        }
        Log.i(TAG, "Anruf wurde auf einem anderen Gerät angenommen: $answeredBy")
        config.appendRingLogEntry("$timestamp  Push  angenommen von: ${answeredBy ?: "unbekannt"}")
        if (_state.value is CallState.Ringing) end()
        if (config.notifyAnsweredElsewhere) IncomingCallNotifier.showAnsweredNotice(context, answeredBy)
    }

    private fun end() {
        timeoutJob?.cancel()
        timeoutJob = null
        preview = null
        stopRingingEffects()
        _state.value = CallState.Idle
    }

    private fun stopRingingEffects() {
        DoorbellRinger.stop()
        IncomingCallNotifier.cancelRinging(context)
    }

    /**
     * Uhrzeit im Klartext (statt nur SystemClock.elapsedRealtime) - so lassen sich
     * Einträge direkt mit Zeitstempeln aus Home-Assistant-/AppDaemon-Logs vergleichen.
     * Mit Datum, da das Klingel-Protokoll (RingLogActivity) über mehrere Tage reicht.
     */
    private fun currentTimestamp(): String =
        SimpleDateFormat("dd.MM. HH:mm:ss.SSS", Locale.GERMANY).format(Date())

    companion object {
        private const val TAG = "CallController"
        private const val DEDUP_WINDOW_MS = 15_000L
        private const val MAX_SEEN_IDS = 20

        const val SOURCE_SCREEN = "Klingelbildschirm"
        const val SOURCE_NOTIFICATION = "Benachrichtigung"
        const val SOURCE_SYSTEM = "System"

        /**
         * Ring-ID aus einer MQTT-Nachricht: JSON mit "id" (siehe README 4.1). Andere Payloads
         * (z. B. das frühere "ring") klingeln weiterhin, nur ohne ID.
         */
        fun ringIdFromPayload(payload: String): String? =
            runCatching { JSONObject(payload).optStringOrNull("id") }.getOrNull()

        /**
         * Die ID ist meist ein Zeitstempel (last_changed). Je nach Weg kann er in einer anderen
         * Zeitzone ("…+00:00" bzw. "…+01:00") oder mit Leerzeichen statt "T" geschrieben sein;
         * als Instant vergleichen.
         */
        fun normalizeRingId(id: String): String =
            runCatching { OffsetDateTime.parse(id.trim().replace(' ', 'T')).toInstant().toString() }.getOrDefault(id)
    }
}

/** Wie optString, aber null statt "" bzw. "null" für fehlende, leere oder JSON-null-Werte. */
fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }
