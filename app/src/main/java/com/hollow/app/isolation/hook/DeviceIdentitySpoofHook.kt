package com.hollow.app.isolation.hook

import android.app.Activity
import android.app.AndroidAppHelper
import android.bluetooth.BluetoothAdapter
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.MatrixCursor
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaDrm
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import android.webkit.WebSettings
import java.util.TimeZone
import javax.microedition.khronos.opengles.GL10
import com.hollow.app.isolation.model.IsolationRuleManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.ConnectException
import java.net.NetworkInterface
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-App Device Identity & Privacy Spoofing Engine for LSPosed / Vector.
 *
 * Spoofs hardware identifiers (Android ID, IMEI/MEID, IMSI, ICCID, Advertising ID, GSF ID,
 * Widevine MediaDrm Unique ID, Wi-Fi/BT MAC, Build/SystemProperties, Carrier) and provides
 * Privacy Shields (Root/Magisk/KSU/LSPosed hiding, ADB/Dev Options hiding, VPN/Proxy hiding,
 * Clipboard protection, Screenshot prevention, Package visibility guard, Location mocking,
 * Logcat silencing, Fake Battery, Knox warranty bit, and Network Kill Switch).
 */
object DeviceIdentitySpoofHook {

    private const val MODULE_PKG = IsolationRuleManager.MODULE_PKG
    private const val PROVIDER_URI = "content://$MODULE_PKG.provider"

    private val ROOT_AND_XPOSED_PACKAGES = setOf(
        MODULE_PKG,
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "io.github.huskydg.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "me.bmax.apatch",
        "org.lsposed.manager",
        "org.lsposed.lspatch",
        "de.robv.android.xposed.installer",
        "org.meowcat.edxposed.manager",
        "com.tsng.hidemyapplist",
        "eu.chainfire.supersu"
    )

    private val VPN_IFACE_PREFIXES = listOf("tun", "ppp", "wg", "tap", "ipsec", "utun", "pptp")

    @Volatile
    private var cachedIdentityMap: Map<String, IsolationRuleManager.IdentitySpoofRule> = emptyMap()

    @Volatile
    private var lastFetchMs: Long = 0L

    @Volatile
    private var fallbackXPrefs: XSharedPreferences? = null

    @Volatile
    private var sysMirrorXPrefs: XSharedPreferences? = null

    @Volatile
    private var tmpMirrorXPrefs: XSharedPreferences? = null

    private val attachedInProcess = AtomicBoolean(false)
    private val isRefreshing = AtomicBoolean(false)
    private val hasLoadedInitial = AtomicBoolean(false)
    private val buildFieldsApplied = AtomicBoolean(false)

    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Hollow-IdentityPrefs").apply { isDaemon = true }
    }

    fun attachForPackage(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        if (currentPkg == MODULE_PKG || currentPkg == "android" || currentPkg == "com.android.systemui") {
            return
        }
        val proc = lpparam.processName ?: currentPkg
        if (!lpparam.isFirstApplication && proc != currentPkg) {
            return
        }
        if (!attachedInProcess.compareAndSet(false, true)) {
            return
        }

        if (xSharedPrefs != null && fallbackXPrefs == null) {
            fallbackXPrefs = xSharedPrefs
        }

        tryLoadFromLocalFiles(xSharedPrefs)
        triggerAsyncRefresh(xSharedPrefs)

        // Apply Build static fields immediately if rule is already loaded
        getRuleForPackage(currentPkg, xSharedPrefs)?.let { applyBuildStaticFieldsOnce(it) }

        attachSettingsAndSystemPropertiesHooks(lpparam, currentPkg, xSharedPrefs)
        attachTelephonyAndHardwareHooks(lpparam, currentPkg, xSharedPrefs)
        attachNetworkAndMacHooks(lpparam, currentPkg, xSharedPrefs)
        attachDrmGsfAndAdIdHooks(lpparam, currentPkg, xSharedPrefs)
        attachPrivacyShieldHooks(lpparam, currentPkg, xSharedPrefs)
        attachLocationMockHooks(lpparam, currentPkg, xSharedPrefs)
        attachAppClonerPrivacyHooks(lpparam, currentPkg, xSharedPrefs)
        attachDigitalFootprintHooks(lpparam, currentPkg, xSharedPrefs)
        attachDeadmanSwitchHooks(lpparam, currentPkg, xSharedPrefs)
    }

    private fun obtainXPrefs(passed: XSharedPreferences?): XSharedPreferences? {
        if (passed != null) return passed
        val existing = fallbackXPrefs
        if (existing != null) return existing
        return try {
            XSharedPreferences(MODULE_PKG, IsolationRuleManager.PREFS_NAME).also {
                fallbackXPrefs = it
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
            val raw = prefs.getString(IsolationRuleManager.KEY_IDENTITY_TABLE, null)
            if (raw != null) {
                cachedIdentityMap = IsolationRuleManager.parseIdentityTable(raw)
                hasLoadedInitial.set(true)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryLoadFromLocalFiles(xSharedPrefs: XSharedPreferences?): Boolean {
        if (tryLoadFromPrefsInstance(obtainXPrefs(xSharedPrefs))) return true
        try {
            val sysFile = File("/data/system/isovault_rules.xml")
            if (sysFile.canRead()) {
                val sp = sysMirrorXPrefs ?: XSharedPreferences(sysFile).also { sysMirrorXPrefs = it }
                if (tryLoadFromPrefsInstance(sp)) return true
            }
        } catch (_: Throwable) {}
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
                val raw = bundle.getString("identityTable")
                if (raw != null) {
                    cachedIdentityMap = IsolationRuleManager.parseIdentityTable(raw)
                    hasLoadedInitial.set(true)
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun getRuleForPackage(
        pkg: String,
        xSharedPrefs: XSharedPreferences?
    ): IsolationRuleManager.IdentitySpoofRule? {
        if (!hasLoadedInitial.get()) {
            if (!tryLoadFromLocalFiles(xSharedPrefs)) {
                tryLoadFromProvider()
            }
        }
        val now = System.currentTimeMillis()
        if ((now - lastFetchMs) > 2000L) {
            triggerAsyncRefresh(xSharedPrefs)
        }
        val rule = cachedIdentityMap[pkg]
        if (rule != null) {
            applyBuildStaticFieldsOnce(rule)
        }
        return rule
    }

    private fun triggerAsyncRefresh(xSharedPrefs: XSharedPreferences?) {
        if (!isRefreshing.compareAndSet(false, true)) return
        lastFetchMs = System.currentTimeMillis()
        try {
            refreshExecutor.execute {
                try {
                    if (!tryLoadFromLocalFiles(xSharedPrefs)) {
                        tryLoadFromProvider()
                    }
                } catch (_: Throwable) {
                } finally {
                    lastFetchMs = if (hasLoadedInitial.get()) System.currentTimeMillis() else 0L
                    isRefreshing.set(false)
                }
            }
        } catch (_: Throwable) {
            isRefreshing.set(false)
        }
    }

    private fun applyBuildStaticFieldsOnce(rule: IsolationRuleManager.IdentitySpoofRule) {
        if (!buildFieldsApplied.compareAndSet(false, true)) return
        try {
            if (rule.isIdentitySpoofed) {
                XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", rule.brand)
                XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", rule.manufacturer)
                XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", rule.model)
                XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", rule.device)
                XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", rule.product)
                XposedHelpers.setStaticObjectField(Build::class.java, "BOARD", rule.board)
                XposedHelpers.setStaticObjectField(Build::class.java, "HARDWARE", rule.hardware.ifBlank { rule.board })
                if (rule.bootloader.isNotBlank()) {
                    XposedHelpers.setStaticObjectField(Build::class.java, "BOOTLOADER", rule.bootloader)
                }
                XposedHelpers.setStaticObjectField(Build::class.java, "FINGERPRINT", rule.fingerprint)
                XposedHelpers.setStaticObjectField(Build::class.java, "SERIAL", rule.serial)
            }
            if (rule.hideRootAndXposed) {
                XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
                XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")
            }
        } catch (_: Throwable) {}
    }

    @Suppress("UNUSED_PARAMETER")
    private fun attachSettingsAndSystemPropertiesHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. Hook SystemProperties.get(String) & get(String, String)
        try {
            val spCls = XposedHelpers.findClassIfExists("android.os.SystemProperties", lpparam.classLoader)
            if (spCls != null) {
                XposedBridge.hookAllMethods(
                    spCls,
                    "get",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args.firstOrNull() as? String ?: return
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            val isIdentityOn = rule.isIdentitySpoofed
                            val spoofed: String? = when (key) {
                                "ro.product.brand", "ro.product.system.brand", "ro.product.vendor.brand" -> if (isIdentityOn) rule.brand else null
                                "ro.product.manufacturer", "ro.product.system.manufacturer", "ro.product.vendor.manufacturer" -> if (isIdentityOn) rule.manufacturer else null
                                "ro.product.model", "ro.product.system.model", "ro.product.vendor.model" -> if (isIdentityOn) rule.model else null
                                "ro.product.device", "ro.product.system.device", "ro.product.vendor.device", "ro.build.product" -> if (isIdentityOn) rule.device else null
                                "ro.product.name", "ro.product.system.name", "ro.product.vendor.name" -> if (isIdentityOn) rule.product else null
                                "ro.product.board" -> if (isIdentityOn) rule.board else null
                                "ro.hardware", "ro.boot.hardware" -> if (isIdentityOn) rule.hardware.ifBlank { rule.board } else null
                                "ro.bootloader", "ro.boot.bootloader" -> if (isIdentityOn && rule.bootloader.isNotBlank()) rule.bootloader else null
                                "ro.build.fingerprint", "ro.system.build.fingerprint", "ro.vendor.build.fingerprint", "ro.bootimage.build.fingerprint" -> if (isIdentityOn) rule.fingerprint else null
                                "ro.serialno", "ro.boot.serialno" -> if (isIdentityOn) rule.serial else null
                                "gsm.sim.operator.alpha", "gsm.operator.alpha" -> if (isIdentityOn) rule.carrierName else null
                                "gsm.sim.operator.numeric", "gsm.operator.numeric" -> if (isIdentityOn) rule.simOperator else null
                                "ro.boot.warranty_bit", "ro.warranty_bit" -> if (rule.knoxWarrantyBitSpoof) "0" else null
                                "ro.build.tags" -> if (rule.hideRootAndXposed) "release-keys" else null
                                "ro.build.type" -> if (rule.hideRootAndXposed) "user" else null
                                "ro.debuggable" -> if (rule.hideRootAndXposed) "0" else null
                                "ro.secure" -> if (rule.hideRootAndXposed || rule.knoxWarrantyBitSpoof) "1" else null
                                "ro.boot.vbmeta.device_state" -> if (rule.hideRootAndXposed) "locked" else null
                                "ro.boot.verifiedbootstate" -> if (rule.hideRootAndXposed) "green" else null
                                "ro.boot.flash.locked" -> if (rule.hideRootAndXposed) "1" else null
                                "persist.sys.timezone" -> when {
                                    rule.vpnProxyEnabled && rule.matchTimezoneWithIp && rule.proxyTimezoneId.isNotBlank() -> rule.proxyTimezoneId
                                    rule.isIdentitySpoofed && rule.timezoneId.isNotBlank() -> rule.timezoneId
                                    rule.autoSyncTimezone && rule.spoofLocationEnabled -> resolveTimezoneForCoordinates(rule.spoofLatitude, rule.spoofLongitude)
                                    else -> null
                                }
                                "persist.sys.locale", "ro.product.locale" -> if (isIdentityOn && rule.localeTag.isNotBlank()) rule.localeTag else null
                                else -> null
                            }
                            if (spoofed != null) {
                                param.result = spoofed
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {}

        // 2. Hook Settings.Secure / Settings.Global / Settings.System
        val settingsStringHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(1) as? String ?: return
                val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                when (name) {
                    Settings.Secure.ANDROID_ID, "android_id" -> {
                        if (rule.isIdentitySpoofed && rule.androidId.isNotBlank()) param.result = rule.androidId
                    }
                    "advertising_id", "aaid" -> {
                        if (rule.isIdentitySpoofed && rule.advertisingId.isNotBlank()) param.result = rule.advertisingId
                    }
                    "limit_ad_tracking" -> if (rule.isIdentitySpoofed) param.result = "1"
                    "bluetooth_name", "device_name" -> {
                        if (rule.isIdentitySpoofed && rule.model.isNotBlank()) param.result = rule.model
                    }
                    Settings.Global.ADB_ENABLED,
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    "adb_wifi_enabled" -> {
                        if (rule.hideAdbAndDevOptions) param.result = "0"
                    }
                    "mock_location" -> {
                        if (rule.hideMockLocation || rule.hideAdbAndDevOptions) param.result = "0"
                    }
                }
            }
        }

        val settingsIntHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(1) as? String ?: return
                val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                if (rule.hideAdbAndDevOptions) {
                    when (name) {
                        Settings.Global.ADB_ENABLED,
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                        "adb_wifi_enabled" -> param.result = 0
                    }
                }
                if (rule.hideMockLocation || rule.hideAdbAndDevOptions) {
                    if (name == "mock_location") param.result = 0
                }
            }
        }

        for (cls in listOf(Settings.Secure::class.java, Settings.Global::class.java, Settings.System::class.java)) {
            for (m in listOf("getString", "getStringForUser")) {
                try {
                    XposedBridge.hookAllMethods(cls, m, settingsStringHook)
                } catch (_: Throwable) {}
            }
            for (m in listOf("getInt", "getIntForUser")) {
                try {
                    XposedBridge.hookAllMethods(cls, m, settingsIntHook)
                } catch (_: Throwable) {}
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun attachTelephonyAndHardwareHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. Build.getSerial()
        try {
            XposedBridge.hookAllMethods(
                Build::class.java,
                "getSerial",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.isIdentitySpoofed && rule.serial.isNotBlank()) param.result = rule.serial
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. TelephonyManager hooks
        val tmCls = TelephonyManager::class.java
        val imeiHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                if (rule.isIdentitySpoofed && rule.imei.isNotBlank()) param.result = rule.imei
            }
        }
        for (m in listOf("getDeviceId", "getImei", "getMeid")) {
            try {
                XposedBridge.hookAllMethods(tmCls, m, imeiHook)
            } catch (_: Throwable) {}
        }

        // IMSI (getSubscriberId)
        try {
            XposedBridge.hookAllMethods(
                tmCls,
                "getSubscriberId",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.isIdentitySpoofed) return
                        val prefix = rule.simOperator.ifBlank { "310260" }.take(6)
                        val suffix = rule.imei.takeLast(15 - prefix.length).padEnd(15 - prefix.length, '4')
                        param.result = prefix + suffix
                    }
                }
            )
        } catch (_: Throwable) {}

        // SIM ICCID (getSimSerialNumber)
        try {
            XposedBridge.hookAllMethods(
                tmCls,
                "getSimSerialNumber",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.isIdentitySpoofed) return
                        val op = rule.simOperator.ifBlank { "310260" }.take(6)
                        val tail = rule.imei.takeLast(11).padEnd(11, '7')
                        param.result = "89$op$tail"
                    }
                }
            )
        } catch (_: Throwable) {}

        // Phone number protection (getLine1Number / getMsisdn) -> empty string so apps can't harvest phone number
        for (m in listOf("getLine1Number", "getMsisdn")) {
            try {
                XposedBridge.hookAllMethods(
                    tmCls,
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.isIdentitySpoofed) {
                                param.result = ""
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // Carrier Name & SIM Operator
        for (m in listOf("getNetworkOperatorName", "getSimOperatorName")) {
            try {
                XposedBridge.hookAllMethods(
                    tmCls,
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.isIdentitySpoofed && rule.carrierName.isNotBlank()) param.result = rule.carrierName
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
        for (m in listOf("getNetworkOperator", "getSimOperator")) {
            try {
                XposedBridge.hookAllMethods(
                    tmCls,
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.isIdentitySpoofed && rule.simOperator.isNotBlank()) param.result = rule.simOperator
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun attachNetworkAndMacHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. WifiInfo.getMacAddress / getSSID / getBSSID
        try {
            XposedBridge.hookAllMethods(
                WifiInfo::class.java,
                "getMacAddress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.isIdentitySpoofed && rule.macAddress.isNotBlank()) param.result = rule.macAddress
                    }
                }
            )
            XposedBridge.hookAllMethods(
                WifiInfo::class.java,
                "getSSID",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.isIdentitySpoofed) {
                            param.result = "\"${rule.brand.replaceFirstChar { it.uppercase() }}-5G\""
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                WifiInfo::class.java,
                "getBSSID",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.isIdentitySpoofed && rule.macAddress.isNotBlank()) {
                            param.result = rule.macAddress.dropLast(2) + "01"
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. BluetoothAdapter.getAddress
        try {
            XposedBridge.hookAllMethods(
                BluetoothAdapter::class.java,
                "getAddress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.isIdentitySpoofed && rule.macAddress.isNotBlank()) param.result = rule.macAddress
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. NetworkInterface.getHardwareAddress
        try {
            XposedBridge.hookAllMethods(
                NetworkInterface::class.java,
                "getHardwareAddress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.isIdentitySpoofed && rule.macAddress.isNotBlank()) {
                            val parts = rule.macAddress.split(":")
                            if (parts.size == 6) {
                                param.result = ByteArray(6) { idx ->
                                    parts[idx].toIntOrNull(16)?.toByte() ?: 0x02.toByte()
                                }
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun attachDrmGsfAndAdIdHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. Widevine MediaDrm.getPropertyByteArray("deviceUniqueId")
        try {
            XposedBridge.hookAllMethods(
                MediaDrm::class.java,
                "getPropertyByteArray",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val prop = param.args.firstOrNull() as? String ?: return
                        if (prop == MediaDrm.PROPERTY_DEVICE_UNIQUE_ID || prop == "deviceUniqueId") {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (!rule.isIdentitySpoofed && !rule.spoofDrmWidevine) return
                            val seed = "${rule.androidId}:${rule.imei}:${rule.serial}"
                            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
                            param.result = digest
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. Google Services Framework ID (GSF ID) query on content://com.google.android.gsf.gservices
        try {
            XposedBridge.hookAllMethods(
                ContentResolver::class.java,
                "query",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uri = param.args.firstOrNull() as? Uri ?: return
                        if (uri.authority == "com.google.android.gsf.gservices") {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (!rule.isIdentitySpoofed) return
                            val fakeDecId = (rule.androidId.take(15).toLongOrNull(16) ?: 3891204918204192L).toString()
                            val cursor = MatrixCursor(arrayOf("key", "value"), 1)
                            cursor.addRow(arrayOf<Any>("android_id", fakeDecId))
                            param.result = cursor
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. Google Play Services AdvertisingIdClient$Info
        try {
            val adInfoCls = XposedHelpers.findClassIfExists(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info",
                lpparam.classLoader
            )
            if (adInfoCls != null) {
                XposedBridge.hookAllMethods(
                    adInfoCls,
                    "getId",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.isIdentitySpoofed && rule.advertisingId.isNotBlank()) param.result = rule.advertisingId
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    adInfoCls,
                    "isLimitAdTrackingEnabled",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.isIdentitySpoofed) {
                                param.result = true
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {}
    }

    private fun attachPrivacyShieldHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. VPN & Proxy Hiding (NetworkInterface.getNetworkInterfaces & NetworkCapabilities.hasTransport)
        try {
            XposedBridge.hookAllMethods(
                NetworkInterface::class.java,
                "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.hideVpnAndProxy) return
                        val origEnum = param.result as? Enumeration<*> ?: return
                        val filtered = ArrayList<NetworkInterface>()
                        while (origEnum.hasMoreElements()) {
                            val ni = origEnum.nextElement() as? NetworkInterface ?: continue
                            val name = ni.name?.lowercase().orEmpty()
                            if (VPN_IFACE_PREFIXES.none { name.startsWith(it) }) {
                                filtered.add(ni)
                            }
                        }
                        param.result = Collections.enumeration(filtered)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                NetworkCapabilities::class.java,
                "hasTransport",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val transport = param.args.firstOrNull() as? Int ?: return
                        if (transport == NetworkCapabilities.TRANSPORT_VPN) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.hideVpnAndProxy) {
                                param.result = false
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. Hide Root, Magisk, KernelSU & LSPosed packages and stacktraces
        val apmClass = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager",
            lpparam.classLoader
        )
        if (apmClass != null) {
            val hideRootPkgHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                    if (!rule.hideRootAndXposed) return
                    val queriedPkg = param.args.firstOrNull { it is String } as? String ?: return
                    if (queriedPkg in ROOT_AND_XPOSED_PACKAGES) {
                        param.throwable = PackageManager.NameNotFoundException(queriedPkg)
                    }
                }
            }
            for (m in listOf("getPackageInfo", "getApplicationInfo", "getPackageUid")) {
                try {
                    XposedBridge.hookAllMethods(apmClass, m, hideRootPkgHook)
                } catch (_: Throwable) {}
            }
        }

        // Scrub Xposed/LSPosed stack frames when hideRootAndXposed is enabled
        try {
            XposedBridge.hookAllMethods(
                Throwable::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.hideRootAndXposed) return
                        val original = param.result as? Array<*> ?: return
                        val filtered = original.filterIsInstance<StackTraceElement>().filter { el ->
                            val cls = el.className
                            !cls.startsWith("de.robv.android.xposed") &&
                                    !cls.contains("LSPHooker") &&
                                    !cls.startsWith("org.lsposed") &&
                                    !cls.startsWith("com.hollow.app.isolation.hook")
                        }
                        if (filtered.size != original.size) {
                            param.result = filtered.toTypedArray()
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. Clipboard Privacy Shield
        try {
            XposedBridge.hookAllMethods(
                ClipboardManager::class.java,
                "getPrimaryClip",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.blockClipboardRead) {
                            param.result = null
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                ClipboardManager::class.java,
                "hasPrimaryClip",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.blockClipboardRead) {
                            param.result = false
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    @Suppress("UNUSED_PARAMETER")
    private fun attachLocationMockHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. LocationManager.getLastKnownLocation & getCurrentLocation
        try {
            XposedBridge.hookAllMethods(
                LocationManager::class.java,
                "getLastKnownLocation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.spoofLocationEnabled) return
                        val provider = param.args.firstOrNull() as? String ?: "gps"
                        val loc = Location(provider).apply {
                            latitude = rule.spoofLatitude
                            longitude = rule.spoofLongitude
                            altitude = 25.0
                            accuracy = 3.5f
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            if (rule.hideMockLocation) {
                                try {
                                    XposedHelpers.callMethod(this, "setIsFromMockProvider", false)
                                } catch (_: Throwable) {}
                            }
                        }
                        param.result = loc
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. Location getters (getLatitude, getLongitude, isFromMockProvider, isMock)
        try {
            XposedBridge.hookAllMethods(
                Location::class.java,
                "getLatitude",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.spoofLocationEnabled) {
                            param.result = rule.spoofLatitude
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Location::class.java,
                "getLongitude",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.spoofLocationEnabled) {
                            param.result = rule.spoofLongitude
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Location::class.java,
                "isFromMockProvider",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.hideMockLocation) {
                            param.result = false
                        }
                    }
                }
            )
            val isMockMethod = XposedHelpers.findMethodExactIfExists(Location::class.java, "isMock")
            if (isMockMethod != null) {
                XposedBridge.hookMethod(
                    isMockMethod,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.hideMockLocation) {
                                param.result = false
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {}
    }

    @Suppress("UNUSED_PARAMETER")
    private fun attachAppClonerPrivacyHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. Screenshot Prevention (FLAG_SECURE)
        try {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.preventScreenshots) {
                            val act = param.thisObject as? Activity ?: return
                            try {
                                act.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.preventScreenshots) {
                            val act = param.thisObject as? Activity ?: return
                            try {
                                act.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. Package Visibility Guard (Hide Other Installed Apps)
        try {
            val appPmCls = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            )
            if (appPmCls != null) {
                XposedBridge.hookAllMethods(
                    appPmCls,
                    "getInstalledPackages",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (!rule.hideOtherApps) return
                            val origList = param.result as? List<*> ?: return
                            val filtered = origList.filterIsInstance<PackageInfo>().filter { pi ->
                                val pn = pi.packageName
                                pn == currentPkg ||
                                    pn.startsWith("android") ||
                                    pn.startsWith("com.android.") ||
                                    pn.startsWith("com.google.android.gms") ||
                                    pn.startsWith("com.google.android.gsf")
                            }
                            param.result = filtered
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    appPmCls,
                    "getInstalledApplications",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (!rule.hideOtherApps) return
                            val origList = param.result as? List<*> ?: return
                            val filtered = origList.filterIsInstance<ApplicationInfo>().filter { ai ->
                                val pn = ai.packageName
                                pn == currentPkg ||
                                    pn.startsWith("android") ||
                                    pn.startsWith("com.android.") ||
                                    pn.startsWith("com.google.android.gms") ||
                                    pn.startsWith("com.google.android.gsf")
                            }
                            param.result = filtered
                        }
                    }
                )
            }
        } catch (_: Throwable) {}

        // 3. Disable Logcat Logging (Silence android.util.Log)
        try {
            val logHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                    if (rule.disableLogcatLogging) {
                        param.result = 0
                    }
                }
            }
            for (m in listOf("v", "d", "i", "w", "e", "println")) {
                try {
                    XposedBridge.hookAllMethods(Log::class.java, m, logHook)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 4. Fake Battery Level & Charging State
        try {
            XposedBridge.hookAllMethods(
                BatteryManager::class.java,
                "getIntProperty",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.fakeBatteryEnabled) return
                        val id = param.args.firstOrNull() as? Int ?: return
                        when (id) {
                            BatteryManager.BATTERY_PROPERTY_CAPACITY -> param.result = rule.fakeBatteryLevel
                            BatteryManager.BATTERY_PROPERTY_STATUS -> param.result = BatteryManager.BATTERY_STATUS_CHARGING
                            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER -> param.result = 3500000
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 5. Network Kill Switch & Per-App VPN/Proxy Gate (V2Ray / SOCKS5 / HTTP)
        try {
            XposedBridge.hookAllMethods(
                Socket::class.java,
                "connect",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.disableAllNetworking) {
                            throw ConnectException("Hollow Privacy Shield: All networking blocked for $currentPkg")
                        }
                        if (rule.requireVpnForNetwork && !rule.vpnProxyEnabled) {
                            throw ConnectException("Hollow VPN Kill-Switch: VPN/Proxy is required for $currentPkg")
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                java.net.ProxySelector::class.java,
                "select",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        // VLESS/Reality is not a SOCKS or HTTP listener. The native Android TUN
                        // routes selected apps at the IP layer; leave Java proxy selection untouched.
                        if (rule.vpnProxyEnabled) return
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    @Suppress("UNUSED_PARAMETER", "DEPRECATION")
    private fun attachDigitalFootprintHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        // 1. OpenGL ES & WebGL Canvas Fingerprint Cloaking (glGetString)
        try {
            val glHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                    if (!rule.spoofGpuFingerprint) return
                    val name = param.args.firstOrNull() as? Int ?: return
                    when (name) {
                        GLES20.GL_RENDERER -> { // 0x1F01
                            param.result = rule.gpuRenderer.ifBlank { rule.preset.glRenderer }
                        }
                        GLES20.GL_VENDOR -> { // 0x1F00
                            param.result = rule.gpuVendor.ifBlank { rule.preset.glVendor }
                        }
                    }
                }
            }

            for (cls in listOf(GLES20::class.java, GLES30::class.java)) {
                try {
                    XposedBridge.hookAllMethods(cls, "glGetString", glHook)
                } catch (_: Throwable) {}
            }
            try {
                XposedBridge.hookAllMethods(GL10::class.java, "glGetString", glHook)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}

        // 2. Chrome, Browser & In-App WebView User-Agent + Deep JS Fingerprint Cloaking
        try {
            val uaHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                    if (!rule.spoofBrowserUserAgent) return
                    param.result = buildEffectiveUserAgent(rule)
                }
            }

            try {
                XposedBridge.hookAllMethods(WebSettings::class.java, "getDefaultUserAgent", uaHook)
            } catch (_: Throwable) {}
            try {
                XposedBridge.hookAllMethods(WebSettings::class.java, "getUserAgentString", uaHook)
            } catch (_: Throwable) {}

            // Inject Chrome/WebView JS Fingerprint Shield into WebView loads
            val webViewCls = android.webkit.WebView::class.java
            val webViewInjectHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                    if (!rule.isIdentitySpoofed && !rule.spoofBrowserUserAgent && !rule.spoofWebRtc && !rule.spoofCanvasNoise) return
                    val wv = param.thisObject as? android.webkit.WebView ?: return
                    try {
                        if (rule.spoofBrowserUserAgent) {
                            wv.settings.userAgentString = buildEffectiveUserAgent(rule)
                        }
                        val js = buildBrowserFingerprintCloakJs(rule)
                        wv.evaluateJavascript(js, null)
                    } catch (_: Throwable) {}
                }
            }
            for (m in listOf("loadUrl", "loadData", "loadDataWithBaseURL", "onAttachedToWindow")) {
                try {
                    XposedBridge.hookAllMethods(webViewCls, m, webViewInjectHook)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 3. CPU Cores (Runtime.availableProcessors), RAM (ActivityManager.getMemoryInfo), & Screen DisplayMetrics
        try {
            XposedBridge.hookAllMethods(
                Runtime::class.java,
                "availableProcessors",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (rule.isIdentitySpoofed && rule.cpuCores > 0) {
                            param.result = rule.cpuCores
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                android.app.ActivityManager::class.java,
                "getMemoryInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.isIdentitySpoofed || rule.ramGb <= 0) return
                        val memInfo = param.args.firstOrNull() as? android.app.ActivityManager.MemoryInfo ?: return
                        val totalBytes = rule.ramGb.toLong() * 1024L * 1024L * 1024L
                        memInfo.totalMem = totalBytes
                        memInfo.availMem = (totalBytes * 58L) / 100L
                    }
                }
            )
            XposedBridge.hookAllMethods(
                android.content.res.Resources::class.java,
                "getDisplayMetrics",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        if (!rule.isIdentitySpoofed) return
                        val dm = param.result as? android.util.DisplayMetrics ?: return
                        if (rule.screenResolution.contains("x")) {
                            val w = rule.screenResolution.substringBefore("x").toIntOrNull()
                            val h = rule.screenResolution.substringAfter("x").toIntOrNull()
                            if (w != null && h != null && w > 0 && h > 0) {
                                dm.widthPixels = w
                                dm.heightPixels = h
                            }
                        }
                        if (rule.screenDpi > 0) {
                            dm.densityDpi = rule.screenDpi
                            dm.density = rule.screenDpi / 160f
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 4. Hardware Sensor Footprint Anonymizer (Block acoustic & sensor bias hardware fingerprinting)
        try {
            try {
                XposedBridge.hookAllMethods(
                    Sensor::class.java,
                    "getVendor",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.anonymizeSensors) {
                                param.result = rule.manufacturer.ifBlank { "Android Open Source Project" }
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(
                    Sensor::class.java,
                    "getName",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (!rule.anonymizeSensors) return
                            val orig = param.result as? String ?: return
                            val scrubbed = orig
                                .replace(Regex("(?i)bosch|invensense|stmicroelectronics|sensirion|murata|tdk|nxp|rohm"), "Standard")
                            param.result = scrubbed
                        }
                    }
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}

        // 5. Cell Tower & Radio Triangulation Shield (Blocks cellular positioning bypass)
        try {
            val tmCls = TelephonyManager::class.java
            val emptyCellInfoHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                    if (rule.blockCellTowerTracking) {
                        param.result = emptyList<Any>()
                    }
                }
            }
            try {
                XposedBridge.hookAllMethods(tmCls, "getAllCellInfo", emptyCellInfoHook)
            } catch (_: Throwable) {}
            try {
                XposedBridge.hookAllMethods(tmCls, "getNeighboringCellInfo", emptyCellInfoHook)
            } catch (_: Throwable) {}
            try {
                XposedBridge.hookAllMethods(
                    tmCls,
                    "getCellLocation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.blockCellTowerTracking) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
            try {
                XposedBridge.hookAllMethods(
                    tmCls,
                    "requestCellInfoUpdate",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.blockCellTowerTracking) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}

        // 6. TimeZone Synchronization with Exit IP (VPN/Proxy) or Spoofed Timezone / Coordinates
        try {
            XposedBridge.hookAllMethods(
                TimeZone::class.java,
                "getDefault",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                        val tzId = when {
                            rule.vpnProxyEnabled && rule.matchTimezoneWithIp && rule.proxyTimezoneId.isNotBlank() ->
                                rule.proxyTimezoneId
                            rule.isIdentitySpoofed && rule.timezoneId.isNotBlank() ->
                                rule.timezoneId
                            rule.autoSyncTimezone && rule.spoofLocationEnabled ->
                                resolveTimezoneForCoordinates(rule.spoofLatitude, rule.spoofLongitude)
                            else -> null
                        }
                        if (tzId != null) {
                            param.result = TimeZone.getTimeZone(tzId)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun buildEffectiveUserAgent(rule: IsolationRuleManager.IdentitySpoofRule): String {
        if (rule.customUserAgent.isNotBlank()) return rule.customUserAgent
        val chromeVersion = "131.0.6778.200"
        val releaseVer = rule.fingerprint.split(":").getOrNull(1)?.split("/")?.firstOrNull() ?: "15"
        val modelStr = rule.model.ifBlank { rule.preset.model }
        val buildIdStr = rule.fingerprint.split("/").getOrNull(3) ?: "AP3A.241005.015"
        return "Mozilla/5.0 (Linux; Android $releaseVer; $modelStr Build/$buildIdStr) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
    }

    /**
     * Generates a comprehensive JavaScript stealth payload injected into Chrome & WebViews
     * so browser fingerprinting scripts (FingerprintJS, Cloudflare, CreepJS, Meta/Snap WebViews)
     * see the spoofed hardware, WebGL, Canvas, AudioContext, WebRTC, and Exit-IP Timezone.
     */
    private fun buildBrowserFingerprintCloakJs(rule: IsolationRuleManager.IdentitySpoofRule): String {
        val ua = buildEffectiveUserAgent(rule).replace("'", "\\'")
        val model = rule.model.ifBlank { "Pixel 9 Pro" }.replace("'", "\\'")
        val glVendor = rule.gpuVendor.ifBlank { "Qualcomm" }.replace("'", "\\'")
        val glRenderer = rule.gpuRenderer.ifBlank { "Adreno (TM) 750" }.replace("'", "\\'")
        val cores = rule.cpuCores.coerceIn(2, 16)
        val ram = rule.ramGb.coerceIn(2, 24)
        val tz = (if (rule.vpnProxyEnabled && rule.matchTimezoneWithIp) rule.proxyTimezoneId else rule.timezoneId)
            .ifBlank { "America/New_York" }.replace("'", "\\'")
        val lang = rule.localeTag.ifBlank { "en-US" }.replace("'", "\\'")
        val screenW = rule.screenResolution.substringBefore("x").toIntOrNull() ?: 1080
        val screenH = rule.screenResolution.substringAfter("x").toIntOrNull() ?: 2400
        val noiseSeed = (rule.androidId.take(6).toIntOrNull(16) ?: 42) % 7 + 1

        return """
            (function() {
                if (window.__hollowFpCloaked) return;
                window.__hollowFpCloaked = true;
                try {
                    Object.defineProperty(navigator, 'userAgent', { get: () => '$ua' });
                    Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => $cores });
                    Object.defineProperty(navigator, 'deviceMemory', { get: () => $ram });
                    Object.defineProperty(navigator, 'language', { get: () => '$lang' });
                    Object.defineProperty(navigator, 'languages', { get: () => ['$lang', 'en'] });
                    Object.defineProperty(navigator, 'doNotTrack', { get: () => '1' });
                    Object.defineProperty(navigator, 'globalPrivacyControl', { get: () => true });
                    Object.defineProperty(screen, 'width', { get: () => $screenW });
                    Object.defineProperty(screen, 'height', { get: () => $screenH });
                    if (${rule.spoofClientHints} && navigator.userAgentData) {
                        Object.defineProperty(navigator, 'userAgentData', {
                            get: () => ({
                                brands: [{brand: 'Google Chrome', version: '131'}, {brand: 'Chromium', version: '131'}],
                                mobile: true,
                                platform: 'Android',
                                getHighEntropyValues: async () => ({
                                    architecture: 'arm',
                                    bitness: '64',
                                    model: '$model',
                                    platform: 'Android',
                                    platformVersion: '15.0.0',
                                    uaFullVersion: '131.0.6778.200'
                                })
                            })
                        });
                    }
                    if (${rule.spoofGpuFingerprint} && window.WebGLRenderingContext) {
                        const origGetParam = WebGLRenderingContext.prototype.getParameter;
                        WebGLRenderingContext.prototype.getParameter = function(p) {
                            if (p === 37445) return '$glVendor';
                            if (p === 37446) return '$glRenderer';
                            return origGetParam.apply(this, arguments);
                        };
                    }
                    if (${rule.spoofCanvasNoise} && window.HTMLCanvasElement) {
                        const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                        HTMLCanvasElement.prototype.toDataURL = function() {
                            const ctx = this.getContext && this.getContext('2d');
                            if (ctx) {
                                ctx.fillStyle = 'rgba($noiseSeed, $noiseSeed, $noiseSeed, 0.01)';
                                ctx.fillRect(0, 0, 1, 1);
                            }
                            return origToDataURL.apply(this, arguments);
                        };
                    }
                    if (${rule.spoofWebRtc}) {
                        window.RTCPeerConnection = undefined;
                        window.webkitRTCPeerConnection = undefined;
                    }
                    const origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
                    Intl.DateTimeFormat.prototype.resolvedOptions = function() {
                        const res = origResolved.apply(this, arguments);
                        res.timeZone = '$tz';
                        return res;
                    };
                } catch (e) {}
            })();
        """.trimIndent()
    }

    private fun resolveTimezoneForCoordinates(lat: Double, lng: Double): String {
        return when {
            lat in 40.0..41.5 && lng in -74.5..-73.5 -> "America/New_York"
            lat in 37.0..38.0 && lng in -123.0..-121.5 -> "America/Los_Angeles"
            lat in 51.0..52.0 && lng in -0.5..0.5 -> "Europe/London"
            lat in 48.0..49.5 && lng in 2.0..3.0 -> "Europe/Paris"
            lat in 35.0..36.0 && lng in 139.0..140.0 -> "Asia/Tokyo"
            lat in 52.0..53.0 && lng in 13.0..14.0 -> "Europe/Berlin"
            lat in 22.0..23.0 && lng in 113.5..115.0 -> "Asia/Hong_Kong"
            lat in 1.0..2.0 && lng in 103.5..104.5 -> "Asia/Singapore"
            lat in -34.5..-33.5 && lng in 150.5..152.0 -> "Australia/Sydney"
            else -> {
                val offsetHours = Math.round(lng / 15.0).toInt().coerceIn(-12, 14)
                if (offsetHours == 0) "UTC"
                else if (offsetHours > 0) "GMT+$offsetHours"
                else "GMT$offsetHours"
            }
        }
    }

    private fun attachDeadmanSwitchHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        currentPkg: String,
        xSharedPrefs: XSharedPreferences?
    ) {
        val sensitivePaths = listOf(
            "/proc/cpuinfo",
            "/sys/class/net/wlan0/address",
            "/sys/devices/soc0/serial_number",
            "/sys/class/net/eth0/address",
            "/proc/net/arp"
        )
        try {
            XposedHelpers.findAndHookConstructor(
                File::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = param.args.firstOrNull() as? String ?: return
                        val norm = path.replace("\\", "/").trim()
                        if (sensitivePaths.any { norm.startsWith(it) }) {
                            val rule = getRuleForPackage(currentPkg, xSharedPrefs) ?: return
                            if (rule.deadmanSwitchEnabled) {
                                XposedBridge.log("[IsoVault DEADMAN] Intercepted identity leak attempt in $currentPkg: $norm")
                                try {
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }
}
