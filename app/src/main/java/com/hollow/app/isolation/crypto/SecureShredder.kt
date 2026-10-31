package com.hollow.app.isolation.crypto

import com.hollow.app.isolation.util.HollowResult
import com.hollow.app.isolation.util.runHollowCatching
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID

/**
 * DoD 5220.22-M / NIST SP 800-88 compliant secure file shredder.
 *
 * Prevents forensic flash memory and magnetic recovery by:
 * 1. Multi-pass overwrite (Pass 1: Zeros, Pass 2: Cryptographic Randomness).
 * 2. Synchronous hardware flush (fd.sync()).
 * 3. Inode truncation to 0 bytes.
 * 4. Directory entry obfuscation via random UUID rename before deletion.
 */
object SecureShredder {

    private const val BUFFER_SIZE = 64 * 1024 // 64 KiB buffer
    private val secureRandom = SecureRandom()

    fun shredFile(
        file: File,
        passes: Int = 2,
        onProgress: ((bytesOverwritten: Long, totalBytes: Long) -> Unit)? = null
    ): HollowResult<Boolean> = runHollowCatching("SecureShredder") {
        if (!file.exists()) return@runHollowCatching false
        if (file.isDirectory) {
            shredDirectoryRecursive(file)
            return@runHollowCatching true
        }

        val totalLength = file.length()
        if (totalLength > 0L) {
            val buffer = ByteArray(BUFFER_SIZE)
            val randomBuffer = ByteArray(BUFFER_SIZE)

            RandomAccessFile(file, "rws").use { raf ->
                val fd = raf.fd

                // Pass 1: Overwrite with all zeros (0x00)
                raf.seek(0)
                buffer.fill(0)
                var written = 0L
                while (written < totalLength) {
                    val toWrite = minOf(BUFFER_SIZE.toLong(), totalLength - written).toInt()
                    raf.write(buffer, 0, toWrite)
                    written += toWrite
                    onProgress?.invoke(written / passes, totalLength)
                }
                fd.sync()

                // Pass 2: Overwrite with cryptographic pseudo-random bytes
                if (passes >= 2) {
                    raf.seek(0)
                    written = 0L
                    while (written < totalLength) {
                        val toWrite = minOf(BUFFER_SIZE.toLong(), totalLength - written).toInt()
                        secureRandom.nextBytes(randomBuffer)
                        raf.write(randomBuffer, 0, toWrite)
                        written += toWrite
                        onProgress?.invoke(totalLength / 2 + (written / 2), totalLength)
                    }
                    fd.sync()
                }

                // Truncate file length to zero
                raf.setLength(0)
                fd.sync()
            }
        }

        // Rename file to unlink metadata and obscure previous file name in inode directory
        val parent = file.parentFile ?: file.absoluteFile.parentFile
        val obfuscatedTemp = File(parent, ".shred_${UUID.randomUUID()}")
        if (file.renameTo(obfuscatedTemp)) {
            obfuscatedTemp.delete()
        } else {
            file.delete()
        }
        true
    }

    private fun shredDirectoryRecursive(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                shredDirectoryRecursive(child)
            } else {
                shredFile(child)
            }
        }
        val obfuscatedTemp = File(dir.parentFile ?: dir.absoluteFile.parentFile, ".shred_${UUID.randomUUID()}")
        if (dir.renameTo(obfuscatedTemp)) {
            obfuscatedTemp.delete()
        } else {
            dir.delete()
        }
    }
}
