package com.hollow.app.isolation.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Manager-wide switches shown in Settings, App Hider, VPN/Proxy Hub, and TopAppBar. */
data class AppSettings(
    /** Master switch for the root-only volume features (attach, detach, seal). */
    val rootVolumesEnabled: Boolean = false,
    /** Seal (encrypt and wipe) a volume whenever it is detached. */
    val encryptVolumesAtRest: Boolean = true,
    /** What isolated apps get when they open the system picker. */
    val pickerPolicy: PickerPolicy = PickerPolicy.ALLOW,
    /** Material 3 Pro Dark Theme toggle (enabled by default). */
    val darkTheme: Boolean = true,
    /** Material You Dynamic Color on Android 12+. */
    val dynamicColor: Boolean = false,
    /** How Hollow itself appears in launchers & SystemUI. */
    val hollowVisibilityMode: LauncherVisibilityMode = LauncherVisibilityMode.VISIBLE,
    /** Custom spoofed launcher name for Hollow when disguised. */
    val hollowCustomName: String = "Calculator",
    /** Decoy icon preset for Hollow when disguised. */
    val hollowIconPreset: DisguiseIconPreset = DisguiseIconPreset.CALCULATOR,
    /** Optional custom image Base64 for Hollow when disguised. */
    val hollowCustomIconBase64: String = "",
    /** Optional donor app package for Hollow when disguised. */
    val hollowCustomIconDonorPkg: String = "",
    /** Spoof Hollow's own name & icon in System Settings / App Management. */
    val hollowSpoofInSettings: Boolean = true,
    /** Injects a direct stealth entry trigger into SystemUI (Quick Settings Clock/Header long-press). */
    val systemUiTriggerEnabled: Boolean = true,
    /** Selected Hollow launcher icon style when Hollow is visible. */
    val hollowIconTheme: HollowIconTheme = HollowIconTheme.DYNAMIC_MATERIAL_YOU,
    // Global V2Ray / Proxy & Network Control Suite
    val vpnTunnelEnabled: Boolean = false,
    val vpnConnectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val connectWholeDevice: Boolean = false,
    val activeProxyServerId: String = "",
    val socksProxyEnabled: Boolean = true,
    val httpProxyEnabled: Boolean = true,
    val webViewProxyEnabled: Boolean = true,
    val trustAllCertificates: Boolean = false,
    val vpnEngineMode: VpnEngineMode = VpnEngineMode.ANDROID_VPN,
    val hideProxyConnection: Boolean = true,
    val hideVpnConnection: Boolean = true,
    val vpnKillSwitchEnabled: Boolean = true,
    val matchTimezoneWithIpGlobal: Boolean = true,
    val showIpInfoInToolbar: Boolean = true,
    val customProxyServers: List<VpnProxyServer> = emptyList()
) {
    val allProxyServers: List<VpnProxyServer>
        get() = customProxyServers + VpnProxyPresets.defaultServers

    val activeServer: VpnProxyServer
        get() = VpnProxyPresets.findById(activeProxyServerId, customProxyServers)
}

object SettingsStore {
    private const val FILE = "isovault_settings"
    private const val K_ROOT = "root_volumes_enabled"
    private const val K_ENCRYPT = "encrypt_volumes_at_rest"
    private const val K_PICKER = "picker_policy"
    private const val K_DARK = "dark_theme_v2"
    private const val K_DYNAMIC = "dynamic_color"
    private const val K_HOLLOW_VISIBILITY = "hollow_visibility_mode"
    private const val K_HOLLOW_NAME = "hollow_custom_name"
    private const val K_HOLLOW_ICON = "hollow_icon_preset"
    private const val K_HOLLOW_CUSTOM_ICON_B64 = "hollow_custom_icon_b64"
    private const val K_HOLLOW_DONOR_PKG = "hollow_donor_pkg"
    private const val K_HOLLOW_SPOOF_SETTINGS = "hollow_spoof_settings"
    private const val K_SYSTEMUI_TRIGGER = "systemui_trigger_enabled"
    private const val K_HOLLOW_ICON_THEME = "hollow_icon_theme"
    // VPN / Proxy keys
    private const val K_VPN_ENABLED = "vpn_tunnel_enabled"
    private const val K_VPN_STATE = "vpn_connection_state"
    private const val K_CONNECT_WHOLE_DEVICE = "vpn_connect_whole_device"
    private const val K_ACTIVE_PROXY_ID = "active_proxy_server_id"
    private const val K_SOCKS_PROXY = "socks_proxy_enabled"
    private const val K_HTTP_PROXY = "http_proxy_enabled"
    private const val K_WEBVIEW_PROXY = "webview_proxy_enabled"
    private const val K_TRUST_ALL_CERTS = "trust_all_certs"
    private const val K_VPN_ENGINE_MODE = "vpn_engine_mode"
    private const val K_HIDE_PROXY_CONN = "hide_proxy_conn"
    private const val K_HIDE_VPN_CONN = "hide_vpn_conn"
    private const val K_VPN_KILL_SWITCH = "vpn_kill_switch"
    private const val K_MATCH_TZ_WITH_IP = "match_tz_with_ip_global"
    private const val K_SHOW_IP_INFO = "show_ip_info_toolbar"
    private const val K_CUSTOM_SERVERS_JSON = "custom_proxy_servers_json"

    fun load(context: Context): AppSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val picker = try {
            PickerPolicy.valueOf(p.getString(K_PICKER, PickerPolicy.ALLOW.name) ?: PickerPolicy.ALLOW.name)
        } catch (_: IllegalArgumentException) {
            PickerPolicy.ALLOW
        }
        val hollowVis = try {
            LauncherVisibilityMode.valueOf(
                p.getString(K_HOLLOW_VISIBILITY, LauncherVisibilityMode.VISIBLE.name)
                    ?: LauncherVisibilityMode.VISIBLE.name
            )
        } catch (_: IllegalArgumentException) {
            LauncherVisibilityMode.VISIBLE
        }
        val hollowIcon = try {
            DisguiseIconPreset.valueOf(
                p.getString(K_HOLLOW_ICON, DisguiseIconPreset.CALCULATOR.name)
                    ?: DisguiseIconPreset.CALCULATOR.name
            )
        } catch (_: IllegalArgumentException) {
            DisguiseIconPreset.CALCULATOR
        }
        val iconTheme = try {
            HollowIconTheme.valueOf(
                p.getString(K_HOLLOW_ICON_THEME, HollowIconTheme.DYNAMIC_MATERIAL_YOU.name)
                    ?: HollowIconTheme.DYNAMIC_MATERIAL_YOU.name
            )
        } catch (_: IllegalArgumentException) {
            HollowIconTheme.DYNAMIC_MATERIAL_YOU
        }
        val vpnEngineMode = try {
            VpnEngineMode.valueOf(
                p.getString(K_VPN_ENGINE_MODE, VpnEngineMode.ANDROID_VPN.name)
                    ?: VpnEngineMode.ANDROID_VPN.name
            )
        } catch (_: IllegalArgumentException) {
            VpnEngineMode.ANDROID_VPN
        }
        val storedCustomServers = p.getString(K_CUSTOM_SERVERS_JSON, "") ?: ""
        val customServers = decodeCustomServers(storedCustomServers)
        if (storedCustomServers.contains("\"usernameOrUuid\"") ||
            storedCustomServers.contains("\"passwordOrSecurity\"")
        ) {
            // Older versions wrote imported credentials as plaintext JSON and had no usable share link.
            p.edit().putString(K_CUSTOM_SERVERS_JSON, encodeCustomServers(customServers)).commit()
        }
        return AppSettings(
            rootVolumesEnabled = p.getBoolean(K_ROOT, false),
            encryptVolumesAtRest = p.getBoolean(K_ENCRYPT, true),
            pickerPolicy = picker,
            darkTheme = p.getBoolean(K_DARK, true),
            dynamicColor = p.getBoolean(K_DYNAMIC, false),
            hollowVisibilityMode = hollowVis,
            hollowCustomName = p.getString(K_HOLLOW_NAME, "Calculator") ?: "Calculator",
            hollowIconPreset = hollowIcon,
            hollowCustomIconBase64 = p.getString(K_HOLLOW_CUSTOM_ICON_B64, "") ?: "",
            hollowCustomIconDonorPkg = p.getString(K_HOLLOW_DONOR_PKG, "") ?: "",
            hollowSpoofInSettings = p.getBoolean(K_HOLLOW_SPOOF_SETTINGS, true),
            systemUiTriggerEnabled = p.getBoolean(K_SYSTEMUI_TRIGGER, true),
            hollowIconTheme = iconTheme,
            // Earlier app versions persisted a UI toggle as if it were a live tunnel. Never restore that fake state.
            vpnTunnelEnabled = false,
            vpnConnectionState = VpnConnectionState.DISCONNECTED,
            connectWholeDevice = p.getBoolean(K_CONNECT_WHOLE_DEVICE, false),
            activeProxyServerId = p.getString(K_ACTIVE_PROXY_ID, "") ?: "",
            socksProxyEnabled = p.getBoolean(K_SOCKS_PROXY, true),
            httpProxyEnabled = p.getBoolean(K_HTTP_PROXY, true),
            webViewProxyEnabled = p.getBoolean(K_WEBVIEW_PROXY, true),
            trustAllCertificates = p.getBoolean(K_TRUST_ALL_CERTS, false),
            vpnEngineMode = vpnEngineMode,
            hideProxyConnection = p.getBoolean(K_HIDE_PROXY_CONN, true),
            hideVpnConnection = p.getBoolean(K_HIDE_VPN_CONN, true),
            vpnKillSwitchEnabled = p.getBoolean(K_VPN_KILL_SWITCH, true),
            matchTimezoneWithIpGlobal = p.getBoolean(K_MATCH_TZ_WITH_IP, true),
            showIpInfoInToolbar = p.getBoolean(K_SHOW_IP_INFO, true),
            customProxyServers = customServers
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ROOT, settings.rootVolumesEnabled)
            .putBoolean(K_ENCRYPT, settings.encryptVolumesAtRest)
            .putString(K_PICKER, settings.pickerPolicy.name)
            .putBoolean(K_DARK, settings.darkTheme)
            .putBoolean(K_DYNAMIC, settings.dynamicColor)
            .putString(K_HOLLOW_VISIBILITY, settings.hollowVisibilityMode.name)
            .putString(K_HOLLOW_NAME, settings.hollowCustomName)
            .putString(K_HOLLOW_ICON, settings.hollowIconPreset.name)
            .putString(K_HOLLOW_CUSTOM_ICON_B64, settings.hollowCustomIconBase64)
            .putString(K_HOLLOW_DONOR_PKG, settings.hollowCustomIconDonorPkg)
            .putBoolean(K_HOLLOW_SPOOF_SETTINGS, settings.hollowSpoofInSettings)
            .putBoolean(K_SYSTEMUI_TRIGGER, settings.systemUiTriggerEnabled)
            .putString(K_HOLLOW_ICON_THEME, settings.hollowIconTheme.name)
            .putBoolean(K_VPN_ENABLED, settings.vpnTunnelEnabled)
            .putString(K_VPN_STATE, settings.vpnConnectionState.name)
            .putBoolean(K_CONNECT_WHOLE_DEVICE, settings.connectWholeDevice)
            .putString(K_ACTIVE_PROXY_ID, settings.activeProxyServerId)
            .putBoolean(K_SOCKS_PROXY, settings.socksProxyEnabled)
            .putBoolean(K_HTTP_PROXY, settings.httpProxyEnabled)
            .putBoolean(K_WEBVIEW_PROXY, settings.webViewProxyEnabled)
            .putBoolean(K_TRUST_ALL_CERTS, settings.trustAllCertificates)
            .putString(K_VPN_ENGINE_MODE, settings.vpnEngineMode.name)
            .putBoolean(K_HIDE_PROXY_CONN, settings.hideProxyConnection)
            .putBoolean(K_HIDE_VPN_CONN, settings.hideVpnConnection)
            .putBoolean(K_VPN_KILL_SWITCH, settings.vpnKillSwitchEnabled)
            .putBoolean(K_MATCH_TZ_WITH_IP, settings.matchTimezoneWithIpGlobal)
            .putBoolean(K_SHOW_IP_INFO, settings.showIpInfoInToolbar)
            .putString(K_CUSTOM_SERVERS_JSON, encodeCustomServers(settings.customProxyServers))
            .commit()
        // Hooked apps read the picker policy and SystemUI trigger through the rule channels.
        IsolationRuleManager.publishPickerPolicy(settings.pickerPolicy)
        IsolationRuleManager.publishSystemUiTrigger(settings.systemUiTriggerEnabled)
    }

    private fun encodeCustomServers(servers: List<VpnProxyServer>): String {
        val arr = JSONArray()
        servers.forEach { server ->
            arr.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("protocol", server.protocol.name)
                    .put("host", server.host)
                    .put("port", server.port)
                    .put("shareUriEncrypted", VpnSecretCipher.encrypt(server.shareUri))
                    .put("latencyMs", server.latencyMs.coerceAtLeast(0))
            )
        }
        return arr.toString()
    }

    private fun decodeCustomServers(raw: String): List<VpnProxyServer> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (index in 0 until arr.length()) {
                    val item = arr.getJSONObject(index)
                    val shareUri = VpnSecretCipher.decrypt(item.optString("shareUriEncrypted", ""))
                    val parsed = VpnProxyPresets.parseUri(shareUri) ?: continue
                    add(
                        parsed.copy(
                            id = item.optString("id", parsed.id),
                            latencyMs = item.optInt("latencyMs", 0).coerceAtLeast(0)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
