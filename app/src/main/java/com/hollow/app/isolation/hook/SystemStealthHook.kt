package com.hollow.app.isolation.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Base64
import android.view.View
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.LauncherVisibilityMode
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lock-free, non-blocking System-wide App Hider, Custom Name/Icon Disguise, Play Store Hider,
 * System Settings App Management Spoofer, and SystemUI Stealth Trigger.
 */
object SystemStealthHook {

    private const val MODULE_PKG = IsolationRuleManager.MODULE_PKG
    private const val PLAY_STORE_PKG = "com.android.vending"
    private const val SETTINGS_PKG = "com.android.settings"
    private const val PROVIDER_URI = "content://$MODULE_PKG.provider"
    const val CATEGORY_LAUNCH_HOLLOW = "$MODULE_PKG.LAUNCH_HOLLOW"

    @Volatile
    private var cachedStealthMap: Map<String, IsolationRuleManager.StealthRule> = emptyMap()

    @Volatile
    private var cachedSystemUiTrigger: Boolean = true

    @Volatile
    private var lastStealthFetchMs: Long = 0L

    @Volatile
    private var fallbackXSharedPrefs: XSharedPreferences? = null

    @Volatile
    private var sysMirrorXPrefs: XSharedPreferences? = null

    @Volatile
    private var tmpMirrorXPrefs: XSharedPreferences? = null

    @Volatile
    private var cachedHollowAppId: Int = -1

    @Volatile
    private var cachedPlayStoreAppId: Int = -1

    @Volatile
    private var cachedSystemUiAppId: Int = -1

    @Volatile
    private var hostPackageName: String = ""

    private val attachedInProcess = AtomicBoolean(false)
    private val isRefreshing = AtomicBoolean(false)
    private val hasLoadedInitialRules = AtomicBoolean(false)

    private val isInPmQueryHook = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val isInPmInfoHook = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val isInResDrawableHook = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Hollow-StealthPrefs").apply { isDaemon = true }
    }

    private val decoyBitmapCache = ConcurrentHashMap<DisguiseIconPreset, Bitmap>()
    private val customBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val knownPackageIconRes = ConcurrentHashMap<String, MutableSet<Int>>()
    private val resourcesToPkgMap = ConcurrentHashMap<Int, String>()
    private val fakeUninstalledPackages: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun extractPkgFromArgs(args: Array<Any?>?): String? {
        if (args == null) return null
        for (arg in args) {
            if (arg == null) continue
            when (arg) {
                is String -> {
                    if (arg.contains(".") && !arg.startsWith("/")) return arg
                }
                is ComponentName -> return arg.packageName
                is Intent -> {
                    val p = arg.`package` ?: arg.component?.packageName
                    if (!p.isNullOrBlank()) return p
                }
                else -> {
                    if (arg.javaClass.name == "android.content.pm.VersionedPackage") {
                        try {
                            val p = XposedHelpers.callMethod(arg, "getPackageName") as? String
                            if (!p.isNullOrBlank()) return p
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        return args.firstOrNull { it is String } as? String
    }

    private fun resolveSpecialAppIdsIfNeeded() {
        if (cachedHollowAppId > 0 && cachedPlayStoreAppId > 0 && cachedSystemUiAppId > 0) return
        val app = try { AndroidAppHelper.currentApplication() } catch (_: Throwable) { null } ?: return
        val pm = try { app.packageManager } catch (_: Throwable) { null } ?: return
        try {
            if (cachedHollowAppId <= 0) {
                val uid = pm.getPackageUid(MODULE_PKG, 0)
                if (uid > 0) cachedHollowAppId = uid % 100_000
            }
        } catch (_: Throwable) {}
        try {
            if (cachedPlayStoreAppId <= 0) {
                val uid = pm.getPackageUid(PLAY_STORE_PKG, 0)
                if (uid > 0) cachedPlayStoreAppId = uid % 100_000
            }
        } catch (_: Throwable) {}
        try {
            if (cachedSystemUiAppId <= 0) {
                val uid = pm.getPackageUid("com.android.systemui", 0)
                if (uid > 0) cachedSystemUiAppId = uid % 100_000
            }
        } catch (_: Throwable) {}
    }

    private fun recordPackageIconRes(pkg: String, vararg resIds: Int) {
        if (pkg.isBlank()) return
        val set = knownPackageIconRes.getOrPut(pkg) {
            Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
        }
        for (id in resIds) {
            if (id != 0) set.add(id)
        }
    }

    private fun recordAppInfoIcons(appInfo: ApplicationInfo?) {
        if (appInfo == null) return
        val pkg = appInfo.packageName ?: return
        val roundRes = try { XposedHelpers.getIntField(appInfo, "roundIconRes") } catch (_: Throwable) { 0 }
        recordPackageIconRes(pkg, appInfo.icon, appInfo.logo, roundRes)
    }

    fun attachForPackage(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // Never hide or spoof apps inside Hollow itself
        if (currentPkg == MODULE_PKG) return

        val proc = lpparam.processName ?: currentPkg
        val isSysServer = currentPkg == "android" && (proc == "android" || proc == "system_server")

        // Ignore secondary sub-packages loaded inside system_server or plugin packages in SystemUI
        if (!isSysServer && (proc == "android" || proc == "system_server")) {
            return
        }
        if (proc.startsWith("com.android.systemui") && currentPkg != "com.android.systemui") {
            return
        }
        if (!isSysServer && !lpparam.isFirstApplication && proc != currentPkg) {
            return
        }

        if (!attachedInProcess.compareAndSet(false, true)) {
            return
        }

        hostPackageName = currentPkg
        if (xSharedPrefs != null && fallbackXSharedPrefs == null) {
            fallbackXSharedPrefs = xSharedPrefs
        }

        // Try immediate fast non-IPC load first, then schedule background refresh
        tryLoadFromLocalFiles(xSharedPrefs)
        triggerAsyncRuleRefresh(xSharedPrefs, isSysServer)

        if (isSysServer) {
            attachSystemServerLauncherAppsHooks(lpparam, xSharedPrefs)
            attachSystemServerPackageManagerHooks(lpparam, xSharedPrefs)
            attachSystemServerActivitySupervisorHooks(lpparam, xSharedPrefs)
            return
        }

        attachGlobalPackageAndLauncherHooks(lpparam, currentPkg, xSharedPrefs)

        if (currentPkg == PLAY_STORE_PKG) {
            attachPlayStoreClientHooks(lpparam, xSharedPrefs)
        }

        if (currentPkg == "com.android.systemui") {
            attachSystemUiDirectHooks(lpparam, xSharedPrefs)
        }
    }

    private fun obtainXSharedPrefs(passed: XSharedPreferences?): XSharedPreferences? {
        if (passed != null) return passed
        val existing = fallbackXSharedPrefs
        if (existing != null) return existing
        return try {
            XSharedPreferences(MODULE_PKG, IsolationRuleManager.PREFS_NAME).also {
                fallbackXSharedPrefs = it
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryLoadFromPrefsInstance(prefs: XSharedPreferences?): Boolean {
        if (prefs == null) return false
        return try {
            if (prefs.hasFileChanged()) {
                prefs.reload()
            }
            val raw = prefs.getString(IsolationRuleManager.KEY_STEALTH_TABLE, null)
            if (raw != null) {
                cachedSystemUiTrigger = prefs.getBoolean(IsolationRuleManager.KEY_SYSTEMUI_TRIGGER, true)
                val parsed = IsolationRuleManager.parseStealthTable(raw) { pkg ->
                    prefs.getString(IsolationRuleManager.customIconKey(pkg), null)
                }
                updateCachesWithRules(parsed)
                hasLoadedInitialRules.set(true)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryLoadFromLocalFiles(xSharedPrefs: XSharedPreferences?): Boolean {
        // 1. Standard LSPosed / Vector XSharedPreferences
        if (tryLoadFromPrefsInstance(obtainXSharedPrefs(xSharedPrefs))) {
            return true
        }
        // 2. Root-mirrored /data/system/isovault_rules.xml (always readable by system_server UID 1000)
        try {
            val sysFile = File("/data/system/isovault_rules.xml")
            if (sysFile.canRead()) {
                val sp = sysMirrorXPrefs ?: XSharedPreferences(sysFile).also { sysMirrorXPrefs = it }
                if (tryLoadFromPrefsInstance(sp)) return true
            }
        } catch (_: Throwable) {}

        // 3. Root-mirrored /data/local/tmp/isovault_rules.xml (readable by client apps & launchers)
        try {
            val tmpFile = File("/data/local/tmp/isovault_rules.xml")
            if (tmpFile.canRead()) {
                val sp = tmpMirrorXPrefs ?: XSharedPreferences(tmpFile).also { tmpMirrorXPrefs = it }
                if (tryLoadFromPrefsInstance(sp)) return true
            }
        } catch (_: Throwable) {}

        return false
    }

    private fun tryLoadFromProvider(): Boolean {
        val app = try { AndroidAppHelper.currentApplication() } catch (_: Throwable) { null } ?: return false
        return try {
            val bundle = app.contentResolver.call(
                Uri.parse(PROVIDER_URI),
                "get_stealth_table",
                null,
                null
            )
            if (bundle != null) {
                val raw = bundle.getString("stealthTable")
                cachedSystemUiTrigger = bundle.getBoolean("systemUiTriggerEnabled", true)
                val iconsBundle = bundle.getBundle("customIcons")
                if (raw != null) {
                    val parsed = IsolationRuleManager.parseStealthTable(raw) { pkg ->
                        iconsBundle?.getString(pkg)
                    }
                    updateCachesWithRules(parsed)
                    hasLoadedInitialRules.set(true)
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns the current stealth rules map.
     * On the very first hook invocation in a client process (e.g. when a Launcher starts up and
     * immediately calls `getActivityList()`), if rules have not loaded yet and `Application` context
     * is ready, loads synchronously once so the launcher never caches the un-disguised original list.
     */
    private fun getStealthRules(
        xSharedPrefs: XSharedPreferences?
    ): Map<String, IsolationRuleManager.StealthRule> {
        val isSys = hostPackageName == "android" || Process.myUid() == Process.SYSTEM_UID
        if (!hasLoadedInitialRules.get()) {
            if (!tryLoadFromLocalFiles(xSharedPrefs) && !isSys) {
                tryLoadFromProvider()
            }
        }
        val now = System.currentTimeMillis()
        if ((now - lastStealthFetchMs) > 1500L) {
            triggerAsyncRuleRefresh(xSharedPrefs, isSys)
        }
        return cachedStealthMap
    }

    private fun triggerAsyncRuleRefresh(xSharedPrefs: XSharedPreferences?, isSysServer: Boolean) {
        if (!isRefreshing.compareAndSet(false, true)) return
        lastStealthFetchMs = System.currentTimeMillis()
        try {
            refreshExecutor.execute {
                try {
                    val loadedLocal = tryLoadFromLocalFiles(xSharedPrefs)
                    if (!loadedLocal || cachedStealthMap.isEmpty()) {
                        // In client apps (or in system_server after boot completes), fallback to ContentProvider
                        val canQueryProvider = if (isSysServer) {
                            try {
                                val spCls = XposedHelpers.findClassIfExists("android.os.SystemProperties", null)
                                val boot = spCls?.let {
                                    XposedHelpers.callStaticMethod(it, "get", "sys.boot_completed") as? String
                                }
                                boot == "1"
                            } catch (_: Throwable) { false }
                        } else {
                            true
                        }
                        if (canQueryProvider) {
                            tryLoadFromProvider()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    if (hasLoadedInitialRules.get()) {
                        lastStealthFetchMs = System.currentTimeMillis()
                    } else {
                        lastStealthFetchMs = 0L
                    }
                    isRefreshing.set(false)
                }
            }
        } catch (_: Throwable) {
            isRefreshing.set(false)
        }
    }

    private fun updateCachesWithRules(parsed: Map<String, IsolationRuleManager.StealthRule>) {
        for ((pkg, rule) in parsed) {
            if (rule.customIconBase64.isNotBlank()) {
                try {
                    val bytes = Base64.decode(rule.customIconBase64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        customBitmapCache[pkg] = bmp
                    }
                } catch (_: Throwable) {}
            } else {
                customBitmapCache.remove(pkg)
            }
        }
        cachedStealthMap = parsed
    }

    private fun shouldSpoofInCurrentClient(
        currentPkg: String,
        rule: IsolationRuleManager.StealthRule
    ): Boolean {
        if (rule.visibilityMode != LauncherVisibilityMode.DISGUISED) return false
        if (currentPkg == "com.android.systemui" && !rule.hideInRecents) return false
        if ((currentPkg == SETTINGS_PKG || currentPkg.contains("permissioncontroller")) && !rule.spoofInSettings) {
            return false
        }
        return true
    }

    private fun attachGlobalPackageAndLauncherHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // A. Hook LauncherApps.getActivityList / resolveActivity / getApplicationInfo -> used by all modern launchers
        try {
            XposedBridge.hookAllMethods(
                LauncherApps::class.java,
                "getActivityList",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return
                        val list = param.result as? List<*> ?: return

                        val filtered = list.filter { item ->
                            val info = item as? LauncherActivityInfo ?: return@filter true
                            val targetPkg = info.componentName?.packageName ?: return@filter true
                            try {
                                val ai = XposedHelpers.callMethod(info, "getActivityInfo") as? ActivityInfo
                                    ?: XposedHelpers.getObjectField(info, "mActivityInfo") as? ActivityInfo
                                if (ai != null) {
                                    recordPackageIconRes(targetPkg, ai.icon, ai.logo)
                                    recordAppInfoIcons(ai.applicationInfo)
                                    val rule = rules[targetPkg]
                                    if (rule != null && shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                                        ai.nonLocalizedLabel = rule.customLabel
                                        ai.labelRes = 0
                                        ai.applicationInfo?.let { appInfo ->
                                            appInfo.nonLocalizedLabel = rule.customLabel
                                            appInfo.labelRes = 0
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}

                            if (targetPkg == currentPkg) return@filter true
                            val rule = rules[targetPkg]
                            if (currentPkg == PLAY_STORE_PKG && rule?.hideFromPlayStore == true) {
                                return@filter false
                            }
                            rule?.visibilityMode != LauncherVisibilityMode.HIDDEN
                        }
                        if (filtered.size != list.size) {
                            param.result = ArrayList(filtered)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(
                LauncherApps::class.java,
                "resolveActivity",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return
                        val info = param.result as? LauncherActivityInfo ?: return
                        val targetPkg = info.componentName?.packageName ?: return
                        val rule = rules[targetPkg] ?: return
                        try {
                            val ai = XposedHelpers.callMethod(info, "getActivityInfo") as? ActivityInfo
                                ?: XposedHelpers.getObjectField(info, "mActivityInfo") as? ActivityInfo
                            if (ai != null) {
                                recordPackageIconRes(targetPkg, ai.icon, ai.logo)
                                recordAppInfoIcons(ai.applicationInfo)
                                if (shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                                    ai.nonLocalizedLabel = rule.customLabel
                                    ai.labelRes = 0
                                    ai.applicationInfo?.let { appInfo ->
                                        appInfo.nonLocalizedLabel = rule.customLabel
                                        appInfo.labelRes = 0
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(
                LauncherApps::class.java,
                "getApplicationInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return
                        val appInfo = param.result as? ApplicationInfo ?: return
                        val targetPkg = appInfo.packageName ?: return
                        recordAppInfoIcons(appInfo)
                        val rule = rules[targetPkg] ?: return
                        if (shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                            appInfo.nonLocalizedLabel = rule.customLabel
                            appInfo.labelRes = 0
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // B. Hook ApplicationPackageManager.queryIntentActivities / queryIntentActivitiesAsUser
        val apmClass = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager",
            lpparam.classLoader
        )
        if (apmClass != null) {
            try {
                val queryHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return

                        val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                        val categories = intent.categories
                        val isLauncherQuery = categories?.contains(Intent.CATEGORY_LAUNCHER) == true ||
                                categories?.contains(Intent.CATEGORY_INFO) == true ||
                                categories?.contains(Intent.CATEGORY_HOME) == true ||
                                intent.action == Intent.ACTION_MAIN
                        if (!isLauncherQuery) return

                        if (currentPkg == "com.android.systemui" &&
                            (intent.`package` == MODULE_PKG || intent.component?.packageName == MODULE_PKG)
                        ) {
                            return
                        }

                        val list = param.result as? List<*> ?: return
                        val filtered = list.filter { item ->
                            val ri = item as? ResolveInfo ?: return@filter true
                            val targetPkg = ri.activityInfo?.packageName
                                ?: ri.resolvePackageName
                                ?: return@filter true
                            ri.activityInfo?.let { ai ->
                                recordPackageIconRes(targetPkg, ai.icon, ai.logo, ri.icon)
                                recordAppInfoIcons(ai.applicationInfo)
                            }
                            if (targetPkg == currentPkg) return@filter true
                            val rule = rules[targetPkg]
                            if (currentPkg == PLAY_STORE_PKG && rule?.hideFromPlayStore == true) {
                                return@filter false
                            }
                            if (rule != null &&
                                shouldSpoofInCurrentClient(currentPkg, rule) &&
                                rule.customLabel.isNotBlank()
                            ) {
                                applyDisguiseToResolveInfo(ri, rule, false)
                            }
                            rule?.visibilityMode != LauncherVisibilityMode.HIDDEN
                        }
                        if (filtered.size != list.size) {
                            param.result = ArrayList(filtered)
                        }
                    }
                }
                XposedBridge.hookAllMethods(apmClass, "queryIntentActivities", queryHook)
                XposedBridge.hookAllMethods(apmClass, "queryIntentActivitiesAsUser", queryHook)
            } catch (_: Throwable) {}

            // B2. Client-side getApplicationInfo / getActivityInfo / getPackageInfo label & icon tracking
            val clientInfoHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val rules = getStealthRules(xSharedPrefs)
                    if (rules.isEmpty()) return
                    when (val res = param.result) {
                        is ApplicationInfo -> {
                            val targetPkg = res.packageName ?: return
                            recordAppInfoIcons(res)
                            val rule = rules[targetPkg] ?: return
                            if (shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                                res.nonLocalizedLabel = rule.customLabel
                                res.labelRes = 0
                            }
                        }
                        is ActivityInfo -> {
                            val targetPkg = res.packageName ?: return
                            recordPackageIconRes(targetPkg, res.icon, res.logo)
                            recordAppInfoIcons(res.applicationInfo)
                            val rule = rules[targetPkg] ?: return
                            if (shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                                res.nonLocalizedLabel = rule.customLabel
                                res.labelRes = 0
                                res.applicationInfo?.let { ai ->
                                    ai.nonLocalizedLabel = rule.customLabel
                                    ai.labelRes = 0
                                }
                            }
                        }
                        is PackageInfo -> {
                            val targetPkg = res.packageName ?: return
                            recordAppInfoIcons(res.applicationInfo)
                            val rule = rules[targetPkg] ?: return
                            if (shouldSpoofInCurrentClient(currentPkg, rule)) {
                                val ruleHash = (rule.customLabel.hashCode() xor
                                        rule.iconPreset.name.hashCode() xor
                                        rule.donorPkg.hashCode() xor
                                        rule.customIconBase64.hashCode()).toLong() and 0xFFFFL
                                res.lastUpdateTime = res.lastUpdateTime + 1000L + ruleHash
                                if (rule.customLabel.isNotBlank()) {
                                    res.applicationInfo?.let { ai ->
                                        ai.nonLocalizedLabel = rule.customLabel
                                        ai.labelRes = 0
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (m in listOf("getApplicationInfo", "getActivityInfo", "getPackageInfo")) {
                try {
                    XposedBridge.hookAllMethods(apmClass, m, clientInfoHook)
                } catch (_: Throwable) {}
            }

            // B3. Track Resources instances returned by getResourcesForApplication(ApplicationInfo / String)
            try {
                XposedBridge.hookAllMethods(
                    apmClass,
                    "getResourcesForApplication",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val resObj = param.result as? Resources ?: return
                            val firstArg = param.args.firstOrNull() ?: return
                            val targetPkg = when (firstArg) {
                                is ApplicationInfo -> {
                                    recordAppInfoIcons(firstArg)
                                    firstArg.packageName
                                }
                                is String -> firstArg
                                else -> null
                            } ?: return
                            resourcesToPkgMap[System.identityHashCode(resObj)] = targetPkg
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // C. Hook LauncherActivityInfo.getLabel() -> custom user-typed name in any launcher
        try {
            XposedBridge.hookAllMethods(
                LauncherActivityInfo::class.java,
                "getLabel",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return
                        val info = param.thisObject as? LauncherActivityInfo ?: return
                        val targetPkg = info.componentName?.packageName ?: return
                        val rule = rules[targetPkg] ?: return
                        if (shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                            param.result = rule.customLabel
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // D. Hook PackageItemInfo.loadLabel(PackageManager) & ApplicationPackageManager.getApplicationLabel
        // Note: We intentionally allow targetPkg == currentPkg when disguised so the app's own notifications,
        // Recents TaskDescription, and toolbar titles use the clean disguised name.
        try {
            XposedBridge.hookAllMethods(
                PackageItemInfo::class.java,
                "loadLabel",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return
                        val item = param.thisObject as? PackageItemInfo ?: return
                        val targetPkg = item.packageName ?: return
                        recordPackageIconRes(targetPkg, item.icon, item.logo)
                        val rule = rules[targetPkg] ?: return
                        if (shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                            param.result = rule.customLabel
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        if (apmClass != null) {
            try {
                XposedBridge.hookAllMethods(
                    apmClass,
                    "getApplicationLabel",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rules = getStealthRules(xSharedPrefs)
                            if (rules.isEmpty()) return
                            val appInfo = param.args.firstOrNull() as? ApplicationInfo ?: return
                            val targetPkg = appInfo.packageName ?: return
                            recordAppInfoIcons(appInfo)
                            val rule = rules[targetPkg] ?: return
                            if (shouldSpoofInCurrentClient(currentPkg, rule) && rule.customLabel.isNotBlank()) {
                                param.result = rule.customLabel
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // E1. Hook ApplicationPackageManager.getApplicationIcon / getActivityIcon / loadItemIcon / loadUnbadgedItemIcon / getDrawable
            val apmIconHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rules = getStealthRules(xSharedPrefs)
                    if (rules.isEmpty()) return
                    val firstArg = param.args.firstOrNull() ?: return
                    val targetPkg = when (firstArg) {
                        is String -> firstArg
                        is ApplicationInfo -> {
                            recordAppInfoIcons(firstArg)
                            firstArg.packageName
                        }
                        is PackageItemInfo -> {
                            recordPackageIconRes(firstArg.packageName ?: "", firstArg.icon, firstArg.logo)
                            firstArg.packageName
                        }
                        is ComponentName -> firstArg.packageName
                        is Intent -> firstArg.`package` ?: firstArg.component?.packageName
                        else -> null
                    } ?: return
                    val rule = rules[targetPkg] ?: return
                    if (!shouldSpoofInCurrentClient(currentPkg, rule)) return
                    if (rule.iconPreset == DisguiseIconPreset.DEFAULT && rule.customIconBase64.isBlank()) return

                    // For getDrawable(String packageName, int resId, ApplicationInfo appInfo), only intercept
                    // when loading the app's icon resource (not arbitrary internal UI drawables inside the app itself)
                    if (param.method.name == "getDrawable" && param.args.size >= 3) {
                        if (targetPkg == currentPkg) return
                        val resId = param.args[1] as? Int ?: 0
                        val appInfo = param.args[2] as? ApplicationInfo
                        recordAppInfoIcons(appInfo)
                        val knownSet = knownPackageIconRes[targetPkg]
                        val isIconRes = (appInfo != null && (resId == appInfo.icon || resId == appInfo.logo)) ||
                                (knownSet != null && knownSet.contains(resId)) ||
                                (rule.donorIconRes != 0 && resId == rule.donorIconRes)
                        if (!isIconRes && appInfo != null && appInfo.icon != 0) {
                            return
                        }
                    }

                    param.result = resolveDisguiseDrawable(rule)
                }
            }

            for (methodName in listOf(
                "getApplicationIcon",
                "getActivityIcon",
                "loadItemIcon",
                "loadUnbadgedItemIcon",
                "getDrawable"
            )) {
                try {
                    XposedBridge.hookAllMethods(apmClass, methodName, apmIconHook)
                } catch (_: Throwable) {}
            }
        }

        // E2. Hook LauncherActivityInfo.getIcon / getBadgedIcon & PackageItemInfo.loadIcon / loadUnbadgedIcon
        val itemIconHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val rules = getStealthRules(xSharedPrefs)
                if (rules.isEmpty()) return
                val targetPkg = when (val obj = param.thisObject) {
                    is LauncherActivityInfo -> obj.componentName?.packageName
                    is PackageItemInfo -> obj.packageName
                    is ActivityInfo -> obj.packageName
                    is ApplicationInfo -> obj.packageName
                    else -> null
                } ?: return
                val rule = rules[targetPkg] ?: return
                if (!shouldSpoofInCurrentClient(currentPkg, rule)) return
                if (rule.iconPreset == DisguiseIconPreset.DEFAULT && rule.customIconBase64.isBlank()) return
                param.result = resolveDisguiseDrawable(rule)
            }
        }

        try {
            XposedBridge.hookAllMethods(LauncherActivityInfo::class.java, "getIcon", itemIconHook)
            XposedBridge.hookAllMethods(LauncherActivityInfo::class.java, "getBadgedIcon", itemIconHook)
            XposedBridge.hookAllMethods(PackageItemInfo::class.java, "loadIcon", itemIconHook)
            XposedBridge.hookAllMethods(PackageItemInfo::class.java, "loadUnbadgedIcon", itemIconHook)
        } catch (_: Throwable) {}

        // E3. Hook Resources.getDrawableForDensity & Resources.getDrawable for launchers that load
        // icons via pm.getResourcesForApplication(appInfo).getDrawableForDensity(iconRes, density)
        val resDrawableHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isInResDrawableHook.get() == true) return
                val resObj = param.thisObject as? Resources ?: return
                val targetPkg = resourcesToPkgMap[System.identityHashCode(resObj)] ?: return
                if (targetPkg == currentPkg) return
                val resId = param.args.firstOrNull() as? Int ?: return
                if (resId == 0) return
                val knownSet = knownPackageIconRes[targetPkg]
                if (knownSet != null && !knownSet.contains(resId)) return

                val rules = getStealthRules(xSharedPrefs)
                val rule = rules[targetPkg] ?: return
                if (!shouldSpoofInCurrentClient(currentPkg, rule)) return
                if (rule.iconPreset == DisguiseIconPreset.DEFAULT && rule.customIconBase64.isBlank()) return

                isInResDrawableHook.set(true)
                try {
                    param.result = resolveDisguiseDrawable(rule)
                } finally {
                    isInResDrawableHook.set(false)
                }
            }
        }
        try {
            XposedBridge.hookAllMethods(Resources::class.java, "getDrawableForDensity", resDrawableHook)
            XposedBridge.hookAllMethods(Resources::class.java, "getDrawable", resDrawableHook)
        } catch (_: Throwable) {}

        // E4. Hook Launcher3 / Pixel / ColorOS / OEM IconProvider.getIcon
        val iconProviderClasses = listOf(
            "com.android.launcher3.icons.IconProvider",
            "com.android.launcher3.IconProvider",
            "com.android.launcher3.icons.QuickstepIconProvider"
        )
        for (clsName in iconProviderClasses) {
            try {
                val iconProviderCls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader) ?: continue
                XposedBridge.hookAllMethods(
                    iconProviderCls,
                    "getIcon",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rules = getStealthRules(xSharedPrefs)
                            if (rules.isEmpty()) return
                            val firstArg = param.args.firstOrNull() ?: return
                            val targetPkg = when (firstArg) {
                                is ActivityInfo -> firstArg.packageName
                                is LauncherActivityInfo -> firstArg.componentName?.packageName
                                is ComponentName -> firstArg.packageName
                                else -> null
                            } ?: return
                            if (targetPkg == currentPkg) return
                            val rule = rules[targetPkg] ?: return
                            if (!shouldSpoofInCurrentClient(currentPkg, rule)) return
                            if (rule.iconPreset == DisguiseIconPreset.DEFAULT && rule.customIconBase64.isBlank()) return
                            param.result = resolveDisguiseDrawable(rule)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // F. Hook Activity.setTaskDescription so Recents screen cards use the disguised label
        try {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "setTaskDescription",
                object : XC_MethodHook() {
                    @Suppress("DEPRECATION")
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        val rule = rules[currentPkg] ?: return
                        if (rule.visibilityMode != LauncherVisibilityMode.DISGUISED || rule.customLabel.isBlank()) return
                        val td = param.args.firstOrNull() as? ActivityManager.TaskDescription ?: return
                        if (td.label != rule.customLabel) {
                            param.args[0] = ActivityManager.TaskDescription(rule.customLabel)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun isHiddenInPlayStore(
        pkg: String?,
        rules: Map<String, IsolationRuleManager.StealthRule>
    ): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (fakeUninstalledPackages.contains(pkg)) return true
        return rules[pkg]?.hideFromPlayStore == true
    }

    private fun dispatchFakeUninstallSuccess(
        args: Array<Any?>?,
        pkg: String
    ) {
        val statusReceiver = args?.firstOrNull {
            it != null && it.javaClass.name == "android.content.IntentSender"
        }
        val deleteObserver = args?.firstOrNull {
            it != null && (it.javaClass.name.contains("IPackageDeleteObserver") ||
                    it.javaClass.interfaces.any { iface -> iface.name.contains("IPackageDeleteObserver") })
        }

        refreshExecutor.execute {
            try {
                // Brief realistic delay so Play Store displays "Uninstalling..." spinner before flipping to "Install"
                Thread.sleep(450L)
            } catch (_: Throwable) {}

            if (statusReceiver != null) {
                try {
                    val fillIn = Intent().apply {
                        putExtra("android.content.pm.extra.PACKAGE_NAME", pkg)
                        putExtra("android.content.pm.extra.STATUS", 0) // PackageInstaller.STATUS_SUCCESS
                        putExtra("android.content.pm.extra.STATUS_MESSAGE", "DELETE_SUCCEEDED")
                    }
                    val app = try { AndroidAppHelper.currentApplication() } catch (_: Throwable) { null }
                    if (app != null) {
                        XposedHelpers.callMethod(
                            statusReceiver,
                            "sendIntent",
                            app,
                            0,
                            fillIn,
                            null,
                            null
                        )
                    } else {
                        val iIntentSender = try {
                            XposedHelpers.callMethod(statusReceiver, "getTarget")
                        } catch (_: Throwable) { null }
                        if (iIntentSender != null) {
                            try {
                                XposedHelpers.callMethod(
                                    iIntentSender,
                                    "send",
                                    0,
                                    fillIn,
                                    null,
                                    null,
                                    null,
                                    null
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (deleteObserver != null) {
                try {
                    XposedHelpers.callMethod(deleteObserver, "onPackageDeleted", pkg, 1, "DELETE_SUCCEEDED")
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.callMethod(deleteObserver, "packageDeleted", pkg, 1)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Client-side hooks inside Google Play Store (`com.android.vending`) when Play Store is in scope.
     */
    private fun attachPlayStoreClientHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        xSharedPrefs: XSharedPreferences?
    ) {
        val apmClass = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager",
            lpparam.classLoader
        )

        if (apmClass != null) {
            try {
                XposedBridge.hookAllMethods(
                    apmClass,
                    "getInstalledPackages",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val rules = getStealthRules(xSharedPrefs)
                            if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                            val list = param.result as? List<*> ?: return
                            val filtered = list.filter { item ->
                                val pkgInfo = item as? PackageInfo ?: return@filter true
                                !isHiddenInPlayStore(pkgInfo.packageName, rules)
                            }
                            if (filtered.size != list.size) {
                                param.result = ArrayList(filtered)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(
                    apmClass,
                    "getInstalledApplications",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val rules = getStealthRules(xSharedPrefs)
                            if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                            val list = param.result as? List<*> ?: return
                            val filtered = list.filter { item ->
                                val appInfo = item as? ApplicationInfo ?: return@filter true
                                !isHiddenInPlayStore(appInfo.packageName, rules)
                            }
                            if (filtered.size != list.size) {
                                param.result = ArrayList(filtered)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // Intercepts getPackageInfo(String, ...), getPackageInfo(VersionedPackage, ...),
            // getApplicationInfo, getPackageUid, getInstallSourceInfo, getInstallerPackageName
            val notFoundHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rules = getStealthRules(xSharedPrefs)
                    if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                    val pkg = extractPkgFromArgs(param.args) ?: return
                    if (isHiddenInPlayStore(pkg, rules)) {
                        param.throwable = PackageManager.NameNotFoundException(pkg)
                    }
                }
            }
            for (methodName in listOf(
                "getPackageInfo",
                "getPackageInfoAsUser",
                "getApplicationInfo",
                "getApplicationInfoAsUser",
                "getPackageUid",
                "getPackageUidAsUser",
                "getInstallSourceInfo",
                "getInstallerPackageName"
            )) {
                try {
                    XposedBridge.hookAllMethods(apmClass, methodName, notFoundHook)
                } catch (_: Throwable) {}
            }

            val nullIntentHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rules = getStealthRules(xSharedPrefs)
                    if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                    val pkg = extractPkgFromArgs(param.args) ?: return
                    if (isHiddenInPlayStore(pkg, rules)) {
                        param.result = null
                    }
                }
            }
            for (methodName in listOf(
                "getLaunchIntentForPackage",
                "getLeanbackLaunchIntentForPackage"
            )) {
                try {
                    XposedBridge.hookAllMethods(apmClass, methodName, nullIntentHook)
                } catch (_: Throwable) {}
            }

            // Intercept legacy deletePackage / deletePackageAsUser in Play Store
            val deleteHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rules = getStealthRules(xSharedPrefs)
                    if (rules.isEmpty()) return
                    val pkg = extractPkgFromArgs(param.args) ?: return
                    if (rules.containsKey(pkg)) {
                        fakeUninstalledPackages.add(pkg)
                        dispatchFakeUninstallSuccess(param.args, pkg)
                        param.result = null
                    }
                }
            }
            for (methodName in listOf("deletePackage", "deletePackageAsUser")) {
                try {
                    XposedBridge.hookAllMethods(apmClass, methodName, deleteHook)
                } catch (_: Throwable) {}
            }
        }

        // Intercept PackageInstaller.uninstall / uninstallExistingPackage in Play Store so clicking
        // "Uninstall" plays the uninstall animation and switches to "Install" without actually uninstalling!
        val packageInstallerClasses = listOf(
            "android.content.pm.PackageInstaller",
            "android.app.PackageInstaller"
        )
        for (clsName in packageInstallerClasses) {
            try {
                val piCls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader) ?: continue
                val uninstallHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return
                        val pkg = extractPkgFromArgs(param.args) ?: return
                        if (rules.containsKey(pkg)) {
                            fakeUninstalledPackages.add(pkg)
                            dispatchFakeUninstallSuccess(param.args, pkg)
                            param.result = null
                        }
                    }
                }
                XposedBridge.hookAllMethods(piCls, "uninstall", uninstallHook)
                XposedBridge.hookAllMethods(piCls, "uninstallExistingPackage", uninstallHook)
            } catch (_: Throwable) {}
        }
    }

    private fun shouldAllowDonorSwapInSystemServer(callingAppId: Int, targetAppId: Int): Boolean {
        return callingAppId >= Process.FIRST_APPLICATION_UID &&
                callingAppId != cachedHollowAppId &&
                callingAppId != cachedSystemUiAppId &&
                (targetAppId <= 0 || callingAppId != targetAppId)
    }

    private fun applyDonorSwapToAppInfo(
        appInfo: ApplicationInfo,
        rule: IsolationRuleManager.StealthRule,
        allowDonorResourceSwap: Boolean
    ) {
        if (rule.customLabel.isNotBlank()) {
            appInfo.nonLocalizedLabel = rule.customLabel
            appInfo.labelRes = 0
        }
        val swapRes = allowDonorResourceSwap &&
                rule.iconPreset != DisguiseIconPreset.DEFAULT &&
                rule.donorSourceDir.isNotBlank() &&
                rule.donorIconRes != 0
        if (swapRes) {
            appInfo.icon = rule.donorIconRes
            appInfo.logo = rule.donorIconRes
            try { XposedHelpers.setIntField(appInfo, "roundIconRes", 0) } catch (_: Throwable) {}
            appInfo.sourceDir = rule.donorSourceDir
            appInfo.publicSourceDir = rule.donorSourceDir
            appInfo.splitSourceDirs = null
            appInfo.splitPublicSourceDirs = null
            try { XposedHelpers.setObjectField(appInfo, "resourceDirs", null) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(appInfo, "overlayPaths", null) } catch (_: Throwable) {}
        }
    }

    /**
     * System Server (`android`) hook on `LauncherAppsService$LauncherAppsImpl.getLauncherActivities` & `resolveActivity`.
     */
    private fun attachSystemServerLauncherAppsHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        xSharedPrefs: XSharedPreferences?
    ) {
        try {
            val implCls = XposedHelpers.findClassIfExists(
                "com.android.server.pm.LauncherAppsService\$LauncherAppsImpl",
                lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(
                implCls,
                "getLauncherActivities",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                        resolveSpecialAppIdsIfNeeded()

                        val callingPkg = param.args.firstOrNull { it is String } as? String
                        if (callingPkg == MODULE_PKG) return
                        val callingAppId = Binder.getCallingUid() % 100_000
                        val allowDonorResourceSwap = shouldAllowDonorSwapInSystemServer(callingAppId, -1) &&
                                callingPkg != "com.android.systemui"

                        val rawResult = param.result ?: return
                        if (rawResult is List<*>) {
                            param.result = filterAndDisguiseLauncherItems(
                                rawResult,
                                callingPkg,
                                rules,
                                allowDonorResourceSwap
                            )
                        } else if (rawResult.javaClass.name == "android.content.pm.ParceledListSlice") {
                            try {
                                val list = XposedHelpers.callMethod(rawResult, "getList") as? List<*> ?: return
                                val modified = filterAndDisguiseLauncherItems(
                                    list,
                                    callingPkg,
                                    rules,
                                    allowDonorResourceSwap
                                )
                                param.result = XposedHelpers.newInstance(rawResult.javaClass, modified)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )

            // Also hook single activity resolution in LauncherAppsService for unhooked 3rd-party launchers
            val resolveHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val rules = getStealthRules(xSharedPrefs)
                    if (rules.isEmpty()) return
                    resolveSpecialAppIdsIfNeeded()

                    val callingPkg = param.args.firstOrNull { it is String } as? String
                    if (callingPkg == MODULE_PKG) return
                    val callingAppId = Binder.getCallingUid() % 100_000
                    val allowDonorResourceSwap = shouldAllowDonorSwapInSystemServer(callingAppId, -1) &&
                            callingPkg != "com.android.systemui"

                    val item = param.result ?: return
                    val activityInfo: ActivityInfo? = when (item) {
                        is ResolveInfo -> item.activityInfo
                        is ActivityInfo -> item
                        else -> try {
                            XposedHelpers.callMethod(item, "getActivityInfo") as? ActivityInfo
                                ?: XposedHelpers.getObjectField(item, "mActivityInfo") as? ActivityInfo
                        } catch (_: Throwable) { null }
                    }
                    val targetPkg = activityInfo?.packageName ?: return
                    if (targetPkg == callingPkg) return
                    val rule = rules[targetPkg] ?: return
                    if (rule.visibilityMode == LauncherVisibilityMode.HIDDEN) {
                        param.result = null
                        return
                    }
                    if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED && activityInfo != null) {
                        if (item is ActivityInfo) {
                            val swapRes = allowDonorResourceSwap &&
                                    rule.iconPreset != DisguiseIconPreset.DEFAULT &&
                                    rule.donorSourceDir.isNotBlank() &&
                                    rule.donorIconRes != 0
                            param.result = ActivityInfo(activityInfo).apply {
                                if (rule.customLabel.isNotBlank()) {
                                    nonLocalizedLabel = rule.customLabel
                                    labelRes = 0
                                }
                                if (swapRes) {
                                    icon = rule.donorIconRes
                                    logo = rule.donorIconRes
                                }
                                if (applicationInfo != null) {
                                    applicationInfo = ApplicationInfo(applicationInfo).also {
                                        applyDonorSwapToAppInfo(it, rule, allowDonorResourceSwap)
                                    }
                                }
                            }
                        } else {
                            applyDisguiseToActivityInfo(item, activityInfo, rule, allowDonorResourceSwap)
                        }
                    }
                }
            }
            for (m in listOf("resolveActivity", "resolveLauncherActivityInternal")) {
                try {
                    XposedBridge.hookAllMethods(implCls, m, resolveHook)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun filterAndDisguiseLauncherItems(
        items: List<*>,
        callingPkg: String?,
        rules: Map<String, IsolationRuleManager.StealthRule>,
        allowDonorResourceSwap: Boolean
    ): List<Any?> {
        val out = ArrayList<Any?>(items.size)
        for (item in items) {
            if (item == null) continue
            val activityInfo: ActivityInfo? = when (item) {
                is ResolveInfo -> item.activityInfo
                is ActivityInfo -> item
                else -> try {
                    XposedHelpers.callMethod(item, "getActivityInfo") as? ActivityInfo
                        ?: XposedHelpers.getObjectField(item, "mActivityInfo") as? ActivityInfo
                } catch (_: Throwable) {
                    null
                }
            }
            val targetPkg = activityInfo?.packageName
            if (targetPkg != null && targetPkg != callingPkg) {
                if (callingPkg == PLAY_STORE_PKG && isHiddenInPlayStore(targetPkg, rules)) {
                    continue
                }
                val rule = rules[targetPkg]
                if (rule != null) {
                    if (rule.visibilityMode == LauncherVisibilityMode.HIDDEN) {
                        continue
                    }
                    if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED && activityInfo != null) {
                        if (item is ActivityInfo) {
                            val swapRes = allowDonorResourceSwap &&
                                    rule.iconPreset != DisguiseIconPreset.DEFAULT &&
                                    rule.donorSourceDir.isNotBlank() &&
                                    rule.donorIconRes != 0
                            val clonedAi = ActivityInfo(activityInfo).apply {
                                if (rule.customLabel.isNotBlank()) {
                                    nonLocalizedLabel = rule.customLabel
                                    labelRes = 0
                                }
                                if (swapRes) {
                                    icon = rule.donorIconRes
                                    logo = rule.donorIconRes
                                }
                                if (applicationInfo != null) {
                                    applicationInfo = ApplicationInfo(applicationInfo).also {
                                        applyDonorSwapToAppInfo(it, rule, allowDonorResourceSwap)
                                    }
                                }
                            }
                            out.add(clonedAi)
                            continue
                        } else {
                            applyDisguiseToActivityInfo(item, activityInfo, rule, allowDonorResourceSwap)
                        }
                    }
                }
            }
            out.add(item)
        }
        return out
    }

    private fun applyDisguiseToActivityInfo(
        container: Any,
        originalInfo: ActivityInfo,
        rule: IsolationRuleManager.StealthRule,
        allowDonorResourceSwap: Boolean
    ) {
        try {
            val swapRes = allowDonorResourceSwap &&
                    rule.iconPreset != DisguiseIconPreset.DEFAULT &&
                    rule.donorSourceDir.isNotBlank() &&
                    rule.donorIconRes != 0

            val clonedActivityInfo = ActivityInfo(originalInfo).apply {
                if (rule.customLabel.isNotBlank()) {
                    nonLocalizedLabel = rule.customLabel
                    labelRes = 0
                }
                if (swapRes) {
                    icon = rule.donorIconRes
                    logo = rule.donorIconRes
                }
                if (applicationInfo != null) {
                    applicationInfo = ApplicationInfo(applicationInfo).also {
                        applyDonorSwapToAppInfo(it, rule, allowDonorResourceSwap)
                    }
                }
            }
            when (container) {
                is ResolveInfo -> container.activityInfo = clonedActivityInfo
                else -> XposedHelpers.setObjectField(container, "mActivityInfo", clonedActivityInfo)
            }
        } catch (_: Throwable) {}
    }

    private fun applyDisguiseToResolveInfo(
        ri: ResolveInfo,
        rule: IsolationRuleManager.StealthRule,
        allowDonorResourceSwap: Boolean
    ) {
        val ai = ri.activityInfo ?: return
        applyDisguiseToActivityInfo(ri, ai, rule, allowDonorResourceSwap)
        if (rule.customLabel.isNotBlank()) {
            ri.nonLocalizedLabel = rule.customLabel
            ri.labelRes = 0
        }
        if (allowDonorResourceSwap &&
            rule.iconPreset != DisguiseIconPreset.DEFAULT &&
            rule.donorSourceDir.isNotBlank() &&
            rule.donorIconRes != 0
        ) {
            ri.icon = rule.donorIconRes
        }
    }

    /**
     * System Server (`android`) hooks on `ComputerEngine` / `PackageManagerService` for:
     *  1. `queryIntentActivitiesInternal` (Launcher queries & donor icon resource swap for unhooked launchers)
     *  2. `getInstalledApplications` & `getInstalledPackages` (Play Store hiding + unhooked Launcher hiding/disguise)
     *  3. `getApplicationInfo`, `getActivityInfo`, `getPackageInfo`, `getPackageUid`, `getInstallSourceInfo`, `getInstallerPackageName`
     *  4. `PackageInstallerService.uninstall` / `deletePackageVersioned` (Play Store Fake Uninstall protection)
     */
    private fun attachSystemServerPackageManagerHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        xSharedPrefs: XSharedPreferences?
    ) {
        val candidateClasses = listOf(
            "com.android.server.pm.ComputerEngine",
            "com.android.server.pm.PackageManagerService"
        )
        for (clsName in candidateClasses) {
            try {
                val cls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader) ?: continue

                // 1. queryIntentActivitiesInternal
                XposedBridge.hookAllMethods(
                    cls,
                    "queryIntentActivitiesInternal",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val rules = getStealthRules(xSharedPrefs)
                            if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return

                            if (isInPmQueryHook.get() == true) return
                            isInPmQueryHook.set(true)
                            try {
                                resolveSpecialAppIdsIfNeeded()
                                val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                                val categories = intent.categories
                                val isLauncherQuery = categories?.contains(Intent.CATEGORY_LAUNCHER) == true ||
                                        categories?.contains(Intent.CATEGORY_INFO) == true ||
                                        intent.action == Intent.ACTION_MAIN
                                if (!isLauncherQuery) return

                                val list = param.result as? List<*> ?: return
                                val callingAppId = Binder.getCallingUid() % 100_000

                                for (item in list) {
                                    val ri = item as? ResolveInfo ?: continue
                                    val pkg = ri.activityInfo?.packageName ?: continue
                                    val uid = ri.activityInfo?.applicationInfo?.uid ?: -1
                                    if (uid > 0) {
                                        if (pkg == MODULE_PKG) cachedHollowAppId = uid % 100_000
                                        if (pkg == PLAY_STORE_PKG) cachedPlayStoreAppId = uid % 100_000
                                        if (pkg == "com.android.systemui") cachedSystemUiAppId = uid % 100_000
                                    }
                                }
                                if (callingAppId == cachedHollowAppId) return

                                val explicitPkg = intent.`package` ?: intent.component?.packageName
                                if (!explicitPkg.isNullOrEmpty() &&
                                    (callingAppId == Process.SYSTEM_UID || callingAppId == cachedHollowAppId)
                                ) {
                                    return
                                }

                                val out = ArrayList<ResolveInfo>(list.size)
                                var modified = false
                                for (item in list) {
                                    val ri = item as? ResolveInfo ?: continue
                                    val targetPkg = ri.activityInfo?.packageName ?: run {
                                        out.add(ri)
                                        continue
                                    }
                                    val targetAppId = (ri.activityInfo?.applicationInfo?.uid ?: -1) % 100_000
                                    if (targetAppId > 0 && targetAppId == callingAppId) {
                                        out.add(ri)
                                        continue
                                    }
                                    if (callingAppId == cachedPlayStoreAppId && isHiddenInPlayStore(targetPkg, rules)) {
                                        modified = true
                                        continue
                                    }
                                    val rule = rules[targetPkg]
                                    if (rule?.visibilityMode == LauncherVisibilityMode.HIDDEN) {
                                        modified = true
                                        continue
                                    }
                                    if (rule != null && rule.visibilityMode == LauncherVisibilityMode.DISGUISED) {
                                        val allowDonorSwap = shouldAllowDonorSwapInSystemServer(callingAppId, targetAppId)
                                        val clonedRi = ResolveInfo(ri)
                                        applyDisguiseToResolveInfo(clonedRi, rule, allowDonorSwap)
                                        out.add(clonedRi)
                                        modified = true
                                    } else {
                                        out.add(ri)
                                    }
                                }
                                if (modified) {
                                    param.result = out
                                }
                            } finally {
                                isInPmQueryHook.set(false)
                            }
                        }
                    }
                )

                // 2. getInstalledApplications / getInstalledPackages
                val listHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                        if (isInPmInfoHook.get() == true) return
                        isInPmInfoHook.set(true)
                        try {
                            resolveSpecialAppIdsIfNeeded()
                            val callingAppId = Binder.getCallingUid() % 100_000
                            if (callingAppId == cachedHollowAppId) return

                            val rawResult = param.result ?: return
                            val isParceledSlice = rawResult.javaClass.name == "android.content.pm.ParceledListSlice"
                            val list: List<*> = if (rawResult is List<*>) {
                                rawResult
                            } else if (isParceledSlice) {
                                XposedHelpers.callMethod(rawResult, "getList") as? List<*> ?: return
                            } else {
                                return
                            }

                            val out = ArrayList<Any?>(list.size)
                            var modified = false

                            for (item in list) {
                                when (item) {
                                    is ApplicationInfo -> {
                                        val pkg = item.packageName ?: continue
                                        val targetAppId = if (item.uid > 0) item.uid % 100_000 else -1
                                        if (pkg == MODULE_PKG && targetAppId > 0) cachedHollowAppId = targetAppId
                                        if (pkg == PLAY_STORE_PKG && targetAppId > 0) cachedPlayStoreAppId = targetAppId
                                        if (pkg == "com.android.systemui" && targetAppId > 0) cachedSystemUiAppId = targetAppId
                                        if (callingAppId == cachedHollowAppId || (targetAppId > 0 && callingAppId == targetAppId)) {
                                            out.add(item)
                                            continue
                                        }
                                        if (callingAppId == cachedPlayStoreAppId && isHiddenInPlayStore(pkg, rules)) {
                                            modified = true
                                            continue
                                        }
                                        val rule = rules[pkg]
                                        if (rule != null) {
                                            // Hide HIDDEN apps from any 3rd-party launcher / app scanner calling getInstalledApplications
                                            if (rule.visibilityMode == LauncherVisibilityMode.HIDDEN &&
                                                callingAppId >= Process.FIRST_APPLICATION_UID &&
                                                callingAppId != cachedSystemUiAppId
                                            ) {
                                                modified = true
                                                continue
                                            }
                                            if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED) {
                                                val allowSwap = shouldAllowDonorSwapInSystemServer(callingAppId, targetAppId)
                                                val cloned = ApplicationInfo(item).also {
                                                    applyDonorSwapToAppInfo(it, rule, allowSwap)
                                                }
                                                out.add(cloned)
                                                modified = true
                                                continue
                                            }
                                        }
                                        out.add(item)
                                    }
                                    is PackageInfo -> {
                                        val pkg = item.packageName ?: continue
                                        val uid = item.applicationInfo?.uid ?: -1
                                        val targetAppId = if (uid > 0) uid % 100_000 else -1
                                        if (pkg == MODULE_PKG && targetAppId > 0) cachedHollowAppId = targetAppId
                                        if (pkg == PLAY_STORE_PKG && targetAppId > 0) cachedPlayStoreAppId = targetAppId
                                        if (pkg == "com.android.systemui" && targetAppId > 0) cachedSystemUiAppId = targetAppId
                                        if (callingAppId == cachedHollowAppId || (targetAppId > 0 && callingAppId == targetAppId)) {
                                            out.add(item)
                                            continue
                                        }
                                        if (callingAppId == cachedPlayStoreAppId && isHiddenInPlayStore(pkg, rules)) {
                                            modified = true
                                            continue
                                        }
                                        val rule = rules[pkg]
                                        if (rule != null) {
                                            if (rule.visibilityMode == LauncherVisibilityMode.HIDDEN &&
                                                callingAppId >= Process.FIRST_APPLICATION_UID &&
                                                callingAppId != cachedSystemUiAppId
                                            ) {
                                                modified = true
                                                continue
                                            }
                                            if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED) {
                                                val allowSwap = shouldAllowDonorSwapInSystemServer(callingAppId, targetAppId)
                                                val clonedPkg = cloneAndSpoofPackageInfo(item, rule, allowSwap)
                                                out.add(clonedPkg)
                                                modified = true
                                                continue
                                            }
                                        }
                                        out.add(item)
                                    }
                                    else -> out.add(item)
                                }
                            }

                            if (modified) {
                                if (isParceledSlice) {
                                    param.result = XposedHelpers.newInstance(rawResult.javaClass, out)
                                } else {
                                    param.result = out
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            isInPmInfoHook.set(false)
                        }
                    }
                }

                for (m in listOf(
                    "getInstalledApplications",
                    "getInstalledPackages"
                )) {
                    try {
                        XposedBridge.hookAllMethods(cls, m, listHook)
                    } catch (_: Throwable) {}
                }

                // 3. getApplicationInfo / getActivityInfo / getPackageInfo
                val singleInfoHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                        if (isInPmInfoHook.get() == true) return
                        isInPmInfoHook.set(true)
                        try {
                            resolveSpecialAppIdsIfNeeded()
                            val callingAppId = Binder.getCallingUid() % 100_000
                            if (callingAppId == cachedHollowAppId) return

                            when (val res = param.result) {
                                is ApplicationInfo -> {
                                    val pkg = res.packageName ?: return
                                    val targetAppId = if (res.uid > 0) res.uid % 100_000 else -1
                                    if (pkg == MODULE_PKG && targetAppId > 0) cachedHollowAppId = targetAppId
                                    if (pkg == PLAY_STORE_PKG && targetAppId > 0) cachedPlayStoreAppId = targetAppId
                                    if (pkg == "com.android.systemui" && targetAppId > 0) cachedSystemUiAppId = targetAppId
                                    if (callingAppId == cachedHollowAppId || (targetAppId > 0 && callingAppId == targetAppId)) return
                                    if (callingAppId == cachedPlayStoreAppId && isHiddenInPlayStore(pkg, rules)) {
                                        param.result = null
                                        return
                                    }
                                    val rule = rules[pkg] ?: return
                                    if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED) {
                                        val allowSwap = shouldAllowDonorSwapInSystemServer(callingAppId, targetAppId)
                                        param.result = ApplicationInfo(res).also {
                                            applyDonorSwapToAppInfo(it, rule, allowSwap)
                                        }
                                    }
                                }
                                is ActivityInfo -> {
                                    val pkg = res.packageName ?: return
                                    val uid = res.applicationInfo?.uid ?: -1
                                    val targetAppId = if (uid > 0) uid % 100_000 else -1
                                    if (pkg == MODULE_PKG && targetAppId > 0) cachedHollowAppId = targetAppId
                                    if (pkg == PLAY_STORE_PKG && targetAppId > 0) cachedPlayStoreAppId = targetAppId
                                    if (pkg == "com.android.systemui" && targetAppId > 0) cachedSystemUiAppId = targetAppId
                                    if (callingAppId == cachedHollowAppId || (targetAppId > 0 && callingAppId == targetAppId)) return
                                    val rule = rules[pkg] ?: return
                                    if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED) {
                                        val allowSwap = shouldAllowDonorSwapInSystemServer(callingAppId, targetAppId)
                                        val swapRes = allowSwap &&
                                                rule.iconPreset != DisguiseIconPreset.DEFAULT &&
                                                rule.donorSourceDir.isNotBlank() &&
                                                rule.donorIconRes != 0
                                        param.result = ActivityInfo(res).apply {
                                            if (rule.customLabel.isNotBlank()) {
                                                nonLocalizedLabel = rule.customLabel
                                                labelRes = 0
                                            }
                                            if (swapRes) {
                                                icon = rule.donorIconRes
                                                logo = rule.donorIconRes
                                            }
                                            if (applicationInfo != null) {
                                                applicationInfo = ApplicationInfo(applicationInfo).also {
                                                    applyDonorSwapToAppInfo(it, rule, allowSwap)
                                                }
                                            }
                                        }
                                    }
                                }
                                is PackageInfo -> {
                                    val pkg = res.packageName ?: return
                                    val uid = res.applicationInfo?.uid ?: -1
                                    val targetAppId = if (uid > 0) uid % 100_000 else -1
                                    if (pkg == MODULE_PKG && targetAppId > 0) cachedHollowAppId = targetAppId
                                    if (pkg == PLAY_STORE_PKG && targetAppId > 0) cachedPlayStoreAppId = targetAppId
                                    if (pkg == "com.android.systemui" && targetAppId > 0) cachedSystemUiAppId = targetAppId
                                    if (callingAppId == cachedHollowAppId || (targetAppId > 0 && callingAppId == targetAppId)) return
                                    if (callingAppId == cachedPlayStoreAppId && isHiddenInPlayStore(pkg, rules)) {
                                        param.result = null
                                        return
                                    }
                                    val rule = rules[pkg] ?: return
                                    if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED) {
                                        val allowSwap = shouldAllowDonorSwapInSystemServer(callingAppId, targetAppId)
                                        param.result = cloneAndSpoofPackageInfo(res, rule, allowSwap)
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            isInPmInfoHook.set(false)
                        }
                    }
                }

                for (m in listOf(
                    "getApplicationInfo",
                    "getApplicationInfoInternal",
                    "getActivityInfo",
                    "getActivityInfoInternal",
                    "getPackageInfo",
                    "getPackageInfoInternal"
                )) {
                    try {
                        XposedBridge.hookAllMethods(cls, m, singleInfoHook)
                    } catch (_: Throwable) {}
                }

                // 3b. Hide getPackageUid / getInstallSourceInfo / getInstallerPackageName from Play Store in system_server
                val playStoreMetaHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty() && fakeUninstalledPackages.isEmpty()) return
                        resolveSpecialAppIdsIfNeeded()
                        val callingAppId = Binder.getCallingUid() % 100_000
                        if (callingAppId != cachedPlayStoreAppId) return
                        val pkg = extractPkgFromArgs(param.args) ?: return
                        if (isHiddenInPlayStore(pkg, rules)) {
                            if (param.method.name.startsWith("getPackageUid")) {
                                param.result = -1
                            } else {
                                param.result = null
                            }
                        }
                    }
                }
                for (m in listOf(
                    "getPackageUid",
                    "getPackageUidInternal",
                    "getInstallSourceInfo",
                    "getInstallerPackageName"
                )) {
                    try {
                        XposedBridge.hookAllMethods(cls, m, playStoreMetaHook)
                    } catch (_: Throwable) {}
                }

                break
            } catch (_: Throwable) {}
        }

        // 4. Hook PackageInstallerService.uninstall & PackageManagerService.deletePackageVersioned in system_server
        // so clicking "Uninstall" in Play Store fakes uninstallation at the system level too!
        val installerServiceClasses = listOf(
            "com.android.server.pm.PackageInstallerService",
            "com.android.server.pm.DeletePackageHelper",
            "com.android.server.pm.PackageManagerService"
        )
        for (clsName in installerServiceClasses) {
            try {
                val svcCls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader) ?: continue
                val sysUninstallHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rules = getStealthRules(xSharedPrefs)
                        if (rules.isEmpty()) return
                        resolveSpecialAppIdsIfNeeded()
                        val callingAppId = Binder.getCallingUid() % 100_000
                        // Only fake-uninstall when triggered by Play Store (so user can still genuinely uninstall from Settings if desired, or Play Store is spoofed)
                        if (callingAppId != cachedPlayStoreAppId &&
                            !param.args.any { it == PLAY_STORE_PKG }
                        ) {
                            return
                        }
                        val pkg = extractPkgFromArgs(param.args) ?: return
                        if (rules.containsKey(pkg)) {
                            fakeUninstalledPackages.add(pkg)
                            dispatchFakeUninstallSuccess(param.args, pkg)
                            param.result = null
                        }
                    }
                }
                for (m in listOf("uninstall", "uninstallExistingPackage", "deletePackageVersioned", "deletePackage")) {
                    try {
                        XposedBridge.hookAllMethods(svcCls, m, sysUninstallHook)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    private fun cloneAndSpoofPackageInfo(
        original: PackageInfo,
        rule: IsolationRuleManager.StealthRule,
        allowDonorResourceSwap: Boolean = false
    ): PackageInfo {
        val parcel = android.os.Parcel.obtain()
        return try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = PackageInfo.CREATOR.createFromParcel(parcel)
            // Bump lastUpdateTime deterministically based on disguise settings so Launcher3's
            // SQLite IconCache (app_icons.db) automatically invalidates stale cached icons!
            val ruleHash = (rule.customLabel.hashCode() xor
                    rule.iconPreset.name.hashCode() xor
                    rule.donorPkg.hashCode() xor
                    rule.customIconBase64.hashCode()).toLong() and 0xFFFFL
            copy.lastUpdateTime = original.lastUpdateTime + 1000L + ruleHash
            if (copy.applicationInfo != null) {
                copy.applicationInfo = ApplicationInfo(copy.applicationInfo).also {
                    applyDonorSwapToAppInfo(it, rule, allowDonorResourceSwap)
                }
            }
            copy
        } catch (_: Throwable) {
            original
        } finally {
            parcel.recycle()
        }
    }

    @SuppressLint("PrivateApi")
    private fun attachSystemServerActivitySupervisorHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        xSharedPrefs: XSharedPreferences?
    ) {
        val supervisorClasses = listOf(
            "com.android.server.wm.ActivityTaskSupervisor",
            "com.android.server.wm.ActivityStackSupervisor",
            "com.android.server.am.ActivityStackSupervisor"
        )
        for (clsName in supervisorClasses) {
            try {
                val supervisorClass = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader) ?: continue
                XposedBridge.hookAllMethods(
                    supervisorClass,
                    "resolveActivity",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val rules = getStealthRules(xSharedPrefs)
                            if (rules.isEmpty()) return
                            val originalInfo = param.result as? ActivityInfo ?: return
                            val targetPkg = originalInfo.packageName ?: return
                            val rule = rules[targetPkg] ?: return

                            if (rule.hideInRecents && rule.visibilityMode == LauncherVisibilityMode.HIDDEN) {
                                val copy = ActivityInfo(originalInfo).apply {
                                    flags = flags or ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
                                }
                                param.result = copy
                            } else if (rule.visibilityMode == LauncherVisibilityMode.DISGUISED &&
                                rule.customLabel.isNotBlank()
                            ) {
                                val copy = ActivityInfo(originalInfo).apply {
                                    nonLocalizedLabel = rule.customLabel
                                    labelRes = 0
                                }
                                param.result = copy
                            }
                        }
                    }
                )
                break
            } catch (_: Throwable) {}
        }
    }

    /**
     * Non-intrusive SystemUI Quick Settings `DateView` long-click hook.
     */
    private fun attachSystemUiDirectHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        xSharedPrefs: XSharedPreferences?
    ) {
        try {
            val dateViewCls = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.policy.DateView",
                lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(
                dateViewCls,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        view.setOnLongClickListener { v ->
                            getStealthRules(xSharedPrefs)
                            if (!cachedSystemUiTrigger) return@setOnLongClickListener false
                            launchHollowFromSystemUi(v.context)
                            true
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    @SuppressLint("WrongConstant")
    fun launchHollowFromSystemUi(context: Context) {
        try {
            val sbm = context.getSystemService("statusbar")
            if (sbm != null) {
                XposedHelpers.callMethod(sbm, "collapsePanels")
            }
        } catch (_: Throwable) {}

        val candidates = listOf(
            ComponentName(MODULE_PKG, "com.hollow.app.MainActivity"),
            ComponentName(MODULE_PKG, "com.hollow.app.MainActivityCalculatorAlias"),
            ComponentName(MODULE_PKG, "com.hollow.app.MainActivityNotesAlias")
        )
        for (component in candidates) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    this.component = component
                    addCategory(CATEGORY_LAUNCH_HOLLOW)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                return
            } catch (_: Throwable) {}
        }
    }

    /**
     * Resolves the disguise icon Drawable for a [rule]:
     *  1. If a custom image or cloned app icon bitmap is cached in `customBitmapCache[packageName]`,
     *     wraps it in an `AdaptiveIconDrawable` (Android 8.0+) or `BitmapDrawable`.
     *  2. Otherwise renders the selected [DisguiseIconPreset] wrapped in an `AdaptiveIconDrawable`.
     */
    fun resolveDisguiseDrawable(rule: IsolationRuleManager.StealthRule): Drawable {
        val customBmp = customBitmapCache[rule.packageName]
        if (customBmp != null) {
            return wrapBitmapAsAdaptiveIcon(customBmp, Color.TRANSPARENT)
        }
        return buildDecoyDrawable(rule.iconPreset)
    }

    private fun wrapBitmapAsAdaptiveIcon(bitmap: Bitmap, bgColor: Int): Drawable {
        val fgDrawable = BitmapDrawable(Resources.getSystem(), bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bgDrawable = if (bgColor == Color.TRANSPARENT) {
                ColorDrawable(Color.parseColor("#1E293B"))
            } else {
                ColorDrawable(bgColor)
            }
            return AdaptiveIconDrawable(bgDrawable, InsetDrawable(fgDrawable, 0.14f))
        }
        return fgDrawable
    }

    fun buildDecoyDrawable(preset: DisguiseIconPreset): Drawable {
        val bgColor = when (preset) {
            DisguiseIconPreset.CALCULATOR -> Color.parseColor("#1E3A5F")
            DisguiseIconPreset.NOTES -> Color.parseColor("#3E2E14")
            DisguiseIconPreset.CALENDAR -> Color.parseColor("#18392B")
            DisguiseIconPreset.CLOCK -> Color.parseColor("#2A244D")
            DisguiseIconPreset.WEATHER -> Color.parseColor("#16384C")
            DisguiseIconPreset.SYSTEM_UTILITY -> Color.parseColor("#262B33")
            else -> Color.parseColor("#1E3A5F")
        }
        val bmp = decoyBitmapCache.getOrPut(preset) {
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.color = bgColor
            canvas.drawRoundRect(RectF(8f, 8f, 184f, 184f), 44f, 44f, paint)

            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val symbol = when (preset) {
                DisguiseIconPreset.CALCULATOR -> "±="
                DisguiseIconPreset.NOTES -> "≡"
                DisguiseIconPreset.CALENDAR -> "31"
                DisguiseIconPreset.CLOCK -> "◷"
                DisguiseIconPreset.WEATHER -> "☀"
                DisguiseIconPreset.SYSTEM_UTILITY -> "⚙"
                else -> "H"
            }
            paint.textSize = 76f
            val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText(symbol, size / 2f, yPos, paint)
            bitmap
        }
        return wrapBitmapAsAdaptiveIcon(bmp, bgColor)
    }
}
