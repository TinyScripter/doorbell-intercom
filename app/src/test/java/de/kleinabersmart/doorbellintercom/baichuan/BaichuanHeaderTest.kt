package de.kleinabersmart.doorbellintercom.baichuan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BaichuanHeaderTest {

    @Test
    fun buildHeader24_layout() {
        val header = BaichuanTalkClient.buildHeader24(cmdId = 201, chId = 1, counter = 0x030201, bodyLen = 300, payloadOffset = 120)
        assertEquals(
            "f0debc0a" + // Magic
                "c9000000" + // cmd_id 201
                "2c010000" + // body_len 300
                "01" + "010203" + // ch_id, Zähler (24 Bit LE)
                "0000" + // status_code beim Senden
                "1464" + // message_class (24-Byte-Header)
                "78000000", // payload_offset 120
            header.toHex()
        )
    }

    @Test
    fun buildHeader20Legacy_layout() {
        val header = BaichuanTalkClient.buildHeader20Legacy(cmdId = 1, chId = 250, counter = 1, bodyLen = 0)
        assertEquals("f0debc0a" + "01000000" + "00000000" + "fa" + "010000" + "12dc" + "1465", header.toHex())
    }

    @Test
    fun readHeader_parsesWhatBuildHeader24Writes() {
        val bytes = BaichuanTalkClient.buildHeader24(cmdId = 202, chId = 2, counter = 7, bodyLen = 64, payloadOffset = 16)
        val header = BaichuanTalkClient.readHeader(ByteArrayInputStream(bytes))
        assertEquals(BaichuanTalkClient.BcHeader(202, 2, 24, 64, 0, "0000"), header)
    }

    @Test
    fun readHeader_parses20ByteResponseWithEncType() {
        val header = BaichuanTalkClient.readHeader(ByteArrayInputStream(response(messageClass = "1466", statusOrEnc = "01dd")))
        assertEquals(20, header.headerLen)
        assertEquals("01dd", header.encTypeHex)
        assertEquals(250, header.chId)
    }

    @Test
    fun readHeader_readsStatusCodeLittleEndian() {
        val bytes = response(messageClass = "0000", statusOrEnc = "c800") + ByteArray(4)
        assertEquals(200, BaichuanTalkClient.readHeader(ByteArrayInputStream(bytes)).statusCode)
    }

    @Test
    fun readHeader_rejectsWrongMagic() {
        val bytes = response().also { it[0] = 0 }
        assertThrows(IOException::class.java) { BaichuanTalkClient.readHeader(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun readHeader_rejectsUnknownMessageClass() {
        assertThrows(IOException::class.java) {
            BaichuanTalkClient.readHeader(ByteArrayInputStream(response(messageClass = "abcd")))
        }
    }

    @Test
    fun readHeader_rejectsNegativeOrHugeBodyLength() {
        for (bodyLen in listOf(-1, BaichuanTalkClient.MAX_BODY_LEN + 1)) {
            assertThrows(IOException::class.java) {
                BaichuanTalkClient.readHeader(ByteArrayInputStream(response(bodyLen = bodyLen)))
            }
        }
    }

    @Test
    fun readHeader_failsOnTruncatedStream() {
        val bytes = response().copyOf(10)
        assertThrows(IOException::class.java) { BaichuanTalkClient.readHeader(ByteArrayInputStream(bytes)) }
    }

    /** 20-Byte-Antwort; bei message_class 1464/0000 folgen noch 4 Byte payload_offset. */
    private fun response(messageClass: String = "1466", statusOrEnc: String = "0000", bodyLen: Int = 10): ByteArray =
        "f0debc0a".hexToBytes() +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(bodyLen).array() +
            "fa000000".hexToBytes() + statusOrEnc.hexToBytes() + messageClass.hexToBytes()
}
