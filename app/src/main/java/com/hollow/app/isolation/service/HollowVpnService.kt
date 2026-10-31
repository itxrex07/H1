package com.hollow.app.isolation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.hollow.app.IsoVaultApp
import com.hollow.app.isolation.model.SettingsStore
import com.hollow.app.isolation.model.VpnConnectionState
import com.hollow.app.isolation.model.VpnProxyServer
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the actual Android TUN interface and Xray-core lifecycle. */
class HollowVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnel: ParcelFileDescriptor? = null
    private var core: CoreController? = null
    private var connecting = false
    private var connectJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                if (core?.isRunning == true || connecting) return START_NOT_STICKY
                connecting = true
                createNotificationChannel()
                startInForeground("Starting VPN tunnel")
                setState(VpnConnectionState.CONNECTING)
                connectJob = serviceScope.launch { connectTunnel() }
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private suspend fun connectTunnel() {
        var stage = "validating the imported VLESS server"
        try {
            val app = application as IsoVaultApp
            val settings = SettingsStore.load(this)
            val node = settings.activeServer
            val configJson = XrayConfigBuilder.build(node)
            stage = "creating the Android VPN interface"
            val builder = Builder()
                .setSession("Hollow VPN")
                .setMtu(TUN_MTU)
                .addAddress(IPV4_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_IPV4)
                .addAddress(IPV6_ADDRESS, 128)
                .addRoute("::", 0)
                .addDnsServer(DNS_IPV6)

            if (!settings.connectWholeDevice) {
                val selectedPackages = app.configRepository.configs.value
                    .filter { it.vpnProxyEnabled }
                    .map { it.packageName }
                    .filter { it != packageName }
                    .distinct()
                require(selectedPackages.isNotEmpty()) {
                    "Select one or more apps, or enable whole-device routing before connecting."
                }
                var addedApplications = 0
                selectedPackages.forEach { selected ->
                    try {
                        builder.addAllowedApplication(selected)
                        addedApplications++
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        // Ignore app selections whose package has since been uninstalled.
                    }
                }
                require(addedApplications > 0) {
                    "None of the selected apps is installed. Select an installed app or enable whole-device routing."
                }
            } else {
                builder.addDisallowedApplication(packageName)
            }

            val established = builder.establish()
                ?: throw IllegalStateException("Android did not grant a VPN interface. Reconnect and approve the system prompt.")
            tunnel = established
            stage = "starting the Xray engine"
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val callback = object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(level: Long, message: String): Long = 0L
            }
            val controller = Libv2ray.newCoreController(callback)
            core = controller
            controller.startLoop(configJson, established.fd)

            // Starting Xray only proves that the local core initialized. Probe through its VLESS outbound
            // before showing Connected so an unreachable/bad node never looks like a working VPN.
            stage = "checking connectivity through the VLESS server"
            val latency = withContext(Dispatchers.IO) {
                CONNECTIVITY_PROBE_URLS.firstNotNullOfOrNull { url ->
                    runCatching { controller.measureDelay(url).takeIf { it >= 0L } }.getOrNull()
                }
            } ?: throw IllegalStateException(
                "Xray started, but neither HTTPS connectivity check succeeded. Verify the VLESS link, server availability, and phone internet connection."
            )

            stage = "saving the VPN connection state"
            app.settingsRepository.updateSettings { current ->
                val updatedServers = current.customProxyServers.map { server ->
                    if (server.id == node.id) server.copy(latencyMs = latency.toInt()) else server
                }
                current.copy(
                    vpnTunnelEnabled = true,
                    vpnConnectionState = VpnConnectionState.CONNECTED,
                    customProxyServers = updatedServers
                )
            }
            app.configRepository.syncStealthRulesToHooksNow()
            app.settingsRepository.postStatusMessage("VPN connected and network check passed")
            updateNotification("VPN connected")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            releaseTunnel()
            val message = connectionFailureMessage(stage, failure)
            setState(VpnConnectionState.ERROR)
            (application as IsoVaultApp).settingsRepository.postStatusMessage(
                message
            )
            updateNotification("VPN connection failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            connecting = false
        }
    }

    private fun connectionFailureMessage(stage: String, failure: Throwable): String {
        var rootCause = failure
        while (rootCause.cause != null && rootCause.cause !== rootCause) {
            rootCause = rootCause.cause!!
        }
        val detail = when (rootCause) {
            is IllegalArgumentException -> safeFailureDetail(rootCause.message)
                ?: "The imported VLESS settings are invalid."
            is SecurityException -> "Android denied access to the VPN interface. Approve the VPN permission and retry."
            is java.net.UnknownHostException -> "The server hostname could not be resolved. Check the host and internet connection."
            is java.net.ConnectException -> "The server endpoint could not be reached. Check its address, port, and availability."
            is java.net.SocketTimeoutException -> "The server connection timed out. Check that the node is online and reachable."
            else -> safeFailureDetail(rootCause.message)
                ?: rootCause.javaClass.simpleName.ifBlank { "Unknown system error" }
        }
        return "VPN connection failed while $stage. $detail"
    }

    private fun safeFailureDetail(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val withoutLinks = message.replace(Regex("(?i)vless://[^\\s,]+"), "VLESS link")
        val withoutCredentials = SENSITIVE_VALUE_PATTERN.replace(withoutLinks) {
            "${it.groupValues[1]}=[redacted]"
        }
        val withoutUuid = UUID_PATTERN.replace(withoutCredentials, "[redacted UUID]")
        return withoutUuid.take(220).ifBlank { null }
    }

    private fun stopTunnel() {
        connecting = false
        connectJob?.cancel()
        setState(VpnConnectionState.DISCONNECTED)
        serviceScope.launch {
            connectJob?.join()
            releaseTunnel()
            (application as IsoVaultApp).settingsRepository.postStatusMessage("VPN disconnected")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseTunnel() {
        runCatching { core?.stopLoop() }
        core = null
        runCatching { tunnel?.close() }
        tunnel = null
    }

    private fun setState(state: VpnConnectionState) {
        val app = application as IsoVaultApp
        app.settingsRepository.updateSettings {
            it.copy(
                vpnTunnelEnabled = state == VpnConnectionState.CONNECTED,
                vpnConnectionState = state
            )
        }
        serviceScope.launch { app.configRepository.syncStealthRulesToHooksNow() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Hollow VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "VPN tunnel connection status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startInForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Hollow VPN")
            .setContentText(text)
            .setOngoing(text != "VPN connection failed")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onRevoke() {
        connectJob?.cancel()
        setState(VpnConnectionState.DISCONNECTED)
        serviceScope.launch {
            connectJob?.join()
            releaseTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        connectJob?.cancel()
        if (tunnel != null || core != null) setState(VpnConnectionState.DISCONNECTED)
        releaseTunnel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_CONNECT = "com.hollow.app.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.hollow.app.vpn.DISCONNECT"
        private const val NOTIFICATION_CHANNEL = "hollow_vpn"
        private const val NOTIFICATION_ID = 4701
        private const val TUN_MTU = 1500
        private const val IPV4_ADDRESS = "10.64.0.1"
        private const val IPV6_ADDRESS = "fd00:64::1"
        private const val DNS_IPV4 = "1.1.1.1"
        private const val DNS_IPV6 = "2606:4700:4700::1111"
        private val SENSITIVE_VALUE_PATTERN = Regex(
            "(?i)(uuid|password|pbk|publicKey|privateKey|shortId)(\\s*[:=]\\s*[\"']?)[^\\s,\"'}]+"
        )
        private val UUID_PATTERN = Regex("(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b")
        private val CONNECTIVITY_PROBE_URLS = listOf(
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://www.google.com/generate_204"
        )
    }
}
