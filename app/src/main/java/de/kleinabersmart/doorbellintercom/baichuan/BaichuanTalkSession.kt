package de.kleinabersmart.doorbellintercom.baichuan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.kleinabersmart.doorbellintercom.R
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kapselt eine vollständige Gegensprech-Session: verbindet per Baichuan-TCP zur
 * Kamera, meldet an, erfasst das Mikrofon und streamt es in Echtzeit als ADPCM
 * (siehe BaichuanTalkClient). Läuft auf
 * einem eigenen Hintergrund-Thread, damit der blockierende Capture-/Sende-Loop
 * die Latenz nicht durch Coroutine-Scheduling o.ä. verschlechtert.
 *
 * Das Protokoll stammt aus reolink_aio (MIT), siehe THIRD_PARTY_NOTICES.md.
 */
class BaichuanTalkSession(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val channel: Int = 0,
    /** Verstärkungsfaktor für das Mikrofonsignal (1.0 = unverändert). */
    private val micGain: Float = 1.0f,
    private val onError: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    /** Meldet, ob gerade jemand ins Mikrofon spricht (Pegel über SPEECH_PEAK_THRESHOLD). */
    private val onSpeaking: ((Boolean) -> Unit)? = null
) {
    private val running = AtomicBoolean(false)
    // Nach stop() keine Meldungen mehr an die UI: z. B. würde ein noch
    // ausstehendes "Sprechverbindung aktiv" sonst den Status überschreiben.
    @Volatile private var stopRequested = false
    private var thread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (running.get()) return
        running.set(true)
        thread = Thread({ runSession() }, "BaichuanTalk").apply { start() }
    }

    fun stop() {
        stopRequested = true
        running.set(false)
        thread?.join(2000)
        thread = null
    }

    private fun postError(message: String) = mainHandler.post { if (!stopRequested) onError(message) }
    private fun postStatus(message: String) = mainHandler.post { if (!stopRequested) onStatus(message) }
    private fun postSpeaking(speaking: Boolean) = mainHandler.post { if (!stopRequested) onSpeaking?.invoke(speaking) }

    private fun runSession() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            postError(context.getString(R.string.status_mic_permission_needed))
            running.set(false)
            return
        }

        val client = BaichuanTalkClient(host, port, username, password)
        var audioRecord: AudioRecord? = null
        var effects: List<AudioEffect> = emptyList()
        try {
            Log.d(TAG, "Starte Talk-Session zu $host:$port Kanal $channel")
            client.connect()
            client.login()
            val ability = client.getTalkAbility(channel)
            client.startTalk(channel, ability)
            postStatus(context.getString(R.string.status_talk_active))

            val sampleRate = ability.sampleRate
            val blockSize = ability.lengthPerEncoder
            val minBufBytes = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufBytes <= 0) {
                throw IOException("Mikrofon unterstützt Sample-Rate $sampleRate Hz nicht")
            }

            val readSamples = blockSize
            val bufferBytes = maxOf(minBufBytes, readSamples * 2 * 2)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("Mikrofon konnte nicht initialisiert werden")
            }
            Log.d(TAG, "AudioRecord initialisiert: sampleRate=$sampleRate blockSize=$blockSize bufferBytes=$bufferBytes")
            effects = enableVoiceEffects(record.audioSessionId)

            val encoder = BaichuanAdpcm.Encoder()
            val pcmBuffer = ShortArray(readSamples)

            record.startRecording()
            Log.d(TAG, "Mikrofon-Aufnahme gestartet, sende Audio bis zum Loslassen des Buttons")
            Log.d(TAG, "Mikrofon-Verstärkung: ${(micGain * 100).toInt()} %")
            var readCount = 0
            var peakIn = 0
            var peakOut = 0
            var clipped = 0
            var total = 0
            var speaking = false
            var lastSpeechAt = 0L
            while (running.get()) {
                val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read() lieferte $read (erwartet > 0)")
                    continue
                }
                readCount++
                var blockPeak = 0
                for (i in 0 until read) blockPeak = maxOf(blockPeak, kotlin.math.abs(pcmBuffer[i].toInt()))
                peakIn = maxOf(peakIn, blockPeak)
                if (onSpeaking != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (blockPeak >= SPEECH_PEAK_THRESHOLD) lastSpeechAt = now
                    val nowSpeaking = now - lastSpeechAt < SPEECH_HANGOVER_MS
                    if (nowSpeaking != speaking) {
                        speaking = nowSpeaking
                        Log.d(TAG, "Sprechen erkannt: $speaking (Spitze $blockPeak)")
                        postSpeaking(speaking)
                    }
                }
                if (micGain != 1.0f) applyGain(pcmBuffer, read, micGain)
                for (i in 0 until read) {
                    val a = kotlin.math.abs(pcmBuffer[i].toInt())
                    peakOut = maxOf(peakOut, a)
                    if (a >= Short.MAX_VALUE) clipped++
                }
                total += read
                if (total >= sampleRate) {
                    Log.d(TAG, "Pegel: Spitze vorher=$peakIn nachher=$peakOut, übersteuert=$clipped/$total")
                    peakIn = 0; peakOut = 0; clipped = 0; total = 0
                }
                val samples = if (read == pcmBuffer.size) pcmBuffer else pcmBuffer.copyOf(read)
                val blocks = encoder.encode(samples, blockSize)
                if (blocks.isNotEmpty()) {
                    client.sendTalkAudio(channel, blocks)
                }
            }
            Log.d(TAG, "Mikrofon-Loop beendet, $readCount Lese-Zyklen insgesamt")
        } catch (e: BaichuanTalkClient.BaichuanAuthError) {
            Log.e(TAG, "Talk-Session fehlgeschlagen", e)
            postError(context.getString(R.string.status_talk_auth_failed))
        } catch (e: Exception) {
            Log.e(TAG, "Talk-Session fehlgeschlagen", e)
            postError(context.getString(R.string.status_talk_failed, e.message))
        } finally {
            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }
            effects.forEach { it.release() }
            audioRecord?.release()
            try {
                client.stopTalk(channel)
            } catch (_: Exception) {
            }
            client.close()
            running.set(false)
            Log.d(TAG, "Talk-Session beendet und aufgeräumt")
        }
    }

    companion object {
        private const val TAG = "BaichuanTalkSession"

        /**
         * Ab dieser Spitze (vor der Verstärkung) gilt ein Block als Sprache. Stille liegt
         * nach Echo- und Rauschunterdrückung meist deutlich unter 100.
         */
        private const val SPEECH_PEAK_THRESHOLD = 1000
        /** Kurze Pausen zwischen Wörtern zählen noch als Sprechen. */
        private const val SPEECH_HANGOVER_MS = 400L

        /**
         * VOICE_COMMUNICATION schaltet Echo- und Rauschunterdrückung meist schon ein,
         * aber nicht auf jedem Gerät – daher zusätzlich ausdrücklich, soweit vorhanden.
         */
        private fun enableVoiceEffects(audioSessionId: Int): List<AudioEffect> {
            val effects = mutableListOf<AudioEffect>()
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)?.let { effects.add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)?.let { effects.add(it) }
            }
            for (effect in effects) {
                try {
                    effect.enabled = true
                } catch (e: Exception) {
                    Log.w(TAG, "${effect.descriptor.name} ließ sich nicht einschalten", e)
                }
            }
            Log.d(TAG, "Audio-Effekte: " + effects.joinToString { "${it.descriptor.name}=${it.enabled}" }
                .ifEmpty { "keine verfügbar" })
            return effects
        }

        /** Verstärkt die ersten [count] Samples in-place; Übersteuerung wird auf den 16-Bit-Bereich begrenzt. */
        internal fun applyGain(samples: ShortArray, count: Int, gain: Float) {
            for (i in 0 until count) {
                samples[i] = (samples[i] * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }
}
