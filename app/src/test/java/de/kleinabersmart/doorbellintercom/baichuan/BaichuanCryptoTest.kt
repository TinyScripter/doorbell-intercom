package de.kleinabersmart.doorbellintercom.baichuan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Referenzwerte unabhängig von der Kotlin-Implementierung erzeugt: AES mit dem Python-Paket
 * cryptography (modes.CFB = 128-Bit-Segmente, wie PyCryptodome mit segment_size=128 in
 * reolink_aio), MD5 mit hashlib, XOR nach reolink_aio/baichuan/util.py.
 */
class BaichuanCryptoTest {

    @Test
    fun md5StrModern_truncatesTo31UpperCaseHexChars() {
        // md5("admin") = 21232f297a57a5a743894a0e4a801fc3
        assertEquals("21232F297A57A5A743894A0E4A801FC", BaichuanCrypto.md5StrModern("admin"))
    }

    @Test
    fun deriveAesKey_takesFirst16CharsOfMd5OfNonceAndPassword() {
        assertEquals("C418FDD04F9470E6", String(BaichuanCrypto.deriveAesKey("ABCDEF0123", "secret")))
    }

    @Test
    fun aesEncrypt_matchesCfb128Reference() {
        val key = BaichuanCrypto.deriveAesKey("ABCDEF0123", "secret")
        assertEquals(AES_CIPHERTEXT, BaichuanCrypto.aesEncrypt(key, AES_PLAINTEXT.toByteArray()).toHex())
    }

    @Test
    fun aesDecrypt_reversesEncrypt() {
        val key = BaichuanCrypto.deriveAesKey("ABCDEF0123", "secret")
        assertEquals(AES_PLAINTEXT, String(BaichuanCrypto.aesDecrypt(key, AES_CIPHERTEXT.hexToBytes())))
    }

    @Test
    fun aes_emptyInputStaysEmpty() {
        val key = BaichuanCrypto.deriveAesKey("n", "p")
        assertEquals(0, BaichuanCrypto.aesEncrypt(key, ByteArray(0)).size)
        assertEquals(0, BaichuanCrypto.aesDecrypt(key, ByteArray(0)).size)
    }

    @Test
    fun encryptBaichuan_matchesReference() {
        assertEquals("fa8ed8feee2593b2b4c2c9fcec", BaichuanCrypto.encryptBaichuan("<?xml version".toByteArray(), 250).toHex())
    }

    @Test
    fun decryptBaichuan_reversesEncrypt() {
        val encrypted = BaichuanCrypto.encryptBaichuan("<?xml version".toByteArray(), 250)
        assertEquals("<?xml version", BaichuanCrypto.decryptBaichuan(encrypted, 250))
    }

    @Test
    fun decryptBaichuan_normalizesNegativeOffset() {
        val encrypted = BaichuanCrypto.encryptBaichuan(byteArrayOf(1, 2, 3), 250)
        assertArrayEquals(byteArrayOf(1, 2, 3), BaichuanCrypto.decryptBaichuan(encrypted, 250 - 256).toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptBaichuan_rejectsOffsetAbove255() {
        BaichuanCrypto.encryptBaichuan(byteArrayOf(1), 256)
    }

    private companion object {
        const val AES_PLAINTEXT = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>talk</body>\n"
        const val AES_CIPHERTEXT =
            "bcaa264358c1115320a58ed6a6fba975c08c078b4c933dec8ceda073e65764b26b16c04f7dfa4435bd5af5cd0578b70e3d7b8ec00332b80df4f6"
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
