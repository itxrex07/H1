package com.hollow.app.isolation.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.hollow.app.MainActivity
import com.hollow.app.isolation.model.IsolationRuleManager

/**
 * High-reliability active heartbeat and status bridge between the Hollow manager app
 * and the LSPosed / Vector Xposed framework module.
 */
object IsoVaultHookBridge {

    private const val TAG = "IsoVaultHookBridge"
    const val HOOK_SPEC_VERSION = 3

    /**
     * Hooked at runtime by StorageIsolationHook in zygote / app process.
     * Returns true when the LSPosed module is loaded and actively intercepting calls.
     */
    @JvmStatic
    fun isHookActive(): Boolean {
        return false // Hooked by StorageIsolationHook to return true
    }

    /**
     * Hooked at runtime by StorageIsolationHook to return the active module build version.
     */
    @JvmStatic
    fun getHookVersion(): Int {
        return 0 // Hooked by StorageIsolationHook to return HOOK_SPEC_VERSION
    }

    /**
     * Hooked at runtime by StorageIsolationHook to return heartbeat millisecond timestamp.
     */
    @JvmStatic
    fun getHeartbeatTimestamp(): Long {
        return 0L // Hooked by StorageIsolationHook to return System.currentTimeMillis()
    }

    data class FrameworkStatus(
        val isHookActive: Boolean,
        val isSharedPrefsHooked: Boolean,
        val isProviderAlive: Boolean,
        val hookVersion: Int,
        val lastHeartbeatMs: Long,
        val summary: String
    )

    /**
     * Multi-layered diagnostic check:
     * 1. Direct in-memory method interception via XposedBridge.
     * 2. XSharedPreferences world-readable mode check.
     * 3. ContentProvider IPC heartbeat check.
     */
    fun checkStatus(context: Context): FrameworkStatus {
        val hookActive = isHookActive() || MainActivity.isLsposedModuleActiveReal()
        val prefsHooked = IsolationRuleManager.isXSharedPrefsHooked(context)
        var providerAlive = false
        var providerVersion = 0

        try {
            val providerUri = Uri.parse("content://${IsolationRuleManager.MODULE_PKG}.provider")
            val bundle = context.contentResolver.call(
                providerUri,
                "heartbeat",
                null,
                Bundle()
            )
            if (bundle != null && bundle.getBoolean("alive", false)) {
                providerAlive = true
                providerVersion = bundle.getInt("version", 0)
            }
        } catch (e: Exception) {
            Log.d(TAG, "ContentProvider heartbeat check: ${e.message}")
        }

        val effectiveActive = hookActive || prefsHooked
        val activeVersion = if (hookActive) getHookVersion() else providerVersion
        val heartbeat = if (hookActive) getHeartbeatTimestamp() else System.currentTimeMillis()

        val summary = when {
            hookActive && prefsHooked -> "Active (LSPosed hooks & XSharedPrefs operational)"
            hookActive -> "Active (LSPosed in-memory hooks active)"
            prefsHooked -> "Active (XSharedPrefs hook bridge operational)"
            providerAlive -> "Standby (Provider IPC ready, module reboot needed)"
            else -> "Inactive (Module not loaded or scope not selected)"
        }

        return FrameworkStatus(
            isHookActive = effectiveActive,
            isSharedPrefsHooked = prefsHooked,
            isProviderAlive = providerAlive,
            hookVersion = if (activeVersion > 0) activeVersion else HOOK_SPEC_VERSION,
            lastHeartbeatMs = heartbeat,
            summary = summary
        )
    }
}
