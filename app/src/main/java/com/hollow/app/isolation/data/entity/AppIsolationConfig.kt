package com.hollow.app.isolation.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hollow.app.isolation.model.DeviceProfilePreset
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.model.LauncherVisibilityMode

@Entity(tableName = "app_isolation_configs")
data class AppIsolationConfig(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isIsolated: Boolean = false,
    val isolationMode: IsolationMode = IsolationMode.SHARED_WORKSPACE,
    val encryptMediaOnly: Boolean = false,
    val blockRootPollution: Boolean = true,
    val whitelistedFolders: String = "", // comma-separated
    val customMountPath: String = "",
    val totalFilesRedirected: Long = 0,
    val totalBytesEncrypted: Long = 0,
    val lastAccessedTimestamp: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    // Independent App Hider & Disguise fields (separate from Storage Protection / isIsolated)
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
    val spoofCpuCores: Int = 0, // 0 = use real hardware unless edited
    val spoofRamGb: Int = 0,    // 0 = use real hardware unless edited
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
