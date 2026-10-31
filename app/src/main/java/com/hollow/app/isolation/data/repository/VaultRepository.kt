package com.hollow.app.isolation.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.hollow.app.isolation.crypto.AesVaultCipher
import com.hollow.app.isolation.data.dao.IsolationDao
import com.hollow.app.isolation.data.entity.VaultFileItem
import com.hollow.app.isolation.util.HollowResult
import com.hollow.app.isolation.util.runHollowCatching
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

sealed interface VaultPreviewResult {
    data class TextPreview(val title: String, val content: String) : VaultPreviewResult
    data class ImagePreview(val title: String, val bitmap: Bitmap) : VaultPreviewResult
}

class VaultRepository(
    private val context: Context,
    private val dao: IsolationDao
) {
    companion object {
        private const val MAX_PREVIEW_BYTES = 2 * 1024 * 1024
        private const val MAX_IMAGE_PREVIEW_BYTES = 25 * 1024 * 1024
    }

    private val lockerPackage: String = context.packageName
    val lockerDir: File
        get() = File(context.filesDir, "locker").apply { mkdirs() }

    val vaultFiles: Flow<List<VaultFileItem>> = dao.getAllVaultFiles()
    val totalEncryptedBytes: Flow<Long> = dao.getTotalEncryptedBytes()

    suspend fun getAllVaultFilesSync(): List<VaultFileItem> =
        runHollowCatching("VaultRepository") { dao.getAllVaultFilesSync() }
            .getOrNull() ?: emptyList()

    suspend fun insertVaultFile(item: VaultFileItem): Long =
        dao.insertVaultFile(item)

    suspend fun deleteVaultFileById(id: Long) {
        dao.deleteVaultFile(id)
    }

    suspend fun deleteVaultFilesForPackage(packageName: String) {
        dao.deleteVaultFilesForPackage(packageName)
    }

    private suspend fun storeInLocker(
        displayName: String,
        mimeType: String,
        write: (OutputStream) -> Long
    ): VaultFileItem {
        val target = File(lockerDir, "${UUID.randomUUID()}.isovault")
        var plainSize = 0L
        try {
            FileOutputStream(target).use { out -> plainSize = write(out) }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        val item = VaultFileItem(
            packageName = lockerPackage,
            relativePath = displayName,
            physicalPath = target.absolutePath,
            isEncrypted = true,
            originalSizeBytes = plainSize,
            encryptedSizeBytes = target.length(),
            mimeType = mimeType,
            lastModified = System.currentTimeMillis()
        )
        dao.insertVaultFile(item)
        return item
    }

    suspend fun addNote(name: String, content: String): HollowResult<String> =
        runHollowCatching("VaultRepository") {
            val fileName = name.trim().substringAfterLast('/').ifBlank { "note" }
                .let { if (it.contains('.')) it else "$it.txt" }
            storeInLocker(fileName, "text/plain") { out ->
                AesVaultCipher.encryptStream(content.toByteArray(Charsets.UTF_8).inputStream(), out)
            }
            fileName
        }

    suspend fun importFile(
        uri: Uri,
        onProgress: ((progress: Float) -> Unit)? = null
    ): HollowResult<String> =
        runHollowCatching("VaultRepository") {
            val resolver = context.contentResolver
            val queriedName: String? = resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            val queriedSize: Long = try {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                } ?: 0L
            } catch (_: Throwable) { 0L }

            val name = queriedName?.takeIf { it.isNotBlank() } ?: "file_${System.currentTimeMillis()}"
            val mime = resolver.getType(uri) ?: inferMimeType(name)
            val input = resolver.openInputStream(uri)
                ?: throw IllegalStateException("File could not be opened")
            input.use { src ->
                storeInLocker(name, mime) { out ->
                    AesVaultCipher.encryptStream(src, out) { processed ->
                        if (queriedSize > 0L) {
                            onProgress?.invoke((processed.toFloat() / queriedSize).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress?.invoke(1f)
            name
        }

    suspend fun encryptLocalFileToLocker(
        sourceFile: File,
        deleteOriginal: Boolean = false,
        onProgress: ((progress: Float) -> Unit)? = null
    ): HollowResult<String> =
        runHollowCatching("VaultRepository") {
            if (!sourceFile.exists() || !sourceFile.isFile) {
                throw IllegalArgumentException("File not found.")
            }
            val name = sourceFile.name
            val mime = inferMimeType(name)
            val totalBytes = sourceFile.length()
            FileInputStream(sourceFile).use { src ->
                storeInLocker(name, mime) { out ->
                    AesVaultCipher.encryptStream(src, out) { processed ->
                        if (totalBytes > 0L) {
                            onProgress?.invoke((processed.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (deleteOriginal) {
                com.hollow.app.isolation.crypto.SecureShredder.shredFile(sourceFile)
            }
            onProgress?.invoke(1f)
            name
        }

    suspend fun exportFile(
        item: VaultFileItem,
        destination: Uri,
        onProgress: ((progress: Float) -> Unit)? = null
    ): HollowResult<String> =
        runHollowCatching("VaultRepository") {
            val out = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IllegalStateException("Destination could not be opened")
            val totalBytes = item.originalSizeBytes
            out.use { dst ->
                FileInputStream(File(item.physicalPath)).use { src ->
                    AesVaultCipher.decryptStream(src, dst) { processed ->
                        if (totalBytes > 0L) {
                            onProgress?.invoke((processed.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress?.invoke(1f)
            item.relativePath.substringAfterLast('/')
        }

    suspend fun decryptVaultFileToWorkspace(
        item: VaultFileItem,
        onProgress: ((progress: Float) -> Unit)? = null
    ): HollowResult<String> =
        runHollowCatching("VaultRepository") {
            val workspaceExports = File(context.filesDir, "workspace/Decrypted").apply { mkdirs() }
            val cleanName = item.relativePath.substringAfterLast('/').ifBlank { "decrypted_${item.id}" }
            val dest = File(workspaceExports, cleanName)
            val totalBytes = item.originalSizeBytes
            FileOutputStream(dest).use { dst ->
                FileInputStream(File(item.physicalPath)).use { src ->
                    AesVaultCipher.decryptStream(src, dst) { processed ->
                        if (totalBytes > 0L) {
                            onProgress?.invoke((processed.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress?.invoke(1f)
            cleanName
        }

    suspend fun deleteVaultFile(item: VaultFileItem, secureShred: Boolean = true): HollowResult<String> =
        runHollowCatching("VaultRepository") {
            val file = File(item.physicalPath)
            if (secureShred) {
                com.hollow.app.isolation.crypto.SecureShredder.shredFile(file)
            } else {
                file.delete()
            }
            dao.deleteVaultFile(item.id)
            item.relativePath.substringAfterLast('/')
        }

    suspend fun decryptAndPreviewFile(item: VaultFileItem): HollowResult<VaultPreviewResult> =
        runHollowCatching("VaultRepository") {
            val file = File(item.physicalPath)
            if (!file.exists()) {
                throw IllegalStateException("Locker file does not exist on disk.")
            }

            val ext = item.relativePath.substringAfterLast('.', "").lowercase()
            val isImage = item.mimeType.startsWith("image/") ||
                ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
            val isText = item.mimeType.startsWith("text/") ||
                ext in setOf("txt", "md", "json", "xml", "log", "csv", "sh", "properties", "ini")

            if (isImage) {
                if (item.originalSizeBytes > MAX_IMAGE_PREVIEW_BYTES) {
                    throw IllegalStateException("Image too large to preview (${item.originalSizeBytes / (1024 * 1024)} MB). Export it instead.")
                }
                val bytes = if (item.isEncrypted) {
                    val buffer = ByteArrayOutputStream()
                    FileInputStream(file).use { AesVaultCipher.decryptStream(it, buffer) }
                    buffer.toByteArray()
                } else {
                    file.readBytes()
                }

                // OOM-safe subsampling: decode image bounds first
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                val maxDimension = 1920
                var sampleSize = 1
                if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                    val halfHeight = options.outHeight / 2
                    val halfWidth = options.outWidth / 2
                    while ((halfHeight / sampleSize) >= maxDimension && (halfWidth / sampleSize) >= maxDimension) {
                        sampleSize *= 2
                    }
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565 // Half memory footprint of ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    ?: throw IllegalStateException("Could not decode image preview.")
                return@runHollowCatching VaultPreviewResult.ImagePreview(item.relativePath, bmp)
            }

            if (item.originalSizeBytes > MAX_PREVIEW_BYTES) {
                throw IllegalStateException("Too large to preview. Export it instead.")
            }

            val content = if (item.isEncrypted) {
                val buffer = ByteArrayOutputStream()
                FileInputStream(file).use { AesVaultCipher.decryptStream(it, buffer) }
                if (!isText) {
                    throw IllegalStateException(
                        "${item.mimeType} cannot be previewed as text. Decrypt to Workspace or Export instead."
                    )
                }
                String(buffer.toByteArray(), Charsets.UTF_8)
            } else {
                file.readText()
            }

            VaultPreviewResult.TextPreview(item.relativePath, content)
        }

    fun inferMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4", "mkv", "mov", "webm" -> "video/mp4"
            "mp3", "m4a", "wav", "ogg", "flac" -> "audio/mpeg"
            "txt", "md", "log", "json", "xml", "csv", "sh", "ini", "conf", "properties" -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
