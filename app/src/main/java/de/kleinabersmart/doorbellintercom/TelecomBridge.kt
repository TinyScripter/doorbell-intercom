package de.kleinabersmart.doorbellintercom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Meldet ein Klingeln beim System als selbstverwalteten Anruf an (androidx.core:core-telecom),
 * wenn das in den Einstellungen eingeschaltet ist (AppConfig.telecomCalls, experimentell).
 *
 * Was das bringt:
 * - Ein Telefonat während des Gesprächs hält die Sprechanlage an ([CallController.setHeld]),
 *   statt mit ihr ums Mikrofon zu konkurrieren.
 * - Annehmen und Auflegen gehen auch über Uhr, Headset-Taste oder Android Auto.
 * - Das System kennt den Anruf: Audio-Fokus, Kommunikationsmodus und Audio-Routing
 *   (Lautsprecher, Bluetooth). Der Ton des Live-Bilds läuft dafür als Sprachanruf
 *   (DirectRtspClient.voiceCall), sobald [CallState.Active.systemCall] gesetzt ist.
 *
 * Der Anruf folgt [CallController.state]: Annehmen dort nimmt ihn an, das Ende dort beendet
 * ihn, [CallController.resumeRequests] setzt ihn nach dem Halten fort. Umgekehrt melden die
 * Callbacks des Systems an den Controller. Ins Anrufprotokoll des
 * Telefons kommt er nicht.
 */
class TelecomBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val calls: CallController
) {
    private val callsManager by lazy {
        CallsManager(context).also {
            it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
        }
    }

    /** Klingeln, für das zuletzt ein Anruf angemeldet wurde. */
    private var lastSeq: Int? = null

    fun start() {
        scope.launch {
            calls.state.collect { state ->
                if (state !is CallState.Ringing || state.seq == lastSeq) return@collect
                lastSeq = state.seq
                if (AppConfig(context).telecomCalls) scope.launch { runCall(state) }
            }
        }
    }

    private suspend fun runCall(ringing: CallState.Ringing) {
        val seq = ringing.seq
        val attributes = CallAttributesCompat(
            displayName = context.getString(R.string.call_caller_name),
            address = Uri.fromParts(URI_SCHEME, ringing.id ?: seq.toString(), null),
            direction = CallAttributesCompat.DIRECTION_INCOMING,
            // Video: das System schaltet beim Annehmen von selbst auf den Lautsprecher statt
            // auf die Hörmuschel – wie bei der Sprechanlage ohne Telecom.
            callType = CallAttributesCompat.CALL_TYPE_VIDEO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            isLogExcluded = true
        )
        try {
            callsManager.addCall(
                attributes,
                // Die Callbacks müssen innerhalb von 5 s fertig sein; eine Exception lehnt ab.
                onAnswer = { check(calls.acceptFromSystem(seq)) { "Klingeln $seq läuft nicht mehr" } },
                onDisconnect = { calls.endFromSystem(seq) },
                onSetActive = { calls.setHeld(seq, held = false) },
                onSetInactive = { calls.setHeld(seq, held = true) }
            ) {
                Log.i(TAG, "Klingeln $seq als Anruf beim System angemeldet")
                calls.onSystemCallAdded(seq)
                launch { follow(seq) }
            }
        } catch (e: Exception) {
            // Z. B. Notruf aktiv oder Telecom nicht verfügbar: dann klingelt es wie ohne Telecom.
            Log.w(TAG, "Klingeln $seq konnte nicht als Anruf angemeldet werden", e)
        }
    }

    /** Überträgt Annehmen, Fortsetzen und Ende des Klingelns [seq] aus dem Controller an das System. */
    private suspend fun CallControlScope.follow(seq: Int) {
        // Fortsetzen nach dem Halten: Telecom macht das nach einem Telefonat nicht von selbst.
        val resumer = launch {
            calls.resumeRequests.filter { it == seq }.collect {
                val result = setActive()
                Log.d(TAG, "Fortsetzen: $result")
                if (result is CallControlResult.Success) calls.setHeld(seq, held = false)
            }
        }
        var answered = false
        calls.state.takeWhile { it.seq == seq }.collect { state ->
            if (state is CallState.Active && !answered) {
                answered = true
                // Kam das Annehmen vom System (onAnswer), meldet answer() nur einen Fehler.
                Log.d(TAG, "Annehmen: ${answer(CallAttributesCompat.CALL_TYPE_VIDEO_CALL)}")
                preferSpeaker()
            }
        }
        resumer.cancel()
        val cause = if (answered) DisconnectCause.LOCAL else DisconnectCause.REJECTED
        Log.d(TAG, "Beenden: ${disconnect(DisconnectCause(cause))}")
    }

    /** Rückfall, falls das System doch die Hörmuschel gewählt hat: Headset und Bluetooth bleiben. */
    private suspend fun CallControlScope.preferSpeaker() {
        if (currentCallEndpoint.first().type != CallEndpointCompat.TYPE_EARPIECE) return
        val speaker = availableEndpoints.first().firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER } ?: return
        Log.d(TAG, "Lautsprecher: ${requestEndpointChange(speaker)}")
    }

    private companion object {
        const val TAG = "TelecomBridge"
        const val URI_SCHEME = "doorbell"
    }
}
