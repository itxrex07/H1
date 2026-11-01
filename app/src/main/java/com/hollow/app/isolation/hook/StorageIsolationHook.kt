package com.hollow.app.isolation.hook

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Instrumentation
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.CursorWindow
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.hollow.app.isolation.model.AuditAction
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.PickerPolicy
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import com.hollow.app.isolation.mount.MountManager

/**
 * IsoVault storage isolation engine for LSPosed / Vector. Loaded via assets/xposed_init.
 *
 * For every app in the module scope that is not explicitly disabled, in-process hooks:
 *  - redirect shared-storage paths (/storage/emulated/0, /sdcard) to a private sandbox directory
 *    inside the app's own data directory, at the java.io.File, libcore and Environment layers;
 *  - answer MediaStore queries and inserts from that sandbox only, so the app's gallery shows
 *    nothing from the real phone and anything it saves stays in the sandbox;
 *  - block reads of real media URIs unless the user picked them through a picker, and copy picked
 *    files into the sandbox.
 *
 * What the hooks cannot do: they run inside the target process, so native code that talks to the
 * kernel directly is not covered, and they cannot encrypt at rest (the app's UID has no access to
 * the manager's Keystore key). When a root volume is attached by MountManager the real mount
 * already provides the isolated storage, so the path hooks stand down and only the MediaStore
 * hooks stay active.
 */
class StorageIsolationHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TAG = "IsoVaultHook"
        const val MODULE_PKG = "com.hollow.app"
        const val PROVIDER_URI = "content://com.hollow.app.provider"

        // Re-entrancy guard to prevent recursion inside File/ContentResolver/OS hooks
        private val isHandlingHook = object : ThreadLocal<Boolean>() {
            override fun initialValue(): Boolean = false
        }

        // Background executor for non-blocking audit IPC calls to IsoVault Manager
        private val auditExecutor = Executors.newSingleThreadExecutor()
        private val recentAuditCache = ConcurrentHashMap<String, Long>()

        // Fast in-memory rule cache
        @Volatile
        private var cachedRule: IsolationRuleManager.AppRule? = null
        @Volatile
        private var lastRuleFetchMs: Long = 0L

        // Fast thread-safe cache mapping virtual media ID -> actual File in isolated sandbox
        private val virtualMediaMap = ConcurrentHashMap<Long, File>()
        // Fast thread-safe cache mapping content URI string -> actual File in isolated sandbox
        private val virtualUriMap = ConcurrentHashMap<String, File>()
        private val nextVirtualId = AtomicLong(100_000L)

        // Cache of URIs explicitly selected by the user via file/media pickers
        private val userAllowedUris = ConcurrentHashMap<String, Boolean>()

        private val MEDIA_AUTHORITIES = setOf(
            "media",
            "com.android.providers.media.documents",
            "com.android.providers.media.photopicker",
            "com.google.android.providers.media.module",
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "downloads"
        )

        private val DEFAULT_MEDIA_COLUMNS = arrayOf(
            "_id",
            "_data",
            "_display_name",
            "_size",
            "mime_type",
            "date_added",
            "date_modified",
            "datetaken",
            "width",
            "height",
            "orientation",
            "bucket_id",
            "bucket_display_name",
            "relative_path",
            "duration",
            "media_type",
            "is_pending",
            "is_trashed",
            "is_favorite",
            "generation_added",
            "generation_modified",
            "volume_name",
            "owner_package_name",
            "title",
            "album",
            "artist",
            "resolution",
            "is_drm",
            "parent",
            "format",
            "storage_id"
        )
    }

    /**
     * Crash-proof empty MatrixCursor for when the sandbox has 0 media files.
     * Prevents IllegalArgumentException in apps (e.g. Snapchat) that call getColumnIndexOrThrow before moveToFirst().
     */
    private class SafeEmptyMatrixCursor(projection: Array<String>) : MatrixCursor(projection, 0) {
        override fun getCount(): Int = 0

        override fun getColumnIndex(columnName: String?): Int {
            if (columnName == null) return 0
            val idx = super.getColumnIndex(columnName)
            return if (idx >= 0) idx else 0
        }

        override fun getColumnIndexOrThrow(columnName: String?): Int {
            if (columnName == null) return 0
            val idx = super.getColumnIndex(columnName)
            return if (idx >= 0) idx else 0
        }

        override fun getExtras(): Bundle = Bundle.EMPTY
    }

    /**
     * Crash-proof MatrixCursor populated with the isolated sandbox's media files.
     */
    private class SafeSandboxMatrixCursor(projection: Array<String>) : MatrixCursor(projection) {
        override fun getColumnIndex(columnName: String?): Int {
            if (columnName == null) return 0
            val idx = super.getColumnIndex(columnName)
            return if (idx >= 0) idx else 0
        }

        override fun getColumnIndexOrThrow(columnName: String?): Int {
            if (columnName == null) return 0
            val idx = super.getColumnIndex(columnName)
            return if (idx >= 0) idx else 0
        }

        override fun getExtras(): Bundle = Bundle.EMPTY
    }

    @Volatile
    private var xSharedPrefs: XSharedPreferences? = null

    private val storageHooksAttachedInProcess = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        // Never instantiate XSharedPreferences inside initZygote — spawning disk-loader threads
        // or calling SELinux services during early Zygote / system_server bootstrap can hang boot.
        Log.i(TAG, "[IsoVault] Zygote initialized on Vector / LSPosed.")
    }

    private fun isCoreSystemProcessOrPackage(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String
    ): Boolean {
        val proc = lpparam.processName ?: pkg
        val uid = android.os.Process.myUid()
        val appId = uid % 100_000

        // Never run storage isolation inside system_server (UID 1000) or any non-app UID (< 10000)
        if (appId < android.os.Process.FIRST_APPLICATION_UID || appId == android.os.Process.SYSTEM_UID) {
            return true
        }
        if (pkg == "android" || pkg == "system" || proc == "android" || proc == "system" || proc == "system_server") {
            return true
        }
        if (pkg.startsWith("com.android.systemui") || proc.startsWith("com.android.systemui")) {
            return true
        }
        if (pkg.startsWith("com.android.providers.") ||
            pkg.startsWith("com.android.server.") ||
            pkg.startsWith("com.android.internal.") ||
            pkg.startsWith("com.android.location.") ||
            pkg == "com.android.settings" ||
            pkg == "com.android.vending" ||
            pkg.contains("permissioncontroller") ||
            pkg == "com.android.phone" ||
            pkg == "com.android.nfc" ||
            pkg == "com.android.bluetooth" ||
            pkg == "com.android.shell" ||
            pkg == "com.android.inputdevices" ||
            pkg == "com.android.keychain" ||
            pkg.contains("documentsui") ||
            pkg == "com.android.externalstorage" ||
            pkg == "com.google.android.providers.media.module"
        ) {
            return true
        }
        return false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return

        // Self-hook for Manager App to confirm module status (exempted from hiding/spoofing)
        if (pkg == MODULE_PKG) {
            for (cls in listOf("com.hollow.app.MainActivity\$Companion", "com.hollow.app.MainActivity")) {
                try {
                    XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "isLsposedModuleActiveReal",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
                        })
                } catch (_: Throwable) {}
            }
            try {
                val bridgeCls = "com.hollow.app.isolation.hook.IsoVaultHookBridge"
                XposedHelpers.findAndHookMethod(bridgeCls, lpparam.classLoader, "isHookActive",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
                    })
                XposedHelpers.findAndHookMethod(bridgeCls, lpparam.classLoader, "getHookVersion",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { param.result = 3 }
                    })
                XposedHelpers.findAndHookMethod(bridgeCls, lpparam.classLoader, "getHeartbeatTimestamp",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { param.result = System.currentTimeMillis() }
                    })
            } catch (_: Throwable) {}
            return
        }

        // Attach System-Wide App Hider, Custom Name/Icon Disguise & Direct SystemUI Injection
        // Runs non-blockingly and only once per process.
        try {
            SystemStealthHook.attachForPackage(lpparam, pkg, xSharedPrefs)
        } catch (_: Throwable) {}

        // Strictly block storage & identity hooks from ever attaching inside system_server,
        // SystemUI, shared UID 1000 sub-packages, or core system packages.
        if (isCoreSystemProcessOrPackage(lpparam, pkg)) {
            return
        }

        // Only attach hooks to the primary package of an application process
        val proc = lpparam.processName ?: pkg
        if (!lpparam.isFirstApplication && proc != pkg) {
            return
        }

        // Attach Per-App Device Identity & Privacy Spoofing hooks (independent of storage isolation)
        try {
            DeviceIdentitySpoofHook.attachForPackage(lpparam, pkg, xSharedPrefs)
        } catch (_: Throwable) {}

        val rule = resolveRuleForPackage(pkg)
        if (!rule.isIsolated) {
            XposedBridge.log("[IsoVault] Skipping storage hooks for $pkg (not isolated)")
            return
        }

        if (!storageHooksAttachedInProcess.compareAndSet(false, true)) {
            return
        }

        XposedBridge.log("[IsoVault] >>> ACTIVATING FULL ISOLATION FOR: $pkg (Mode: ${rule.mode}) <<<")
        Log.i(TAG, "[IsoVault] Activating full isolation for $pkg (Mode: ${rule.mode})")

        try {
            attachMediaStoreAndProviderHooks(lpparam, pkg)
            attachPickerInterceptorHooks(lpparam, pkg)
            attachSnapchatAndGallerySqliteHooks(lpparam, pkg)
            attachFileHooks(lpparam, pkg, rule)
            attachLowLevelOsHooks(lpparam, pkg, rule)
            attachEnvironmentHooks(lpparam, pkg, rule)
        } catch (t: Throwable) {
            XposedBridge.log("[IsoVault] Error attaching hooks to $pkg: ${t.message}")
            Log.e(TAG, "Error attaching hooks to $pkg", t)
        }
    }

    private fun registerSandboxMedia(file: File): Long {
        for ((id, f) in virtualMediaMap) {
            if (f.absolutePath == file.absolutePath) {
                return id
            }
        }
        val id = nextVirtualId.incrementAndGet()
        virtualMediaMap[id] = file
        val uriStr = "content://media/external/images/media/$id"
        virtualUriMap[uriStr] = file
        virtualUriMap["content://media/external/video/media/$id"] = file
        virtualUriMap["content://media/external/audio/media/$id"] = file
        virtualUriMap["content://media/external/downloads/$id"] = file
        virtualUriMap["content://media/external/file/$id"] = file
        return id
    }

    private fun resolveRuleForPackage(pkg: String): IsolationRuleManager.AppRule {
        val now = System.currentTimeMillis()
        val existing = cachedRule
        if (existing != null && existing.packageName == pkg && (now - lastRuleFetchMs) < 2500L) {
            return existing
        }

        val resolved = fetchRuleInternal(pkg)
        cachedRule = resolved
        lastRuleFetchMs = now
        return resolved
    }

    private fun fetchRuleInternal(pkg: String): IsolationRuleManager.AppRule {
        // 1. Try live IPC call to IsoVaultConfigProvider
        try {
            val app = AndroidAppHelper.currentApplication()
            if (app != null) {
                val bundle = app.contentResolver.call(
                    Uri.parse(PROVIDER_URI),
                    "check_isolated",
                    pkg,
                    null
                )
                if (bundle != null && bundle.getBoolean("isConfigured", false)) {
                    val explicitlyDisabled = bundle.getBoolean("explicitlyDisabled", false)
                    val isolated = bundle.getBoolean("isIsolated", true) && !explicitlyDisabled
                    val modeStr = bundle.getString("mode", "SHARED_WORKSPACE") ?: "SHARED_WORKSPACE"
                    val mode = try { IsolationMode.valueOf(modeStr) } catch (_: Exception) { IsolationMode.SHARED_WORKSPACE }
                    val pollution = bundle.getBoolean("blockPollution", true)
                    val whitelistStr = bundle.getString("whitelistedFolders", "") ?: ""
                    val whitelist = whitelistStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    val picker = parsePickerPolicy(bundle.getString("pickerPolicy"))
                    return IsolationRuleManager.AppRule(pkg, isolated, mode, pollution, whitelist, picker)
                }
            }
        } catch (_: Throwable) {}

        // 2. Try XSharedPreferences
        try {
            val prefs = xSharedPrefs ?: XSharedPreferences(MODULE_PKG, IsolationRuleManager.PREFS_NAME).also {
                xSharedPrefs = it
            }
            prefs.reload()
            if (prefs.getBoolean("${pkg}_configured", false)) {
                val explicitlyDisabled = prefs.getBoolean("${pkg}_explicitly_disabled", false)
                val isolated = prefs.getBoolean("${pkg}_isolated", true) && !explicitlyDisabled
                val modeStr = prefs.getString("${pkg}_mode", "SHARED_WORKSPACE") ?: "SHARED_WORKSPACE"
                val mode = try { IsolationMode.valueOf(modeStr) } catch (_: Exception) { IsolationMode.SHARED_WORKSPACE }
                val pollution = prefs.getBoolean("${pkg}_pollution", true)
                val whitelistStr = prefs.getString("${pkg}_whitelist", "") ?: ""
                val whitelist = whitelistStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                val picker = parsePickerPolicy(prefs.getString(IsolationRuleManager.KEY_PICKER_POLICY, null))
                return IsolationRuleManager.AppRule(pkg, isolated, mode, pollution, whitelist, picker)
            }
        } catch (_: Throwable) {}

        // 3. Default: connect regular apps in scope to Hollow Drive (SHARED_WORKSPACE),
        //    but do not isolate home launchers by default.
        val isLauncherPkg = pkg.contains("launcher", ignoreCase = true) ||
                pkg == "com.miui.home" ||
                pkg == "com.bbk.launcher2" ||
                pkg == "net.oneplus.launcher"
        return IsolationRuleManager.AppRule(
            packageName = pkg,
            isIsolated = !isLauncherPkg,
            mode = IsolationMode.SHARED_WORKSPACE,
            blockRootPollution = true,
            whitelistedFolders = emptySet()
        )
    }

    private fun parsePickerPolicy(name: String?): PickerPolicy =
        try { PickerPolicy.valueOf(name ?: "ALLOW") } catch (_: IllegalArgumentException) { PickerPolicy.ALLOW }

    /**
     * Cancels a picker the app tried to open: the app is told the user backed out, exactly as if
     * they had pressed back in the picker.
     */
    private fun cancelPicker(activity: Activity, requestCode: Int) {
        Handler(Looper.getMainLooper()).post {
            try {
                XposedHelpers.callMethod(
                    activity,
                    "onActivityResult",
                    arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java),
                    requestCode,
                    Activity.RESULT_CANCELED,
                    null
                )
            } catch (t: Throwable) {
                XposedBridge.log("[IsoVault] Could not deliver picker cancel: ${t.message}")
            }
        }
    }

    private fun isGalleryWhitelisted(rule: IsolationRuleManager.AppRule): Boolean {
        return rule.whitelistedFolders.any {
            it.equals("DCIM", ignoreCase = true) ||
                it.equals("Pictures", ignoreCase = true) ||
                it.equals("Camera", ignoreCase = true) ||
                it.equals("All", ignoreCase = true)
        }
    }

    private fun isMediaOrExternalDocumentUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme == "file") {
            val path = uri.path ?: return false
            return shouldInterceptPath("", path)
        }
        if (scheme != "content") return false
        val auth = uri.authority?.lowercase() ?: return false
        return auth in MEDIA_AUTHORITIES || auth.startsWith("com.google.android.apps.photos")
    }

    private fun isMediaFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic",
            "mp4", "mkv", "3gp", "webm", "avi", "mov",
            "mp3", "m4a", "wav", "ogg", "aac", "flac"
        )
    }

    private class MediaProbe(val width: Int?, val height: Int?, val durationMs: Long?)

    private val probeCache = ConcurrentHashMap<String, MediaProbe>()

    /** Reads the real dimensions / duration of a sandbox media file. Unknown values stay null. */
    private fun probeMedia(file: File, mime: String): MediaProbe {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        probeCache[key]?.let { return it }
        val probe = try {
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    MediaProbe(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    )
                } finally {
                    retriever.release()
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                MediaProbe(
                    opts.outWidth.takeIf { it > 0 },
                    opts.outHeight.takeIf { it > 0 },
                    null
                )
            }
        } catch (_: Throwable) {
            MediaProbe(null, null, null)
        }
        if (probeCache.size > 2000) probeCache.clear()
        probeCache[key] = probe
        return probe
    }

    private fun getMimeTypeFromExtension(ext: String): String {
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/x-wav"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun guessExtension(uri: Uri): String {
        val path = uri.path ?: ""
        val last = path.substringAfterLast('/')
        if (last.contains('.')) {
            val ext = last.substringAfterLast('.').lowercase()
            if (ext.length in 2..4) return ext
        }
        val uriStr = uri.toString().lowercase()
        return when {
            uriStr.contains("video") -> "mp4"
            uriStr.contains("audio") -> "mp3"
            else -> "jpg"
        }
    }

    /**
     * LAYER 1: Full Virtual MediaStore & ContentProvider Engine.
     * 1. MediaStore.insert / openOutputStream:
     *    Creates files in the app's isolated sandbox and provides writable descriptors.
     *    Apps can save photos, videos, and downloads seamlessly without "Failed to save".
     * 2. MediaStore.query:
     *    Returns files present in the app's isolated sandbox.
     *    Camera roll in WhatsApp/Snapchat shows ONLY the app's isolated media (0 real phone photos).
     * 3. Media reads / thumbnails:
     *    Streams sandbox media to the app while blocking access to real phone media URIs.
     */
    private fun attachMediaStoreAndProviderHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String
    ) {
        // A. MediaStore & ContentProvider QUERIES (Camera Roll)
        val mediaQueryHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return

                val uriIndex = param.args.indexOfFirst { it is Uri }
                if (uriIndex < 0) return
                val uri = param.args[uriIndex] as? Uri ?: return
                if (!isMediaOrExternalDocumentUri(uri)) return

                isHandlingHook.set(true)
                try {
                    val rule = resolveRuleForPackage(pkg)
                    if (!rule.isIsolated || rule.mode == IsolationMode.READ_ONLY) return
                    if (isGalleryWhitelisted(rule)) return

                    val requestedProjection = param.args.getOrNull(uriIndex + 1) as? Array<*>
                    val mergedColumns = LinkedHashSet<String>()
                    requestedProjection?.forEach { col ->
                        if (col is String && col.isNotBlank()) mergedColumns.add(col)
                    }
                    DEFAULT_MEDIA_COLUMNS.forEach { mergedColumns.add(it) }

                    val sandboxRoot = getOrCreateSandboxRoot(pkg)
                    val mediaFiles = mutableListOf<File>()

                    // Scan sandbox directories for files saved by this isolated app
                    try {
                        val searchDirs = listOf(
                            File(sandboxRoot, "DCIM"),
                            File(sandboxRoot, "Pictures"),
                            File(sandboxRoot, "Movies"),
                            File(sandboxRoot, "Download"),
                            File(sandboxRoot, "Android/media/$pkg"),
                            sandboxRoot
                        )
                        for (dir in searchDirs) {
                            if (dir.exists()) {
                                dir.walkTopDown()
                                    // Chat apps commonly save media several levels below
                                    // Android/media/<package>/WhatsApp/Media/...
                                    .maxDepth(12)
                                    .filter { it.isFile && isMediaFile(it) && !it.name.startsWith(".") }
                                    .take(500)
                                    .forEach { mediaFiles.add(it) }
                            }
                        }
                    } catch (_: Throwable) {}

                    if (mediaFiles.isEmpty()) {
                        param.result = SafeEmptyMatrixCursor(mergedColumns.toTypedArray())
                    } else {
                        val cursor = SafeSandboxMatrixCursor(mergedColumns.toTypedArray())
                        for (file in mediaFiles.distinctBy { it.absolutePath }) {
                            val virtualId = registerSandboxMedia(file)
                            val ext = file.extension.lowercase()
                            val mime = getMimeTypeFromExtension(ext)
                            val isVideo = mime.startsWith("video/")
                            val isAudio = mime.startsWith("audio/")
                            val probe = probeMedia(file, mime)
                            val relDir = try {
                                file.parentFile?.relativeToOrSelf(sandboxRoot)?.path ?: "Pictures"
                            } catch (_: Throwable) { "Pictures" }

                            val row = arrayOfNulls<Any>(mergedColumns.size)
                            mergedColumns.forEachIndexed { index, col ->
                                row[index] = when (col) {
                                    "_id", "id" -> virtualId
                                    "_data", "data" -> file.absolutePath
                                    "_display_name", "display_name" -> file.name
                                    "_size", "size" -> file.length()
                                    "mime_type" -> mime
                                    "date_added" -> file.lastModified() / 1000
                                    "date_modified" -> file.lastModified() / 1000
                                    "datetaken" -> file.lastModified()
                                    "width" -> probe.width
                                    "height" -> probe.height
                                    "orientation" -> 0
                                    "bucket_id" -> relDir.hashCode()
                                    "bucket_display_name" -> file.parentFile?.name ?: "Pictures"
                                    "relative_path" -> if (relDir.endsWith("/")) relDir else "$relDir/"
                                    "duration" -> probe.durationMs
                                    "media_type" -> if (isVideo) 3 else if (isAudio) 2 else 1
                                    "is_pending" -> 0
                                    "is_trashed" -> 0
                                    "is_favorite" -> 0
                                    "volume_name" -> "external_primary"
                                    "owner_package_name" -> pkg
                                    "title" -> file.nameWithoutExtension
                                    else -> null
                                }
                            }
                            cursor.addRow(row)
                        }
                        param.result = cursor
                    }

                    XposedBridge.log("[IsoVault] Media query in $pkg (${mediaFiles.size} sandbox files returned)")
                    reportAuditToManager(
                        pkg = pkg,
                        requestedPath = uri.toString(),
                        resolvedPath = "${sandboxRoot.absolutePath} (${mediaFiles.size} media)",
                        action = AuditAction.REDIRECTED,
                        operation = "CAMERA_ROLL_QUERY",
                        details = "Served ${mediaFiles.size} media items from isolated sandbox"
                    )
                } finally {
                    isHandlingHook.set(false)
                }
            }
        }

        try {
            XposedBridge.hookAllMethods(ContentResolver::class.java, "query", mediaQueryHook)
            XposedBridge.hookAllMethods(ContentProviderClient::class.java, "query", mediaQueryHook)
            val proxyClass = XposedHelpers.findClass("android.content.ContentProviderProxy", null)
            XposedBridge.hookAllMethods(proxyClass, "query", mediaQueryHook)
        } catch (_: Throwable) {}

        // B. MediaStore & ContentResolver INSERT (Saving photos/videos/files)
        val mediaInsertHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return
                val uri = param.args.firstOrNull { it is Uri } as? Uri ?: return
                if (!isMediaOrExternalDocumentUri(uri)) return

                isHandlingHook.set(true)
                try {
                    val rule = resolveRuleForPackage(pkg)
                    if (!rule.isIsolated || rule.mode == IsolationMode.READ_ONLY) return

                    val values = param.args.firstOrNull { it is ContentValues } as? ContentValues
                    val sandboxRoot = getOrCreateSandboxRoot(pkg)

                    val requestedRelPath = values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.trim('/')
                        ?: values?.getAsString("relative_path")?.trim('/')
                        ?: "Pictures"
                    val relPath = IsolationRuleManager.sanitizeRelative(requestedRelPath).ifBlank { "Pictures" }
                    val requestedName = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                        ?: values?.getAsString("_display_name")
                        ?: "media_${System.currentTimeMillis()}.jpg"
                    val displayName = requestedName.substringAfterLast('/').substringAfterLast('\\')
                        .ifBlank { "media_${System.currentTimeMillis()}.jpg" }

                    val targetDir = File(sandboxRoot, relPath)
                    targetDir.mkdirs()
                    val targetFile = File(targetDir, displayName)
                    try { targetFile.createNewFile() } catch (_: Throwable) {}

                    val virtualId = registerSandboxMedia(targetFile)
                    // Keep the collection the caller inserted into. Returning an Images URI for
                    // audio/video inserts can make apps display the item but fail to reopen it.
                    val resultUri = uri.buildUpon().appendPath(virtualId.toString()).build()
                    virtualUriMap[resultUri.toString()] = targetFile

                    param.result = resultUri
                    XposedBridge.log("[IsoVault] Intercepted insert in $pkg -> sandbox file: ${targetFile.absolutePath}")
                    reportAuditToManager(
                        pkg = pkg,
                        requestedPath = uri.toString(),
                        resolvedPath = targetFile.absolutePath,
                        action = AuditAction.REDIRECTED,
                        operation = "MEDIA_INSERT",
                        details = "Created media entry inside isolated sandbox ($displayName)"
                    )
                } finally {
                    isHandlingHook.set(false)
                }
            }
        }

        try {
            XposedBridge.hookAllMethods(ContentResolver::class.java, "insert", mediaInsertHook)
            XposedBridge.hookAllMethods(ContentProviderClient::class.java, "insert", mediaInsertHook)
        } catch (_: Throwable) {}

        // C. Media Streams & Descriptors (openInputStream, openOutputStream, openFileDescriptor, openAssetFileDescriptor, etc.)
        val mediaStreamAndDescriptorHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return
                val uri = param.args.firstOrNull { it is Uri } as? Uri ?: return
                if (!isMediaOrExternalDocumentUri(uri)) return

                val methodName = param.method.name
                val uriStr = uri.toString()
                val mode = param.args.firstOrNull { it is String && it.length <= 4 } as? String ?: ""
                val isWrite = methodName.contains("OutputStream", ignoreCase = true) ||
                    mode.contains("w") || mode.contains("rw") || mode.contains("wt") || mode.contains("wa")

                isHandlingHook.set(true)
                try {
                    val rule = resolveRuleForPackage(pkg)
                    if (!rule.isIsolated || rule.mode == IsolationMode.READ_ONLY) return
                    if (isGalleryWhitelisted(rule)) return

                    val sandboxRoot = getOrCreateSandboxRoot(pkg)

                    // Find or resolve target sandbox file
                    var targetFile = virtualUriMap[uriStr]
                    if (targetFile == null) {
                        val idLong = uri.lastPathSegment?.toLongOrNull()
                        if (idLong != null) {
                            targetFile = virtualMediaMap[idLong]
                        }
                    }
                    if (targetFile == null && uri.scheme == "file") {
                        val p = uri.path
                        if (p != null && shouldInterceptPath(pkg, p)) {
                            val rel = IsolationRuleManager.extractRelative(p)
                            targetFile = File(sandboxRoot, rel)
                        }
                    }

                    if (isWrite) {
                        // SAVING MEDIA: Ensure file exists in sandbox and return write descriptor
                        if (targetFile == null) {
                            val ext = guessExtension(uri)
                            val dir = File(sandboxRoot, "Pictures")
                            dir.mkdirs()
                            targetFile = File(dir, "saved_media_${System.currentTimeMillis()}.$ext")
                            try { targetFile.createNewFile() } catch (_: Throwable) {}
                            registerSandboxMedia(targetFile)
                            virtualUriMap[uriStr] = targetFile
                        }
                        targetFile.parentFile?.mkdirs()
                        try { targetFile.createNewFile() } catch (_: Throwable) {}

                        when (methodName) {
                            "openOutputStream" -> param.result = FileOutputStream(targetFile, mode.contains("a"))
                            else -> {
                                var flags = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
                                if (!mode.contains("a")) flags = flags or ParcelFileDescriptor.MODE_TRUNCATE
                                val pfd = ParcelFileDescriptor.open(targetFile, flags)
                                param.result = if (methodName == "openFileDescriptor" || methodName == "openFile") pfd
                                               else AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                            }
                        }

                        reportAuditToManager(
                            pkg = pkg,
                            requestedPath = uriStr,
                            resolvedPath = targetFile.absolutePath,
                            action = AuditAction.REDIRECTED,
                            operation = "MEDIA_WRITE",
                            details = "Saved media file into isolated sandbox (${targetFile.name})"
                        )
                        return
                    }

                    // READING MEDIA: If the file is in our sandbox, return real descriptor
                    if (targetFile != null && targetFile.exists()) {
                        when (methodName) {
                            "openInputStream" -> param.result = FileInputStream(targetFile)
                            "loadThumbnail" -> param.result = BitmapFactory.decodeFile(targetFile.absolutePath)
                            else -> {
                                val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
                                param.result = if (methodName == "openFileDescriptor" || methodName == "openFile") pfd
                                               else AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                            }
                        }
                        return
                    }

                    // User explicitly picked this URI via a picker/gallery: Allow it!
                    if (userAllowedUris.containsKey(uriStr)) {
                        XposedBridge.log("[IsoVault] Allowed user-picked URI read in $pkg: $uriStr")
                        return
                    }

                    // URI does NOT belong to the sandbox: Block read access to protect phone's real gallery
                    reportAuditToManager(
                        pkg = pkg,
                        requestedPath = uriStr,
                        resolvedPath = "IsoVault://blocked_media_read",
                        action = AuditAction.BLOCKED,
                        operation = "MEDIA_URI_READ",
                        details = "Blocked access to real phone media URI ($methodName)"
                    )
                    param.throwable = FileNotFoundException("Protected by IsoVault: media URI isolated")
                } finally {
                    isHandlingHook.set(false)
                }
            }
        }

        val resolverOpenMethods = listOf(
            "openInputStream",
            "openOutputStream",
            "openFileDescriptor",
            "openAssetFileDescriptor",
            "openTypedAssetFileDescriptor",
            "loadThumbnail"
        )
        for (m in resolverOpenMethods) {
            try { XposedBridge.hookAllMethods(ContentResolver::class.java, m, mediaStreamAndDescriptorHook) } catch (_: Throwable) {}
        }

        val clientOpenMethods = listOf("openFile", "openAssetFile", "openTypedAssetFile")
        for (m in clientOpenMethods) {
            try { XposedBridge.hookAllMethods(ContentProviderClient::class.java, m, mediaStreamAndDescriptorHook) } catch (_: Throwable) {}
        }

        val rewriteToSandboxFile = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val i = param.args.indexOfFirst { it is Uri }
                if (i < 0) return
                val uri = param.args[i] as Uri
                val file = virtualUriMap[uri.toString()]
                    ?: uri.lastPathSegment?.toLongOrNull()?.let { virtualMediaMap[it] }
                if (file != null && file.exists()) param.args[i] = Uri.fromFile(file)
            }
        }
        try {
            XposedBridge.hookAllMethods(ImageDecoder::class.java, "createSource", rewriteToSandboxFile)
            XposedBridge.hookAllMethods(MediaMetadataRetriever::class.java, "setDataSource", rewriteToSandboxFile)
        } catch (_: Throwable) {}

        // D. Delete and Update
        val mediaDeleteHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args.firstOrNull { it is Uri } as? Uri ?: return
                val targetFile = virtualUriMap[uri.toString()]
                if (targetFile != null) {
                    try { targetFile.delete() } catch (_: Throwable) {}
                    virtualUriMap.remove(uri.toString())
                    param.result = 1
                }
            }
        }
        try {
            XposedBridge.hookAllMethods(ContentResolver::class.java, "delete", mediaDeleteHook)
            XposedBridge.hookAllMethods(ContentProviderClient::class.java, "delete", mediaDeleteHook)
        } catch (_: Throwable) {}
    }

    /**
     * LAYER 2: Snapchat & Local Gallery SQLiteSession CursorWindow Guard.
     * Clears CursorWindow when Snapchat queries its local SQLite cache for real phone photos.
     */
    private fun attachSnapchatAndGallerySqliteHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String
    ) {
        try {
            val sqliteSessionClass = XposedHelpers.findClass("android.database.sqlite.SQLiteSession", null)
            XposedBridge.hookAllMethods(
                sqliteSessionClass,
                "executeForCursorWindow",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        val sql = param.args.firstOrNull() as? String ?: return
                        val lower = sql.trimStart().lowercase()
                        if (!lower.startsWith("select")) return

                        if (lower.contains("camera_roll") ||
                            lower.contains("cameraroll") ||
                            lower.contains("local_media_reference") ||
                            lower.contains("/storage/emulated/0/dcim") ||
                            lower.contains("/storage/emulated/0/pictures")
                        ) {
                            isHandlingHook.set(true)
                            try {
                                val rule = resolveRuleForPackage(pkg)
                                if (!rule.isIsolated || rule.mode == IsolationMode.READ_ONLY) return
                                if (isGalleryWhitelisted(rule)) return

                                val window = param.args.firstOrNull { it is CursorWindow } as? CursorWindow
                                window?.clear()
                                param.result = 0
                                XposedBridge.log("[IsoVault] Cleared cached Camera Roll SQLite CursorWindow in $pkg")
                            } finally {
                                isHandlingHook.set(false)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * LAYER 3: System picker handling.
     * The system pickers still open normally (they are not replaced); the hook only hints the
     * picker's start location to the sandbox. Whatever the user explicitly picks is recorded in
     * [userAllowedUris] so the app may read it, and a copy is stored in the sandbox.
     * In-app galleries run natively and are served from the sandbox by the MediaStore and File hooks.
     */
    private fun attachPickerInterceptorHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String
    ) {
        val execStartActivityHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return

                val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                if (!isExternalPickerIntent(intent, pkg)) return

                isHandlingHook.set(true)
                try {
                    val rule = resolveRuleForPackage(pkg)
                    if (!rule.isIsolated || rule.mode == IsolationMode.READ_ONLY) return
                    if (isGalleryWhitelisted(rule)) return

                    val sandboxRoot = getOrCreateSandboxRoot(pkg)
                    val picturesDir = File(sandboxRoot, "Pictures").apply { mkdirs() }
                    val initialUri = Uri.fromFile(picturesDir)
                    intent.putExtra("android.provider.extra.INITIAL_URI", initialUri)

                    XposedBridge.log("[IsoVault] Bound external picker in $pkg to sandbox: ${picturesDir.absolutePath}")
                    reportAuditToManager(
                        pkg = pkg,
                        requestedPath = "Picker://${intent.action ?: "PICK"}",
                        resolvedPath = picturesDir.absolutePath,
                        action = AuditAction.REDIRECTED,
                        operation = "PICKER_SANDBOX_BOUND",
                        details = "Bound file picker initial storage to isolated sandbox"
                    )
                } finally {
                    isHandlingHook.set(false)
                }
            }
        }

        try {
            XposedBridge.hookAllMethods(Instrumentation::class.java, "execStartActivity", execStartActivityHook)
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "startActivityForResult",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                        if (!isExternalPickerIntent(intent, pkg)) return

                        isHandlingHook.set(true)
                        try {
                            val rule = resolveRuleForPackage(pkg)
                            if (!rule.isIsolated || rule.mode == IsolationMode.READ_ONLY) return
                            if (isGalleryWhitelisted(rule)) return

                            if (rule.pickerPolicy == PickerPolicy.BLOCK) {
                                val activity = param.thisObject as? Activity
                                val requestCode = param.args.getOrNull(1) as? Int
                                if (activity != null && requestCode != null && requestCode >= 0) {
                                    param.result = null // the picker never opens
                                    cancelPicker(activity, requestCode)
                                    XposedBridge.log("[IsoVault] Blocked system picker in $pkg")
                                    reportAuditToManager(
                                        pkg = pkg,
                                        requestedPath = "Picker://${intent.action ?: "PICK"}",
                                        resolvedPath = "IsoVault://picker_blocked",
                                        action = AuditAction.BLOCKED,
                                        operation = "PICKER_BLOCKED",
                                        details = "System picker blocked by IsoVault settings"
                                    )
                                    return
                                }
                            }

                            val sandboxRoot = getOrCreateSandboxRoot(pkg)
                            val picturesDir = File(sandboxRoot, "Pictures").apply { mkdirs() }
                            val initialUri = Uri.fromFile(picturesDir)
                            intent.putExtra("android.provider.extra.INITIAL_URI", initialUri)

                            XposedBridge.log("[IsoVault] Bound startActivityForResult picker in $pkg to sandbox: ${picturesDir.absolutePath}")
                        } finally {
                            isHandlingHook.set(false)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // Track user-selected URIs in dispatchActivityResult so every result passes through it, including Activity Result API
        try {
            XposedBridge.hookAllMethods(Activity::class.java, "dispatchActivityResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // signature: (String who, int requestCode, int resultCode, Intent data, String reason)
                    val resultCode = param.args.getOrNull(2) as? Int ?: return
                    val data = param.args.getOrNull(3) as? Intent
                    handlePickResult(param.thisObject as? Activity, pkg, resultCode, data)
                }
            })
        } catch (_: Throwable) {}
    }

    private fun handlePickResult(activity: Activity?, pkg: String, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        val picked = mutableListOf<Uri>()
        data.data?.let { picked.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { picked.add(it) }
        }
        for (u in picked) {
            val key = u.toString()
            userAllowedUris[key] = true            // allow immediately, before any copy
            if (activity == null) continue
            auditExecutor.execute {
                try {
                    val dir = File(getOrCreateSandboxRoot(pkg), "Pictures").apply { mkdirs() }
                    val name = "picked_${System.nanoTime()}.${guessExtension(u)}"
                    val tmp = File(dir, "$name.part")      // fix for the truncated-copy race
                    val dst = File(dir, name)
                    activity.contentResolver.openInputStream(u)?.use { i ->
                        FileOutputStream(tmp).use { o -> i.copyTo(o) }
                    }
                    if (tmp.renameTo(dst)) {
                        registerSandboxMedia(dst)
                        virtualUriMap[key] = dst           // only visible once fully written
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun isExternalPickerIntent(intent: Intent?, pkg: String): Boolean {
        if (intent == null) return false

        // NEVER intercept in-app activities within the target app itself (e.g. WhatsApp gallery, Snapchat camera roll)
        val componentPkg = intent.component?.packageName
        val targetPkg = intent.`package`
        if (componentPkg == pkg || targetPkg == pkg) {
            return false
        }

        val action = intent.action ?: ""
        if (action == Intent.ACTION_CHOOSER) {
            @Suppress("DEPRECATION")
            val target = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (isExternalPickerIntent(target, pkg)) return true
        }

        val pickerActions = setOf(
            Intent.ACTION_PICK,
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_OPEN_DOCUMENT_TREE,
            "android.provider.action.PICK_IMAGES",
            "com.google.android.gms.provider.action.PICK_IMAGES",
            "androidx.activity.result.contract.action.PICK_IMAGES"
        )
        if (action in pickerActions) return true

        val type = intent.type?.lowercase() ?: ""
        if ((action.contains("PICK", ignoreCase = true) || action.contains("CONTENT", ignoreCase = true)) &&
            (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/") || type == "*/*")
        ) {
            return true
        }

        val targetPkgLower = (targetPkg ?: componentPkg ?: "").lowercase()
        if (targetPkgLower.contains("photopicker") ||
            targetPkgLower.contains("documentsui") ||
            targetPkgLower.contains("providers.media")
        ) {
            return true
        }

        val dataUri = intent.data
        if (dataUri != null && isMediaOrExternalDocumentUri(dataUri)) {
            return true
        }

        return false
    }


    /**
     * LAYER 4: Java File Redirection & Virtual Directory Listing.
     * Creates and lists files in the app's isolated sandbox so the app can create folders,
     * read/write files, and see its own directory structure seamlessly.
     */
    private fun attachFileHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String,
        initialRule: IsolationRuleManager.AppRule
    ) {
        // 1. File(String pathname)
        try {
            XposedHelpers.findAndHookConstructor(
                File::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        val path = param.args[0] as? String ?: return
                        if (shouldInterceptPath(pkg, path)) {
                            isHandlingHook.set(true)
                            try {
                                val redirected = redirectPathForApp(pkg, path, initialRule)
                                if (redirected != path) {
                                    param.args[0] = redirected
                                }
                            } finally {
                                isHandlingHook.set(false)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. File(String parent, String child)
        try {
            XposedHelpers.findAndHookConstructor(
                File::class.java,
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        val parent = param.args[0] as? String ?: return
                        val child = param.args[1] as? String ?: return
                        val full = "${parent.trimEnd('/')}/${child.trimStart('/')}"
                        if (shouldInterceptPath(pkg, full)) {
                            isHandlingHook.set(true)
                            try {
                                val redirected = redirectPathForApp(pkg, full, initialRule)
                                if (redirected != full) {
                                    param.args[0] = redirected.substringBeforeLast('/')
                                    param.args[1] = redirected.substringAfterLast('/')
                                }
                            } finally {
                                isHandlingHook.set(false)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. File(File parent, String child)
        try {
            XposedHelpers.findAndHookConstructor(
                File::class.java,
                File::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        val parentFile = param.args[0] as? File ?: return
                        val child = param.args[1] as? String ?: return
                        isHandlingHook.set(true)
                        try {
                            val full = "${parentFile.path.trimEnd('/')}/${child.trimStart('/')}"
                            if (shouldInterceptPath(pkg, full)) {
                                val redirected = redirectPathForApp(pkg, full, initialRule)
                                if (redirected != full) {
                                    param.args[0] = File(redirected.substringBeforeLast('/'))
                                    param.args[1] = redirected.substringAfterLast('/')
                                }
                            }
                        } finally {
                            isHandlingHook.set(false)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 4. File.list() & File.list(FilenameFilter) -> lists isolated sandbox directory
        val listStringsHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return
                val file = param.thisObject as? File ?: return
                val path = file.path
                if (shouldInterceptPath(pkg, path)) {
                    isHandlingHook.set(true)
                    try {
                        val rule = resolveRuleForPackage(pkg)
                        if (rule.isIsolated && !isWhitelisted(path, rule.whitelistedFolders)) {
                            val relative = IsolationRuleManager.extractRelative(path)
                            val sandboxDir = File(getOrCreateSandboxRoot(pkg), relative)
                            if (sandboxDir.exists() && sandboxDir.isDirectory) {
                                param.result = sandboxDir.list() ?: emptyArray<String>()
                            } else {
                                param.result = emptyArray<String>()
                            }
                        }
                    } finally {
                        isHandlingHook.set(false)
                    }
                }
            }
        }

        // 5. File.listFiles() -> lists isolated sandbox directory
        val listFilesHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return
                val file = param.thisObject as? File ?: return
                val path = file.path
                if (shouldInterceptPath(pkg, path)) {
                    isHandlingHook.set(true)
                    try {
                        val rule = resolveRuleForPackage(pkg)
                        if (rule.isIsolated && !isWhitelisted(path, rule.whitelistedFolders)) {
                            val relative = IsolationRuleManager.extractRelative(path)
                            val sandboxDir = File(getOrCreateSandboxRoot(pkg), relative)
                            if (sandboxDir.exists() && sandboxDir.isDirectory) {
                                param.result = sandboxDir.listFiles() ?: emptyArray<File>()
                            } else {
                                param.result = emptyArray<File>()
                            }
                        }
                    } finally {
                        isHandlingHook.set(false)
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(File::class.java, "list", listStringsHook)
            XposedHelpers.findAndHookMethod(File::class.java, "list", FilenameFilter::class.java, listStringsHook)
            XposedHelpers.findAndHookMethod(File::class.java, "listFiles", listFilesHook)
            XposedHelpers.findAndHookMethod(File::class.java, "listFiles", FilenameFilter::class.java, listFilesHook)
            XposedHelpers.findAndHookMethod(File::class.java, "listFiles", FileFilter::class.java, listFilesHook)
        } catch (_: Throwable) {}

        // 6. File status & existence queries (exists, isDirectory, isFile, length, canRead, canWrite)
        val fileStatusHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return
                val file = param.thisObject as? File ?: return
                val path = file.path
                if (shouldInterceptPath(pkg, path)) {
                    isHandlingHook.set(true)
                    try {
                        val rule = resolveRuleForPackage(pkg)
                        if (rule.isIsolated && !isWhitelisted(path, rule.whitelistedFolders)) {
                            val relative = IsolationRuleManager.extractRelative(path)
                            val sandboxFile = File(getOrCreateSandboxRoot(pkg), relative)
                            when (param.method.name) {
                                "exists" -> param.result = sandboxFile.exists()
                                "isDirectory" -> param.result = sandboxFile.isDirectory
                                "isFile" -> param.result = sandboxFile.isFile
                                "length" -> param.result = if (sandboxFile.exists()) sandboxFile.length() else 0L
                                "canRead" -> param.result = sandboxFile.canRead()
                                "canWrite" -> param.result = canWriteOrCreate(sandboxFile)
                            }
                        }
                    } finally {
                        isHandlingHook.set(false)
                    }
                }
            }
        }

        try {
            for (m in listOf("exists", "isDirectory", "isFile", "length", "canRead", "canWrite")) {
                XposedHelpers.findAndHookMethod(File::class.java, m, fileStatusHook)
            }
        } catch (_: Throwable) {}

        // 7. BitmapFactory.decodeFile
        try {
            XposedBridge.hookAllMethods(
                BitmapFactory::class.java,
                "decodeFile",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        val path = param.args.firstOrNull() as? String ?: return
                        if (shouldInterceptPath(pkg, path)) {
                            isHandlingHook.set(true)
                            try {
                                val redirected = redirectPathForApp(pkg, path, initialRule)
                                if (redirected != path) {
                                    param.args[0] = redirected
                                }
                            } finally {
                                isHandlingHook.set(false)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * LAYER 5: Low-Level OS & libcore.io Bridge Hooks.
     */
    private fun attachLowLevelOsHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String,
        initialRule: IsolationRuleManager.AppRule
    ) {
        val osPathRedirectHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isHandlingHook.get() == true) return
                val path = param.args.firstOrNull() as? String ?: return
                if (shouldInterceptPath(pkg, path)) {
                    isHandlingHook.set(true)
                    try {
                        val redirected = redirectPathForApp(pkg, path, initialRule)
                        if (redirected != path) {
                            param.args[0] = redirected
                        }
                    } finally {
                        isHandlingHook.set(false)
                    }
                }
            }
        }

        try {
            val ioBridgeClass = XposedHelpers.findClass("libcore.io.IoBridge", null)
            XposedBridge.hookAllMethods(ioBridgeClass, "open", osPathRedirectHook)
        } catch (_: Throwable) {}

        try {
            val blockGuardOsClass = XposedHelpers.findClass("libcore.io.BlockGuardOs", null)
            for (method in listOf("open", "stat", "lstat", "access", "opendir")) {
                XposedBridge.hookAllMethods(blockGuardOsClass, method, osPathRedirectHook)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Environment external storage getters redirection.
     */
    private fun attachEnvironmentHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String,
        initialRule: IsolationRuleManager.AppRule
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.Environment",
                lpparam.classLoader,
                "getExternalStorageDirectory",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        if (isVolumeMounted()) return
                        isHandlingHook.set(true)
                        try {
                            val sandboxRoot = getOrCreateSandboxRoot(pkg)
                            param.result = sandboxRoot
                            reportAuditToManager(
                                pkg = pkg,
                                requestedPath = "/storage/emulated/0",
                                resolvedPath = sandboxRoot.absolutePath,
                                action = AuditAction.REDIRECTED,
                                operation = "GET_EXT_STORAGE",
                                details = "Redirected root external storage to isolated sandbox"
                            )
                        } finally {
                            isHandlingHook.set(false)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "android.os.Environment",
                lpparam.classLoader,
                "getExternalStoragePublicDirectory",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isHandlingHook.get() == true) return
                        val type = param.args[0] as? String ?: "Files"
                        if (isVolumeMounted()) return
                        isHandlingHook.set(true)
                        try {
                            val rule = resolveRuleForPackage(pkg)
                            if (isWhitelisted(type, rule.whitelistedFolders)) return
                            val sandboxSubDir = File(getOrCreateSandboxRoot(pkg), type)
                            sandboxSubDir.mkdirs()
                            param.result = sandboxSubDir
                            reportAuditToManager(
                                pkg = pkg,
                                requestedPath = "/storage/emulated/0/$type",
                                resolvedPath = sandboxSubDir.absolutePath,
                                action = AuditAction.REDIRECTED,
                                operation = "GET_PUBLIC_DIR",
                                details = "Redirected public directory ($type) to isolated sandbox"
                            )
                        } finally {
                            isHandlingHook.set(false)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun shouldInterceptPath(pkg: String, path: String): Boolean {
        val norm = path.replace("\\", "/")
        if (!norm.startsWith("/storage/emulated/0") &&
            !norm.startsWith("/sdcard") &&
            !norm.startsWith("/mnt/sdcard") &&
            !norm.startsWith("/storage/self/primary")
        ) {
            return false
        }
        if (norm.contains("/IsoVault_Sandbox") ||
            norm.contains("/isovault_storage") ||
            norm.contains("/hollow_drive") ||
            norm.contains("/hollow_drives") ||
            norm.contains("/Hollow_Workspace") ||
            norm.contains("/Hollow_Drives") ||
            (pkg.isNotBlank() && norm.contains("/Android/data/$pkg"))
        ) {
            return false
        }
        // A root volume is mounted over the storage root: the kernel already isolates this app.
        if (isVolumeMounted()) return false
        return true
    }

    @Volatile private var volumeMounted = false
    @Volatile private var volumeCheckedAtMs = 0L

    private fun isVolumeMounted(): Boolean {
        val now = System.currentTimeMillis()
        if (now - volumeCheckedAtMs < 2500L) return volumeMounted
        val wasHandling = isHandlingHook.get() == true
        isHandlingHook.set(true) // keep our own File hooks out of this probe
        try {
            volumeMounted = File("/storage/emulated/0/${MountManager.VOLUME_MARKER}").exists()
        } catch (_: Throwable) {
            volumeMounted = false
        } finally {
            isHandlingHook.set(wasHandling)
        }
        volumeCheckedAtMs = now
        return volumeMounted
    }

    /** A missing file is writable when the nearest existing ancestor is. */
    private fun canWriteOrCreate(file: File): Boolean {
        var node: File? = file
        while (node != null && !node.exists()) node = node.parentFile
        return node?.canWrite() ?: false
    }

    private fun isWhitelisted(path: String, whitelistedFolders: Set<String>): Boolean {
        for (wf in whitelistedFolders) {
            if (wf.isNotBlank() && path.contains("/$wf", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun getOrCreateSandboxRoot(pkg: String): File {
        val app = try { AndroidAppHelper.currentApplication() } catch (_: Throwable) { null }
        // Every isolated app gets its own normal app-private directory. This is also the source
        // used by MountManager, so switching between hook and root mode cannot split its files.
        val rootDir = if (app != null) {
            File(app.filesDir, "isovault_storage")
        } else {
            File("/data/user/0/$pkg/files/isovault_storage")
        }
        if (!rootDir.exists() && app != null) {
            // Keep data created by older hook-only builds instead of silently starting an empty drive.
            val legacyDirs = listOf(File(app.filesDir, "hollow_drives/$pkg"), File(app.filesDir, "hollow_drive"))
            val legacy = legacyDirs.firstOrNull {
                it.isDirectory && try { it.canonicalFile == it.absoluteFile } catch (_: Throwable) { false }
            }
            if (legacy != null) {
                rootDir.parentFile?.mkdirs()
                legacy.renameTo(rootDir)
            }
        }
        rootDir.mkdirs()

        try {
            val nomedia = File(rootDir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
        } catch (_: Throwable) {}

        val subDirs = listOf(
            "DCIM",
            "DCIM/Camera",
            "Pictures",
            "Movies",
            "Download",
            "Documents",
            "Music",
            "Android/media/$pkg"
        )
        for (sub in subDirs) {
            try { File(rootDir, sub).mkdirs() } catch (_: Throwable) {}
        }
        return rootDir
    }

    private fun redirectPathForApp(
        pkg: String,
        originalPath: String,
        fallbackRule: IsolationRuleManager.AppRule
    ): String {
        val rule = resolveRuleForPackage(pkg)
        if (!rule.isIsolated) return originalPath

        val norm = originalPath.replace("\\", "/")
        if (isWhitelisted(norm, rule.whitelistedFolders)) {
            return originalPath
        }

        if (rule.blockRootPollution && IsolationRuleManager.isRootPollution(norm)) {
            reportAuditToManager(
                pkg = pkg,
                requestedPath = originalPath,
                resolvedPath = "/dev/null",
                action = AuditAction.BLOCKED,
                operation = "BLOCK_POLLUTION",
                details = "Blocked root storage pollution folder"
            )
            return "/dev/null"
        }

        val relative = IsolationRuleManager.extractRelative(norm)
        val sandboxRoot = getOrCreateSandboxRoot(pkg)
        val targetFile = File(sandboxRoot, relative)
        try { targetFile.parentFile?.mkdirs() } catch (_: Throwable) {}

        val action = when (rule.mode) {
            IsolationMode.ENCRYPTED_VAULT -> AuditAction.REDIRECTED
            IsolationMode.SANDBOX_REDIRECT -> AuditAction.REDIRECTED
            IsolationMode.SHARED_WORKSPACE -> AuditAction.REDIRECTED
            IsolationMode.FAKE_EMPTY -> AuditAction.SPOOFED_EMPTY
            IsolationMode.READ_ONLY -> AuditAction.BLOCKED
        }

        reportAuditToManager(
            pkg = pkg,
            requestedPath = originalPath,
            resolvedPath = targetFile.absolutePath,
            action = action,
            operation = "FILE_REDIRECT",
            details = "Redirected to isolated container (${rule.mode.badge})"
        )

        return targetFile.absolutePath
    }

    private fun reportAuditToManager(
        pkg: String,
        requestedPath: String,
        resolvedPath: String,
        action: AuditAction,
        operation: String,
        details: String
    ) {
        val key = "$pkg|$operation|$requestedPath"
        val now = System.currentTimeMillis()
        val lastTime = recentAuditCache[key] ?: 0L
        if (now - lastTime < 3500L) return
        recentAuditCache[key] = now

        auditExecutor.execute {
            try {
                val app = AndroidAppHelper.currentApplication() ?: return@execute
                val extras = Bundle().apply {
                    putString("requestedPath", requestedPath)
                    putString("resolvedPath", resolvedPath)
                    putString("action", action.name)
                    putString("operation", operation)
                    putString("details", details)
                }
                app.contentResolver.call(
                    Uri.parse(PROVIDER_URI),
                    "record_audit",
                    pkg,
                    extras
                )
            } catch (_: Throwable) {}
        }
    }
}
