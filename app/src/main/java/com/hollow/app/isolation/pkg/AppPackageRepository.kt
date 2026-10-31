package com.hollow.app.isolation.pkg

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.model.DeviceProfilePreset
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.model.LauncherVisibilityMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val targetSdkVersion: Int,
    val isIsolated: Boolean = false,
    val isolationMode: IsolationMode = IsolationMode.SHARED_WORKSPACE,
    val hasStoragePermission: Boolean = true,
    val icon: Drawable? = null,
    // Independent App Hider & Disguise fields (separate from Storage Protection)
    val isHiddenOrDisguised: Boolean = false,
    val launcherVisibilityMode: LauncherVisibilityMode = LauncherVisibilityMode.VISIBLE,
    val customLauncherLabel: String = "",
    val disguiseIconPreset: DisguiseIconPreset = DisguiseIconPreset.CALCULATOR,
    val hideInSystemUiRecents: Boolean = true,
    val hideFromPlayStore: Boolean = false,
    val spoofInSettings: Boolean = true,
    val customIconBase64: String = "",
    val customIconDonorPkg: String = "",
    // Independent Per-App Device Identity & Privacy Spoofing fields
    val isIdentitySpoofed: Boolean = false,
    val deviceProfilePreset: DeviceProfilePreset = DeviceProfilePreset.REAL_HARDWARE,
    val spoofBrand: String = "",
    val spoofModel: String = "",
    val spoofDevice: String = "",
    val spoofAndroidId: String = "",
    val spoofImei: String = "",
    val spoofAdvertisingId: String = "",
    val spoofMacAddress: String = "",
    val spoofCarrierName: String = "",
    val spoofSimOperator: String = "",
    val spoofSerial: String = "",
    // Location / GPS Mocking
    val spoofLocationEnabled: Boolean = false,
    val spoofLatitude: Double = 37.7749,
    val spoofLongitude: Double = -122.4194,
    val hideMockLocation: Boolean = true,
    // Privacy & System Guards
    val preventScreenshots: Boolean = false,
    val hideOtherApps: Boolean = false,
    val disableLogcatLogging: Boolean = false,
    val fakeBatteryEnabled: Boolean = false,
    val fakeBatteryLevel: Int = 100,
    val knoxWarrantyBitSpoof: Boolean = false,
    // Environment & Security Shields
    val hideRootAndXposed: Boolean = true,
    val hideAdbAndDevOptions: Boolean = true,
    val hideVpnAndProxy: Boolean = true,
    val blockClipboardRead: Boolean = false,
    // Digital Footprint & Anti-Tracking Shield (Snap, Meta, Chrome & Browsers)
    val spoofGpuFingerprint: Boolean = false,
    val spoofBrowserUserAgent: Boolean = false,
    val anonymizeSensors: Boolean = false,
    val blockCellTowerTracking: Boolean = false,
    val autoSyncTimezone: Boolean = false,
    // Networking
    val disableAllNetworking: Boolean = false,
    // Extended Real-First Editable Hardware & Build Fields
    val spoofManufacturer: String = "",
    val spoofProduct: String = "",
    val spoofBoard: String = "",
    val spoofHardware: String = "",
    val spoofBootloader: String = "",
    val spoofFingerprint: String = "",
    val spoofGpuVendor: String = "",
    val spoofGpuRenderer: String = "",
    val spoofCpuCores: Int = 0,
    val spoofRamGb: Int = 0,
    val spoofScreenResolution: String = "",
    val spoofScreenDpi: Int = 0,
    val spoofTimezoneId: String = "",
    val spoofLocaleTag: String = "",
    val customUserAgent: String = "",
    // Chrome / Browser & WebView Deep Fingerprint Spoofing
    val spoofWebRtc: Boolean = false,
    val spoofCanvasNoise: Boolean = false,
    val spoofAudioContext: Boolean = false,
    val spoofClientHints: Boolean = false,
    val spoofFontEnumeration: Boolean = false,
    // Per-App VPN / Proxy (V2Ray / SOCKS5 / HTTP) & VPN-Gated Controls
    val vpnProxyEnabled: Boolean = false,
    val proxyServerId: String = "us_ny_vless",
    val requireVpnForNetwork: Boolean = false,
    val matchTimezoneWithIp: Boolean = true,
    val webViewProxyEnabled: Boolean = true,
    val trustAllCerts: Boolean = false,
    // Reusable Privacy Profiles & Anonymity Suite
    val privacyProfileId: String = "",
    val deadmanSwitchEnabled: Boolean = true,
    val spoofDrmWidevine: Boolean = true,
    val sensorBiasNeutralizer: Boolean = true,
    val spoofWifiBssid: Boolean = true
)

class AppPackageRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    private fun buildInstalledAppInfo(
        pkg: String,
        appName: String,
        isSystem: Boolean,
        targetSdk: Int,
        icon: Drawable?,
        config: AppIsolationConfig?
    ): InstalledAppInfo {
        return InstalledAppInfo(
            packageName = pkg,
            appName = appName,
            isSystemApp = isSystem,
            targetSdkVersion = targetSdk,
            isIsolated = config?.isIsolated ?: false,
            isolationMode = config?.isolationMode ?: IsolationMode.SHARED_WORKSPACE,
            hasStoragePermission = true,
            icon = icon,
            isHiddenOrDisguised = config?.isHiddenOrDisguised ?: false,
            launcherVisibilityMode = config?.launcherVisibilityMode ?: LauncherVisibilityMode.VISIBLE,
            customLauncherLabel = config?.customLauncherLabel ?: "",
            disguiseIconPreset = config?.disguiseIconPreset ?: DisguiseIconPreset.CALCULATOR,
            hideInSystemUiRecents = config?.hideInSystemUiRecents ?: true,
            hideFromPlayStore = config?.hideFromPlayStore ?: false,
            spoofInSettings = config?.spoofInSettings ?: true,
            customIconBase64 = config?.customIconBase64 ?: "",
            customIconDonorPkg = config?.customIconDonorPkg ?: "",
            isIdentitySpoofed = config?.isIdentitySpoofed ?: false,
            deviceProfilePreset = config?.deviceProfilePreset ?: DeviceProfilePreset.REAL_HARDWARE,
            spoofBrand = config?.spoofBrand ?: "",
            spoofModel = config?.spoofModel ?: "",
            spoofDevice = config?.spoofDevice ?: "",
            spoofAndroidId = config?.spoofAndroidId ?: "",
            spoofImei = config?.spoofImei ?: "",
            spoofAdvertisingId = config?.spoofAdvertisingId ?: "",
            spoofMacAddress = config?.spoofMacAddress ?: "",
            spoofCarrierName = config?.spoofCarrierName ?: "",
            spoofSimOperator = config?.spoofSimOperator ?: "",
            spoofSerial = config?.spoofSerial ?: "",
            spoofLocationEnabled = config?.spoofLocationEnabled ?: false,
            spoofLatitude = config?.spoofLatitude ?: 37.7749,
            spoofLongitude = config?.spoofLongitude ?: -122.4194,
            hideMockLocation = config?.hideMockLocation ?: true,
            preventScreenshots = config?.preventScreenshots ?: false,
            hideOtherApps = config?.hideOtherApps ?: false,
            disableLogcatLogging = config?.disableLogcatLogging ?: false,
            fakeBatteryEnabled = config?.fakeBatteryEnabled ?: false,
            fakeBatteryLevel = config?.fakeBatteryLevel ?: 100,
            knoxWarrantyBitSpoof = config?.knoxWarrantyBitSpoof ?: false,
            hideRootAndXposed = config?.hideRootAndXposed ?: true,
            hideAdbAndDevOptions = config?.hideAdbAndDevOptions ?: true,
            hideVpnAndProxy = config?.hideVpnAndProxy ?: true,
            blockClipboardRead = config?.blockClipboardRead ?: false,
            spoofGpuFingerprint = config?.spoofGpuFingerprint ?: false,
            spoofBrowserUserAgent = config?.spoofBrowserUserAgent ?: false,
            anonymizeSensors = config?.anonymizeSensors ?: false,
            blockCellTowerTracking = config?.blockCellTowerTracking ?: false,
            autoSyncTimezone = config?.autoSyncTimezone ?: false,
            disableAllNetworking = config?.disableAllNetworking ?: false,
            spoofManufacturer = config?.spoofManufacturer ?: "",
            spoofProduct = config?.spoofProduct ?: "",
            spoofBoard = config?.spoofBoard ?: "",
            spoofHardware = config?.spoofHardware ?: "",
            spoofBootloader = config?.spoofBootloader ?: "",
            spoofFingerprint = config?.spoofFingerprint ?: "",
            spoofGpuVendor = config?.spoofGpuVendor ?: "",
            spoofGpuRenderer = config?.spoofGpuRenderer ?: "",
            spoofCpuCores = config?.spoofCpuCores ?: 0,
            spoofRamGb = config?.spoofRamGb ?: 0,
            spoofScreenResolution = config?.spoofScreenResolution ?: "",
            spoofScreenDpi = config?.spoofScreenDpi ?: 0,
            spoofTimezoneId = config?.spoofTimezoneId ?: "",
            spoofLocaleTag = config?.spoofLocaleTag ?: "",
            customUserAgent = config?.customUserAgent ?: "",
            spoofWebRtc = config?.spoofWebRtc ?: false,
            spoofCanvasNoise = config?.spoofCanvasNoise ?: false,
            spoofAudioContext = config?.spoofAudioContext ?: false,
            spoofClientHints = config?.spoofClientHints ?: false,
            spoofFontEnumeration = config?.spoofFontEnumeration ?: false,
            vpnProxyEnabled = config?.vpnProxyEnabled ?: false,
            proxyServerId = config?.proxyServerId ?: "us_ny_vless",
            requireVpnForNetwork = config?.requireVpnForNetwork ?: false,
            matchTimezoneWithIp = config?.matchTimezoneWithIp ?: true,
            webViewProxyEnabled = config?.webViewProxyEnabled ?: true,
            trustAllCerts = config?.trustAllCerts ?: false,
            privacyProfileId = config?.privacyProfileId ?: "",
            deadmanSwitchEnabled = config?.deadmanSwitchEnabled ?: true,
            spoofDrmWidevine = config?.spoofDrmWidevine ?: true,
            sensorBiasNeutralizer = config?.sensorBiasNeutralizer ?: true,
            spoofWifiBssid = config?.spoofWifiBssid ?: true
        )
    }

    suspend fun getInstalledApps(
        configs: List<AppIsolationConfig>,
        includeSystemApps: Boolean = false
    ): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val configMap = configs.associateBy { it.packageName }
        val resultMap = mutableMapOf<String, InstalledAppInfo>()

        // 1. Query launcher apps (user-visible installed apps like Snapchat, Chrome, WhatsApp, etc.)
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            for (resolveInfo in resolveInfos) {
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: continue
                val pkg = appInfo.packageName
                if (pkg == context.packageName) continue

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isBrowser = pkg == "com.android.chrome" || pkg.contains("browser") || pkg.contains("webview")
                if (!includeSystemApps && isSystem && !isBrowser) continue

                val appName = try {
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg
                }

                val icon = try {
                    packageManager.getApplicationIcon(appInfo)
                } catch (_: Exception) {
                    ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
                }

                resultMap[pkg] = buildInstalledAppInfo(
                    pkg = pkg,
                    appName = appName,
                    isSystem = isSystem,
                    targetSdk = appInfo.targetSdkVersion,
                    icon = icon,
                    config = configMap[pkg]
                )
            }
        } catch (_: Exception) {}

        // 2. Query all installed packages
        try {
            val installedPackages = packageManager.getInstalledPackages(0)
            for (pkgInfo in installedPackages) {
                val appInfo = pkgInfo.applicationInfo ?: continue
                val pkg = pkgInfo.packageName
                if (pkg == context.packageName || resultMap.containsKey(pkg)) continue

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isBrowser = pkg == "com.android.chrome" || pkg.contains("browser") || pkg.contains("webview")
                if (!includeSystemApps && isSystem && !isBrowser) continue

                val appName = try {
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg
                }

                val icon = try {
                    packageManager.getApplicationIcon(appInfo)
                } catch (_: Exception) {
                    ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
                }

                resultMap[pkg] = buildInstalledAppInfo(
                    pkg = pkg,
                    appName = appName,
                    isSystem = isSystem,
                    targetSdk = appInfo.targetSdkVersion,
                    icon = icon,
                    config = configMap[pkg]
                )
            }
        } catch (_: Exception) {}

        // Always ensure any configured app is present in the list even if hidden from PM queries
        configs.forEach { cfg ->
            if (!resultMap.containsKey(cfg.packageName)) {
                resultMap[cfg.packageName] = buildInstalledAppInfo(
                    pkg = cfg.packageName,
                    appName = cfg.appName,
                    isSystem = false,
                    targetSdk = 35,
                    icon = ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon),
                    config = cfg
                )
            }
        }

        resultMap.values.sortedWith(
            compareByDescending<InstalledAppInfo> {
                it.isIsolated || it.isHiddenOrDisguised || it.isIdentitySpoofed || it.vpnProxyEnabled
            }.thenBy { it.appName.lowercase() }
        )
    }
}
