package com.hollow.app.isolation.feature.devicespoofer

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.model.DeviceProfilePreset
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.PrivacyProfile
import com.hollow.app.isolation.model.RealHardwareSnapshot
import com.hollow.app.isolation.mount.MountManager
import com.hollow.app.isolation.pkg.InstalledAppInfo
import com.hollow.app.isolation.service.DeadmanIncident
import com.hollow.app.isolation.service.RootDeadmanWatchdog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceSpooferViewModel(
    private val app: Application,
    private val configRepository: IsolationConfigRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val allInstalledApps: StateFlow<List<InstalledAppInfo>> = configRepository.allInstalledApps
    val privacyProfiles: StateFlow<List<PrivacyProfile>> = settingsRepository.privacyProfiles
    val deadmanBlockedIncidents: StateFlow<List<DeadmanIncident>> = RootDeadmanWatchdog.blockedUidsFlow
    val realHardware: RealHardwareSnapshot = settingsRepository.realHardware

    val identitySpoofedCount: StateFlow<Int> = configRepository.identitySpoofedCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    fun saveAppIdentitySpoofConfig(
        appInfo: InstalledAppInfo,
        enabled: Boolean,
        preset: DeviceProfilePreset,
        brand: String,
        model: String,
        device: String,
        androidId: String,
        imei: String,
        advertisingId: String,
        macAddress: String,
        carrierName: String,
        simOperator: String,
        serial: String,
        hideRootAndXposed: Boolean = appInfo.hideRootAndXposed,
        hideAdbAndDevOptions: Boolean = appInfo.hideAdbAndDevOptions,
        hideVpnAndProxy: Boolean = appInfo.hideVpnAndProxy,
        blockClipboardRead: Boolean = appInfo.blockClipboardRead,
        spoofLocationEnabled: Boolean = appInfo.spoofLocationEnabled,
        spoofLatitude: Double = appInfo.spoofLatitude,
        spoofLongitude: Double = appInfo.spoofLongitude,
        hideMockLocation: Boolean = appInfo.hideMockLocation,
        preventScreenshots: Boolean = appInfo.preventScreenshots,
        hideOtherApps: Boolean = appInfo.hideOtherApps,
        disableLogcatLogging: Boolean = appInfo.disableLogcatLogging,
        fakeBatteryEnabled: Boolean = appInfo.fakeBatteryEnabled,
        fakeBatteryLevel: Int = appInfo.fakeBatteryLevel,
        knoxWarrantyBitSpoof: Boolean = appInfo.knoxWarrantyBitSpoof,
        disableAllNetworking: Boolean = appInfo.disableAllNetworking,
        spoofGpuFingerprint: Boolean = appInfo.spoofGpuFingerprint,
        spoofBrowserUserAgent: Boolean = appInfo.spoofBrowserUserAgent,
        anonymizeSensors: Boolean = appInfo.anonymizeSensors,
        blockCellTowerTracking: Boolean = appInfo.blockCellTowerTracking,
        autoSyncTimezone: Boolean = appInfo.autoSyncTimezone,
        spoofManufacturer: String = appInfo.spoofManufacturer,
        spoofProduct: String = appInfo.spoofProduct,
        spoofBoard: String = appInfo.spoofBoard,
        spoofHardware: String = appInfo.spoofHardware,
        spoofBootloader: String = appInfo.spoofBootloader,
        spoofFingerprint: String = appInfo.spoofFingerprint,
        spoofGpuVendor: String = appInfo.spoofGpuVendor,
        spoofGpuRenderer: String = appInfo.spoofGpuRenderer,
        spoofCpuCores: Int = appInfo.spoofCpuCores,
        spoofRamGb: Int = appInfo.spoofRamGb,
        spoofScreenResolution: String = appInfo.spoofScreenResolution,
        spoofScreenDpi: Int = appInfo.spoofScreenDpi,
        spoofTimezoneId: String = appInfo.spoofTimezoneId,
        spoofLocaleTag: String = appInfo.spoofLocaleTag,
        customUserAgent: String = appInfo.customUserAgent,
        spoofWebRtc: Boolean = appInfo.spoofWebRtc,
        spoofCanvasNoise: Boolean = appInfo.spoofCanvasNoise,
        spoofAudioContext: Boolean = appInfo.spoofAudioContext,
        spoofClientHints: Boolean = appInfo.spoofClientHints,
        spoofFontEnumeration: Boolean = appInfo.spoofFontEnumeration,
        privacyProfileId: String = appInfo.privacyProfileId,
        deadmanSwitchEnabled: Boolean = appInfo.deadmanSwitchEnabled,
        spoofDrmWidevine: Boolean = appInfo.spoofDrmWidevine,
        sensorBiasNeutralizer: Boolean = appInfo.sensorBiasNeutralizer,
        spoofWifiBssid: Boolean = appInfo.spoofWifiBssid
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(appInfo.packageName) ?: AppIsolationConfig(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                customMountPath = MountManager.volumeDir(app, appInfo.packageName).absolutePath
            )
            val resolvedAndroidId = androidId.trim().ifBlank { IsolationRuleManager.generateAndroidId() }
            val resolvedImei = imei.trim().ifBlank { IsolationRuleManager.generateLuhnImei() }
            val resolvedAdId = advertisingId.trim().ifBlank { IsolationRuleManager.generateAdvertisingId() }
            val resolvedMac = macAddress.trim().ifBlank { IsolationRuleManager.generateMacAddress() }
            val resolvedSerial = serial.trim().ifBlank { IsolationRuleManager.generateSerial() }

            val updated = existing.copy(
                isIdentitySpoofed = enabled,
                deviceProfilePreset = preset,
                spoofBrand = brand.trim(),
                spoofModel = model.trim(),
                spoofDevice = device.trim(),
                spoofAndroidId = resolvedAndroidId,
                spoofImei = resolvedImei,
                spoofAdvertisingId = resolvedAdId,
                spoofMacAddress = resolvedMac,
                spoofCarrierName = carrierName.trim(),
                spoofSimOperator = simOperator.trim(),
                spoofSerial = resolvedSerial,
                hideRootAndXposed = hideRootAndXposed,
                hideAdbAndDevOptions = hideAdbAndDevOptions,
                hideVpnAndProxy = hideVpnAndProxy,
                blockClipboardRead = blockClipboardRead,
                spoofLocationEnabled = spoofLocationEnabled,
                spoofLatitude = spoofLatitude,
                spoofLongitude = spoofLongitude,
                hideMockLocation = hideMockLocation,
                preventScreenshots = preventScreenshots,
                hideOtherApps = hideOtherApps,
                disableLogcatLogging = disableLogcatLogging,
                fakeBatteryEnabled = fakeBatteryEnabled,
                fakeBatteryLevel = fakeBatteryLevel,
                knoxWarrantyBitSpoof = knoxWarrantyBitSpoof,
                disableAllNetworking = disableAllNetworking,
                spoofGpuFingerprint = spoofGpuFingerprint,
                spoofBrowserUserAgent = spoofBrowserUserAgent,
                anonymizeSensors = anonymizeSensors,
                blockCellTowerTracking = blockCellTowerTracking,
                autoSyncTimezone = autoSyncTimezone,
                spoofManufacturer = spoofManufacturer.trim(),
                spoofProduct = spoofProduct.trim(),
                spoofBoard = spoofBoard.trim(),
                spoofHardware = spoofHardware.trim(),
                spoofBootloader = spoofBootloader.trim(),
                spoofFingerprint = spoofFingerprint.trim(),
                spoofGpuVendor = spoofGpuVendor.trim(),
                spoofGpuRenderer = spoofGpuRenderer.trim(),
                spoofCpuCores = spoofCpuCores,
                spoofRamGb = spoofRamGb,
                spoofScreenResolution = spoofScreenResolution.trim(),
                spoofScreenDpi = spoofScreenDpi,
                spoofTimezoneId = spoofTimezoneId.trim(),
                spoofLocaleTag = spoofLocaleTag.trim(),
                customUserAgent = customUserAgent.trim(),
                spoofWebRtc = spoofWebRtc,
                spoofCanvasNoise = spoofCanvasNoise,
                spoofAudioContext = spoofAudioContext,
                spoofClientHints = spoofClientHints,
                spoofFontEnumeration = spoofFontEnumeration,
                privacyProfileId = privacyProfileId,
                deadmanSwitchEnabled = deadmanSwitchEnabled,
                spoofDrmWidevine = spoofDrmWidevine,
                sensorBiasNeutralizer = sensorBiasNeutralizer,
                spoofWifiBssid = spoofWifiBssid,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.syncStealthRulesToHooksNow()

            if (enabled) {
                settingsRepository.postStatusMessage("${appInfo.appName}: Privacy & identity shield active (${preset.label})")
            } else {
                settingsRepository.postStatusMessage("${appInfo.appName}: Real hardware identity restored")
            }
        }
    }

    fun removeAppIdentitySpoof(appInfo: InstalledAppInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(appInfo.packageName) ?: return@launch
            val updated = existing.copy(
                isIdentitySpoofed = false,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.syncStealthRulesToHooksNow()
            settingsRepository.postStatusMessage("${appInfo.appName}: Identity spoofing disabled")
        }
    }

    fun savePrivacyProfile(profile: PrivacyProfile) {
        settingsRepository.savePrivacyProfile(profile)
    }

    fun deletePrivacyProfile(profileId: String) {
        settingsRepository.deletePrivacyProfile(profileId)
    }

    fun resetPrivacyProfilesToDefaults() {
        settingsRepository.resetPrivacyProfilesToDefaults()
    }

    fun applyPrivacyProfileToApp(appInfo: InstalledAppInfo, profile: PrivacyProfile) {
        val preset = if (appInfo.deviceProfilePreset == DeviceProfilePreset.REAL_HARDWARE) {
            DeviceProfilePreset.PIXEL_9_PRO
        } else {
            appInfo.deviceProfilePreset
        }
        saveAppIdentitySpoofConfig(
            appInfo = appInfo,
            enabled = true,
            preset = preset,
            brand = appInfo.spoofBrand.ifBlank { preset.brand },
            model = appInfo.spoofModel.ifBlank { preset.model },
            device = appInfo.spoofDevice.ifBlank { preset.device },
            androidId = if (profile.spoofAndroidId) IsolationRuleManager.generateAndroidId() else realHardware.androidId,
            imei = if (profile.spoofImei) IsolationRuleManager.generateLuhnImei() else "",
            advertisingId = if (profile.spoofAdvertisingId) IsolationRuleManager.generateAdvertisingId() else "",
            macAddress = if (profile.spoofMacAddress) IsolationRuleManager.generateMacAddress() else "",
            carrierName = appInfo.spoofCarrierName.ifBlank { realHardware.carrierName },
            simOperator = appInfo.spoofSimOperator.ifBlank { realHardware.simOperator },
            serial = if (profile.spoofSerial) IsolationRuleManager.generateSerial() else realHardware.serial,
            hideRootAndXposed = profile.hideRootAndXposed,
            hideAdbAndDevOptions = profile.hideAdbAndDevOptions,
            hideVpnAndProxy = profile.hideVpnAndProxy,
            blockClipboardRead = profile.blockClipboardRead,
            preventScreenshots = profile.preventScreenshots,
            disableLogcatLogging = profile.disableLogcatLogging,
            fakeBatteryEnabled = profile.fakeBatteryEnabled,
            fakeBatteryLevel = profile.fakeBatteryLevel,
            spoofGpuFingerprint = profile.spoofGpuFingerprint,
            spoofGpuVendor = profile.gpuVendor,
            spoofGpuRenderer = profile.gpuRenderer,
            spoofCanvasNoise = profile.spoofCanvasNoise,
            spoofAudioContext = profile.spoofAudioContext,
            spoofScreenResolution = profile.spoofScreenResolution,
            spoofScreenDpi = profile.spoofScreenDpi,
            anonymizeSensors = profile.anonymizeSensors,
            sensorBiasNeutralizer = profile.sensorBiasNeutralizer,
            spoofWebRtc = profile.spoofWebRtc,
            blockCellTowerTracking = profile.blockCellTowerTracking,
            spoofWifiBssid = profile.spoofWifiBssid,
            autoSyncTimezone = profile.autoSyncTimezone,
            spoofBrowserUserAgent = profile.spoofBrowserUserAgent,
            privacyProfileId = profile.id,
            deadmanSwitchEnabled = profile.deadmanSwitchEnabled,
            spoofDrmWidevine = profile.spoofDrmWidevine
        )
    }

    fun restoreAppNetwork(appUid: Int) {
        RootDeadmanWatchdog.restoreAppNetwork(appUid)
        settingsRepository.postStatusMessage("Restored network access for UID $appUid")
    }

    fun restoreAllNetworks() {
        RootDeadmanWatchdog.restoreAllNetworks()
        settingsRepository.postStatusMessage("Restored all Deadman-blocked networks")
    }

    fun simulateIdentityLeak(packageName: String, appName: String, uid: Int) {
        RootDeadmanWatchdog.simulateIdentityLeak(
            context = app,
            packageName = packageName,
            appName = appName,
            uid = uid
        )
        settingsRepository.postStatusMessage("Deadman Switch triggered for $appName")
    }
}
