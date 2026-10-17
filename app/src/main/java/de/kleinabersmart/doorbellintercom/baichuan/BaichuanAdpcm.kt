package de.kleinabersmart.doorbellintercom.baichuan

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IMA/DVI-4-ADPCM-Encoder und BcMedia-Framing für den Reolink-Baichuan-Rückkanal.
 *
 * Portiert aus reolink_aio (PR #165, fredrsat/reolink_aio, Branch add-two-way-audio),
 * dort explizit als "confirmed from pcap + Ghidra audioTalkSendStream" markiert –
 * die dort parallel vorhandene, ältere audio.py-Variante mit abweichendem
 * Sub-Magic/Half-Block wurde bewusst NICHT übernommen, da sie mit den
 * hardware-validierten Werten in Konflikt steht.
 *
 * Quelle/Lizenz: reolink_aio (MIT), siehe THIRD_PARTY_NOTICES.md.
 */
object BaichuanAdpcm {

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private const val BC_MEDIA_ADPCM_MAGIC = 0x62773130 // "01wb" als LE-u32

    /** Läuft über eine gesamte Talk-Session hinweg fort (Encoder-Zustand pro Session). */
    class Encoder {
        private var predictor = 0
        private var stepIndex = 0

        /**
         * Kodiert 16-bit-Mono-PCM (little endian) in ADPCM-Blöcke der gegebenen
         * Blockgröße (Samples pro Block = von der Kamera gemeldetes `lengthPerEncoder`).
         * Encoder-Zustand (predictor/stepIndex) wird über Blockgrenzen hinweg fortgeführt.
         */
        fun encode(pcm: ShortArray, samplesPerBlock: Int): List<ByteArray> {
            if (pcm.isEmpty()) return emptyList()
            val blocks = mutableListOf<ByteArray>()

            var blockStart = 0
            while (blockStart < pcm.size) {
                val blockEnd = minOf(blockStart + samplesPerBlock, pcm.size)
                val blockSamples = pcm.copyOfRange(blockStart, blockEnd)
                blocks.add(encodeBlock(blockSamples))
                blockStart = blockEnd
            }
            return blocks
        }

        private fun encodeBlock(samples: ShortArray): ByteArray {
            // Preamble: Encoder-Zustand VOR diesem Block (predictor i16 LE, step_index u8, reserved u8).
            val preamble = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(predictor.toShort())
                .put(stepIndex.toByte())
                .put(0.toByte())
                .array()

            val nibbles = IntArray(samples.size)
            for (i in samples.indices) {
                val step = STEP_TABLE[stepIndex]
                var diff = samples[i].toInt() - predictor

                var sign = 0
                if (diff < 0) {
                    sign = 8
                    diff = -diff
                }

                var code = 0
                var threshold = step
                if (diff >= threshold) {
                    code = code or 4
                    diff -= threshold
                }
                threshold = threshold shr 1
                if (diff >= threshold) {
                    code = code or 2
                    diff -= threshold
                }
                threshold = threshold shr 1
                if (diff >= threshold) {
                    code = code or 1
                }

                code = code or sign
                nibbles[i] = code

                var decodedDiff = step shr 3
                if (code and 4 != 0) decodedDiff += step
                if (code and 2 != 0) decodedDiff += step shr 1
                if (code and 1 != 0) decodedDiff += step shr 2

                predictor = if (code and 8 != 0) predictor - decodedDiff else predictor + decodedDiff
                predictor = predictor.coerceIn(-32768, 32767)

                stepIndex = (stepIndex + INDEX_TABLE[code]).coerceIn(0, 88)
            }

            val data = ByteArray((nibbles.size + 1) / 2)
            var i = 0
            while (i < nibbles.size) {
                val low = nibbles[i]
                val high = if (i + 1 < nibbles.size) nibbles[i + 1] else 0
                data[i / 2] = ((high shl 4) or low).toByte()
                i += 2
            }

            return preamble + data
        }
    }

    /**
     * Verpackt einen oder mehrere ADPCM-Blöcke in BcMedia-Frames für cmd_id 202.
     * Frame-Layout (hardware-validiert):
     *   0x00  4  Magic "01wb" (LE)
     *   0x04  2  payload_size (LE) = len(block) + 4
     *   0x06  2  payload_size (Duplikat)
     *   0x08  2  Sub-Magic 0x0100 (LE)
     *   0x0A  2  half_block = 2 (konstant)
     *   0x0C  N  roher ADPCM-Block (Preamble + Nibble-Daten)
     *   ...   P  Null-Padding auf 8-Byte-Grenze
     */
    fun buildBcMediaFrames(blocks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (block in blocks) {
            val payloadSize = block.size + 4
            val padSize = (8 - payloadSize % 8) % 8

            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(BC_MEDIA_ADPCM_MAGIC)
                .putShort(payloadSize.toShort())
                .putShort(payloadSize.toShort())
                .putShort(0x0100.toShort())
                .putShort(2)
                .array()

            out.write(header)
            out.write(block)
            repeat(padSize) { out.write(0) }
        }
        return out.toByteArray()
    }
}
