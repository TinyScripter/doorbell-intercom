package de.kleinabersmart.doorbellintercom.baichuan

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Krypto-Primitiven des Reolink-Baichuan-Protokolls, portiert aus reolink_aio
 * (github.com/starkillerOG/reolink_aio, reolink_aio/baichuan/util.py + baichuan.py).
 * Byte-für-Byte an der Referenzimplementierung ausgerichtet, nicht selbst entworfen.
 *
 * Quelle/Lizenz: reolink_aio (MIT), siehe THIRD_PARTY_NOTICES.md.
 */
object BaichuanCrypto {

    const val HEADER_MAGIC = "f0debc0a"
    const val DEFAULT_PORT = 9000

    private val XML_KEY = intArrayOf(0x1F, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78, 0xFF)
    private val AES_IV = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

    /** Einfache XOR-Verschlüsselung ("BC"-Encoding), u. a. für die Nonce-Anfrage. */
    fun encryptBaichuan(buf: ByteArray, offset: Int): ByteArray {
        require(offset in 0..255) { "Baichuan encryption offset $offset must be 0..255" }
        val out = ByteArray(buf.size)
        for (idx in buf.indices) {
            val key = XML_KEY[(offset + idx) % XML_KEY.size]
            val b = buf[idx].toInt() and 0xFF
            out[idx] = (b xor key xor offset).toByte()
        }
        return out
    }

    fun decryptBaichuan(buf: ByteArray, offset: Int): String {
        // Symmetrisch identisch zur Verschlüsselung (XOR ist selbstinvers).
        val off = ((offset % 256) + 256) % 256
        return String(encryptBaichuan(buf, off), Charsets.UTF_8)
    }

    /** MD5-Hex-Digest, auf 31 Zeichen gekürzt und großgeschrieben (Baichuan-Eigenheit). */
    fun md5StrModern(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 31).uppercase()
    }

    // Explizit "CFB128" statt nur "CFB": ohne Segmentgröße ist das Verhalten
    // provider-abhängig (nicht garantiert 128 Bit), die Referenz (PyCryptodome)
    // nutzt explizit segment_size=128 – muss exakt übereinstimmen, sonst lässt
    // sich nichts entschlüsseln/verschlüsseln, das die Kamera versteht.
    private const val AES_TRANSFORMATION = "AES/CFB128/NoPadding"

    /** AES-128-CFB128 (volles 128-Bit-Feedback-Segment), fester IV, wie von der Kamera erwartet. */
    fun aesEncrypt(key: ByteArray, body: ByteArray): ByteArray {
        if (body.isEmpty()) return ByteArray(0)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
        return cipher.doFinal(body)
    }

    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
        return cipher.doFinal(data)
    }

    /** AES-Schlüssel aus Nonce+Passwort ableiten: erste 16 Zeichen des MD5-Hashes als Bytes. */
    fun deriveAesKey(nonce: String, password: String): ByteArray {
        return md5StrModern("$nonce-$password").substring(0, 16).toByteArray(Charsets.UTF_8)
    }
}
