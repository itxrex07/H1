package com.hollow.app.isolation.mount

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.BufferedReader
import com.hollow.app.isolation.crypto.AesVaultCipher
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class MountEntry(
    val device: String,
    val mountPoint: String,
    val fsType: String,
    val options: String
)

data class MountExecutionResult(
    val isSuccess: Boolean,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Root-backed per-app storage volumes.
 *
 * A volume is a directory owned by the manager that is bind-mounted over /storage/emulated/0
 * inside the mount namespace of ONE target app's processes, so only that app sees it and the
 * real shared storage is invisible to it. Nothing here pretends to succeed: every operation runs
 * real commands through `su` and reports the real exit code and output.
 *
 * Limits that cannot be fixed from userspace: a mount applied to a running process does not reach
 * processes started later (the boot service re-attaches them shortly after they start), and the
 * system MediaProvider still serves the real MediaStore, which is why the LSPosed MediaStore hooks
 * stay active alongside the mount.
 */
object MountManager {

    const val VOLUME_MARKER = ".isovault_volume"
    internal const val LONG_TIMEOUT_SECONDS = 600L

    private val locks = ConcurrentHashMap<String, Any>()

    /** Every attach / detach / seal / unseal / purge of one app runs under this lock. */
    private fun lockFor(packageName: String): Any = locks.getOrPut(packageName) { Any() }
    private const val EXTERNAL_ROOT = "/storage/emulated/0"
    private const val SU_TIMEOUT_SECONDS = 20L

    // ---- Inspection (no root needed) -------------------------------------------------------

    fun isStorageManagerGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /** Reads the kernel mount table visible to this app. Returns an empty list if unreadable. */
    fun readSystemMounts(): List<MountEntry> {
        val mounts = mutableListOf<MountEntry>()
        for (path in listOf("/proc/self/mounts", "/proc/mounts")) {
            val file = File(path)
            if (!file.canRead()) continue
            try {
                BufferedReader(FileReader(file)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        val parts = line.split(" ")
                        if (parts.size >= 4) {
                            mounts.add(MountEntry(parts[0], parts[1], parts[2], parts[3]))
                        }
                    }
                }
                if (mounts.isNotEmpty()) return mounts
            } catch (_: Exception) {
            }
        }
        return mounts
    }

    /** "Enforcing", "Permissive" or "Unknown" (apps are normally not allowed to read the flag). */
    fun getSelinuxStatus(): String {
        return try {
            val file = File("/sys/fs/selinux/enforce")
            if (!file.canRead()) return "Unknown"
            when (file.readText().trim()) {
                "1" -> "Enforcing"
                "0" -> "Permissive"
                else -> "Unknown"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    // ---- Root execution --------------------------------------------------------------------

    internal data class ShellResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    internal fun runAsRoot(script: String, timeoutSeconds: Long = SU_TIMEOUT_SECONDS): ShellResult {
        // The script is an argument, not stdin, so no command in it can swallow the rest of it.
        val process = try {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return ShellResult(127, "su is not available: ${e.message}", false)
        }
        try {
            process.outputStream.close()
        } catch (_: Exception) {
        }

        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds)
        var exit: Int? = null
        while (exit == null && System.currentTimeMillis() < deadline) {
            exit = try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50)
                null
            }
        }
        val timedOut = exit == null
        if (timedOut) process.destroy()
        reader.join(1000)
        return ShellResult(exit ?: 124, output.toString().trim(), timedOut)
    }

    fun isRootAvailable(): Boolean {
        val result = runAsRoot("id -u")
        return result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "0" }
    }

    data class DetailedRootDiagnostics(
        val isRootAvailable: Boolean,
        val selinuxMode: String,
        val suVersion: String,
        val nsenterSupported: Boolean,
        val activeMountsCount: Int,
        val details: String
    )

    fun getDetailedRootDiagnostics(): DetailedRootDiagnostics {
        val rootOk = isRootAvailable()
        val selinux = getSelinuxStatus()
        val suVerResult = runAsRoot("su -v 2>/dev/null || su --version 2>/dev/null || echo 'standard'")
        val suVer = suVerResult.output.lines().firstOrNull()?.trim() ?: "unknown"
        val nsenterResult = runAsRoot("which nsenter 2>/dev/null || [ -x /system/bin/nsenter ] && echo 'ok' || echo 'missing'")
        val nsenterOk = nsenterResult.output.contains("nsenter") || nsenterResult.output.contains("ok")
        val mounts = readSystemMounts()

        val details = buildString {
            append("Root: ${if (rootOk) "UID 0 (Available)" else "Not Available"}\n")
            append("SELinux: $selinux\n")
            append("SU binary: $suVer\n")
            append("Namespace isolation (nsenter): ${if (nsenterOk) "Supported" else "Missing / Unavailable"}\n")
            append("Active visible mounts: ${mounts.size}")
        }

        return DetailedRootDiagnostics(
            isRootAvailable = rootOk,
            selinuxMode = selinux,
            suVersion = suVer,
            nsenterSupported = nsenterOk,
            activeMountsCount = mounts.size,
            details = details
        )
    }

    // ---- Volumes ---------------------------------------------------------------------------

    fun volumeDir(context: Context, packageName: String): File =
        File(context.filesDir, "vaults/$packageName/storage")

    private fun requirePackageName(packageName: String) {
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName)) {
            "Invalid package name"
        }
    }

    /**
     * Shell snippet that defines pids_of() and prepares the volume directory for [packageName]:
     * ownership and SELinux label are copied from the app's own data directory so the app can use
     * the mounted directory as ordinary storage.
     */
    private fun commonShell(packageName: String, volume: String): String = """
        PKG='$packageName'
        VOL='$volume'
        MARK="${'$'}VOL/$VOLUME_MARKER"
        pids_of() {
          for p in /proc/[0-9]*; do
            n=${'$'}(tr '\0' '\n' < "${'$'}p/cmdline" 2>/dev/null | head -n1)
            case "${'$'}n" in
              "${'$'}PKG"|"${'$'}PKG":*) echo "${'$'}{p#/proc/}" ;;
            esac
          done
        }
    """.trimIndent()

    private fun attachShell(packageName: String, volume: String): String = commonShell(packageName, volume) + """

        APP_DATA="/data/user/0/${'$'}PKG"
        [ -d "${'$'}APP_DATA" ] || APP_DATA="/data/data/${'$'}PKG"
        [ -d "${'$'}APP_DATA" ] || { echo "ERROR: ${'$'}PKG is not installed for user 0"; exit 2; }
        UID_APP=${'$'}(stat -c %u "${'$'}APP_DATA")
        CTX=${'$'}(ls -dZ "${'$'}APP_DATA" | awk '{print ${'$'}1}')
        mkdir -p "${'$'}VOL" "${'$'}VOL/Android/data/${'$'}PKG" "${'$'}VOL/Android/media/${'$'}PKG" || exit 3
        : > "${'$'}MARK"
        chown -R "${'$'}UID_APP:${'$'}UID_APP" "${'$'}VOL" || exit 4
        case "${'$'}CTX" in
          u:object_r:*)
            chcon -R "${'$'}CTX" "${'$'}VOL" 2>/dev/null || restorecon -R "${'$'}VOL" 2>/dev/null || echo "WARN: chcon/restorecon fallback applied"
            ;;
          *)
            restorecon -R "${'$'}VOL" 2>/dev/null || true
            ;;
        esac
        PIDS=${'$'}(pids_of)
        [ -n "${'$'}PIDS" ] || { echo "ERROR: ${'$'}PKG is not running. Open the app, then attach."; exit 5; }
        RC=0
        for pid in ${'$'}PIDS; do
          if nsenter -t "${'$'}pid" -m test -f "$EXTERNAL_ROOT/$VOLUME_MARKER" 2>/dev/null; then
            echo "pid ${'$'}pid: already attached"
            continue
          fi
          if nsenter -t "${'$'}pid" -m mount --bind "${'$'}VOL" "$EXTERNAL_ROOT"; then
            echo "pid ${'$'}pid: attached"
          else
            echo "pid ${'$'}pid: mount FAILED"
            RC=6
          fi
        done
        exit ${'$'}RC
    """.trimIndent()

    private fun detachShell(packageName: String, volume: String): String = commonShell(packageName, volume) + """

        PIDS=${'$'}(pids_of)
        [ -n "${'$'}PIDS" ] || { echo "${'$'}PKG is not running; nothing to detach"; exit 0; }
        RC=0
        for pid in ${'$'}PIDS; do
          # Only ever unmount a volume that carries our marker, never the real storage.
          if nsenter -t "${'$'}pid" -m test -f "$EXTERNAL_ROOT/$VOLUME_MARKER" 2>/dev/null; then
            if nsenter -t "${'$'}pid" -m umount -l "$EXTERNAL_ROOT"; then
              echo "pid ${'$'}pid: detached"
              nsenter -t "${'$'}pid" -m restorecon -R "$EXTERNAL_ROOT" 2>/dev/null || true
            else
              echo "pid ${'$'}pid: umount FAILED"
              RC=6
            fi
          else
            echo "pid ${'$'}pid: not attached"
          fi
        done
        exit ${'$'}RC
    """.trimIndent()

    private fun statusShell(packageName: String, volume: String): String = commonShell(packageName, volume) + """

        PIDS=${'$'}(pids_of)
        [ -n "${'$'}PIDS" ] || { echo "not running"; exit 0; }
        for pid in ${'$'}PIDS; do
          if nsenter -t "${'$'}pid" -m test -f "$EXTERNAL_ROOT/$VOLUME_MARKER" 2>/dev/null; then
            echo "pid ${'$'}pid: attached"
          else
            echo "pid ${'$'}pid: not attached"
          fi
        done
    """.trimIndent()

    fun attachVolume(context: Context, packageName: String): MountExecutionResult {
        requirePackageName(packageName)
        return synchronized(lockFor(packageName)) {
            File(context.filesDir, "vaults/$packageName").mkdirs()
            execute("attach $packageName", attachShell(packageName, volumeDir(context, packageName).absolutePath))
        }
    }

    fun detachVolume(context: Context, packageName: String): MountExecutionResult {
        requirePackageName(packageName)
        return synchronized(lockFor(packageName)) {
            execute("detach $packageName", detachShell(packageName, volumeDir(context, packageName).absolutePath))
        }
    }

    fun volumeStatus(context: Context, packageName: String): MountExecutionResult {
        requirePackageName(packageName)
        return synchronized(lockFor(packageName)) {
            execute("status $packageName", statusShell(packageName, volumeDir(context, packageName).absolutePath))
        }
    }


    // ---- Encrypted volumes: seal / unseal --------------------------------------------------
    //
    // A volume is plain files while it is in use (the app has to read and write it). Sealing
    // stops the app, packs the volume with tar, encrypts the stream with AES-256-GCM (Keystore
    // wrapped key), proves the archive decrypts completely and matches the packed size, makes it
    // durable, and only then deletes the plaintext. Unsealing reverses it. While a volume is
    // sealed there is no plaintext of it on the device.
    //
    // Sealed archives can only be opened with this app's Keystore key: clearing the manager's
    // data or uninstalling it makes them permanently unreadable.

    private const val ARCHIVE_NAME = "volume.sealed"
    private const val SEALING_MARKER = ".sealing"

    fun sealedArchive(context: Context, packageName: String): File =
        File(context.filesDir, "vaults/$packageName/$ARCHIVE_NAME")

    fun isSealed(context: Context, packageName: String): Boolean =
        sealedArchive(context, packageName).isFile

    private class DiscardingOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private class SuProcess(val process: Process) {
        val stderr = StringBuilder()
        private val drain = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        fun finish(): Int {
            val code = process.waitFor()
            drain.join(1000)
            return code
        }

        /** Kills the whole `su` child and collects what it printed. */
        fun abort(): String {
            process.destroyForcibly()
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
            }
            drain.join(1000)
            return stderr.toString().trim()
        }
    }

    private fun startSu(command: String): SuProcess =
        SuProcess(ProcessBuilder("su", "-c", command).start())

    private fun failure(label: String, message: String, code: Int = 1) =
        MountExecutionResult(isSuccess = false, command = label, output = message, exitCode = code)

    /** The volume directory, only if it really is <filesDir>/vaults/<pkg>/storage (no symlink tricks). */
    private fun safeVolume(context: Context, packageName: String): File? {
        val expected = File(File(context.filesDir.canonicalPath), "vaults/$packageName/storage").path
        val actual = volumeDir(context, packageName)
        return if (actual.canonicalPath == expected) actual else null
    }

    /** Stops the app and fails unless it is really gone. */
    private fun stopAppShell(packageName: String, volume: String): String =
        commonShell(packageName, volume) + "\n" +
            "am force-stop \"${'$'}PKG\" </dev/null\n" +
            "sleep 2\n" +
            "[ -z \"${'$'}(pids_of)\" ] || { echo 'ERROR: app is still running'; exit 8; }\n"

    /**
     * Stops the app, encrypts its volume into [sealedArchive] and deletes the plaintext.
     * The app is force-stopped first: sealing a volume that is being written would corrupt it.
     * Nothing is deleted unless a complete, verified, durable archive exists.
     */
    fun sealVolume(context: Context, packageName: String): MountExecutionResult {
        requirePackageName(packageName)
        return synchronized(lockFor(packageName)) { sealLocked(context, packageName) }
    }

    private fun sealLocked(context: Context, packageName: String): MountExecutionResult {
        val label = "seal $packageName"
        val volume = safeVolume(context, packageName) ?: return failure(label, "Unexpected volume path.")
        val archive = sealedArchive(context, packageName)
        val part = File(archive.parentFile, "$ARCHIVE_NAME.part")
        val sealing = File(archive.parentFile, SEALING_MARKER)

        if (archive.exists()) return failure(label, "Already sealed.")
        archive.parentFile?.mkdirs()
        // Tells the boot service not to re-attach the app while it is being sealed.
        try { sealing.createNewFile() } catch (_: Exception) {}

        try {
            val stop = runAsRoot(
                stopAppShell(packageName, volume.absolutePath) +
                    "[ -d \"${'$'}VOL\" ] || { echo 'ERROR: no volume to seal'; exit 9; }\n"
            )
            if (stop.exitCode != 0) return failure(label, "Could not stop the app: ${stop.output}", stop.exitCode)

            part.delete()
            var packedBytes = 0L
            val tar = try {
                startSu("exec tar -cf - -C '${volume.absolutePath}' .")
            } catch (e: Exception) {
                return failure(label, "su is not available: ${e.message}")
            }
            try {
                tar.process.outputStream.close()
                FileOutputStream(part).use { out ->
                    packedBytes = AesVaultCipher.encryptStream(tar.process.inputStream, out)
                    out.flush()
                    out.fd.sync() // durable before anything is deleted
                }
            } catch (e: Exception) {
                val err = tar.abort()
                part.delete()
                return failure(label, "Encryption failed, nothing was deleted: ${e.message} $err".trim())
            }
            val code = tar.finish()
            if (code != 0) {
                part.delete()
                return failure(label, "tar failed (exit $code), nothing was deleted: ${tar.stderr}".trim(), code)
            }

            // Prove the archive is complete: every chunk authenticates and the size matches.
            val verifiedBytes = try {
                FileInputStream(part).use { AesVaultCipher.decryptStream(it, DiscardingOutputStream()) }
            } catch (e: Exception) {
                part.delete()
                return failure(label, "Archive failed verification, nothing was deleted: ${e.message}")
            }
            if (verifiedBytes != packedBytes) {
                part.delete()
                return failure(label, "Archive size mismatch, nothing was deleted.")
            }

            if (archive.exists() || !part.renameTo(archive)) {
                part.delete()
                return failure(label, "Could not store the sealed archive; nothing was deleted.")
            }

            // Last chance: if the app came back while we were encrypting, the archive is stale.
            val wipe = runAsRoot(
                commonShell(packageName, volume.absolutePath) + "\n" +
                    "[ -z \"${'$'}(pids_of)\" ] || { echo 'ERROR: app restarted while sealing'; exit 8; }\n" +
                    "sync\n" +
                    "[ ! -L \"${'$'}VOL\" ] || { echo 'ERROR: volume is a symlink'; exit 7; }\n" +
                    "rm -rf \"${'$'}VOL\"\n" +
                    "[ ! -e \"${'$'}VOL\" ] || { echo 'ERROR: plaintext not fully deleted'; exit 10; }\n",
                LONG_TIMEOUT_SECONDS
            )
            if (wipe.exitCode == 8 || wipe.exitCode == 7) {
                // The live volume is the newer truth. Drop the archive so it can never overwrite it.
                archive.delete()
                return failure(label, "Seal cancelled, volume left unchanged: ${wipe.output}", wipe.exitCode)
            }
            if (wipe.exitCode != 0) {
                return failure(
                    label,
                    "The encrypted archive is complete, but deleting the plain files did not finish: ${wipe.output}. Detach again to retry.",
                    wipe.exitCode
                )
            }
            return MountExecutionResult(true, label, "Sealed (${archive.length()} bytes encrypted). No plaintext left.", 0)
        } finally {
            sealing.delete()
        }
    }

    /**
     * Decrypts the sealed archive back into the volume directory. No-op if not sealed.
     * The archive is extracted next to the volume and swapped in, so an existing volume is never
     * removed before a complete extract exists. File ownership and SELinux labels are applied by
     * the attach step that always follows.
     */
    fun unsealVolume(context: Context, packageName: String): MountExecutionResult {
        requirePackageName(packageName)
        return synchronized(lockFor(packageName)) { unsealLocked(context, packageName) }
    }

    private fun unsealLocked(context: Context, packageName: String): MountExecutionResult {
        val label = "unseal $packageName"
        val archive = sealedArchive(context, packageName)
        if (!archive.isFile) return MountExecutionResult(true, label, "Not sealed.", 0)
        val volume = safeVolume(context, packageName) ?: return failure(label, "Unexpected volume path.")
        val staging = File(volume.parentFile, "storage.new")

        val prepare = runAsRoot("rm -rf '${staging.absolutePath}'; mkdir -p '${staging.absolutePath}'", LONG_TIMEOUT_SECONDS)
        if (prepare.exitCode != 0) return failure(label, "Could not prepare the volume: ${prepare.output}", prepare.exitCode)

        fun cleanup() { runAsRoot("rm -rf '${staging.absolutePath}'", LONG_TIMEOUT_SECONDS) }

        val tar = try {
            startSu("exec tar -xf - -C '${staging.absolutePath}'")
        } catch (e: Exception) {
            cleanup()
            return failure(label, "su is not available: ${e.message}")
        }
        try {
            tar.process.outputStream.use { dst ->
                FileInputStream(archive).use { src -> AesVaultCipher.decryptStream(src, dst) }
            }
        } catch (e: Exception) {
            val err = tar.abort()
            cleanup()
            return failure(label, "Decryption failed, the encrypted archive was kept: ${e.message} $err".trim())
        }
        val code = tar.finish()
        if (code != 0) {
            cleanup()
            return failure(label, "tar failed (exit $code), the encrypted archive was kept: ${tar.stderr}".trim(), code)
        }

        val swap = runAsRoot(
            "[ ! -L '${volume.absolutePath}' ] && rm -rf '${volume.absolutePath}' && mv '${staging.absolutePath}' '${volume.absolutePath}'",
            LONG_TIMEOUT_SECONDS
        )
        if (swap.exitCode != 0) {
            cleanup()
            return failure(label, "Could not put the volume in place, the encrypted archive was kept: ${swap.output}", swap.exitCode)
        }

        // The extracted volume is now the single source of truth; the archive must not outlive it.
        if (!archive.delete()) {
            return failure(
                label,
                "Unlocked, but the old encrypted archive could not be removed. Delete ${archive.absolutePath} " +
                    "before using this volume, or it would overwrite newer files on the next unlock."
            )
        }
        return MountExecutionResult(true, label, "Unsealed.", 0)
    }

    /** Stops the app and deletes its whole volume directory, including any sealed archive. */
    fun purgeVolume(context: Context, packageName: String): MountExecutionResult {
        requirePackageName(packageName)
        return synchronized(lockFor(packageName)) {
            val base = File(context.filesDir, "vaults/$packageName")
            val expected = File(File(context.filesDir.canonicalPath), "vaults/$packageName").path
            if (base.canonicalPath != expected) {
                failure("purge $packageName", "Unexpected path, refusing to delete.")
            } else {
                val script = stopAppShell(packageName, volumeDir(context, packageName).absolutePath) + "\n" +
                    "BASE='${base.absolutePath}'\n" +
                    "[ ! -L \"${'$'}BASE\" ] || { echo 'ERROR: refusing to delete a symlink'; exit 7; }\n" +
                    "rm -rf \"${'$'}BASE\"\n" +
                    "[ ! -e \"${'$'}BASE\" ] && echo purged || { echo 'ERROR: not fully deleted'; exit 10; }\n"
                execute("purge $packageName", script, LONG_TIMEOUT_SECONDS)
            }
        }
    }

    private fun execute(label: String, script: String, timeoutSeconds: Long = SU_TIMEOUT_SECONDS): MountExecutionResult {
        val result = runAsRoot(script, timeoutSeconds)
        val output = when {
            result.timedOut -> "Timed out after ${timeoutSeconds}s\n${result.output}"
            else -> result.output
        }
        return MountExecutionResult(
            isSuccess = result.exitCode == 0,
            command = label,
            output = output.ifBlank { "(no output)" },
            exitCode = result.exitCode
        )
    }

    /**
     * Boot service for Magisk / KernelSU / APatch (service.d). It watches for the given packages
     * and attaches their volume to each new process shortly after it starts. Apps that touch
     * storage in their first moments of life can still see the real storage; the zygote-level
     * route that closes that window needs a native Zygisk module.
     */
    fun generateBootService(context: Context, packages: List<String>): String {
        val valid = packages.filter { Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(it) }
        val sb = StringBuilder()
        sb.appendLine("#!/system/bin/sh")
        sb.appendLine("# IsoVault volume attach service (generated by IsoVault Manager)")
        sb.appendLine("# Attaches each isolated app's volume to its processes after they start.")
        sb.appendLine("until [ \"\$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 5; done")
        sb.appendLine()
        sb.appendLine("attach_pid() {")
        sb.appendLine("  pkg=\"\$1\"; vol=\"\$2\"; pid=\"\$3\"")
        sb.appendLine("  nsenter -t \"\$pid\" -m test -f \"$EXTERNAL_ROOT/$VOLUME_MARKER\" 2>/dev/null && return 0")
        sb.appendLine("  nsenter -t \"\$pid\" -m mount --bind \"\$vol\" \"$EXTERNAL_ROOT\"")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("prepare_volume() {")
        sb.appendLine("  pkg=\"\$1\"; vol=\"\$2\"")
        sb.appendLine("  app=\"/data/user/0/\$pkg\"")
        sb.appendLine("  [ -d \"\$app\" ] || return 1")
        sb.appendLine("  uid=\$(stat -c %u \"\$app\")")
        sb.appendLine("  ctx=\$(ls -dZ \"\$app\" | awk '{print \$1}')")
        sb.appendLine("  mgr=\$(stat -c %u '${context.filesDir.absolutePath}'); mkdir -p \"\$(dirname \"\$vol\")\"; chown \"\$mgr:\$mgr\" \"\$(dirname \"\$vol\")\"")
        sb.appendLine("  mkdir -p \"\$vol\" \"\$vol/Android/data/\$pkg\" \"\$vol/Android/media/\$pkg\"")
        sb.appendLine("  : > \"\$vol/$VOLUME_MARKER\"")
        sb.appendLine("  chown -R \"\$uid:\$uid\" \"\$vol\"; chmod 700 \"\$vol\"")
        sb.appendLine("  case \"\$ctx\" in u:object_r:*) chcon -R \"\$ctx\" \"\$vol\" ;; esac")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("while true; do")
        for (pkg in valid) {
            val vol = volumeDir(context, pkg).absolutePath
            val sealed = sealedArchive(context, pkg).absolutePath
            val sealing = File(sealedArchive(context, pkg).parentFile, SEALING_MARKER).absolutePath
            // A sealed volume can only be opened by the manager app (it holds the Keystore key),
            // and a volume that is being sealed must not be re-attached.
            sb.appendLine("  if [ ! -f '$sealed' ] && [ ! -f '$sealing' ]; then")
            sb.appendLine("  [ -f '$vol/$VOLUME_MARKER' ] || prepare_volume '$pkg' '$vol'")
            sb.appendLine("  for p in /proc/[0-9]*; do")
            sb.appendLine("    n=\$(tr '\\0' '\\n' < \"\$p/cmdline\" 2>/dev/null | head -n1)")
            sb.appendLine("    case \"\$n\" in '$pkg'|'$pkg':*) attach_pid '$pkg' '$vol' \"\${p#/proc/}\" ;; esac")
            sb.appendLine("  done")
            sb.appendLine("  fi")
        }
        sb.appendLine("  sleep 2")
        sb.appendLine("done")
        return sb.toString()
    }

    /**
     * Immediately refreshes the active Home Launcher (clearing its SQLite icon cache `app_icons.db`)
     * and optionally restarts Google Play Store / System Settings via Root (`su`) so app hiding and
     * custom icon/name spoofing take effect in < 1 second without rebooting the phone.
     */
    fun refreshLaunchersAndStealthCaches(
        context: Context,
        refreshPlayStore: Boolean = false,
        refreshSettings: Boolean = false
    ): Boolean {
        val launchers = LinkedHashSet<String>()
        try {
            val pm = context.packageManager
            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
            }
            val resolved = pm.resolveActivity(homeIntent, 0)
            val defaultPkg = resolved?.activityInfo?.packageName
            if (!defaultPkg.isNullOrBlank() && defaultPkg != "android" && defaultPkg != context.packageName) {
                launchers.add(defaultPkg)
            }
            val allHomes = pm.queryIntentActivities(homeIntent, 0)
            for (ri in allHomes) {
                val p = ri.activityInfo?.packageName ?: continue
                if (p != "android" && p != context.packageName && !p.contains("settings", ignoreCase = true)) {
                    launchers.add(p)
                }
            }
        } catch (_: Throwable) {}

        launchers.addAll(
            listOf(
                "com.google.android.apps.nexuslauncher",
                "com.android.launcher3",
                "com.miui.home",
                "com.sec.android.app.launcher",
                "com.oppo.launcher",
                "com.android.launcher",
                "net.oneplus.launcher",
                "com.nothing.launcher",
                "app.lawnchair"
            )
        )

        val pkgRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val safeLaunchers = launchers.filter { pkgRegex.matches(it) }

        val sb = StringBuilder()
        // 1. Ensure world-readable permissions on Hollow's SharedPreferences & Vector prefs bridge,
        //    and mirror to /data/system & /data/local/tmp so system_server (UID 1000) and launchers
        //    can read stealth rules even when SELinux blocks cross-app XSharedPreferences.
        val dataPath = context.dataDir?.absolutePath ?: "/data/data/${context.packageName}"
        val prefsXml = "$dataPath/shared_prefs/isovault_rules.xml"
        sb.appendLine("chmod 755 '$dataPath' '$dataPath/shared_prefs' 2>/dev/null || true")
        sb.appendLine("chmod 644 '$prefsXml' 2>/dev/null || true")
        sb.appendLine("if [ -f '$prefsXml' ]; then")
        sb.appendLine("  cp -f '$prefsXml' /data/system/isovault_rules.xml 2>/dev/null || true")
        sb.appendLine("  chown 1000:1000 /data/system/isovault_rules.xml 2>/dev/null || true")
        sb.appendLine("  chmod 644 /data/system/isovault_rules.xml 2>/dev/null || true")
        sb.appendLine("  cp -f '$prefsXml' /data/local/tmp/isovault_rules.xml 2>/dev/null || true")
        sb.appendLine("  chmod 644 /data/local/tmp/isovault_rules.xml 2>/dev/null || true")
        sb.appendLine("fi")
        sb.appendLine("chmod -R 755 /data/misc/*/prefs/${context.packageName} 2>/dev/null || true")
        sb.appendLine("chmod 644 /data/misc/*/prefs/${context.packageName}/*.xml 2>/dev/null || true")

        // 2. Wipe cached launcher icon databases (`app_icons.db`) and restart active launchers
        for (lPkg in safeLaunchers) {
            sb.appendLine("if [ -d '/data/data/$lPkg' ]; then")
            sb.appendLine("  rm -f /data/data/$lPkg/databases/app_icons.db* /data/user_de/0/$lPkg/databases/app_icons.db* 2>/dev/null || true")
            sb.appendLine("  am force-stop '$lPkg' 2>/dev/null || true")
            sb.appendLine("fi")
        }

        if (refreshPlayStore) {
            sb.appendLine("am force-stop com.android.vending 2>/dev/null || true")
        }
        if (refreshSettings) {
            sb.appendLine("am force-stop com.android.settings 2>/dev/null || true")
        }

        val res = runAsRoot(sb.toString(), timeoutSeconds = 8L)
        return res.exitCode == 0
    }
}
