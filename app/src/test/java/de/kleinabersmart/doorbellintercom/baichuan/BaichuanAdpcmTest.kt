package de.kleinabersmart.doorbellintercom.baichuan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class BaichuanAdpcmTest {

    /** Referenz: IMA-ADPCM nach der IMA-Spezifikation, unabhängig in Python implementiert. */
    @Test
    fun encode_matchesImaReference() {
        val pcm = shortArrayOf(0, 1000, 2000, 3000, -3000, 32767, -32768, 100, 0, 5)
        val blocks = BaichuanAdpcm.Encoder().encode(pcm, samplesPerBlock = 4)
        assertEquals(listOf("000000007077", "680018007f2f", "52002f0008"), blocks.map { it.toHex() })
    }

    /** Der Encoder-Zustand läuft über Aufrufe hinweg weiter, wie während einer Talk-Session. */
    @Test
    fun encode_keepsStateAcrossCalls() {
        val pcm = shortArrayOf(0, 1000, 2000, 3000, -3000, 32767, -32768, 100, 0, 5)
        val encoder = BaichuanAdpcm.Encoder()
        val first = encoder.encode(pcm.copyOfRange(0, 4), samplesPerBlock = 4)
        val rest = encoder.encode(pcm.copyOfRange(4, pcm.size), samplesPerBlock = 4)
        assertEquals(BaichuanAdpcm.Encoder().encode(pcm, 4).map { it.toHex() }, (first + rest).map { it.toHex() })
    }

    @Test
    fun encode_emptyInputGivesNoBlocks() {
        assertTrue(BaichuanAdpcm.Encoder().encode(ShortArray(0), 4).isEmpty())
    }

    /** Ein Sinus übersteht Kodieren und Dekodieren mit der für IMA-ADPCM üblichen Qualität. */
    @Test
    fun encodeThenDecode_reproducesSine() {
        val samplesPerBlock = 505
        // Ganze Blöcke: im letzten, kurzen Block wüsste der Dekoder nicht, wo die Samples enden.
        val pcm = ShortArray(16 * samplesPerBlock) { (10_000 * sin(2 * PI * 440 * it / 8000)).toInt().toShort() }
        val decoded = BaichuanAdpcm.Encoder().encode(pcm, samplesPerBlock).flatMap { decodeBlock(it, samplesPerBlock) }

        assertEquals(pcm.size, decoded.size)
        var signal = 0.0
        var noise = 0.0
        for (i in pcm.indices) {
            signal += pcm[i].toDouble() * pcm[i]
            val error = (pcm[i] - decoded[i]).toDouble()
            noise += error * error
        }
        val snrDb = 10 * log10(signal / noise)
        assertTrue("SNR $snrDb dB", snrDb > 20)
    }

    @Test
    fun buildBcMediaFrames_writesHeaderAndPadsTo8Bytes() {
        val block = byteArrayOf(1, 2, 3, 4, 5, 6)
        val frame = BaichuanAdpcm.buildBcMediaFrames(listOf(block))

        assertEquals(
            "30317762" + // Magic "01wb"
                "0a00" + "0a00" + // payload_size = 6 + 4, doppelt
                "0001" + "0200" + // Sub-Magic 0x0100, half_block = 2
                "010203040506" +
                "000000000000", // Padding auf 24 Byte
            frame.toHex()
        )
        assertEquals(0, frame.size % 8)
    }

    @Test
    fun buildBcMediaFrames_concatenatesBlocks() {
        val frames = BaichuanAdpcm.buildBcMediaFrames(listOf(ByteArray(4), ByteArray(12)))
        // 12 + 4 + 0 Padding (payload 8) und 12 + 12 + 0 Padding (payload 16)
        assertEquals(16 + 24, frames.size)
    }

    /** IMA-Dekoder: Preamble (predictor i16, step_index u8, reserviert) und Nibbles, niedriges zuerst. */
    private fun decodeBlock(block: ByteArray, samples: Int): List<Short> {
        val header = ByteBuffer.wrap(block, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        var predictor = header.short.toInt()
        var index = header.get().toInt() and 0xFF
        val out = ArrayList<Short>(samples)
        for (i in 0 until (block.size - 4) * 2) {
            if (out.size == samples) break
            val byte = block[4 + i / 2].toInt() and 0xFF
            val code = if (i % 2 == 0) byte and 0x0F else byte shr 4
            val step = STEP_TABLE[index]
            var diff = step shr 3
            if (code and 4 != 0) diff += step
            if (code and 2 != 0) diff += step shr 1
            if (code and 1 != 0) diff += step shr 2
            predictor = (if (code and 8 != 0) predictor - diff else predictor + diff).coerceIn(-32768, 32767)
            index = (index + INDEX_TABLE[code and 7]).coerceIn(0, 88)
            out.add(predictor.toShort())
        }
        return out
    }

    private companion object {
        val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
        val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552,
            1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
            7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385,
            24623, 27086, 29794, 32767
        )
    }
}
