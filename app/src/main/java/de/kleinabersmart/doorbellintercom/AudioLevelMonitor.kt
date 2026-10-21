package de.kleinabersmart.doorbellintercom

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reicht den Ton der Klingel unverändert durch und protokolliert, wann er laut
 * wird. Zusammen mit "Sprechen erkannt" im Log von BaichuanTalkSession lässt
 * sich so ablesen, wie lange das Echo der eigenen Stimme bis zurück ins Handy braucht.
 */
@OptIn(UnstableApi::class)
class AudioLevelMonitor : BaseAudioProcessor() {

    private var loud = false
    private var lastLoudAt = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val samples = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        var peak = 0
        while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.get().toInt()))

        val now = SystemClock.elapsedRealtime()
        if (peak >= LOUD_PEAK_THRESHOLD) lastLoudAt = now
        val nowLoud = now - lastLoudAt < LOUD_HANGOVER_MS
        if (nowLoud != loud) {
            loud = nowLoud
            Log.d(TAG, "Klingel-Ton laut: $loud (Spitze $peak)")
        }

        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    companion object {
        private const val TAG = "AudioLevelMonitor"
        private const val LOUD_PEAK_THRESHOLD = 1000
        private const val LOUD_HANGOVER_MS = 400L
    }
}
