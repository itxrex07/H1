package com.hollow.app.isolation.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps and unwraps the random per-file data keys. Production uses the Android Keystore, so the
 * key that protects every file never leaves secure hardware / the Keystore daemon.
 */
interface MasterKeyProvider {
    fun wrap(fileKey: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

class KeystoreMasterKeyProvider(private val alias: String = DEFAULT_ALIAS) : MasterKeyProvider {

    companion object {
        const val DEFAULT_ALIAS = "isovault_master_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun wrap(fileKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        return iv + cipher.doFinal(fileKey)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size <= IV_LENGTH) throw GeneralSecurityException("Wrapped key is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            masterKey(),
            GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, IV_LENGTH))
        )
        return cipher.doFinal(wrapped, IV_LENGTH, wrapped.size - IV_LENGTH)
    }
}

/**
 * Streaming AES-256-GCM file encryption with envelope keys.
 *
 * Container layout (version 2):
 *
 *   "ISOV" 0x02 | wrappedKeyLength (u16) | wrappedKey | noncePrefix (8 bytes) | chunk*
 *
 * Each plaintext chunk (64 KiB, the last one may be shorter or empty) is sealed independently
 * with nonce = noncePrefix || chunkIndex (u32) and a one byte AAD that marks the final chunk.
 * Reordering, dropping or truncating chunks makes authentication fail, and memory use is
 * bounded by the chunk size, not by the file size.
 */
object AesVaultCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8
    private const val NONCE_PREFIX_BYTES = 8
    private const val CHUNK_BYTES = 64 * 1024
    private const val MAX_WRAPPED_KEY_BYTES = 512

    private val MAGIC_PREFIX = byteArrayOf(0x49, 0x53, 0x4F, 0x56) // "ISOV"
    private const val VERSION: Byte = 0x02
    private const val HEADER_BYTES = 5

    private val secureRandom = SecureRandom()

    @Volatile
    private var provider: MasterKeyProvider? = null

    /** Replaces the key provider. Intended for tests, where the Android Keystore is unavailable. */
    fun setKeyProvider(newProvider: MasterKeyProvider?) {
        provider = newProvider
    }

    private fun keyProvider(): MasterKeyProvider {
        provider?.let { return it }
        return synchronized(this) {
            provider ?: KeystoreMasterKeyProvider().also { provider = it }
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plaintext.size + 128)
        encryptStream(ByteArrayInputStream(plaintext), out)
        return out.toByteArray()
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(encrypted.size)
        decryptStream(ByteArrayInputStream(encrypted), out)
        return out.toByteArray()
    }

    /** Encrypts [input] into [output]. Neither stream is closed. Returns the plaintext length. */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        onProgress: ((bytesProcessed: Long) -> Unit)? = null
    ): Long {
        val fileKey = ByteArray(KEY_BYTES).also { secureRandom.nextBytes(it) }
        try {
            val wrapped = keyProvider().wrap(fileKey)
            if (wrapped.size > MAX_WRAPPED_KEY_BYTES) throw IOException("Wrapped key too large")
            val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also { secureRandom.nextBytes(it) }

            val data = DataOutputStream(output)
            data.write(MAGIC_PREFIX)
            data.writeByte(VERSION.toInt())
            data.writeShort(wrapped.size)
            data.write(wrapped)
            data.write(noncePrefix)

            val key = SecretKeySpec(fileKey, "AES")
            var current = ByteArray(CHUNK_BYTES)
            var currentLen = readFully(input, current)
            var next = ByteArray(CHUNK_BYTES)
            var index = 0L
            var total = 0L

            while (true) {
                val nextLen = if (currentLen == CHUNK_BYTES) readFully(input, next) else 0
                val isFinal = nextLen == 0
                data.write(sealChunk(key, noncePrefix, index, isFinal, current, currentLen))
                total += currentLen
                onProgress?.invoke(total)
                if (isFinal) break
                val swap = current
                current = next
                next = swap
                currentLen = nextLen
                index++
            }
            data.flush()
            return total
        } catch (e: GeneralSecurityException) {
            throw IOException("Encryption failed: ${e.message}", e)
        } finally {
            fileKey.fill(0)
        }
    }

    /** Decrypts [input] into [output]. Neither stream is closed. Returns the plaintext length. */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        onProgress: ((bytesProcessed: Long) -> Unit)? = null
    ): Long {
        val data = DataInputStream(input)
        var fileKey: ByteArray? = null
        try {
            val header = ByteArray(HEADER_BYTES)
            data.readFully(header)
            for (i in MAGIC_PREFIX.indices) {
                if (header[i] != MAGIC_PREFIX[i]) throw IOException("Not an IsoVault file")
            }
            if (header[4] != VERSION) {
                throw IOException("Unsupported IsoVault container version ${header[4]}")
            }

            val wrappedLen = data.readUnsignedShort()
            if (wrappedLen == 0 || wrappedLen > MAX_WRAPPED_KEY_BYTES) throw IOException("Corrupt key header")
            val wrapped = ByteArray(wrappedLen).also { data.readFully(it) }
            val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also { data.readFully(it) }

            fileKey = keyProvider().unwrap(wrapped)
            val key = SecretKeySpec(fileKey, "AES")

            var current = ByteArray(CHUNK_BYTES + TAG_BYTES)
            var currentLen = readFully(data, current)
            if (currentLen < TAG_BYTES) throw IOException("Truncated IsoVault file")
            var next = ByteArray(CHUNK_BYTES + TAG_BYTES)
            var index = 0L
            var total = 0L

            while (true) {
                val nextLen = if (currentLen == CHUNK_BYTES + TAG_BYTES) readFully(data, next) else 0
                val isFinal = nextLen == 0
                val plain = openChunk(key, noncePrefix, index, isFinal, current, currentLen)
                output.write(plain)
                total += plain.size
                onProgress?.invoke(total)
                if (isFinal) break
                if (nextLen < TAG_BYTES) throw IOException("Truncated IsoVault file")
                val swap = current
                current = next
                next = swap
                currentLen = nextLen
                index++
            }
            output.flush()
            return total
        } catch (e: EOFException) {
            throw IOException("Truncated IsoVault file", e)
        } catch (e: GeneralSecurityException) {
            throw IOException("Decryption failed: file is corrupt, was modified, or the key is gone", e)
        } finally {
            fileKey?.fill(0)
        }
    }

    /** Encrypts [source] to [destination] atomically; a failure never leaves a partial file. */
    fun encryptFile(
        source: File,
        destination: File,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val totalBytes = source.length()
        FileInputStream(source).use { input ->
            writeAtomically(destination) { out ->
                encryptStream(input, out) { processed ->
                    onProgress?.invoke(processed, totalBytes)
                }
            }
        }
    }

    /** Decrypts [source] to [destination] atomically; a failure never leaves a partial file. */
    fun decryptFile(
        source: File,
        destination: File,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val totalBytes = source.length()
        FileInputStream(source).use { input ->
            writeAtomically(destination) { out ->
                decryptStream(input, out) { processed ->
                    onProgress?.invoke(processed, totalBytes)
                }
            }
        }
    }

    fun isEncryptedIsoVaultFile(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_BYTES) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(HEADER_BYTES)
                fis.read(header) == HEADER_BYTES &&
                    header.copyOfRange(0, MAGIC_PREFIX.size).contentEquals(MAGIC_PREFIX) &&
                    header[4] == VERSION
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun writeAtomically(destination: File, block: (OutputStream) -> Unit) {
        destination.absoluteFile.parentFile?.mkdirs()
        val temp = File(destination.absoluteFile.parentFile, ".${destination.name}.part")
        try {
            FileOutputStream(temp).use { out ->
                block(out)
                out.fd.sync()
            }
            if (!temp.renameTo(destination)) {
                throw IOException("Could not move ${temp.name} into place")
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun sealChunk(
        key: SecretKey,
        noncePrefix: ByteArray,
        index: Long,
        isFinal: Boolean,
        buffer: ByteArray,
        length: Int
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)))
        cipher.updateAAD(byteArrayOf(if (isFinal) 1 else 0))
        return cipher.doFinal(buffer, 0, length)
    }

    private fun openChunk(
        key: SecretKey,
        noncePrefix: ByteArray,
        index: Long,
        isFinal: Boolean,
        buffer: ByteArray,
        length: Int
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)))
        cipher.updateAAD(byteArrayOf(if (isFinal) 1 else 0))
        return cipher.doFinal(buffer, 0, length)
    }

    private fun nonce(prefix: ByteArray, index: Long): ByteArray {
        if (index < 0 || index > 0xFFFF_FFFFL) throw GeneralSecurityException("File too large")
        val nonce = ByteArray(NONCE_PREFIX_BYTES + 4)
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_BYTES)
        nonce[8] = (index shr 24).toByte()
        nonce[9] = (index shr 16).toByte()
        nonce[10] = (index shr 8).toByte()
        nonce[11] = index.toByte()
        return nonce
    }

    /** Reads until [buffer] is full or the stream ends. Returns the number of bytes read. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }
}
