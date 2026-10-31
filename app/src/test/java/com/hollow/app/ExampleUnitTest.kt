package com.hollow.app

import com.hollow.app.isolation.crypto.AesVaultCipher
import com.hollow.app.isolation.crypto.MasterKeyProvider
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.mount.FileKind
import com.hollow.app.isolation.mount.kindOf
import com.hollow.app.isolation.mount.parseListing
import com.hollow.app.isolation.mount.shellQuote
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The Android Keystore is not available on the JVM, so tests wrap keys with an in-memory key. */
private class SoftwareKeyProvider : MasterKeyProvider {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    override fun wrap(fileKey: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(fileKey)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }
}

class ExampleUnitTest {

    @Before
    fun useSoftwareKeys() {
        AesVaultCipher.setKeyProvider(SoftwareKeyProvider())
    }

    private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    @Test
    fun cipherRoundTripsText() {
        val plain = "IsoVault confidential storage content".toByteArray(Charsets.UTF_8)
        val encrypted = AesVaultCipher.encrypt(plain)

        assertNotEquals(plain.toList(), encrypted.toList())
        assertTrue(encrypted.size > plain.size + 16)
        assertArrayEquals(plain, AesVaultCipher.decrypt(encrypted))
    }

    @Test
    fun cipherRoundTripsEmptyAndMultiChunkInputs() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 131072, 300_000)) {
            val plain = randomBytes(size)
            assertArrayEquals("size $size", plain, AesVaultCipher.decrypt(AesVaultCipher.encrypt(plain)))
        }
    }

    @Test
    fun encryptingTwiceGivesDifferentCiphertext() {
        val plain = randomBytes(1000)
        assertFalse(AesVaultCipher.encrypt(plain).contentEquals(AesVaultCipher.encrypt(plain)))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val encrypted = AesVaultCipher.encrypt(randomBytes(200_000))
        encrypted[encrypted.size / 2] = (encrypted[encrypted.size / 2].toInt() xor 0x01).toByte()
        assertDecryptFails(encrypted)
    }

    @Test
    fun truncatedCiphertextIsRejected() {
        val encrypted = AesVaultCipher.encrypt(randomBytes(200_000))
        // Cut exactly on a chunk boundary: only the final-chunk marker can catch this.
        val header = encrypted.size - (200_000 + 4 * 16)
        assertDecryptFails(encrypted.copyOf(header + 2 * (64 * 1024 + 16)))
        assertDecryptFails(encrypted.copyOf(encrypted.size - 5))
    }

    @Test
    fun foreignDataIsRejected() {
        assertDecryptFails("definitely not an isovault file".toByteArray())
    }

    @Test
    fun pathsCannotEscapeTheSandbox() {
        assertEquals("DCIM/a.jpg", IsolationRuleManager.extractRelative("/storage/emulated/0/DCIM/a.jpg"))
        assertEquals("Download/b.pdf", IsolationRuleManager.extractRelative("/sdcard/Download/b.pdf"))
        assertEquals("", IsolationRuleManager.extractRelative("/sdcard"))
        assertEquals("data/data/victim", IsolationRuleManager.extractRelative("/sdcard/../../data/data/victim"))
        assertEquals("a/c", IsolationRuleManager.sanitizeRelative("a/./b/../c"))
        assertEquals("", IsolationRuleManager.sanitizeRelative("../../.."))
    }

    @Test
    fun rootPollutionFoldersAreRecognised() {
        assertTrue(IsolationRuleManager.isRootPollution("/sdcard/.tencent/ad_tracker"))
        assertFalse(IsolationRuleManager.isRootPollution("/sdcard/DCIM/Camera/photo.jpg"))
    }

    @Test
    fun shellQuoteKeepsHostileNamesInsideOneWord() {
        assertEquals("'plain.jpg'", shellQuote("plain.jpg"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'a; rm -rf /'", shellQuote("a; rm -rf /"))
    }

    @Test
    fun listingParserOnlyTrustsLinesWithTheNonce() {
        val n = "abc123"
        val output = listOf(
            "$n|directory|0|1700000000|Pictures",
            "$n|regular file|2048|1700000100|b.jpg",
            "$n|regular empty file|0|1700000150|empty.txt",
            "$n|regular file|10|1700000200|a|b.txt",
            "$n|symbolic link|7|1700000300|evil",
            "$n|fifo|0|1700000310|pipe",
            "$n|socket|0|1700000320|sock",
            "$n|regular file|0|1700000400|.isovault_volume",
            "$n|regular file|abc|1700000500|badsize",
            "$n|regular file|1|1700000600|b.jpg",
            // a file whose NAME contains a newline and a forged line, without knowing the nonce:
            "$n|regular file|99999|1700000700|x",
            "regular file|1|0|big.jpg",
            "this line is not a listing entry"
        ).joinToString("\n")

        val entries = parseListing(output, "DCIM", n)
        assertEquals(listOf("Pictures", "a|b.txt", "b.jpg", "empty.txt", "x"), entries.map { it.name })
        assertEquals("DCIM/Pictures", entries[0].relPath)
        assertTrue(entries[0].isDir)
        assertEquals(2048L, entries.first { it.name == "b.jpg" }.size) // the duplicate b.jpg is ignored
        assertFalse(entries.any { it.name == "big.jpg" })
    }

    @Test
    fun fileKindsFollowExtensions() {
        assertEquals(FileKind.IMAGE, kindOf("IMG_1.JPG"))
        assertEquals(FileKind.VIDEO, kindOf("clip.mp4"))
        assertEquals(FileKind.TEXT, kindOf("notes.txt"))
        assertEquals(FileKind.OTHER, kindOf("archive.bin"))
        assertEquals(FileKind.OTHER, kindOf("noextension"))
    }

    private fun assertDecryptFails(data: ByteArray) {
        try {
            AesVaultCipher.decrypt(data)
            fail("decrypt should have failed")
        } catch (_: IOException) {
            // expected
        }
    }
}
