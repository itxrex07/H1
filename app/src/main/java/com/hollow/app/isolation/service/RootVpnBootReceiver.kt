package com.hollow.app.isolation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hollow.app.IsoVaultApp
import com.hollow.app.isolation.model.SettingsStore
import com.hollow.app.isolation.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restores the H1 root tunnel only when the user explicitly enabled boot restore. */
class RootVpnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as? IsoVaultApp
        if (app == null) {
            pending.finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore.load(app)
                if (!settings.rootAutoStart ||
                    settings.vpnEngineMode.name != "ROOT_XRAY_TPROXY" ||
                    settings.activeServer.shareUri.isBlank()
                ) return@launch
                val selectedPackages = app.configRepository.configs.value
                    .filter { it.vpnProxyEnabled }
                    .map { it.packageName }
                    .toSet()
                if (!settings.connectWholeDevice && selectedPackages.isEmpty()) return@launch
                val result = RootVpnEngine.start(
                    app,
                    settings.activeServer,
                    RootVpnEngine.RouteOptions(
                        wholeDevice = settings.connectWholeDevice,
                        selectedPackages = selectedPackages,
                        ipv6Enabled = settings.rootIpv6Enabled,
                        hotspotEnabled = settings.rootHotspotEnabled,
                        usbEnabled = settings.rootUsbEnabled
                    )
                )
                if (result.exitCode == 0 && RootVpnEngine.isRunning()) {
                    app.settingsRepository.updateSettings {
                        it.copy(vpnTunnelEnabled = true, vpnConnectionState = VpnConnectionState.CONNECTED)
                    }
                    app.settingsRepository.postStatusMessage("Root TProxy restored after boot")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
