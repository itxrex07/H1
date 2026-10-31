package com.hollow.app.isolation.mount

/** Where an app's isolated files live. */
enum class FileSource(val title: String) {
    /** The root-mounted volume (see MountManager). */
    VOLUME("Volume"),

    /** The folder the LSPosed hooks redirect the app into, inside the app's own data directory. */
    SANDBOX("Hook sandbox")
}

enum class FileKind { IMAGE, VIDEO, AUDIO, TEXT, OTHER }

/** One file or folder inside an app's isolated storage. [relPath] is relative to the source root. */
data class RemoteEntry(
    val name: String,
    val relPath: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedSec: Long,
    /** Which app and storage this entry was listed from, so later reads cannot use another one. */
    val packageName: String = "",
    val source: FileSource = FileSource.VOLUME
) {
    val kind: FileKind get() = if (isDir) FileKind.OTHER else kindOf(name)
}

data class BrowseResult(val entries: List<RemoteEntry>, val error: String?)

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
private val VIDEO_EXT = setOf("mp4", "mkv", "3gp", "webm", "avi", "mov", "m4v")
private val AUDIO_EXT = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus", "amr")
private val TEXT_EXT = setOf("txt", "log", "json", "xml", "md", "csv", "html", "ini", "conf", "properties")

fun kindOf(fileName: String): FileKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXT -> FileKind.IMAGE
        in VIDEO_EXT -> FileKind.VIDEO
        in AUDIO_EXT -> FileKind.AUDIO
        in TEXT_EXT -> FileKind.TEXT
        else -> FileKind.OTHER
    }
}

/** Quotes [value] as one single-quoted shell word. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * Parses the output of the listing script: one `nonce|type|size|mtime|name` line per entry.
 *
 * File names are chosen by the (untrusted) app and may contain newlines or `|`. Every genuine line
 * starts with [nonce], a random value only this call knows, so a name cannot forge another entry.
 * Only regular files and folders are kept: symbolic links (an app could plant one to make a root
 * reader follow it elsewhere), pipes, sockets and devices are dropped, as are IsoVault's own
 * marker files and any line that does not parse. Duplicates are removed.
 */
fun parseListing(output: String, parentRel: String, nonce: String): List<RemoteEntry> {
    val prefix = "$nonce|"
    val entries = ArrayList<RemoteEntry>()
    for (line in output.lineSequence()) {
        if (!line.startsWith(prefix)) continue
        val parts = line.substring(prefix.length).split('|', limit = 4)
        if (parts.size != 4) continue
        val type = parts[0]
        val isDir = type.contains("directory")
        if (!isDir && !type.startsWith("regular")) continue
        val name = parts[3]
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) continue
        if (name == MountManager.VOLUME_MARKER) continue
        val size = parts[1].toLongOrNull() ?: continue
        val modified = parts[2].toLongOrNull() ?: 0L
        val rel = if (parentRel.isEmpty()) name else "$parentRel/$name"
        entries.add(RemoteEntry(name, rel, isDir, if (isDir) 0L else size, modified))
    }
    return entries
        .distinctBy { it.relPath }
        .sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
}
