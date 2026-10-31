package com.hollow.app.isolation.feature.gallery

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.IsoVaultApp
import com.hollow.app.isolation.crypto.AesVaultCipher
import com.hollow.app.isolation.data.entity.VaultFileItem
import com.hollow.app.isolation.feature.files.FileManagerViewModel
import com.hollow.app.isolation.mount.FileKind
import com.hollow.app.isolation.mount.FileSource
import com.hollow.app.isolation.mount.MountManager
import com.hollow.app.isolation.mount.RemoteEntry
import com.hollow.app.isolation.mount.RootFileBrowser
import com.hollow.app.isolation.mount.kindOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class GallerySourceTab(val title: String) {
    WORKSPACE_ALBUMS("Hollow Drive"),
    ENCRYPTED_LOCKER("Vault"),
    DEVICE_MEDIA("Device"),
    ISOLATED_APPS("App Drives")
}

enum class GalleryLayoutMode {
    TIMELINE,
    ALBUMS,
    FAVORITES
}

enum class GallerySortOrder(val label: String) {
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    NAME_ASC("Name (A–Z)"),
    SIZE_DESC("Largest first")
}

enum class MediaFilterKind(val label: String) {
    ALL("All Media"),
    IMAGE("Photos"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    TEXT("Docs & Notes")
}

enum class PhotoFilterPreset(val label: String) {
    NONE("Original"),
    NOIR("Monochrome"),
    EMERALD("Emerald Tint"),
    WARM("Warm Film"),
    PRIVACY_BLUR("Privacy Pixelate")
}

data class GalleryMediaItem(
    val id: String,
    val name: String,
    val albumName: String,
    val kind: FileKind,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val sourceTab: GallerySourceTab,
    val isEncrypted: Boolean = false,
    val isFavorite: Boolean = false,
    val localFile: File? = null,
    val vaultItem: VaultFileItem? = null,
    val contentUri: Uri? = null,
    val remoteEntry: RemoteEntry? = null,
    val width: Int = 0,
    val height: Int = 0
)

data class GalleryAlbumSummary(
    val name: String,
    val itemCount: Int,
    val totalBytes: Long,
    val coverItem: GalleryMediaItem?
)

data class MediaExifDetails(
    val name: String,
    val pathOrSource: String,
    val sizeBytes: Long,
    val dimensions: String,
    val mimeType: String,
    val sha256: String,
    val isEncrypted: Boolean,
    val modifiedMillis: Long
)

data class BrowserState(
    val packageName: String? = null,
    val source: FileSource = FileSource.VOLUME,
    val path: String = "",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    /** Set when the volume is encrypted and has to be unlocked before it can be browsed. */
    val locked: Boolean = false,
    /** Why the listing is empty or failed, if there is a reason worth showing. */
    val message: String? = null
)

data class ProGalleryState(
    val sourceTab: GallerySourceTab = GallerySourceTab.WORKSPACE_ALBUMS,
    val layoutMode: GalleryLayoutMode = GalleryLayoutMode.TIMELINE,
    val activeAlbum: String? = null,
    val mediaFilter: MediaFilterKind = MediaFilterKind.ALL,
    val sortOrder: GallerySortOrder = GallerySortOrder.DATE_DESC,
    val searchQuery: String = "",
    val gridColumns: Int = 3,
    val items: List<GalleryMediaItem> = emptyList(),
    val albums: List<GalleryAlbumSummary> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false
)

/**
 * Full-fledged Gallery ViewModel supporting Private Workspace Albums, Encrypted AES-256 Locker Media,
 * Device MediaStore, and Root Isolated App Volumes/Sandboxes.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_THUMB_BYTES = 30L * 1024 * 1024
        private const val MAX_FULL_IMAGE_BYTES = 80L * 1024 * 1024
        private const val MAX_TEXT_BYTES = 1L * 1024 * 1024
        private const val THUMB_SIDE = 320
        private const val FULL_SIDE = 2048
        private const val PREF_FAVORITES = "gallery_favorites_prefs"
        private const val KEY_FAV_SET = "fav_ids"
    }

    private val ctx = application
    private val isoApp = application as? IsoVaultApp
    private val cacheDir = File(application.cacheDir, "gallery")
    private val albumsRootDir = File(FileManagerViewModel.resolveHollowDriveRoot(application), "Pictures").apply { mkdirs() }
    private val lockerDir = File(application.filesDir, "locker").apply { mkdirs() }

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val _proState = MutableStateFlow(ProGalleryState())
    val proState: StateFlow<ProGalleryState> = _proState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val thumbs = LruCache<String, Bitmap>(120)
    private val decodeGate = Semaphore(3)
    private var listJob: Job? = null
    private val generation = AtomicLong(0)

    init {
        wipeCache()
        viewModelScope.launch(Dispatchers.IO) {
            reloadProGallery()
        }
    }

    override fun onCleared() {
        wipeCache()
        thumbs.evictAll()
    }

    private fun wipeCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun clearNotice() {
        _notice.value = null
    }

    private fun getFavoriteIds(): MutableSet<String> {
        val prefs = ctx.getSharedPreferences(PREF_FAVORITES, Context.MODE_PRIVATE)
        return (prefs.getStringSet(KEY_FAV_SET, emptySet()) ?: emptySet()).toMutableSet()
    }

    private fun saveFavoriteIds(ids: Set<String>) {
        ctx.getSharedPreferences(PREF_FAVORITES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAV_SET, ids)
            .apply()
    }

    private fun createProceduralArtwork(
        target: File,
        title: String,
        subtitle: String,
        colors: IntArray,
        accentColor: Int,
        seed: Int
    ) {
        if (target.exists()) return
        val width = 720
        val height = 900
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                colors, null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Geometric circles & architectural lines
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 38
            style = Paint.Style.FILL
        }
        canvas.drawCircle(width * 0.78f, height * 0.24f, 210f + (seed * 12), shapePaint)
        shapePaint.alpha = 24
        canvas.drawCircle(width * 0.22f, height * 0.68f, 260f, shapePaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 85
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(RectF(48f, 48f, width - 48f, height - 48f), 36f, 36f, strokePaint)
        canvas.drawCircle(width * 0.5f, height * 0.42f, 140f, strokePaint)

        // Inner emblem
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 55
            style = Paint.Style.FILL
        }
        val pathOffset = (seed % 3) * 18f
        canvas.drawRoundRect(
            RectF(width * 0.34f - pathOffset, height * 0.30f, width * 0.66f + pathOffset, height * 0.54f),
            42f, 42f, innerPaint
        )

        // Typography overlay
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText(title, 84f, height - 150f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 220
            textSize = 21f
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(subtitle, 84f, height - 105f, subPaint)

        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        bmp.recycle()
    }

    // ---- Pro Gallery Controls --------------------------------------------------------------

    fun setSourceTab(tab: GallerySourceTab) {
        _proState.update { it.copy(sourceTab = tab, activeAlbum = null, selectedIds = emptySet()) }
        reloadProGallery()
    }

    fun setLayoutMode(mode: GalleryLayoutMode) {
        _proState.update { it.copy(layoutMode = mode, selectedIds = emptySet()) }
        reloadProGallery()
    }

    fun selectAlbum(albumName: String?) {
        _proState.update { it.copy(activeAlbum = albumName, selectedIds = emptySet()) }
        reloadProGallery()
    }

    fun setMediaFilter(filter: MediaFilterKind) {
        _proState.update { it.copy(mediaFilter = filter) }
        reloadProGallery()
    }

    fun setSortOrder(order: GallerySortOrder) {
        _proState.update { it.copy(sortOrder = order) }
        reloadProGallery()
    }

    fun setSearchQuery(query: String) {
        _proState.update { it.copy(searchQuery = query) }
        reloadProGallery()
    }

    fun cycleGridColumns() {
        _proState.update {
            val next = when (it.gridColumns) {
                3 -> 2
                2 -> 4
                4 -> 1
                else -> 3
            }
            it.copy(gridColumns = next)
        }
    }

    fun toggleSelectItem(id: String) {
        _proState.update { state ->
            val next = state.selectedIds.toMutableSet()
            if (!next.add(id)) next.remove(id)
            state.copy(selectedIds = next)
        }
    }

    fun selectAllItems() {
        _proState.update { state ->
            state.copy(selectedIds = state.items.map { it.id }.toSet())
        }
    }

    fun clearSelection() {
        _proState.update { it.copy(selectedIds = emptySet()) }
    }

    fun toggleFavorite(item: GalleryMediaItem) {
        val favs = getFavoriteIds()
        val nowFav = if (favs.contains(item.id)) {
            favs.remove(item.id)
            false
        } else {
            favs.add(item.id)
            true
        }
        saveFavoriteIds(favs)
        _notice.value = if (nowFav) "Added ${item.name} to Favorites." else "Removed ${item.name} from Favorites."
        reloadProGallery()
    }

    fun createNewAlbum(albumName: String) {
        val clean = albumName.trim().replace("/", "_").replace("\\", "_")
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(albumsRootDir, clean)
            if (!dir.exists()) dir.mkdirs()
            // Create a cover card in the new album so it immediately shows up in Albums
            createProceduralArtwork(
                target = File(dir, "${clean.replace(' ', '_')}_Cover.png"),
                title = clean,
                subtitle = "PRIVATE ALBUM • ISOVAULT PRO",
                colors = intArrayOf(0xFF0E3A2F.toInt(), 0xFF1F6E58.toInt(), 0xFF4BC4A4.toInt()),
                accentColor = 0xFFFFE5B4.toInt(),
                seed = clean.hashCode()
            )
            _notice.value = "Created album '$clean'."
            reloadProGallery()
        }
    }

    fun createStudioArtworkInAlbum(title: String, subtitle: String, albumName: String, themeIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val safeAlbum = albumName.ifBlank { "Camera Roll" }
                val dir = File(albumsRootDir, safeAlbum).apply { mkdirs() }
                val fileName = "${title.trim().ifBlank { "Artwork" }.replace(' ', '_')}_${System.currentTimeMillis() % 10000}.png"
                val palette = when (themeIndex % 4) {
                    0 -> intArrayOf(0xFF0A382C.toInt(), 0xFF156953.toInt(), 0xFF53D6B4.toInt())
                    1 -> intArrayOf(0xFF121C33.toInt(), 0xFF284275.toInt(), 0xFF6B98E8.toInt())
                    2 -> intArrayOf(0xFF331812.toInt(), 0xFF7D3726.toInt(), 0xFFE8836B.toInt())
                    else -> intArrayOf(0xFF211536.toInt(), 0xFF4D317A.toInt(), 0xFFA782E8.toInt())
                }
                createProceduralArtwork(
                    target = File(dir, fileName),
                    title = title.ifBlank { "IsoVault Studio" },
                    subtitle = subtitle.ifBlank { "PRIVATE ALBUM CAPTURE" },
                    colors = palette,
                    accentColor = 0xFFFFDFBA.toInt(),
                    seed = (System.currentTimeMillis() % 100).toInt()
                )
                _notice.value = "Saved '$fileName' to $safeAlbum."
                reloadProGallery()
            } catch (e: Exception) {
                _notice.value = "Failed to generate artwork: ${e.message}"
            }
        }
    }

    fun importUrisToGallery(uris: List<Uri>, targetAlbum: String = "Imported") {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val destAlbum = File(albumsRootDir, targetAlbum.ifBlank { "Imported" }).apply { mkdirs() }
            var importedCount = 0
            for (uri in uris) {
                try {
                    val resolver = ctx.contentResolver
                    val queriedName = resolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val name = (queriedName?.takeIf { it.isNotBlank() } ?: "media_${System.currentTimeMillis()}.jpg")
                        .substringAfterLast('/')
                    val outFile = uniqueFile(destAlbum, name)
                    resolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        importedCount++
                    }
                } catch (_: Exception) {
                }
            }
            _notice.value = "Imported $importedCount item(s) into '$targetAlbum'."
            reloadProGallery()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var idx = 1
        while (candidate.exists()) {
            val nextName = if (ext.isEmpty()) "${base}_$idx" else "${base}_$idx.$ext"
            candidate = File(dir, nextName)
            idx++
        }
        return candidate
    }

    fun reloadProGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            _proState.update { it.copy(isLoading = true) }
            val current = _proState.value
            val favs = getFavoriteIds()

            val rawItems = when (current.sourceTab) {
                GallerySourceTab.WORKSPACE_ALBUMS -> loadWorkspaceAlbumMedia(favs)
                GallerySourceTab.ENCRYPTED_LOCKER -> loadEncryptedLockerMedia(favs)
                GallerySourceTab.DEVICE_MEDIA -> loadDeviceMedia(favs)
                GallerySourceTab.ISOLATED_APPS -> emptyList()
            }

            // Build album summaries
            val groupedByAlbum = rawItems.groupBy { it.albumName }
            val albums = groupedByAlbum.map { (albumName, list) ->
                GalleryAlbumSummary(
                    name = albumName,
                    itemCount = list.size,
                    totalBytes = list.sumOf { it.sizeBytes },
                    coverItem = list.firstOrNull { it.kind == FileKind.IMAGE } ?: list.firstOrNull()
                )
            }.sortedByDescending { it.itemCount }

            // Filter items
            val filtered = rawItems.filter { item ->
                val matchesAlbum = current.activeAlbum == null || item.albumName == current.activeAlbum
                val matchesFav = current.layoutMode != GalleryLayoutMode.FAVORITES || item.isFavorite
                val matchesKind = when (current.mediaFilter) {
                    MediaFilterKind.ALL -> true
                    MediaFilterKind.IMAGE -> item.kind == FileKind.IMAGE
                    MediaFilterKind.VIDEO -> item.kind == FileKind.VIDEO
                    MediaFilterKind.AUDIO -> item.kind == FileKind.AUDIO
                    MediaFilterKind.TEXT -> item.kind == FileKind.TEXT || item.kind == FileKind.OTHER
                }
                val matchesQuery = current.searchQuery.isBlank() ||
                    item.name.contains(current.searchQuery, ignoreCase = true) ||
                    item.albumName.contains(current.searchQuery, ignoreCase = true)
                matchesAlbum && matchesFav && matchesKind && matchesQuery
            }

            val sorted = when (current.sortOrder) {
                GallerySortOrder.DATE_DESC -> filtered.sortedByDescending { it.modifiedMillis }
                GallerySortOrder.DATE_ASC -> filtered.sortedBy { it.modifiedMillis }
                GallerySortOrder.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
                GallerySortOrder.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
            }

            _proState.update {
                it.copy(
                    items = sorted,
                    albums = albums,
                    isLoading = false
                )
            }
        }
    }

    private fun loadWorkspaceAlbumMedia(favs: Set<String>): List<GalleryMediaItem> {
        val result = mutableListOf<GalleryMediaItem>()
        val roots = listOf(
            albumsRootDir,
            File(ctx.filesDir, "workspace/Downloads"),
            File(ctx.filesDir, "workspace/Documents")
        )
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile && !file.name.startsWith(".")) {
                    val album = file.parentFile?.name?.takeIf { it != "workspace" } ?: "Workspace"
                    val id = "local:${file.absolutePath}"
                    result.add(
                        GalleryMediaItem(
                            id = id,
                            name = file.name,
                            albumName = album,
                            kind = kindOf(file.name),
                            sizeBytes = file.length(),
                            modifiedMillis = file.lastModified(),
                            sourceTab = GallerySourceTab.WORKSPACE_ALBUMS,
                            isEncrypted = false,
                            isFavorite = favs.contains(id),
                            localFile = file
                        )
                    )
                }
            }
        }
        return result.distinctBy { it.id }
    }

    private suspend fun loadEncryptedLockerMedia(favs: Set<String>): List<GalleryMediaItem> {
        val vaultRepo = isoApp?.vaultRepository ?: return emptyList()
        val vaultItems = vaultRepo.getAllVaultFilesSync()
        return vaultItems.mapNotNull { v ->
            val f = File(v.physicalPath)
            if (!f.exists()) return@mapNotNull null
            val displayName = v.relativePath.substringAfterLast('/')
            val kind = when {
                v.mimeType.startsWith("image/") -> FileKind.IMAGE
                v.mimeType.startsWith("video/") -> FileKind.VIDEO
                v.mimeType.startsWith("audio/") -> FileKind.AUDIO
                v.mimeType.startsWith("text/") -> FileKind.TEXT
                else -> kindOf(displayName)
            }
            val id = "vault:${v.id}"
            GalleryMediaItem(
                id = id,
                name = displayName,
                albumName = "Encrypted Vault",
                kind = kind,
                sizeBytes = v.originalSizeBytes.takeIf { it > 0 } ?: v.encryptedSizeBytes,
                modifiedMillis = v.lastModified,
                sourceTab = GallerySourceTab.ENCRYPTED_LOCKER,
                isEncrypted = true,
                isFavorite = favs.contains(id),
                vaultItem = v
            )
        }
    }

    private fun loadDeviceMedia(favs: Set<String>): List<GalleryMediaItem> {
        val result = mutableListOf<GalleryMediaItem>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                var count = 0
                while (cursor.moveToNext() && count < 150) {
                    val mediaId = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "IMG_$mediaId.jpg"
                    val bucket = cursor.getString(bucketCol) ?: "Device Media"
                    val size = cursor.getLong(sizeCol)
                    val dateSec = cursor.getLong(dateCol)
                    val w = cursor.getInt(wCol)
                    val h = cursor.getInt(hCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
                    val id = "device:$mediaId"
                    result.add(
                        GalleryMediaItem(
                            id = id,
                            name = name,
                            albumName = bucket,
                            kind = FileKind.IMAGE,
                            sizeBytes = size,
                            modifiedMillis = dateSec * 1000L,
                            sourceTab = GallerySourceTab.DEVICE_MEDIA,
                            isEncrypted = false,
                            isFavorite = favs.contains(id),
                            contentUri = uri,
                            width = w,
                            height = h
                        )
                    )
                    count++
                }
            }
        } catch (_: Exception) {
        }
        // Fallback: if MediaStore has no entries or permission isn't granted, also include external app media dirs
        if (result.isEmpty()) {
            ctx.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && !file.name.startsWith(".")) {
                        val id = "ext:${file.absolutePath}"
                        result.add(
                            GalleryMediaItem(
                                id = id,
                                name = file.name,
                                albumName = file.parentFile?.name ?: "External",
                                kind = kindOf(file.name),
                                sizeBytes = file.length(),
                                modifiedMillis = file.lastModified(),
                                sourceTab = GallerySourceTab.DEVICE_MEDIA,
                                isFavorite = favs.contains(id),
                                localFile = file
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    // ---- Pro Gallery Thumbnails, Full Image, Editing & Vault Lock --------------------------

    suspend fun loadMediaBitmap(item: GalleryMediaItem, fullRes: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        if (item.kind != FileKind.IMAGE) return@withContext null
        val maxSide = if (fullRes) FULL_SIDE else THUMB_SIDE
        val cacheKey = "${item.id}|${item.modifiedMillis}|$maxSide"
        if (!fullRes) {
            thumbs.get(cacheKey)?.let { return@withContext it }
        }

        decodeGate.withPermit {
            if (!fullRes) {
                thumbs.get(cacheKey)?.let { return@withPermit it }
            }
            val bmp: Bitmap? = try {
                when {
                    item.localFile != null && item.localFile.exists() -> {
                        decodeSampled(item.localFile, maxSide)
                    }
                    item.vaultItem != null -> {
                        val encFile = File(item.vaultItem.physicalPath)
                        if (!encFile.exists()) null
                        else {
                            val bytes = if (item.vaultItem.isEncrypted) {
                                val buffer = ByteArrayOutputStream()
                                FileInputStream(encFile).use { AesVaultCipher.decryptStream(it, buffer) }
                                buffer.toByteArray()
                            } else {
                                encFile.readBytes()
                            }
                            decodeSampledBytes(bytes, maxSide)
                        }
                    }
                    item.contentUri != null -> {
                        val bytes = ctx.contentResolver.openInputStream(item.contentUri)?.use { it.readBytes() }
                        if (bytes != null) decodeSampledBytes(bytes, maxSide) else null
                    }
                    item.remoteEntry != null -> {
                        if (fullRes) fullImage(item.remoteEntry) else thumbnail(item.remoteEntry)
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null && !fullRes) {
                thumbs.put(cacheKey, bmp)
            }
            bmp
        }
    }

    suspend fun loadMediaText(item: GalleryMediaItem): String? = withContext(Dispatchers.IO) {
        try {
            when {
                item.localFile != null && item.localFile.exists() -> {
                    item.localFile.readText(Charsets.UTF_8).take(100_000)
                }
                item.vaultItem != null -> {
                    val encFile = File(item.vaultItem.physicalPath)
                    if (!encFile.exists()) null
                    else if (item.vaultItem.isEncrypted) {
                        val buffer = ByteArrayOutputStream()
                        FileInputStream(encFile).use { AesVaultCipher.decryptStream(it, buffer) }
                        String(buffer.toByteArray(), Charsets.UTF_8).take(100_000)
                    } else {
                        encFile.readText(Charsets.UTF_8).take(100_000)
                    }
                }
                item.remoteEntry != null -> textPreview(item.remoteEntry)
                else -> null
            }
        } catch (e: Exception) {
            "Could not load text preview: ${e.message}"
        }
    }

    suspend fun inspectMediaExif(item: GalleryMediaItem): MediaExifDetails = withContext(Dispatchers.IO) {
        var dims = if (item.width > 0 && item.height > 0) "${item.width} × ${item.height} px" else "N/A"
        var sha256 = "Computed on demand"
        val pathDesc = when {
            item.localFile != null -> item.localFile.absolutePath
            item.vaultItem != null -> "AES-256-GCM Vault (${item.vaultItem.physicalPath.substringAfterLast('/')})"
            item.contentUri != null -> item.contentUri.toString()
            item.remoteEntry != null -> "/${item.remoteEntry.relPath}"
            else -> item.albumName
        }

        try {
            val bytes: ByteArray? = when {
                item.localFile != null && item.localFile.exists() && item.localFile.length() <= 30 * 1024 * 1024 -> item.localFile.readBytes()
                item.vaultItem != null -> {
                    val f = File(item.vaultItem.physicalPath)
                    if (f.exists() && f.length() <= 30 * 1024 * 1024) {
                        val buf = ByteArrayOutputStream()
                        FileInputStream(f).use { AesVaultCipher.decryptStream(it, buf) }
                        buf.toByteArray()
                    } else null
                }
                else -> null
            }
            if (bytes != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                sha256 = digest.joinToString("") { "%02x".format(it) }
                if (item.kind == FileKind.IMAGE) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        dims = "${opts.outWidth} × ${opts.outHeight} px"
                    }
                }
            }
        } catch (_: Exception) {
        }

        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "txt", "md", "log" -> "text/plain"
            else -> "application/octet-stream"
        }

        MediaExifDetails(
            name = item.name,
            pathOrSource = pathDesc,
            sizeBytes = item.sizeBytes,
            dimensions = dims,
            mimeType = mime,
            sha256 = sha256,
            isEncrypted = item.isEncrypted,
            modifiedMillis = item.modifiedMillis
        )
    }

    /** Transforms an image (rotation, flip, or filter) and saves it to the workspace album. */
    fun saveEditedImage(
        item: GalleryMediaItem,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
        filterPreset: PhotoFilterPreset,
        overwrite: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val srcBitmap = loadMediaBitmap(item, fullRes = true)
                if (srcBitmap == null) {
                    _notice.value = "Could not decode image for editing."
                    return@launch
                }
                val transformed = applyTransformAndFilter(srcBitmap, rotationDegrees, flipHorizontal, filterPreset)
                val targetFile = if (overwrite && item.localFile != null) {
                    item.localFile
                } else {
                    val albumDir = File(albumsRootDir, item.albumName.ifBlank { "Edited" }).apply { mkdirs() }
                    val base = item.name.substringBeforeLast('.', item.name)
                    uniqueFile(albumDir, "${base}_edited.png")
                }

                FileOutputStream(targetFile).use { out ->
                    transformed.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                thumbs.evictAll()
                _notice.value = "Saved edited photo: ${targetFile.name}"
                reloadProGallery()
            } catch (e: Exception) {
                _notice.value = "Failed to save image: ${e.message}"
            }
        }
    }

    fun applyTransformAndFilter(
        source: Bitmap,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
        filterPreset: PhotoFilterPreset
    ): Bitmap {
        val matrix = Matrix()
        if (flipHorizontal) {
            matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }
        if (rotationDegrees != 0f) {
            matrix.postRotate(rotationDegrees)
        }
        val rotated = if (rotationDegrees != 0f || flipHorizontal) {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }

        if (filterPreset == PhotoFilterPreset.NONE) return rotated

        if (filterPreset == PhotoFilterPreset.PRIVACY_BLUR) {
            val smallW = max(16, rotated.width / 18)
            val smallH = max(16, rotated.height / 18)
            val tiny = Bitmap.createScaledBitmap(rotated, smallW, smallH, false)
            return Bitmap.createScaledBitmap(tiny, rotated.width, rotated.height, false)
        }

        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cm = ColorMatrix()
        when (filterPreset) {
            PhotoFilterPreset.NOIR -> {
                cm.setSaturation(0f)
            }
            PhotoFilterPreset.EMERALD -> {
                cm.setScale(0.82f, 1.12f, 0.98f, 1f)
            }
            PhotoFilterPreset.WARM -> {
                cm.setScale(1.14f, 1.03f, 0.84f, 1f)
            }
            else -> {}
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(rotated, 0f, 0f, paint)
        return out
    }

    /** Encrypts a gallery item (or batch of selected items) into the AES-256-GCM Locker. */
    fun lockItemToVault(item: GalleryMediaItem, deleteOriginal: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val locked = lockSingleItemInternal(item, deleteOriginal)
            if (locked) {
                _notice.value = "Locked ${item.name} into AES-256 Vault."
                reloadProGallery()
            } else {
                _notice.value = "Could not lock ${item.name}."
            }
        }
    }

    fun batchLockSelectedToVault(deleteOriginals: Boolean = true) {
        val current = _proState.value
        val selectedItems = current.items.filter { current.selectedIds.contains(it.id) && !it.isEncrypted }
        if (selectedItems.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            for (item in selectedItems) {
                if (lockSingleItemInternal(item, deleteOriginals)) count++
            }
            _proState.update { it.copy(selectedIds = emptySet()) }
            _notice.value = "Encrypted $count item(s) into the AES-256 Vault."
            reloadProGallery()
        }
    }

    fun batchMoveSelectedToAlbum(targetAlbum: String) {
        val cleanAlbum = targetAlbum.trim().ifBlank { return }
        val current = _proState.value
        val selectedItems = current.items.filter { current.selectedIds.contains(it.id) && it.localFile != null }
        if (selectedItems.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val destDir = File(albumsRootDir, cleanAlbum).apply { mkdirs() }
            var moved = 0
            for (item in selectedItems) {
                val src = item.localFile ?: continue
                if (src.exists()) {
                    val dst = uniqueFile(destDir, src.name)
                    if (src.renameTo(dst)) moved++
                }
            }
            _proState.update { it.copy(selectedIds = emptySet()) }
            _notice.value = "Moved $moved item(s) to '$cleanAlbum'."
            reloadProGallery()
        }
    }

    fun batchDeleteSelected() {
        val current = _proState.value
        val selectedItems = current.items.filter { current.selectedIds.contains(it.id) }
        if (selectedItems.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val vaultRepo = isoApp?.vaultRepository
            var deleted = 0
            for (item in selectedItems) {
                when {
                    item.localFile != null -> if (item.localFile.delete()) deleted++
                    item.vaultItem != null -> {
                        File(item.vaultItem.physicalPath).delete()
                        vaultRepo?.deleteVaultFileById(item.vaultItem.id)
                        deleted++
                    }
                }
            }
            thumbs.evictAll()
            _proState.update { it.copy(selectedIds = emptySet()) }
            _notice.value = "Deleted $deleted item(s)."
            reloadProGallery()
        }
    }

    fun deleteMediaItem(item: GalleryMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val vaultRepo = isoApp?.vaultRepository
            when {
                item.localFile != null -> item.localFile.delete()
                item.vaultItem != null -> {
                    File(item.vaultItem.physicalPath).delete()
                    vaultRepo?.deleteVaultFileById(item.vaultItem.id)
                }
                item.remoteEntry != null -> {
                    RootFileBrowser.delete(ctx, item.remoteEntry.packageName, item.remoteEntry.source, item.remoteEntry.relPath)
                }
            }
            thumbs.evictAll()
            _notice.value = "Deleted ${item.name}."
            reloadProGallery()
        }
    }

    fun exportMediaItem(item: GalleryMediaItem, destination: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = ctx.contentResolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException("Could not open destination")
                out.use { dst ->
                    when {
                        item.localFile != null -> FileInputStream(item.localFile).use { it.copyTo(dst) }
                        item.vaultItem != null -> FileInputStream(File(item.vaultItem.physicalPath)).use {
                            AesVaultCipher.decryptStream(it, dst)
                        }
                        item.contentUri != null -> ctx.contentResolver.openInputStream(item.contentUri)?.use {
                            it.copyTo(dst)
                        }
                        item.remoteEntry != null -> {
                            RootFileBrowser.copyOut(ctx, item.remoteEntry.packageName, item.remoteEntry.source, item.remoteEntry.relPath, dst)
                        }
                    }
                }
                _notice.value = "Exported ${item.name}."
            } catch (e: Exception) {
                _notice.value = "Export failed: ${e.message}"
            }
        }
    }

    private suspend fun lockSingleItemInternal(item: GalleryMediaItem, deleteOriginal: Boolean): Boolean {
        val vaultRepo = isoApp?.vaultRepository ?: return false
        val target = File(lockerDir, "${UUID.randomUUID()}.isovault")
        var plainSize = 0L
        return try {
            when {
                item.localFile != null && item.localFile.exists() -> {
                    FileInputStream(item.localFile).use { src ->
                        FileOutputStream(target).use { out ->
                            plainSize = AesVaultCipher.encryptStream(src, out)
                        }
                    }
                    if (deleteOriginal) item.localFile.delete()
                }
                item.contentUri != null -> {
                    val input = ctx.contentResolver.openInputStream(item.contentUri) ?: return false
                    input.use { src ->
                        FileOutputStream(target).use { out ->
                            plainSize = AesVaultCipher.encryptStream(src, out)
                        }
                    }
                }
                else -> return false
            }
            val ext = item.name.substringAfterLast('.', "").lowercase()
            val mime = when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "txt", "md" -> "text/plain"
                else -> "image/png"
            }
            vaultRepo.insertVaultFile(
                VaultFileItem(
                    packageName = ctx.packageName,
                    relativePath = item.name,
                    physicalPath = target.absolutePath,
                    isEncrypted = true,
                    originalSizeBytes = plainSize,
                    encryptedSizeBytes = target.length(),
                    mimeType = mime,
                    lastModified = System.currentTimeMillis()
                )
            )
            true
        } catch (_: Exception) {
            target.delete()
            false
        }
    }

    // ---- Isolated App Root Navigation (preserved for App Sandbox tab) ----------------------

    fun open(packageName: String, source: FileSource) {
        thumbs.evictAll()
        _state.value = BrowserState(packageName = packageName, source = source)
        refresh()
    }

    fun openFolder(entry: RemoteEntry) {
        if (!entry.isDir) return
        _state.update { it.copy(path = entry.relPath) }
        refresh()
    }

    fun up() {
        val current = _state.value.path
        if (current.isEmpty()) return
        _state.update { it.copy(path = current.substringBeforeLast('/', "")) }
        refresh()
    }

    fun refresh() {
        val s = _state.value
        val pkg = s.packageName ?: return
        listJob?.cancel()
        val gen = generation.incrementAndGet()

        if (s.source == FileSource.VOLUME && MountManager.isSealed(ctx, pkg)) {
            _state.update {
                it.copy(entries = emptyList(), loading = false, locked = true, message = "This volume is locked (encrypted).")
            }
            return
        }

        _state.update { it.copy(loading = true, locked = false, message = null) }
        listJob = viewModelScope.launch(Dispatchers.IO) {
            val result = RootFileBrowser.list(ctx, pkg, s.source, s.path)
            _state.update {
                if (generation.get() != gen) it
                else it.copy(
                    entries = result.entries,
                    loading = false,
                    message = result.error ?: if (result.entries.isEmpty()) "Nothing here yet." else null
                )
            }
        }
    }

    fun unlock() {
        val pkg = _state.value.packageName ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true) }
            val result = MountManager.unsealVolume(ctx, pkg)
            _notice.value = if (result.isSuccess) "Volume unlocked. Lock it again when you are done." else result.output
            refresh()
        }
    }

    fun lock() {
        val pkg = _state.value.packageName ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true) }
            MountManager.detachVolume(ctx, pkg)
            val result = MountManager.sealVolume(ctx, pkg)
            thumbs.evictAll()
            _notice.value = if (result.isSuccess) "Volume locked. The app was stopped." else result.output
            refresh()
        }
    }

    private fun key(e: RemoteEntry) = "${e.packageName}|${e.source.name}|${e.relPath}|${e.modifiedSec}|${e.size}"

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decodeSampledBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    suspend fun thumbnail(entry: RemoteEntry): Bitmap? = withContext(Dispatchers.IO) {
        if (entry.packageName.isEmpty() || entry.kind != FileKind.IMAGE || entry.size > MAX_THUMB_BYTES) return@withContext null
        val k = key(entry)
        thumbs.get(k)?.let { return@withContext it }

        decodeGate.withPermit {
            thumbs.get(k)?.let { return@withPermit it }
            val (file, _) = RootFileBrowser.copyToCache(
                ctx, entry.packageName, entry.source, entry.relPath, cacheDir, entry.size + 4096
            )
            if (file == null) return@withPermit null
            try {
                decodeSampled(file, THUMB_SIDE)?.also { thumbs.put(k, it) }
            } finally {
                file.delete()
            }
        }
    }

    suspend fun fullImage(entry: RemoteEntry): Bitmap? = withContext(Dispatchers.IO) {
        if (entry.packageName.isEmpty() || entry.kind != FileKind.IMAGE || entry.size > MAX_FULL_IMAGE_BYTES) return@withContext null
        decodeGate.withPermit {
            val (file, _) = RootFileBrowser.copyToCache(
                ctx, entry.packageName, entry.source, entry.relPath, cacheDir, entry.size + 4096
            )
            if (file == null) return@withPermit null
            try {
                decodeSampled(file, FULL_SIDE)
            } finally {
                file.delete()
            }
        }
    }

    suspend fun textPreview(entry: RemoteEntry): String? = withContext(Dispatchers.IO) {
        if (entry.packageName.isEmpty() || entry.kind != FileKind.TEXT || entry.size > MAX_TEXT_BYTES) return@withContext null
        val (file, _) = RootFileBrowser.copyToCache(
            ctx, entry.packageName, entry.source, entry.relPath, cacheDir, MAX_TEXT_BYTES + 1
        )
        if (file == null) return@withContext null
        try {
            file.readText(Charsets.UTF_8)
        } finally {
            file.delete()
        }
    }

    fun export(entry: RemoteEntry, destination: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val error: String? = try {
                val out = ctx.contentResolver.openOutputStream(destination, "w")
                if (out == null) "Could not open the destination." else out.use {
                    RootFileBrowser.copyOut(ctx, entry.packageName, entry.source, entry.relPath, it)
                }
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
            if (error != null) {
                try { DocumentsContract.deleteDocument(ctx.contentResolver, destination) } catch (_: Exception) {}
            }
            _notice.value = error ?: "Exported ${entry.name}."
        }
    }

    fun delete(entry: RemoteEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val error = RootFileBrowser.delete(ctx, entry.packageName, entry.source, entry.relPath)
            thumbs.remove(key(entry))
            _notice.value = error ?: "Deleted ${entry.name}."
            refresh()
        }
    }
}
