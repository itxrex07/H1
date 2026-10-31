package com.hollow.app.isolation.feature.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.model.AppSettings
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.HollowIconTheme
import com.hollow.app.isolation.model.LauncherVisibilityMode
import com.hollow.app.isolation.model.PickerPolicy
import com.hollow.app.isolation.model.VpnProxyPresets
import com.hollow.app.isolation.model.VpnProxyServer
import com.hollow.app.isolation.model.VpnConnectionState
import com.hollow.app.isolation.model.VpnEngineMode
import com.hollow.app.isolation.service.RootVpnEngine
import com.hollow.app.isolation.service.HollowVpnService
import com.hollow.app.isolation.mount.MountEntry
import com.hollow.app.isolation.mount.MountManager
import com.hollow.app.isolation.pkg.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val app: Application,
    private val settingsRepository: SettingsRepository,
    private val configRepository: IsolationConfigRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val isLsposedActive: StateFlow<Boolean> = settingsRepository.isLsposedActive
    val rootStatus: StateFlow<String> = settingsRepository.rootStatus
    val terminalLogs: StateFlow<List<String>> = settingsRepository.terminalLogs
    val systemMounts: StateFlow<List<MountEntry>> = settingsRepository.systemMounts
    val selinuxStatus: StateFlow<String> = settingsRepository.selinuxStatus
    val statusMessage: StateFlow<String?> = settingsRepository.statusMessage
    val allInstalledApps: StateFlow<List<InstalledAppInfo>> = configRepository.allInstalledApps

    fun clearStatusMessage() {
        settingsRepository.clearStatusMessage()
    }

    fun selectVpnEngine(mode: VpnEngineMode) {
        stopVpnIfActive()
        settingsRepository.updateSettings {
            it.copy(vpnEngineMode = mode, vpnConnectionState = VpnConnectionState.DISCONNECTED)
        }
        settingsRepository.postStatusMessage(
            if (mode == VpnEngineMode.ROOT_NETPROXY) {
                "Root TProxy mode selected; NetProxy-Magisk must be installed"
            } else {
                "Android VPN mode selected"
            }
        )
    }

    fun toggleRootVpn() {
        if (settings.value.vpnConnectionState == VpnConnectionState.CONNECTED ||
            settings.value.vpnConnectionState == VpnConnectionState.CONNECTING
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                RootVpnEngine.stop()
                settingsRepository.updateSettings {
                    it.copy(vpnTunnelEnabled = false, vpnConnectionState = VpnConnectionState.DISCONNECTED)
                }
                settingsRepository.postStatusMessage("Root TProxy stopped; network rules restored")
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateSettings { it.copy(vpnConnectionState = VpnConnectionState.CONNECTING) }
            if (!RootVpnEngine.isAvailable()) {
                settingsRepository.updateSettings {
                    it.copy(vpnTunnelEnabled = false, vpnConnectionState = VpnConnectionState.ERROR)
                }
                settingsRepository.postStatusMessage(
                    "Root TProxy unavailable. Install and enable NetProxy-Magisk v6.0.1 or newer."
                )
                return@launch
            }
            val result = RootVpnEngine.start()
            if (result.exitCode == 0 && RootVpnEngine.isRunning()) {
                settingsRepository.updateSettings {
                    it.copy(vpnTunnelEnabled = true, vpnConnectionState = VpnConnectionState.CONNECTED)
                }
                settingsRepository.postStatusMessage("Root TProxy connected; Android VPN interface was not created")
            } else {
                settingsRepository.updateSettings {
                    it.copy(vpnTunnelEnabled = false, vpnConnectionState = VpnConnectionState.ERROR)
                }
                settingsRepository.postStatusMessage(
                    "Root TProxy failed: ${result.output.take(220).ifBlank { "check NetProxy logs" }}"
                )
            }
        }
    }

    fun setDarkTheme(dark: Boolean) {
        settingsRepository.updateSettings { it.copy(darkTheme = dark) }
    }

    fun setDynamicColor(dynamic: Boolean) {
        settingsRepository.updateSettings { it.copy(dynamicColor = dynamic) }
    }

    fun setRootVolumesEnabled(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(rootVolumesEnabled = enabled) }
        if (enabled) checkRoot()
    }

    fun setEncryptVolumesAtRest(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(encryptVolumesAtRest = enabled) }
    }

    fun setHollowIconTheme(theme: HollowIconTheme) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = settingsRepository.updateSettings { it.copy(hollowIconTheme = theme) }
            settingsRepository.applyNativeHollowAlias(
                visibilityMode = next.hollowVisibilityMode,
                iconPreset = next.hollowIconPreset,
                iconTheme = theme
            )
            settingsRepository.postStatusMessage("Hollow icon updated to ${theme.label}")
        }
    }

    fun setPickerPolicy(policy: PickerPolicy) {
        settingsRepository.updateSettings { it.copy(pickerPolicy = policy) }
        settingsRepository.postStatusMessage("System picker policy set to ${policy.title}")
    }

    fun checkRoot() {
        viewModelScope.launch {
            settingsRepository.checkRoot()
        }
    }

    fun runRootCheck() {
        checkRoot()
    }

    fun generateBootScript(): String {
        val packages = configRepository.configs.value.filter { it.isIsolated }.map { it.packageName }
        return MountManager.generateBootService(app, packages)
    }

    fun decodeImageUriToBase64(uri: Uri, onDecoded: (String) -> Unit) {
        viewModelScope.launch {
            val encoded = settingsRepository.decodeImageUriToBase64(uri)
            if (!encoded.isNullOrBlank()) {
                onDecoded(encoded)
            } else {
                settingsRepository.postStatusMessage("Could not decode selected image")
            }
        }
    }

    fun updateHollowStealthConfig(
        visibilityMode: LauncherVisibilityMode,
        customName: String,
        iconPreset: DisguiseIconPreset,
        systemUiTriggerEnabled: Boolean,
        customIconBase64: String = "",
        customIconDonorPkg: String = "",
        spoofInSettings: Boolean = true
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = settingsRepository.updateSettings {
                it.copy(
                    hollowVisibilityMode = visibilityMode,
                    hollowCustomName = customName.trim().ifBlank { "Calculator" },
                    hollowIconPreset = iconPreset,
                    systemUiTriggerEnabled = systemUiTriggerEnabled,
                    hollowCustomIconBase64 = customIconBase64,
                    hollowCustomIconDonorPkg = customIconDonorPkg,
                    hollowSpoofInSettings = spoofInSettings
                )
            }
            configRepository.syncStealthRulesToHooksNow()
            settingsRepository.applyNativeHollowAlias(
                visibilityMode = visibilityMode,
                iconPreset = iconPreset,
                iconTheme = next.hollowIconTheme
            )
            MountManager.refreshLaunchersAndStealthCaches(
                context = app,
                refreshPlayStore = false,
                refreshSettings = spoofInSettings
            )

            val msg = when (visibilityMode) {
                LauncherVisibilityMode.HIDDEN -> "Hollow hidden from launcher"
                LauncherVisibilityMode.DISGUISED -> "Hollow disguised as ${next.hollowCustomName}"
                LauncherVisibilityMode.VISIBLE -> "Hollow launcher visibility restored"
            }
            settingsRepository.postStatusMessage(msg)
        }
    }

    fun refreshRootVpnState() {
        if (settings.value.vpnEngineMode != VpnEngineMode.ROOT_NETPROXY) return
        viewModelScope.launch(Dispatchers.IO) {
            val running = RootVpnEngine.isRunning()
            settingsRepository.updateSettings {
                it.copy(
                    vpnTunnelEnabled = running,
                    vpnConnectionState = if (running) VpnConnectionState.CONNECTED else VpnConnectionState.DISCONNECTED
                )
            }
        }
    }

    fun selectActiveProxyServer(proxyId: String) {
        if (settings.value.vpnTunnelEnabled) {
            stopVpnIfActive()
        }
        val next = settingsRepository.updateSettings {
            it.copy(
                activeProxyServerId = proxyId,
                vpnTunnelEnabled = false,
                vpnConnectionState = VpnConnectionState.DISCONNECTED
            )
        }
        configRepository.syncStealthRulesToHooks()
        val srv = next.activeServer
        settingsRepository.postStatusMessage("Selected endpoint: ${srv.name} (${srv.protocol.label})")
    }

    fun importProxyServerFromUri(rawInput: String): Boolean {
        val parsed = VpnProxyPresets.parseUri(rawInput)
        if (parsed == null) {
            settingsRepository.postStatusMessage("Unsupported or invalid VLESS TCP/RAW Reality/TLS link")
            return false
        }
        settingsRepository.updateSettings {
            it.copy(
                customProxyServers = it.customProxyServers + parsed,
                activeProxyServerId = parsed.id
            )
        }
        configRepository.syncStealthRulesToHooks()
        settingsRepository.postStatusMessage("Imported ${parsed.protocol.label} node: ${parsed.name}")
        return true
    }

    fun addCustomProxyServer(server: VpnProxyServer) {
        settingsRepository.updateSettings {
            it.copy(
                customProxyServers = it.customProxyServers + server,
                activeProxyServerId = server.id
            )
        }
        configRepository.syncStealthRulesToHooks()
        settingsRepository.postStatusMessage("Added ${server.name} (${server.protocol.label})")
    }

    fun deleteCustomProxyServer(proxyId: String) {
        val current = settings.value
        if (current.vpnTunnelEnabled && current.activeProxyServerId == proxyId) {
            stopVpnIfActive()
        }
        val remainingCustom = current.customProxyServers.filterNot { it.id == proxyId }
        val nextActive = if (current.activeProxyServerId == proxyId) {
            remainingCustom.firstOrNull()?.id.orEmpty()
        } else current.activeProxyServerId
        settingsRepository.updateSettings {
            it.copy(
                customProxyServers = remainingCustom,
                activeProxyServerId = nextActive,
                vpnTunnelEnabled = if (current.activeProxyServerId == proxyId) false else it.vpnTunnelEnabled,
                vpnConnectionState = if (current.activeProxyServerId == proxyId) VpnConnectionState.DISCONNECTED else it.vpnConnectionState
            )
        }
        configRepository.syncStealthRulesToHooks()
        settingsRepository.postStatusMessage("Removed custom proxy endpoint")
    }

    fun updateProxyingSuiteOptions(
        socksProxy: Boolean = settings.value.socksProxyEnabled,
        httpProxy: Boolean = settings.value.httpProxyEnabled,
        webViewProxy: Boolean = settings.value.webViewProxyEnabled,
        trustAllCerts: Boolean = settings.value.trustAllCertificates,
        hideProxyConn: Boolean = settings.value.hideProxyConnection,
        hideVpnConn: Boolean = settings.value.hideVpnConnection,
        vpnKillSwitch: Boolean = settings.value.vpnKillSwitchEnabled,
        matchTzWithIp: Boolean = settings.value.matchTimezoneWithIpGlobal
    ) {
        settingsRepository.updateSettings {
            it.copy(
                socksProxyEnabled = socksProxy,
                httpProxyEnabled = httpProxy,
                webViewProxyEnabled = webViewProxy,
                trustAllCertificates = trustAllCerts,
                hideProxyConnection = hideProxyConn,
                hideVpnConnection = hideVpnConn,
                vpnKillSwitchEnabled = vpnKillSwitch,
                matchTimezoneWithIpGlobal = matchTzWithIp
            )
        }
        configRepository.syncStealthRulesToHooks()
        settingsRepository.postStatusMessage("Updated VPN & Proxying Suite security policy")
    }

    fun toggleConnectWholeDevice(enabled: Boolean = !settings.value.connectWholeDevice) {
        stopVpnIfActive()
        settingsRepository.updateSettings { it.copy(connectWholeDevice = enabled) }
        configRepository.syncStealthRulesToHooks()
        settingsRepository.postStatusMessage(
            if (enabled) "Whole device VPN routing enabled" else "Whole device VPN routing disabled"
        )
    }

    fun toggleVpnGlobalKillSwitch(enabled: Boolean = !settings.value.vpnKillSwitchEnabled) {
        settingsRepository.updateSettings { it.copy(vpnKillSwitchEnabled = enabled) }
        configRepository.syncStealthRulesToHooks()
        settingsRepository.postStatusMessage(
            if (enabled) "Hooked-app VPN protection enabled" else "Hooked-app VPN protection disabled"
        )
    }

    fun toggleAppVpnProxy(
        appInfo: InstalledAppInfo,
        enabled: Boolean,
        serverId: String = appInfo.proxyServerId.ifBlank { settings.value.activeProxyServerId },
        requireVpnForNetwork: Boolean = appInfo.requireVpnForNetwork,
        matchTimezoneWithIp: Boolean = appInfo.matchTimezoneWithIp,
        webViewProxyEnabled: Boolean = appInfo.webViewProxyEnabled,
        trustAllCerts: Boolean = appInfo.trustAllCerts
    ) {
        stopVpnIfActive()
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(appInfo.packageName) ?: AppIsolationConfig(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                customMountPath = MountManager.volumeDir(app, appInfo.packageName).absolutePath
            )
            val updated = existing.copy(
                vpnProxyEnabled = enabled,
                proxyServerId = serverId,
                requireVpnForNetwork = requireVpnForNetwork,
                matchTimezoneWithIp = matchTimezoneWithIp,
                webViewProxyEnabled = webViewProxyEnabled,
                trustAllCerts = trustAllCerts,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.syncStealthRulesToHooksNow()

            val srv = VpnProxyPresets.findById(serverId, settings.value.customProxyServers)
            settingsRepository.postStatusMessage(
                if (enabled) "${appInfo.appName}: routed through ${srv.name}"
                else "${appInfo.appName}: direct connection restored"
            )
        }
    }

    fun setAllAppsVpnProxy(enabled: Boolean, userAppsOnly: Boolean = true) {
        stopVpnIfActive()
        viewModelScope.launch(Dispatchers.IO) {
            val activeServerId = settings.value.activeProxyServerId
            val targets = allInstalledApps.value.filter { if (userAppsOnly) !it.isSystemApp else true }
            targets.forEach { appInfo ->
                val existing = configRepository.getConfigForPackage(appInfo.packageName) ?: AppIsolationConfig(
                    packageName = appInfo.packageName,
                    appName = appInfo.appName,
                    customMountPath = MountManager.volumeDir(app, appInfo.packageName).absolutePath
                )
                configRepository.insertOrUpdateConfig(
                    existing.copy(
                        vpnProxyEnabled = enabled,
                        proxyServerId = existing.proxyServerId.ifBlank { activeServerId },
                        lastAccessedTimestamp = System.currentTimeMillis()
                    )
                )
            }
            configRepository.syncStealthRulesToHooksNow()
            settingsRepository.postStatusMessage(
                if (enabled) "Routed ${targets.size} apps through Proxy Tunnel"
                else "Cleared per-app proxy routing"
            )
        }
    }

    private fun stopVpnIfActive() {
        if (!settings.value.vpnTunnelEnabled) return
        if (settings.value.vpnEngineMode == VpnEngineMode.ROOT_NETPROXY) {
            viewModelScope.launch(Dispatchers.IO) { RootVpnEngine.stop() }
        } else {
            app.startService(Intent(app, HollowVpnService::class.java).setAction(HollowVpnService.ACTION_DISCONNECT))
        }
        settingsRepository.updateSettings {
            it.copy(vpnTunnelEnabled = false, vpnConnectionState = VpnConnectionState.DISCONNECTED)
        }
    }
}
