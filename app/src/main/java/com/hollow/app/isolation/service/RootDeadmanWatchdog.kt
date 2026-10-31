package com.hollow.app.isolation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hollow.app.MainActivity
import com.hollow.app.R
import com.hollow.app.isolation.mount.MountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class DeadmanIncident(
    val id: String = java.util.UUID.randomUUID().toString(),
    val packageName: String,
    val appName: String,
    val appUid: Int,
    val triggerVector: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis(),
    val networkSevered: Boolean = true
)

/**
 * Total Identity Leak Deadman Switch Engine.
 *
 * If an app attempts to read real hardware identity (Android ID, IMEI, real MAC, DRM Widevine ID,
 * proc/sys files) or bypass LSPosed hooks:
 * 1. Process is instantly terminated (am force-stop + kill -9).
 * 2. Network traffic is blackholed at the kernel level via iptables.
 * 3. A high-priority system alert notification is posted.
 */
object RootDeadmanWatchdog {

    private const val TAG = "DeadmanWatchdog"
    const val CHANNEL_ID = "deadman_security_alerts"
    private const val NOTIFICATION_ID_BASE = 88000

    private val blockedUids = ConcurrentHashMap<Int, DeadmanIncident>()
    private val incidentsList = mutableListOf<DeadmanIncident>()

    private val _blockedUidsFlow = MutableStateFlow<List<DeadmanIncident>>(emptyList())
    val blockedUidsFlow: StateFlow<List<DeadmanIncident>> = _blockedUidsFlow.asStateFlow()

    private val watchdogScope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        createNotificationChannel(context)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Identity Leak Deadman Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority security notifications when an app attempts to leak real hardware identity"
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Executes the 3-step fail-safe deadman routine:
     * 1. Force-stop app process
     * 2. Sever app network via root iptables
     * 3. Send high-priority alert notification
     */
    fun triggerDeadmanSwitch(
        context: Context,
        packageName: String,
        appName: String,
        appUid: Int,
        triggerVector: String,
        details: String
    ) {
        Log.w(TAG, "DEADMAN TRIGGERED: $packageName (UID: $appUid) vector: $triggerVector - $details")

        val incident = DeadmanIncident(
            packageName = packageName,
            appName = appName,
            appUid = appUid,
            triggerVector = triggerVector,
            details = details
        )

        blockedUids[appUid] = incident
        synchronized(incidentsList) {
            incidentsList.add(0, incident)
            if (incidentsList.size > 50) incidentsList.removeAt(incidentsList.lastIndex)
        }
        _blockedUidsFlow.value = blockedUids.values.toList()

        watchdogScope.launch {
            // Step 1: Kill target process immediately
            if (MountManager.isRootAvailable()) {
                MountManager.runAsRoot("am force-stop $packageName")
                MountManager.runAsRoot("pkill -9 -f $packageName")
            }

            // Step 2: Blackhole network packets via iptables
            if (appUid > 0 && MountManager.isRootAvailable()) {
                MountManager.runAsRoot("iptables -I OUTPUT -m owner --uid-owner $appUid -j DROP")
                MountManager.runAsRoot("ip6tables -I OUTPUT -m owner --uid-owner $appUid -j DROP")
            }

            // Step 3: Trigger High-Priority System Notification
            showLeakAlertNotification(context, incident)
        }
    }

    private fun showLeakAlertNotification(context: Context, incident: DeadmanIncident) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "privacy")
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                incident.appUid,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Identity Leak Attempt Blocked")
                .setContentText("${incident.appName} tried to access ${incident.triggerVector}. Process terminated & network dropped.")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "CRITICAL LEAK SHIELD: ${incident.appName} (${incident.packageName}) attempted to access ${incident.triggerVector}.\n" +
                                    "Actions executed:\n" +
                                    "• Process terminated (am force-stop)\n" +
                                    "• Outbound network blocked via iptables\n" +
                                    "• Identity protected: Real device attributes intact."
                        )
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val manager = NotificationManagerCompat.from(context)
            try {
                manager.notify(NOTIFICATION_ID_BASE + (incident.appUid % 1000), builder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission not granted: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send leak notification: ${e.message}", e)
        }
    }

    /**
     * Restores network connectivity for a previously severed app UID.
     */
    fun restoreAppNetwork(appUid: Int) {
        blockedUids.remove(appUid)
        _blockedUidsFlow.value = blockedUids.values.toList()

        watchdogScope.launch {
            if (appUid > 0 && MountManager.isRootAvailable()) {
                MountManager.runAsRoot("iptables -D OUTPUT -m owner --uid-owner $appUid -j DROP")
                MountManager.runAsRoot("ip6tables -D OUTPUT -m owner --uid-owner $appUid -j DROP")
            }
        }
    }

    /**
     * Restores all network blackholes.
     */
    fun restoreAllNetworks() {
        val uids = blockedUids.keys.toList()
        blockedUids.clear()
        _blockedUidsFlow.value = emptyList()

        watchdogScope.launch {
            if (MountManager.isRootAvailable()) {
                for (uid in uids) {
                    MountManager.runAsRoot("iptables -D OUTPUT -m owner --uid-owner $uid -j DROP")
                    MountManager.runAsRoot("ip6tables -D OUTPUT -m owner --uid-owner $uid -j DROP")
                }
            }
        }
    }

    /**
     * Testing / simulation function for Deadman Switch.
     */
    fun simulateIdentityLeak(context: Context, packageName: String, appName: String, uid: Int) {
        triggerDeadmanSwitch(
            context = context,
            packageName = packageName,
            appName = appName,
            appUid = uid,
            triggerVector = "Direct /proc/cpuinfo & Native DRM Read",
            details = "Simulated identity leak bypass attempt intercepted by LSPosed & Root Watchdog"
        )
    }
}
