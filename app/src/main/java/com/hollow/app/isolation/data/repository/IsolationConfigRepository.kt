package com.hollow.app.isolation.data.repository

import android.content.Context
import com.hollow.app.isolation.data.dao.IsolationDao
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.model.DeviceProfilePreset
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.LauncherVisibilityMode
import com.hollow.app.isolation.pkg.AppPackageRepository
import com.hollow.app.isolation.pkg.InstalledAppInfo
import com.hollow.app.isolation.provider.IsoVaultConfigProvider
import com.hollow.app.isolation.util.runHollowCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central repository for per-app isolation, stealth, device identity spoofing, and VPN configurations.
 * Ensures ViewModels never call IsolationDao directly and all ViewModels share a single source of truth.
 */
class IsolationConfigRepository(
    val context: Context,
    private val dao: IsolationDao,
    private val pkgRepo: AppPackageRepository,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) {
    val configs: StateFlow<List<AppIsolationConfig>> = dao.getAllConfigs()
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    val isolatedCount: Flow<Int> = dao.getIsolatedAppsCount()
    val hiddenOrDisguisedCount: Flow<Int> = dao.getHiddenOrDisguisedAppsCount()
    val identitySpoofedCount: Flow<Int> = dao.getIdentitySpoofedAppsCount()

    private val _rawInstalledApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val rawInstalledApps: StateFlow<List<InstalledAppInfo>> = _rawInstalledApps.asStateFlow()

    val allInstalledApps: StateFlow<List<InstalledAppInfo>> = combine(
        _rawInstalledApps,
        configs,
        settingsRepository.settings
    ) { apps, cfgList, currentSettings ->
        val cfgMap = cfgList.associateBy { it.packageName }
        apps.map { app ->
            val cfg = cfgMap[app.packageName]
            app.copy(
                isIsolated = cfg?.isIsolated ?: false,
                isolationMode = cfg?.isolationMode ?: IsolationMode.SHARED_WORKSPACE,
                isHiddenOrDisguised = cfg?.isHiddenOrDisguised ?: false,
                launcherVisibilityMode = cfg?.launcherVisibilityMode ?: LauncherVisibilityMode.VISIBLE,
                customLauncherLabel = cfg?.customLauncherLabel ?: "",
                disguiseIconPreset = cfg?.disguiseIconPreset ?: DisguiseIconPreset.CALCULATOR,
                hideInSystemUiRecents = cfg?.hideInSystemUiRecents ?: true,
                hideFromPlayStore = cfg?.hideFromPlayStore ?: false,
                spoofInSettings = cfg?.spoofInSettings ?: true,
                customIconBase64 = cfg?.customIconBase64 ?: "",
                customIconDonorPkg = cfg?.customIconDonorPkg ?: "",
                isIdentitySpoofed = cfg?.isIdentitySpoofed ?: false,
                deviceProfilePreset = cfg?.deviceProfilePreset ?: DeviceProfilePreset.REAL_HARDWARE,
                spoofBrand = cfg?.spoofBrand ?: "",
                spoofModel = cfg?.spoofModel ?: "",
                spoofDevice = cfg?.spoofDevice ?: "",
                spoofAndroidId = cfg?.spoofAndroidId ?: "",
                spoofImei = cfg?.spoofImei ?: "",
                spoofAdvertisingId = cfg?.spoofAdvertisingId ?: "",
                spoofMacAddress = cfg?.spoofMacAddress ?: "",
                spoofCarrierName = cfg?.spoofCarrierName ?: "",
                spoofSimOperator = cfg?.spoofSimOperator ?: "",
                spoofSerial = cfg?.spoofSerial ?: "",
                hideRootAndXposed = cfg?.hideRootAndXposed ?: true,
                hideAdbAndDevOptions = cfg?.hideAdbAndDevOptions ?: true,
                hideVpnAndProxy = cfg?.hideVpnAndProxy ?: true,
                blockClipboardRead = cfg?.blockClipboardRead ?: false,
                spoofLocationEnabled = cfg?.spoofLocationEnabled ?: false,
                spoofLatitude = cfg?.spoofLatitude ?: 37.7749,
                spoofLongitude = cfg?.spoofLongitude ?: -122.4194,
                hideMockLocation = cfg?.hideMockLocation ?: true,
                preventScreenshots = cfg?.preventScreenshots ?: false,
                hideOtherApps = cfg?.hideOtherApps ?: false,
                disableLogcatLogging = cfg?.disableLogcatLogging ?: false,
                fakeBatteryEnabled = cfg?.fakeBatteryEnabled ?: false,
                fakeBatteryLevel = cfg?.fakeBatteryLevel ?: 85,
                knoxWarrantyBitSpoof = cfg?.knoxWarrantyBitSpoof ?: false,
                disableAllNetworking = cfg?.disableAllNetworking ?: false,
                spoofGpuFingerprint = cfg?.spoofGpuFingerprint ?: false,
                spoofBrowserUserAgent = cfg?.spoofBrowserUserAgent ?: false,
                anonymizeSensors = cfg?.anonymizeSensors ?: false,
                blockCellTowerTracking = cfg?.blockCellTowerTracking ?: false,
                autoSyncTimezone = cfg?.autoSyncTimezone ?: false,
                spoofManufacturer = cfg?.spoofManufacturer ?: "",
                spoofProduct = cfg?.spoofProduct ?: "",
                spoofBoard = cfg?.spoofBoard ?: "",
                spoofHardware = cfg?.spoofHardware ?: "",
                spoofBootloader = cfg?.spoofBootloader ?: "",
                spoofFingerprint = cfg?.spoofFingerprint ?: "",
                spoofGpuVendor = cfg?.spoofGpuVendor ?: "",
                spoofGpuRenderer = cfg?.spoofGpuRenderer ?: "",
                spoofCpuCores = cfg?.spoofCpuCores ?: 0,
                spoofRamGb = cfg?.spoofRamGb ?: 0,
                spoofScreenResolution = cfg?.spoofScreenResolution ?: "",
                spoofScreenDpi = cfg?.spoofScreenDpi ?: 0,
                spoofTimezoneId = cfg?.spoofTimezoneId ?: "",
                spoofLocaleTag = cfg?.spoofLocaleTag ?: "",
                customUserAgent = cfg?.customUserAgent ?: "",
                spoofWebRtc = cfg?.spoofWebRtc ?: false,
                spoofCanvasNoise = cfg?.spoofCanvasNoise ?: false,
                spoofAudioContext = cfg?.spoofAudioContext ?: false,
                spoofClientHints = cfg?.spoofClientHints ?: false,
                spoofFontEnumeration = cfg?.spoofFontEnumeration ?: false,
                vpnProxyEnabled = cfg?.vpnProxyEnabled ?: false,
                proxyServerId = cfg?.proxyServerId ?: currentSettings.activeProxyServerId,
                requireVpnForNetwork = cfg?.requireVpnForNetwork ?: false,
                matchTimezoneWithIp = cfg?.matchTimezoneWithIp ?: true,
                webViewProxyEnabled = cfg?.webViewProxyEnabled ?: true,
                trustAllCerts = cfg?.trustAllCerts ?: false,
                privacyProfileId = cfg?.privacyProfileId ?: "",
                deadmanSwitchEnabled = cfg?.deadmanSwitchEnabled ?: true,
                spoofDrmWidevine = cfg?.spoofDrmWidevine ?: true,
                sensorBiasNeutralizer = cfg?.sensorBiasNeutralizer ?: true,
                spoofWifiBssid = cfg?.spoofWifiBssid ?: true
            )
        }
    }.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    init {
        loadInstalledApps()
        syncStealthRulesToHooks()
    }

    fun loadInstalledApps() {
        appScope.launch(Dispatchers.IO) {
            runHollowCatching("IsolationConfigRepository") {
                val apps = pkgRepo.getInstalledApps(configs.value, includeSystemApps = true)
                _rawInstalledApps.value = apps
            }
        }
    }

    suspend fun getAllConfigsSync(): List<AppIsolationConfig> =
        withContext(Dispatchers.IO) {
            runHollowCatching("IsolationConfigRepository") {
                dao.getAllConfigsSync()
            }.getOrNull() ?: emptyList()
        }

    suspend fun getConfigForPackage(packageName: String): AppIsolationConfig? =
        withContext(Dispatchers.IO) {
            runHollowCatching("IsolationConfigRepository") {
                dao.getConfigForPackage(packageName)
            }.getOrNull()
        }

    suspend fun insertOrUpdateConfig(config: AppIsolationConfig) {
        withContext(Dispatchers.IO) {
            runHollowCatching("IsolationConfigRepository") {
                dao.insertOrUpdateConfig(config)
            }
        }
    }

    fun notifyConfigChanged() {
        runHollowCatching("IsolationConfigRepository") {
            context.contentResolver.notifyChange(
                IsoVaultConfigProvider.CONTENT_URI,
                null
            )
        }
    }

    fun syncStealthRulesToHooks() {
        appScope.launch(Dispatchers.IO) {
            runHollowCatching("IsolationConfigRepository") {
                val allConfigs = dao.getAllConfigsSync()
                IsolationRuleManager.publishStealthTable(allConfigs, settingsRepository.settings.value)
                notifyConfigChanged()
            }
        }
    }

    suspend fun syncStealthRulesToHooksNow() = withContext(Dispatchers.IO) {
        runHollowCatching("IsolationConfigRepository") {
            val allConfigs = dao.getAllConfigsSync()
            IsolationRuleManager.publishStealthTable(allConfigs, settingsRepository.settings.value)
            notifyConfigChanged()
        }
    }

    fun whitelistSet(config: AppIsolationConfig): Set<String> =
        config.whitelistedFolders.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

    fun publishEnabledRule(config: AppIsolationConfig) {
        IsolationRuleManager.updateRule(
            packageName = config.packageName,
            mode = config.isolationMode,
            blockRootPollution = config.blockRootPollution,
            whitelistedFolders = whitelistSet(config)
        )
    }

    fun disableRule(config: AppIsolationConfig) {
        IsolationRuleManager.disableRule(
            packageName = config.packageName,
            mode = config.isolationMode,
            blockRootPollution = config.blockRootPollution,
            whitelistedFolders = whitelistSet(config)
        )
    }
}
