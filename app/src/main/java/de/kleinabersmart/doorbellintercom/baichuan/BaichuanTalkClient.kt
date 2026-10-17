package de.kleinabersmart.doorbellintercom.baichuan

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direkter Baichuan-TCP-Client für den Reolink-Zwei-Wege-Audio-Rückkanal
 * (Gegensprechen). Läuft direkt gegen die Kamera, ohne Umweg über einen
 * Streaming-Server.
 *
 * Portiert aus reolink_aio (github.com/starkillerOG/reolink_aio), insbesondere
 * PR #165 (fredrsat/reolink_aio, Branch add-two-way-audio) für den Talk-Teil.
 * Alle Wire-Format-Details (Header-Layout, Verschlüsselung, XML-Templates,
 * Command-IDs) sind 1:1 an dieser Referenzimplementierung ausgerichtet.
 * Quelle/Lizenz: reolink_aio (MIT), siehe THIRD_PARTY_NOTICES.md.
 *
 * Blockierendes Socket-I/O – muss auf einem Hintergrund-Thread/Dispatcher
 * aufgerufen werden, niemals auf dem Main-Thread.
 */
class BaichuanTalkClient(
    private val host: String,
    private val port: Int = BaichuanCrypto.DEFAULT_PORT,
    private val username: String,
    private val password: String
) {
    enum class EncType { BC, AES }

    class BaichuanApiError(val statusCode: Int, cmdId: Int) :
        IOException("Baichuan: Fehlercode $statusCode für Kommando $cmdId")

    class BaichuanAuthError(message: String, cause: Throwable? = null) : IOException(message, cause)

    data class TalkAbility(
        val duplex: String,
        val audioStreamMode: String,
        val audioType: String,
        val sampleRate: Int,
        val samplePrecision: Int,
        val lengthPerEncoder: Int,
        val soundTrack: String
    )

    private data class BcHeader(
        val cmdId: Int,
        val chId: Int,
        val headerLen: Int,
        val bodyLen: Int,
        val statusCode: Int,
        val encTypeHex: String
    )

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var aesKey: ByteArray? = null
    private var messageCounter: Int = 0

    companion object {
        private const val TAG = "BaichuanTalkClient"
    }

    fun connect(timeoutMs: Int = 5000) {
        Log.d(TAG, "Verbinde zu $host:$port ...")
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 8000
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        Log.d(TAG, "TCP-Verbindung zu $host:$port steht")
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Egal, wir wollten die Verbindung ohnehin schließen.
        }
        socket = null
        input = null
        output = null
        aesKey = null
    }

    /** Voller Login-Handshake: Nonce holen, AES-Schlüssel ableiten, mit Hash-Zugangsdaten anmelden. */
    fun login() {
        val nonceXml = requestNonce()
        val nonce = xmlValue(nonceXml, "nonce")
            ?: throw IOException("Baichuan: keine Nonce in der Antwort gefunden (Rohantwort: ${nonceXml.take(200)})")
        Log.d(TAG, "Nonce erhalten: $nonce")
        aesKey = BaichuanCrypto.deriveAesKey(nonce, password)

        val userHash = BaichuanCrypto.md5StrModern("$username$nonce")
        val passwordHash = BaichuanCrypto.md5StrModern("$password$nonce")
        val xml = loginXml(userHash, passwordHash)
        try {
            sendAndReceive(cmdId = 1, channel = null, body = xml, encType = EncType.BC)
            Log.d(TAG, "Login erfolgreich für Benutzer \"$username\"")
        } catch (e: BaichuanApiError) {
            if (e.statusCode == 401) {
                throw BaichuanAuthError("Baichuan: Zugangsdaten abgelehnt (Benutzername/Passwort prüfen)", e)
            }
            throw e
        }
    }

    fun getTalkAbility(channel: Int): TalkAbility {
        val xml = sendAndReceive(cmdId = 10, channel = channel, body = "", encType = EncType.AES)
        val ability = TalkAbility(
            duplex = xmlValue(xml, "duplex") ?: "FDX",
            audioStreamMode = xmlValue(xml, "audioStreamMode") ?: "followVideoStream",
            audioType = xmlValue(xml, "audioType") ?: "adpcm",
            sampleRate = xmlValue(xml, "sampleRate")?.toIntOrNull() ?: 8000,
            samplePrecision = xmlValue(xml, "samplePrecision")?.toIntOrNull() ?: 16,
            lengthPerEncoder = xmlValue(xml, "lengthPerEncoder")?.toIntOrNull() ?: 320,
            soundTrack = xmlValue(xml, "soundTrack") ?: "mono"
        )
        Log.d(TAG, "TalkAbility Kanal $channel: $ability (Rohantwort: ${xml.take(400)})")
        return ability
    }

    /** Startet die Talk-Session (TalkConfig). Bei "Session belegt" (422) einmal zurücksetzen und erneut versuchen. */
    fun startTalk(channel: Int, ability: TalkAbility) {
        val xml = talkConfigSetXml(channel, ability)
        try {
            sendAndReceive(cmdId = 201, channel = channel, body = xml, encType = EncType.AES)
            Log.d(TAG, "TalkConfig (cmd 201) für Kanal $channel erfolgreich gestartet")
        } catch (e: BaichuanApiError) {
            if (e.statusCode == 422 || e.statusCode == 400 || e.statusCode == 421) {
                Log.w(TAG, "TalkConfig meldet Status ${e.statusCode} (Session belegt) – setze zurück und versuche erneut")
                try {
                    sendAndReceive(cmdId = 11, channel = channel, body = "", encType = EncType.AES)
                } catch (_: Exception) {
                    // Reset-Fehler ist hier egal, wir versuchen ohnehin gleich neu.
                }
                Thread.sleep(500)
                sendAndReceive(cmdId = 201, channel = channel, body = xml, encType = EncType.AES)
                Log.d(TAG, "TalkConfig (cmd 201) für Kanal $channel nach Reset erfolgreich gestartet")
            } else {
                throw e
            }
        }
    }

    /** Beendet die Talk-Session (TalkReset). Fehler hier werden verschluckt, da wir eh schließen. */
    fun stopTalk(channel: Int) {
        try {
            sendAndReceive(cmdId = 11, channel = channel, body = "", encType = EncType.AES)
            Log.d(TAG, "TalkReset (cmd 11) für Kanal $channel gesendet")
        } catch (e: Exception) {
            Log.w(TAG, "TalkReset (cmd 11) fehlgeschlagen: ${e.message}")
        }
    }

    private var framesSent = 0

    /** Sendet bereits ADPCM-kodierte, BcMedia-verpackte Audio-Blöcke (cmd_id 202, ohne Antwort). */
    fun sendTalkAudio(channel: Int, blocks: List<ByteArray>) {
        if (blocks.isEmpty()) return
        val payload = BaichuanAdpcm.buildBcMediaFrames(blocks)
        sendBinaryNoReply(cmdId = 202, channel = channel, binaryBody = payload)
        framesSent += blocks.size
        if (framesSent <= 5 || framesSent % 50 == 0) {
            Log.d(TAG, "Audio-Frame(s) gesendet: ${blocks.size} Block(e), ${payload.size} Bytes, insgesamt $framesSent Blöcke")
        }
    }

    // ---- Wire-Protokoll ----

    private fun nextCounter(): Int {
        messageCounter = (messageCounter + 1) % 16777216
        return messageCounter
    }

    private fun requestNonce(): String {
        val out = output ?: throw IOException("Baichuan: nicht verbunden")
        val chId = 250
        val counter = nextCounter()
        val header = buildHeader20Legacy(cmdId = 1, chId = chId, counter = counter, bodyLen = 0)
        out.write(header)
        out.flush()

        val resp = readHeader()
        val payload = readFully(resp.bodyLen)
        return decryptResponseBody(resp, sentEncType = EncType.BC, encBody = payload)
    }

    private fun sendAndReceive(cmdId: Int, channel: Int?, body: String, encType: EncType): String {
        val out = output ?: throw IOException("Baichuan: nicht verbunden")
        val chId = if (channel == null) 250 else channel + 1
        val ext = if (channel != null) channelExtensionXml(channel) else ""
        val extBytes = ext.toByteArray(Charsets.UTF_8)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val bodyLen = extBytes.size + bodyBytes.size
        val counter = nextCounter()

        val encExt: ByteArray
        val encBody: ByteArray
        if (bodyLen > 0) {
            when (encType) {
                EncType.BC -> {
                    encExt = BaichuanCrypto.encryptBaichuan(extBytes, chId)
                    encBody = BaichuanCrypto.encryptBaichuan(bodyBytes, chId)
                }
                EncType.AES -> {
                    val key = aesKey ?: throw IOException("Baichuan: kein AES-Schlüssel, zuerst login() aufrufen")
                    encExt = BaichuanCrypto.aesEncrypt(key, extBytes)
                    encBody = BaichuanCrypto.aesEncrypt(key, bodyBytes)
                }
            }
        } else {
            encExt = ByteArray(0)
            encBody = ByteArray(0)
        }

        val header = buildHeader24(cmdId, chId, counter, encExt.size + encBody.size, encExt.size)
        out.write(header)
        out.write(encExt)
        out.write(encBody)
        out.flush()

        val resp = readHeader()
        val payload = readFully(resp.bodyLen)
        Log.d(TAG, "cmd_id $cmdId gesendet, Antwort: cmd_id=${resp.cmdId} status=${resp.statusCode} headerLen=${resp.headerLen} bodyLen=${resp.bodyLen}")

        if (resp.headerLen == 24 && resp.statusCode !in intArrayOf(200, 201, 300)) {
            throw BaichuanApiError(resp.statusCode, cmdId)
        }

        return decryptResponseBody(resp, sentEncType = encType, encBody = payload)
    }

    private fun sendBinaryNoReply(cmdId: Int, channel: Int, binaryBody: ByteArray) {
        val out = output ?: throw IOException("Baichuan: nicht verbunden")
        val key = aesKey ?: throw IOException("Baichuan: kein AES-Schlüssel, zuerst login() aufrufen")
        val chId = channel + 1
        val extBytes = binaryExtensionXml(channel).toByteArray(Charsets.UTF_8)
        val encExt = BaichuanCrypto.aesEncrypt(key, extBytes)
        val counter = nextCounter()
        val header = buildHeader24(cmdId, chId, counter, encExt.size + binaryBody.size, encExt.size)
        out.write(header)
        out.write(encExt)
        out.write(binaryBody)
        out.flush()
        // Fire-and-forget: die Kamera sendet für cmd_id 202 keine Antwort.
    }

    private fun readHeader(): BcHeader {
        val inp = input ?: throw IOException("Baichuan: nicht verbunden")
        val magic = readFully(4, inp)
        val magicHex = magic.joinToString("") { "%02x".format(it) }
        if (magicHex != BaichuanCrypto.HEADER_MAGIC) {
            throw IOException("Baichuan: ungültiger Magic-Header $magicHex")
        }

        val rest = readFully(16, inp)
        val buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
        val cmdId = buf.int
        val bodyLenField = buf.int
        val messIdBytes = ByteArray(4)
        buf.get(messIdBytes)
        val chId = messIdBytes[0].toInt() and 0xFF
        val statusOrEncBytes = ByteArray(2)
        buf.get(statusOrEncBytes)
        val classBytes = ByteArray(2)
        buf.get(classBytes)
        val messageClassHex = classBytes.joinToString("") { "%02x".format(it) }

        val headerLen: Int
        when (messageClassHex) {
            "1466" -> headerLen = 20
            "1464", "0000" -> {
                headerLen = 24
                readFully(4, inp) // payload_offset – für uns nicht weiter relevant
            }
            else -> throw IOException("Baichuan: nicht unterstützte message_class $messageClassHex")
        }

        val statusCode = (statusOrEncBytes[0].toInt() and 0xFF) or ((statusOrEncBytes[1].toInt() and 0xFF) shl 8)
        val encTypeHex = statusOrEncBytes.joinToString("") { "%02x".format(it) }

        return BcHeader(cmdId, chId, headerLen, bodyLenField, statusCode, encTypeHex)
    }

    private fun decryptResponseBody(header: BcHeader, sentEncType: EncType, encBody: ByteArray): String {
        if (encBody.isEmpty()) return ""

        var text = tryDecrypt(header, sentEncType, encBody)
        if (!text.startsWith("<?xml")) {
            if (header.encTypeHex == "00dd") {
                text = String(encBody, Charsets.UTF_8)
            }
            if (!text.startsWith("<?xml") && sentEncType != EncType.BC) {
                text = BaichuanCrypto.decryptBaichuan(encBody, header.chId)
            }
            if (!text.startsWith("<?xml")) {
                throw IOException("Baichuan: Antwort konnte nicht entschlüsselt werden (cmd_id ${header.cmdId})")
            }
        }
        return text
    }

    private fun tryDecrypt(header: BcHeader, sentEncType: EncType, encBody: ByteArray): String {
        val isBcByType = header.encTypeHex == "01dd" || header.encTypeHex == "12dd"
        val isAesByType = header.encTypeHex == "02dd" || header.encTypeHex == "03dd"
        return when {
            (header.headerLen == 20 && isBcByType) || sentEncType == EncType.BC ->
                BaichuanCrypto.decryptBaichuan(encBody, header.chId)
            (header.headerLen == 20 && isAesByType) || (header.headerLen == 24 && sentEncType == EncType.AES) -> {
                val key = aesKey ?: return ""
                try {
                    String(BaichuanCrypto.aesDecrypt(key, encBody), Charsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }
            }
            header.encTypeHex == "00dd" -> String(encBody, Charsets.UTF_8)
            else -> ""
        }
    }

    private fun readFully(n: Int, inp: InputStream = input ?: throw IOException("Baichuan: nicht verbunden")): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = inp.read(buf, off, n - off)
            if (read == -1) throw IOException("Baichuan: Verbindung geschlossen (nur $off von $n Bytes gelesen)")
            off += read
        }
        return buf
    }

    private fun buildHeader24(cmdId: Int, chId: Int, counter: Int, bodyLen: Int, payloadOffset: Int): ByteArray {
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(hexToBytes(BaichuanCrypto.HEADER_MAGIC))
        bb.putInt(cmdId)
        bb.putInt(bodyLen)
        bb.put(chId.toByte())
        bb.put((counter and 0xFF).toByte())
        bb.put(((counter shr 8) and 0xFF).toByte())
        bb.put(((counter shr 16) and 0xFF).toByte())
        bb.putShort(0) // status_code = 0000 beim Senden
        bb.put(hexToBytes("1464"))
        bb.putInt(payloadOffset)
        return bb.array()
    }

    private fun buildHeader20Legacy(cmdId: Int, chId: Int, counter: Int, bodyLen: Int): ByteArray {
        val bb = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(hexToBytes(BaichuanCrypto.HEADER_MAGIC))
        bb.putInt(cmdId)
        bb.putInt(bodyLen)
        bb.put(chId.toByte())
        bb.put((counter and 0xFF).toByte())
        bb.put(((counter shr 8) and 0xFF).toByte())
        bb.put(((counter shr 16) and 0xFF).toByte())
        bb.put(hexToBytes("12dc"))
        bb.put(hexToBytes("1465"))
        return bb.array()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    private fun xmlValue(xml: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)

    // ---- XML-Templates (1:1 aus reolink_aio/baichuan/xmls.py) ----

    private fun loginXml(userHash: String, passwordHash: String) = """<?xml version="1.0" encoding="UTF-8" ?>
<body>
<LoginUser version="1.1">
<userName>$userHash</userName>
<password>$passwordHash</password>
<userVer>1</userVer>
</LoginUser>
<LoginNet version="1.1">
<type>LAN</type>
<udpPort>0</udpPort>
</LoginNet>
</body>
"""

    private fun channelExtensionXml(channel: Int) = """<?xml version="1.0" encoding="UTF-8" ?>
<Extension version="1.1">
<channelId>$channel</channelId>
</Extension>
"""

    private fun binaryExtensionXml(channel: Int) = """<?xml version="1.0" encoding="UTF-8" ?>
<Extension version="1.1">
<channelId>$channel</channelId>
<binaryData>1</binaryData>
</Extension>
"""

    private fun talkConfigSetXml(channel: Int, ability: TalkAbility) = """<?xml version="1.0" encoding="UTF-8" ?>
<body>
<TalkConfig version="1.1">
<channelId>$channel</channelId>
<duplex>${ability.duplex}</duplex>
<audioStreamMode>${ability.audioStreamMode}</audioStreamMode>
<audioConfig>
<audioType>${ability.audioType}</audioType>
<sampleRate>${ability.sampleRate}</sampleRate>
<samplePrecision>${ability.samplePrecision}</samplePrecision>
<lengthPerEncoder>${ability.lengthPerEncoder}</lengthPerEncoder>
<soundTrack>${ability.soundTrack}</soundTrack>
</audioConfig>
</TalkConfig>
</body>
"""
}
