package com.hollow.app.isolation.mount

import android.content.Context
import com.hollow.app.isolation.model.IsolationRuleManager
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads, exports and deletes files inside an app's isolated storage as root.
 *
 * The files belong to the target app, which is not trusted: it can plant symlinks, swap files
 * while we work, and choose hostile names. So every call
 *  - anchors the base folder to a path the app cannot redirect (a symlinked base is refused),
 *  - requires the resolved target to be exactly base/<requested path>, which fails if any part
 *    of it is a symlink,
 *  - for reads, opens the file once and checks the opened file, not the path,
 *  - for deletes, stops the app first so nothing can change underneath the delete.
 * Nothing here ever runs a path or name through the shell unquoted.
 */
object RootFileBrowser {

    private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private const val COPY_DEADLINE_MS = 120_000L

    fun baseDir(context: Context, packageName: String, source: FileSource): String {
        require(PACKAGE_REGEX.matches(packageName)) { "Invalid package name" }
        return when (source) {
            FileSource.VOLUME -> MountManager.volumeDir(context, packageName).absolutePath
            FileSource.SANDBOX -> "/data/user/0/$packageName/files/IsoVault_Sandbox"
        }
    }

    /**
     * Shell lines that set `R` to the real path of base/rel and exit unless it is exactly the
     * expected path. Messages go to stderr so they never mix into file data on stdout.
     * Exit codes: 4 = base or target missing, 7 = refused.
     */
    private fun guard(context: Context, packageName: String, source: FileSource, rel: String): String {
        val base = baseDir(context, packageName, source)
        val expectedBase = when (source) {
            // Trust-anchor the path at the target app's private data directory. The app may write
            // inside the volume, but a symlinked volume root is never accepted as its own base.
            FileSource.VOLUME -> "\"\$(realpath /data/user/0/$packageName 2>/dev/null)\"" + shellQuote("/files/isovault_storage")
            // The app owns files/IsoVault_Sandbox, so it is compared against the trusted anchor
            // /data/user/0/<pkg>: a symlink planted anywhere below it changes the resolved path.
            FileSource.SANDBOX -> "\"\$(realpath /data/user/0/$packageName 2>/dev/null)\"" + shellQuote("/files/IsoVault_Sandbox")
        }
        val want = if (rel.isEmpty()) "\"\$RB\"" else "\"\$RB\"" + shellQuote("/$rel")
        return "RB=\$(realpath ${shellQuote(base)} 2>/dev/null) || { echo 'Nothing here yet.' >&2; exit 4; }\n" +
            "EXP=$expectedBase\n" +
            "[ \"\$RB\" = \"\$EXP\" ] || { echo 'Storage folder was redirected, refused.' >&2; exit 7; }\n" +
            "WANT=$want\n" +
            "R=\$(realpath ${shellQuote(if (rel.isEmpty()) base else "$base/$rel")} 2>/dev/null) || { echo 'Not found.' >&2; exit 4; }\n" +
            "[ \"\$R\" = \"\$WANT\" ] || { echo 'Path is not what was requested (symlink?), refused.' >&2; exit 7; }\n"
    }

    fun list(context: Context, packageName: String, source: FileSource, rel: String): BrowseResult {
        val cleanRel = IsolationRuleManager.sanitizeRelative(rel)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val script = guard(context, packageName, source, cleanRel) +
            "[ -d \"\$R\" ] || { echo 'Not a folder.' >&2; exit 5; }\n" +
            "cd \"\$R\" || exit 3\n" +
            "[ \"\$(pwd -P)\" = \"\$R\" ] || { echo 'Folder changed while reading, refused.' >&2; exit 7; }\n" +
            "set --\n" +
            "for f in .[!.]* ..?* *; do\n" +
            "  [ -e \"\$f\" ] || [ -L \"\$f\" ] || continue\n" +
            "  case \"\$f\" in *[[:cntrl:]]*) continue ;; esac\n" +
            "  set -- \"\$@\" \"\$f\"\n" +
            "done\n" +
            "if [ \$# -gt 0 ]; then stat -c '$nonce|%F|%s|%Y|%n' -- \"\$@\" 2>/dev/null </dev/null; fi\n" +
            "exit 0\n"

        val result = MountManager.runAsRoot(script)
        if (result.timedOut) return BrowseResult(emptyList(), "Timed out while reading the folder.")
        return when (result.exitCode) {
            0 -> BrowseResult(
                parseListing(result.output, cleanRel, nonce).map { it.copy(packageName = packageName, source = source) },
                null
            )
            4 -> BrowseResult(emptyList(), null) // nothing saved there yet
            127 -> BrowseResult(emptyList(), "Root is needed to read app files, or a tool is missing. ${result.output}".trim())
            else -> BrowseResult(emptyList(), result.output.lineSequence().firstOrNull { it.isNotBlank() }
                ?: "Could not read the folder (exit ${result.exitCode}).")
        }
    }

    /**
     * Streams one regular file into [out], at most [maxBytes]. Returns null on success,
     * otherwise an error message.
     */
    fun copyOut(
        context: Context,
        packageName: String,
        source: FileSource,
        rel: String,
        out: OutputStream,
        maxBytes: Long = Long.MAX_VALUE
    ): String? {
        val cleanRel = IsolationRuleManager.sanitizeRelative(rel)
        if (cleanRel.isEmpty()) return "Choose a file."
        val script = guard(context, packageName, source, cleanRel) +
            "[ -f \"\$R\" ] || { echo 'Not a file.' >&2; exit 5; }\n" +
            // Open once, then check the file that was actually opened.
            "exec 3< \"\$R\" || exit 3\n" +
            "[ \"\$(readlink /proc/self/fd/3)\" = \"\$WANT\" ] && [ -f /proc/self/fd/3 ] || { echo 'File changed while opening, refused.' >&2; exit 7; }\n" +
            "exec cat <&3\n"

        val process = try {
            ProcessBuilder("su", "-c", script).start()
        } catch (e: Exception) {
            return "Root is needed to read app files: ${e.message}"
        }
        val finished = AtomicBoolean(false)
        // A file swapped for a pipe would block forever; the watchdog ends the copy.
        Thread {
            try { Thread.sleep(COPY_DEADLINE_MS) } catch (_: InterruptedException) { return@Thread }
            if (!finished.get()) process.destroyForcibly()
        }.apply { isDaemon = true; start() }

        val err = StringBuilder()
        val drain = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        try {
            process.outputStream.close()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            process.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("File is larger than expected, stopped.")
                    out.write(buffer, 0, n)
                }
            }
            out.flush()
        } catch (e: Exception) {
            finished.set(true)
            process.destroyForcibly()
            drain.join(500)
            return "Copy failed: ${e.message}"
        }
        val code = process.waitFor()
        finished.set(true)
        drain.join(1000)
        return if (code == 0) null else err.toString().trim().ifBlank { "Copy failed (exit $code)." }
    }

    /** Copies one file into a fresh file in [cacheDir]. The caller must delete it after use. */
    fun copyToCache(
        context: Context,
        packageName: String,
        source: FileSource,
        rel: String,
        cacheDir: File,
        maxBytes: Long
    ): Pair<File?, String?> {
        cacheDir.mkdirs()
        val target = File(cacheDir, "v_${System.nanoTime()}.tmp")
        val error = try {
            target.outputStream().use { copyOut(context, packageName, source, rel, it, maxBytes) }
        } catch (e: Exception) {
            "Copy failed: ${e.message}"
        }
        if (error != null) {
            target.delete()
            return Pair(null, error)
        }
        return Pair(target, null)
    }

    /**
     * Deletes one file or folder. The owning app is force-stopped first and the path is checked
     * again right before the delete, so the app cannot swap something in between.
     */
    fun delete(context: Context, packageName: String, source: FileSource, rel: String): String? {
        val cleanRel = IsolationRuleManager.sanitizeRelative(rel)
        if (cleanRel.isEmpty()) return "Refusing to delete the whole storage. Use Purge for that."
        require(PACKAGE_REGEX.matches(packageName)) { "Invalid package name" }
        val script = "am force-stop $packageName </dev/null >/dev/null 2>&1\n" +
            "sleep 1\n" +
            guard(context, packageName, source, cleanRel) +
            "rm -rf -- \"\$R\" </dev/null\n" +
            "[ ! -e \"\$R\" ] || { echo 'Could not delete everything.' >&2; exit 10; }\n"
        val result = MountManager.runAsRoot(script, MountManager.LONG_TIMEOUT_SECONDS)
        if (result.timedOut) return "Timed out while deleting."
        return if (result.exitCode == 0) null
        else result.output.lineSequence().firstOrNull { it.isNotBlank() } ?: "Delete failed (exit ${result.exitCode})."
    }
}
