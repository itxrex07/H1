package com.hollow.app.isolation.feature.files

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.IsoVaultApp
import com.hollow.app.isolation.crypto.AesVaultCipher
import com.hollow.app.isolation.data.entity.VaultFileItem
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.mount.FileSource
import com.hollow.app.isolation.mount.MountManager
import com.hollow.app.isolation.mount.RootFileBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class StorageRootType(val title: String, val subtitle: String) {
    WORKSPACE("Hollow Drive", "Default shared virtual storage & gallery"),
    APP_INTERNAL("Separate Drive", "Dedicated per-app storage volume"),
    SHARED_STORAGE("Phone Storage", "Real device storage")
}

data class SeparateAppDriveInfo(
    val packageName: String,
    val appName: String,
    val badge: String,
    val rootPath: String,
    val usedBytes: Long,
    val itemCount: Int
)

enum class FileSortField(val label: String) {
    NAME("Name"),
    DATE("Date"),
    SIZE("Size"),
    TYPE("Type")
}

enum class FileCategory {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    CODE_OR_TEXT,
    PDF_OR_DOC,
    ENCRYPTED_VAULT,
    OTHER
}

enum class ClipboardMode {
    COPY,
    CUT
}

data class FileEntryItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String,
    val category: FileCategory,
    val childCount: Int = 0,
    val isReadable: Boolean = true,
    val isWritable: Boolean = true,
    val isHidden: Boolean = false
)

data class BreadcrumbSegment(
    val label: String,
    val absolutePath: String
)

data class StorageBreakdown(
    val totalDiskBytes: Long = 0L,
    val freeDiskBytes: Long = 0L,
    val usedDiskBytes: Long = 0L,
    val imagesBytes: Long = 0L,
    val videosBytes: Long = 0L,
    val audioBytes: Long = 0L,
    val docsAndCodeBytes: Long = 0L,
    val archivesBytes: Long = 0L,
    val encryptedVaultBytes: Long = 0L
)

data class FilePropertiesDetail(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val itemCount: Int,
    val lastModified: Long,
    val permissions: String,
    val mimeType: String,
    val md5: String,
    val sha256: String
)

data class FileManagerUiState(
    val activeRoot: StorageRootType = StorageRootType.WORKSPACE,
    val activeSeparateDrivePkg: String? = null,
    val activeDriveTitle: String = "Hollow Drive",
    val rootPath: String = "",
    val currentPath: String = "",
    val breadcrumbs: List<BreadcrumbSegment> = emptyList(),
    val entries: List<FileEntryItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val clipboardPaths: List<String> = emptyList(),
    val clipboardMode: ClipboardMode = ClipboardMode.COPY,
    val searchQuery: String = "",
    val sortField: FileSortField = FileSortField.NAME,
    val sortAscending: Boolean = true,
    val isGridView: Boolean = false,
    val showHiddenFiles: Boolean = false,
    val isLoading: Boolean = false,
    val storageBreakdown: StorageBreakdown = StorageBreakdown(),
    val errorMessage: String? = null
)

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application
    private val isoApp = application as? IsoVaultApp
    private val workspaceRoot: File = resolveHollowDriveRoot(application)
    private val lockerDir = File(application.filesDir, "locker").apply { mkdirs() }

    companion object {
        fun resolveHollowDriveRoot(context: android.content.Context): File {
            val privateDir = File(context.filesDir, "hollow_drive").apply {
                if (!exists()) {
                    mkdirs()
                    try {
                        setReadable(true, true)
                        setWritable(true, true)
                        setExecutable(true, true)
                    } catch (_: Throwable) {}
                }
            }
            try {
                val noMedia = File(privateDir, ".nomedia")
                if (!noMedia.exists()) noMedia.createNewFile()
            } catch (_: Throwable) {}

            // Auto-migrate any legacy items from public storage into sandboxed Hollow Drive
            try {
                val legacy = File("/storage/emulated/0/Hollow_Workspace")
                if (legacy.exists() && legacy.isDirectory) {
                    legacy.copyRecursively(privateDir, overwrite = false)
                    legacy.deleteRecursively()
                }
            } catch (_: Throwable) {}

            listOf("DCIM", "DCIM/Camera", "Pictures", "Movies", "Download", "Downloads", "Documents", "Music", "Archives", "Notes")
                .forEach { sub -> try { File(privateDir, sub).mkdirs() } catch (_: Throwable) {} }
            return privateDir
        }

        fun resolveSeparateAppDriveRoot(context: android.content.Context, packageName: String): File =
            MountManager.volumeDir(context, packageName)
    }

    private val _uiState = MutableStateFlow(
        FileManagerUiState(
            activeRoot = StorageRootType.WORKSPACE,
            activeSeparateDrivePkg = null,
            activeDriveTitle = "Hollow Drive",
            rootPath = workspaceRoot.absolutePath,
            currentPath = workspaceRoot.absolutePath
        )
    )
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            refreshDirectory()
            refreshStorageBreakdown()
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // ---- Navigation & Root Switching -------------------------------------------------------

    private fun resolveRootDir(type: StorageRootType): File {
        return when (type) {
            StorageRootType.WORKSPACE -> workspaceRoot.apply { mkdirs() }
            StorageRootType.APP_INTERNAL -> ctx.filesDir
            StorageRootType.SHARED_STORAGE -> {
                val ext = Environment.getExternalStorageDirectory()
                if (ext != null && ext.exists() && ext.canRead()) {
                    ext
                } else {
                    ctx.getExternalFilesDir(null) ?: workspaceRoot
                }
            }
        }
    }

    fun switchStorageRoot(type: StorageRootType) {
        val rootDir = resolveRootDir(type)
        _uiState.update {
            it.copy(
                activeRoot = type,
                activeSeparateDrivePkg = null,
                activeDriveTitle = type.title,
                rootPath = rootDir.absolutePath,
                currentPath = rootDir.absolutePath,
                selectedPaths = emptySet(),
                searchQuery = "",
                errorMessage = null
            )
        }
        refreshDirectory()
        refreshStorageBreakdown()
    }

    fun openSeparateAppDrive(packageName: String, appName: String) {
        val driveDir = resolveSeparateAppDriveRoot(ctx, packageName)
        _uiState.update {
            it.copy(
                activeRoot = StorageRootType.APP_INTERNAL,
                activeSeparateDrivePkg = packageName,
                activeDriveTitle = "$appName Drive",
                rootPath = driveDir.absolutePath,
                currentPath = driveDir.absolutePath,
                selectedPaths = emptySet(),
                searchQuery = "",
                errorMessage = null
            )
        }
        refreshDirectory()
        refreshStorageBreakdown()
    }

    fun inspectSeparateDriveStats(packageName: String, appName: String, badge: String): SeparateAppDriveInfo {
        val dir = resolveSeparateAppDriveRoot(ctx, packageName)
        val entries = RootFileBrowser.list(ctx, packageName, FileSource.VOLUME, "").entries
        val count = entries.size
        val bytes = entries.filterNot { it.isDir }.sumOf { it.size }
        return SeparateAppDriveInfo(
            packageName = packageName,
            appName = appName,
            badge = badge,
            rootPath = dir.absolutePath,
            usedBytes = bytes,
            itemCount = count
        )
    }

    fun navigateToPath(path: String) {
        val currentState = _uiState.value
        if (currentState.activeSeparateDrivePkg != null) {
            val root = File(currentState.rootPath).absolutePath.trimEnd('/')
            val target = File(path).absolutePath
            val rel = if (target == root) "" else target.removePrefix("$root/")
            if ((target != root && rel == target) || rel.split('/').any { it == ".." || it == "." }) {
                _notice.value = "That path is outside this app's isolated volume."
                return
            }
            val safeRel = IsolationRuleManager.sanitizeRelative(rel)
            if (safeRel != rel) {
                _notice.value = "That folder path is not valid."
                return
            }
            _uiState.update {
                it.copy(currentPath = if (rel.isEmpty()) root else "$root/$rel", selectedPaths = emptySet(), searchQuery = "")
            }
            refreshDirectory()
            return
        }
        val target = File(path)
        if (!target.exists()) {
            try { target.mkdirs() } catch (_: Throwable) {}
        }
        if (!target.exists() || !target.isDirectory) {
            _notice.value = "Folder is not accessible."
            return
        }
        _uiState.update {
            it.copy(
                currentPath = target.absolutePath,
                selectedPaths = emptySet(),
                searchQuery = ""
            )
        }
        refreshDirectory()
    }

    fun navigateUp(): Boolean {
        val state = _uiState.value
        val current = File(state.currentPath)
        val root = File(state.rootPath)
        if (current.absolutePath == root.absolutePath) return false
        val parent = current.parentFile ?: return false
        if (!parent.absolutePath.startsWith(root.absolutePath)) {
            navigateToPath(root.absolutePath)
        } else {
            navigateToPath(parent.absolutePath)
        }
        return true
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        refreshDirectory()
    }

    fun setSort(field: FileSortField, ascending: Boolean = _uiState.value.sortAscending) {
        _uiState.update { it.copy(sortField = field, sortAscending = ascending) }
        refreshDirectory()
    }

    fun toggleSortDirection() {
        _uiState.update { it.copy(sortAscending = !it.sortAscending) }
        refreshDirectory()
    }

    fun toggleGridView() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
    }

    fun toggleShowHiddenFiles() {
        _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
        refreshDirectory()
    }

    // ---- Selection & Clipboard -------------------------------------------------------------

    fun toggleSelection(path: String) {
        _uiState.update { state ->
            val next = state.selectedPaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            state.copy(selectedPaths = next)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedPaths = state.entries.map { it.path }.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun copySelectedToClipboard() {
        val selected = _uiState.value.selectedPaths.toList()
        if (selected.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboardPaths = selected,
                clipboardMode = ClipboardMode.COPY,
                selectedPaths = emptySet()
            )
        }
        _notice.value = "${selected.size} item(s) copied to clipboard. Open destination folder and tap Paste."
    }

    fun cutSelectedToClipboard() {
        val selected = _uiState.value.selectedPaths.toList()
        if (selected.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboardPaths = selected,
                clipboardMode = ClipboardMode.CUT,
                selectedPaths = emptySet()
            )
        }
        _notice.value = "${selected.size} item(s) ready to move. Open destination folder and tap Paste."
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboardPaths = emptyList()) }
    }

    fun pasteClipboardHere() {
        val state = _uiState.value
        val destDir = File(state.currentPath)
        val sources = state.clipboardPaths.map { File(it) }.filter { it.exists() }
        if (sources.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            var processed = 0
            for (src in sources) {
                try {
                    if (src.isDirectory && destDir.canonicalPath.startsWith(src.canonicalPath)) {
                        _notice.value = "Cannot copy/move a folder into itself."
                        continue
                    }
                    val target = uniqueDestination(destDir, src.name)
                    if (src.isDirectory) {
                        src.copyRecursively(target, overwrite = false)
                    } else {
                        src.copyTo(target, overwrite = false)
                    }
                    if (state.clipboardMode == ClipboardMode.CUT) {
                        src.deleteRecursively()
                    }
                    processed++
                } catch (_: Exception) {
                }
            }
            val verb = if (state.clipboardMode == ClipboardMode.CUT) "Moved" else "Pasted"
            _uiState.update {
                it.copy(
                    clipboardPaths = if (state.clipboardMode == ClipboardMode.CUT) emptyList() else it.clipboardPaths,
                    isLoading = false
                )
            }
            _notice.value = "$verb $processed item(s) into ${destDir.name}."
            refreshDirectory()
            refreshStorageBreakdown()
        }
    }

    // ---- Directory Listing & Storage Analyzer ----------------------------------------------

    fun refreshDirectory() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val rootFile = File(state.rootPath)
            val currentDir = File(state.currentPath)

            // Build breadcrumbs
            val crumbs = mutableListOf<BreadcrumbSegment>()
            crumbs.add(BreadcrumbSegment(state.activeDriveTitle.ifBlank { state.activeRoot.title }, rootFile.absolutePath))
            if (currentDir.absolutePath.startsWith(rootFile.absolutePath) && currentDir.absolutePath != rootFile.absolutePath) {
                val rel = currentDir.absolutePath.removePrefix(rootFile.absolutePath).trimStart('/')
                var running = rootFile.absolutePath
                for (part in rel.split('/').filter { it.isNotEmpty() }) {
                    running = "$running/$part"
                    crumbs.add(BreadcrumbSegment(part, running))
                }
            }

            // Per-app volumes live under another package's private data directory. java.io.File
            // in this manager process cannot enumerate them; use the guarded root reader instead.
            val remotePackage = state.activeSeparateDrivePkg
            if (remotePackage != null) {
                val rel = if (currentDir.absolutePath == rootFile.absolutePath) ""
                else currentDir.absolutePath.removePrefix("${rootFile.absolutePath.trimEnd('/')}/")
                val isLocked = MountManager.isSealed(ctx, remotePackage)
                val result = if (isLocked) null else RootFileBrowser.list(ctx, remotePackage, FileSource.VOLUME, rel)
                val remoteItems = result?.entries.orEmpty()
                    .filter { state.showHiddenFiles || !it.name.startsWith(".") }
                    .filter { state.searchQuery.isBlank() || it.name.contains(state.searchQuery, ignoreCase = true) }
                    .map { remote ->
                        val extension = if (remote.isDir) "" else remote.name.substringAfterLast('.', "").lowercase()
                        FileEntryItem(
                            path = File(rootFile, remote.relPath).absolutePath,
                            name = remote.name,
                            isDirectory = remote.isDir,
                            sizeBytes = remote.size,
                            lastModified = remote.modifiedSec * 1000L,
                            extension = extension,
                            category = if (remote.isDir) FileCategory.FOLDER else categorizeFile(File(remote.name)),
                            childCount = 0,
                            isReadable = true,
                            isWritable = false,
                            isHidden = remote.name.startsWith(".")
                        )
                    }
                val comparator = when (state.sortField) {
                    FileSortField.NAME -> compareBy<FileEntryItem> { it.name.lowercase() }
                    FileSortField.DATE -> compareBy<FileEntryItem> { it.lastModified }
                    FileSortField.SIZE -> compareBy<FileEntryItem> { it.sizeBytes }
                    FileSortField.TYPE -> compareBy<FileEntryItem> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
                }
                val directedComparator = if (state.sortAscending) comparator else comparator.reversed()
                val sorted = remoteItems.sortedWith(compareByDescending<FileEntryItem> { it.isDirectory }.then(directedComparator))
                _uiState.update {
                    it.copy(
                        breadcrumbs = crumbs,
                        entries = sorted,
                        isLoading = false,
                        errorMessage = if (isLocked) {
                            "This app volume is locked. Unlock it from the app's isolation controls first."
                        } else result?.error
                    )
                }
                return@launch
            }

            if (!currentDir.exists() || !currentDir.canRead()) {
                _uiState.update {
                    it.copy(
                        breadcrumbs = crumbs,
                        entries = emptyList(),
                        isLoading = false,
                        errorMessage = "This folder cannot be read directly without additional storage access."
                    )
                }
                return@launch
            }

            val rawFiles: List<File> = if (state.searchQuery.isNotBlank()) {
                currentDir.walkTopDown()
                    .maxDepth(4)
                    .filter { f ->
                        f.absolutePath != currentDir.absolutePath &&
                            (state.showHiddenFiles || !f.name.startsWith(".")) &&
                            f.name.contains(state.searchQuery, ignoreCase = true)
                    }
                    .take(250)
                    .toList()
            } else {
                currentDir.listFiles()?.filter { f ->
                    state.showHiddenFiles || !f.name.startsWith(".")
                } ?: emptyList()
            }

            val mapped = rawFiles.map { f -> toEntryItem(f) }
            val comparator = when (state.sortField) {
                FileSortField.NAME -> compareBy<FileEntryItem> { it.name.lowercase() }
                FileSortField.DATE -> compareBy<FileEntryItem> { it.lastModified }
                FileSortField.SIZE -> compareBy<FileEntryItem> { it.sizeBytes }
                FileSortField.TYPE -> compareBy<FileEntryItem> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
            }
            val directedComparator = if (state.sortAscending) comparator else comparator.reversed()
            // Always keep folders grouped at top
            val sorted = mapped.sortedWith(compareByDescending<FileEntryItem> { it.isDirectory }.then(directedComparator))

            _uiState.update {
                it.copy(
                    breadcrumbs = crumbs,
                    entries = sorted,
                    isLoading = false
                )
            }
        }
    }

    fun refreshStorageBreakdown() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stat = StatFs(ctx.filesDir.absolutePath)
                val totalDisk = stat.totalBytes
                val freeDisk = stat.availableBytes
                val usedDisk = (totalDisk - freeDisk).coerceAtLeast(0L)

                var img = 0L
                var vid = 0L
                var aud = 0L
                var doc = 0L
                var arc = 0L
                var vlt = 0L

                ctx.filesDir.walkTopDown().maxDepth(5).forEach { f ->
                    if (f.isFile) {
                        val len = f.length()
                        when (categorizeFile(f)) {
                            FileCategory.IMAGE -> img += len
                            FileCategory.VIDEO -> vid += len
                            FileCategory.AUDIO -> aud += len
                            FileCategory.ARCHIVE -> arc += len
                            FileCategory.CODE_OR_TEXT, FileCategory.PDF_OR_DOC -> doc += len
                            FileCategory.ENCRYPTED_VAULT -> vlt += len
                            else -> doc += len
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        storageBreakdown = StorageBreakdown(
                            totalDiskBytes = totalDisk,
                            freeDiskBytes = freeDisk,
                            usedDiskBytes = usedDisk,
                            imagesBytes = img,
                            videosBytes = vid,
                            audioBytes = aud,
                            docsAndCodeBytes = doc,
                            archivesBytes = arc,
                            encryptedVaultBytes = vlt
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun toEntryItem(file: File): FileEntryItem {
        val isDir = file.isDirectory
        val ext = if (isDir) "" else file.name.substringAfterLast('.', "").lowercase()
        val children = if (isDir) {
            file.list()?.count { _uiState.value.showHiddenFiles || !it.startsWith(".") } ?: 0
        } else 0
        return FileEntryItem(
            path = file.absolutePath,
            name = file.name,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else file.length(),
            lastModified = file.lastModified(),
            extension = ext,
            category = categorizeFile(file),
            childCount = children,
            isReadable = file.canRead(),
            isWritable = file.canWrite(),
            isHidden = file.name.startsWith(".")
        )
    }

    private fun categorizeFile(file: File): FileCategory {
        if (file.isDirectory) return FileCategory.FOLDER
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "isovault", "sealed", "enc" -> FileCategory.ENCRYPTED_VAULT
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg" -> FileCategory.IMAGE
            "mp4", "mkv", "mov", "webm", "avi", "3gp" -> FileCategory.VIDEO
            "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> FileCategory.AUDIO
            "zip", "tar", "gz", "7z", "rar", "jar", "apk" -> FileCategory.ARCHIVE
            "txt", "md", "json", "xml", "log", "csv", "sh", "kt", "java", "py", "js", "html", "css", "ini", "conf", "properties", "yml", "yaml" -> FileCategory.CODE_OR_TEXT
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> FileCategory.PDF_OR_DOC
            else -> FileCategory.OTHER
        }
    }

    // ---- File & Folder Actions -------------------------------------------------------------

    fun createNewFolder(folderName: String) {
        val clean = folderName.trim().replace("/", "_").replace("\\", "_")
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val target = File(_uiState.value.currentPath, clean)
            if (target.exists()) {
                _notice.value = "A folder or file named '$clean' already exists."
                return@launch
            }
            if (target.mkdirs()) {
                _notice.value = "Created folder '$clean'."
                refreshDirectory()
            } else {
                _notice.value = "Could not create folder '$clean'."
            }
        }
    }

    fun createNewTextFile(fileName: String, content: String) {
        val clean = fileName.trim().replace("/", "_").replace("\\", "_")
            .let { if (it.isBlank()) "untitled.txt" else if (!it.contains('.')) "$it.txt" else it }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = uniqueDestination(File(_uiState.value.currentPath), clean)
                target.writeText(content, Charsets.UTF_8)
                _notice.value = "Saved ${target.name}."
                refreshDirectory()
                refreshStorageBreakdown()
            } catch (e: Exception) {
                _notice.value = "Could not create file: ${e.message}"
            }
        }
    }

    fun saveTextFileContent(path: String, newContent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(path)
                file.writeText(newContent, Charsets.UTF_8)
                _notice.value = "Saved changes to ${file.name}."
                refreshDirectory()
                refreshStorageBreakdown()
            } catch (e: Exception) {
                _notice.value = "Could not save file: ${e.message}"
            }
        }
    }

    suspend fun readTextFileContent(path: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.length() > 2 * 1024 * 1024) {
                "File is larger than 2 MB. Showing first 64 KB:\n\n" +
                    file.inputStream().bufferedReader().use {
                        val buf = CharArray(65536)
                        val n = it.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
            } else {
                file.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "Unable to read file: ${e.message}"
        }
    }

    fun renameEntry(entry: FileEntryItem, newName: String) {
        val clean = newName.trim().replace("/", "_").replace("\\", "_")
        if (clean.isBlank() || clean == entry.name) return
        viewModelScope.launch(Dispatchers.IO) {
            val src = File(entry.path)
            val dst = File(src.parentFile, clean)
            if (dst.exists()) {
                _notice.value = "A file named '$clean' already exists."
                return@launch
            }
            if (src.renameTo(dst)) {
                _notice.value = "Renamed to '$clean'."
                refreshDirectory()
            } else {
                _notice.value = "Could not rename ${entry.name}."
            }
        }
    }

    fun duplicateEntry(entry: FileEntryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val src = File(entry.path)
                val parent = src.parentFile ?: return@launch
                val base = src.nameWithoutExtension
                val ext = src.extension
                val copyName = if (ext.isEmpty()) "${base}_copy" else "${base}_copy.$ext"
                val dst = uniqueDestination(parent, copyName)
                if (src.isDirectory) {
                    src.copyRecursively(dst, overwrite = false)
                } else {
                    src.copyTo(dst, overwrite = false)
                }
                _notice.value = "Duplicated as ${dst.name}."
                refreshDirectory()
                refreshStorageBreakdown()
            } catch (e: Exception) {
                _notice.value = "Duplicate failed: ${e.message}"
            }
        }
    }

    fun deleteEntries(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            for (p in paths) {
                val f = File(p)
                if (f.exists() && f.deleteRecursively()) count++
            }
            _uiState.update { it.copy(selectedPaths = emptySet()) }
            _notice.value = "Deleted $count item(s)."
            refreshDirectory()
            refreshStorageBreakdown()
        }
    }

    fun importExternalUrisHere(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val destDir = File(_uiState.value.currentPath)
            var count = 0
            for (uri in uris) {
                try {
                    val resolver = ctx.contentResolver
                    val queriedName = resolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val name = (queriedName?.takeIf { it.isNotBlank() } ?: "imported_${System.currentTimeMillis()}")
                        .substringAfterLast('/')
                    val dst = uniqueDestination(destDir, name)
                    resolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dst).use { out -> input.copyTo(out) }
                        count++
                    }
                } catch (_: Exception) {
                }
            }
            _notice.value = "Imported $count file(s) into ${destDir.name}."
            refreshDirectory()
            refreshStorageBreakdown()
        }
    }

    /**
     * Recursively imports an external folder selected via ActivityResultContracts.OpenDocumentTree()
     * into the current Hollow workspace directory.
     */
    fun importExternalFolderHere(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val resolver = ctx.contentResolver
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

                val rawFolderName = try {
                    resolver.query(
                        rootDocUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } catch (_: Exception) {
                    null
                } ?: treeDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Imported_Folder" }

                val cleanFolderName = rawFolderName.replace("/", "_").ifBlank { "Imported_Folder" }
                val currentDir = File(_uiState.value.currentPath).apply { mkdirs() }
                val targetRootDir = uniqueDestination(currentDir, cleanFolderName).apply { mkdirs() }

                var copiedFiles = 0
                var createdDirs = 1

                fun copyTreeRecursive(parentDocId: String, destFolder: File, depth: Int) {
                    if (depth > 12) return
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                        while (cursor.moveToNext()) {
                            val childDocId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                            val childName = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)
                                ?.replace("/", "_")
                                ?.takeIf { it.isNotBlank() }
                                ?: "item_${System.currentTimeMillis()}"
                            val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else ""

                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                val subDir = File(destFolder, childName).apply { mkdirs() }
                                createdDirs++
                                copyTreeRecursive(childDocId, subDir, depth + 1)
                            } else {
                                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                                val dstFile = uniqueDestination(destFolder, childName)
                                try {
                                    resolver.openInputStream(childUri)?.use { input ->
                                        FileOutputStream(dstFile).use { out -> input.copyTo(out) }
                                        copiedFiles++
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }

                copyTreeRecursive(treeDocId, targetRootDir, 0)
                _uiState.update { it.copy(isLoading = false) }
                _notice.value = "Imported folder '${targetRootDir.name}' ($copiedFiles file(s), $createdDirs folder(s))."
                refreshDirectory()
                refreshStorageBreakdown()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _notice.value = "Folder import failed: ${e.message}"
            }
        }
    }

    fun exportFileToUri(path: String, destination: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val src = File(path)
                val out = ctx.contentResolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException("Could not open destination")
                out.use { dst ->
                    FileInputStream(src).use { input -> input.copyTo(dst) }
                }
                _notice.value = "Exported ${src.name}."
            } catch (e: Exception) {
                _notice.value = "Export failed: ${e.message}"
            }
        }
    }

    // ---- ZIP Compression & Extraction ------------------------------------------------------

    fun compressToZip(paths: List<String>, archiveName: String) {
        if (paths.isEmpty()) return
        val cleanArchive = archiveName.trim().replace("/", "_")
            .let { if (it.isBlank()) "Archive.zip" else if (!it.endsWith(".zip", ignoreCase = true)) "$it.zip" else it }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val destDir = File(_uiState.value.currentPath)
                val zipFile = uniqueDestination(destDir, cleanArchive)
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    for (p in paths) {
                        val src = File(p)
                        if (src.exists()) {
                            addFileToZip(src, src.name, zos)
                        }
                    }
                }
                _uiState.update { it.copy(selectedPaths = emptySet(), isLoading = false) }
                _notice.value = "Created archive ${zipFile.name} (${zipFile.length()} bytes)."
                refreshDirectory()
                refreshStorageBreakdown()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _notice.value = "ZIP compression failed: ${e.message}"
            }
        }
    }

    private fun addFileToZip(file: File, entryPath: String, zos: ZipOutputStream) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: emptyArray()
            if (children.isEmpty()) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
            } else {
                for (child in children) {
                    addFileToZip(child, "$entryPath/${child.name}", zos)
                }
            }
        } else {
            BufferedInputStream(FileInputStream(file)).use { bis ->
                val entry = ZipEntry(entryPath).apply { time = file.lastModified() }
                zos.putNextEntry(entry)
                bis.copyTo(zos)
                zos.closeEntry()
            }
        }
    }

    fun extractZipArchive(zipEntryItem: FileEntryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val zipFile = File(zipEntryItem.path)
                val parent = zipFile.parentFile ?: File(_uiState.value.currentPath)
                val outFolder = uniqueDestination(parent, zipFile.nameWithoutExtension.ifBlank { "Extracted" })
                outFolder.mkdirs()
                val canonicalOutRoot = outFolder.canonicalPath

                var extractedCount = 0
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(outFolder, entry.name)
                        // Zip-Slip guard
                        if (!outFile.canonicalPath.startsWith(canonicalOutRoot)) {
                            throw SecurityException("Blocked Zip-Slip path traversal: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                zis.copyTo(bos)
                            }
                            extractedCount++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                _uiState.update { it.copy(isLoading = false) }
                _notice.value = "Extracted $extractedCount file(s) into ${outFolder.name}/."
                refreshDirectory()
                refreshStorageBreakdown()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _notice.value = "Extraction failed: ${e.message}"
            }
        }
    }

    // ---- Encrypt Selected Files into AES-256 Locker ----------------------------------------

    fun encryptEntriesToLocker(paths: List<String>, shredOriginals: Boolean) {
        if (paths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val vaultRepo = isoApp?.vaultRepository ?: return@launch
            var lockedCount = 0
            for (p in paths) {
                val f = File(p)
                if (!f.exists() || !f.isFile) continue
                val target = File(lockerDir, "${UUID.randomUUID()}.isovault")
                try {
                    var plainSize = 0L
                    FileInputStream(f).use { src ->
                        FileOutputStream(target).use { out ->
                            plainSize = AesVaultCipher.encryptStream(src, out)
                        }
                    }
                    vaultRepo.insertVaultFile(
                        VaultFileItem(
                            packageName = ctx.packageName,
                            relativePath = f.name,
                            physicalPath = target.absolutePath,
                            isEncrypted = true,
                            originalSizeBytes = plainSize,
                            encryptedSizeBytes = target.length(),
                            mimeType = inferMime(f.name),
                            lastModified = System.currentTimeMillis()
                        )
                    )
                    if (shredOriginals) {
                        com.hollow.app.isolation.crypto.SecureShredder.shredFile(f)
                    }
                    lockedCount++
                } catch (_: Exception) {
                    target.delete()
                }
            }
            _uiState.update { it.copy(selectedPaths = emptySet()) }
            _notice.value = if (shredOriginals) {
                "Encrypted & shredded $lockedCount file(s) into Private Locker."
            } else {
                "Encrypted $lockedCount file(s) into Private Locker."
            }
            refreshDirectory()
            refreshStorageBreakdown()
        }
    }

    // ---- File Properties & Cryptographic Checksums -----------------------------------------

    suspend fun inspectProperties(entry: FileEntryItem): FilePropertiesDetail = withContext(Dispatchers.IO) {
        val file = File(entry.path)
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        val x = if (file.canExecute()) "x" else "-"
        val typeChar = if (file.isDirectory) "d" else "-"
        val perms = "$typeChar$r$w$x"

        var totalBytes = file.length()
        var count = 1
        var md5 = "N/A (Folder)"
        var sha256 = "N/A (Folder)"

        if (file.isDirectory) {
            count = 0
            totalBytes = 0L
            file.walkTopDown().maxDepth(5).forEach { child ->
                if (child.absolutePath != file.absolutePath) {
                    count++
                    if (child.isFile) totalBytes += child.length()
                }
            }
        } else if (file.exists() && file.canRead() && file.length() <= 50L * 1024 * 1024) {
            try {
                val md5Digest = MessageDigest.getInstance("MD5")
                val sha256Digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(32 * 1024)
                FileInputStream(file).use { fis ->
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        md5Digest.update(buf, 0, n)
                        sha256Digest.update(buf, 0, n)
                    }
                }
                md5 = md5Digest.digest().joinToString("") { "%02x".format(it) }
                sha256 = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                md5 = "Error: ${e.message}"
                sha256 = "Error: ${e.message}"
            }
        } else {
            md5 = "Skipped (> 50 MB)"
            sha256 = "Skipped (> 50 MB)"
        }

        FilePropertiesDetail(
            name = file.name,
            absolutePath = file.absolutePath,
            isDirectory = file.isDirectory,
            sizeBytes = totalBytes,
            itemCount = count,
            lastModified = file.lastModified(),
            permissions = perms,
            mimeType = if (file.isDirectory) "inode/directory" else inferMime(file.name),
            md5 = md5,
            sha256 = sha256
        )
    }

    private fun inferMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4", "mkv", "mov" -> "video/mp4"
            "mp3", "m4a", "wav", "ogg" -> "audio/mpeg"
            "zip" -> "application/zip"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "md" -> "text/markdown"
            "sh" -> "application/x-sh"
            "csv" -> "text/csv"
            "txt", "log", "ini", "conf", "properties" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun uniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) name.substringAfterLast('.', "") else ""
        var index = 1
        while (candidate.exists()) {
            val next = if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext"
            candidate = File(dir, next)
            index++
        }
        return candidate
    }
}
