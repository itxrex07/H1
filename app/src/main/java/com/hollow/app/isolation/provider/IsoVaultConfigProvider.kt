package com.hollow.app.isolation.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.hollow.app.IsoVaultApp
import com.hollow.app.isolation.data.entity.AccessAuditLog
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.model.AuditAction
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.model.IsolationRuleManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * IPC channel between hooked target apps, SystemUI, launchers, and the Hollow manager.
 *
 * The provider has to be exported because hooked apps live in other UIDs, so every request is
 * authenticated by the caller's Binder UID instead of by anything the caller says about itself:
 *
 *  - A storage rule request is only served when the package it names belongs to the calling UID.
 *  - Global stealth/disguise table queries (`get_stealth_table`) return the non-sensitive
 *    launcher visibility and spoofed label table so SystemUI and any launcher can apply disguises.
 *  - The manager's own UID may query any package.
 *  - There is no file access of any kind. The provider never opens files on behalf of a caller.
 */
class IsoVaultConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.hollow.app.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/rules")
        private const val TAG = "IsoVaultProvider"

        const val METHOD_CHECK_ISOLATED = "check_isolated"
        const val METHOD_RECORD_AUDIT = "record_audit"
        const val METHOD_GET_STEALTH_TABLE = "get_stealth_table"
        const val METHOD_HEARTBEAT = "heartbeat"

        private const val MAX_FIELD_LENGTH = 512
        private const val MAX_PACKAGE_LENGTH = 255
        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun notifyRuleChanged(context: Context, packageName: String) {
            try {
                val uri = Uri.withAppendedPath(CONTENT_URI, packageName)
                context.contentResolver.notifyChange(uri, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify provider change: ${e.message}")
            }
        }
    }

    override fun onCreate(): Boolean = true

    /** True when the Binder caller is allowed to act on [pkg]. */
    private fun callerMayAccess(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg.length > MAX_PACKAGE_LENGTH || !PACKAGE_REGEX.matches(pkg)) return false
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val owned = ctx.packageManager.getPackagesForUid(uid) ?: return false
        return pkg in owned
    }

    private data class RuleSnapshot(
        val configured: Boolean,
        val isolated: Boolean,
        val mode: String,
        val whitelist: String,
        val blockPollution: Boolean,
        val pickerPolicy: String
    )

    private fun loadRule(pkg: String): RuleSnapshot {
        val app = context?.applicationContext as? IsoVaultApp
        val config = if (app != null) {
            try {
                runBlocking { app.database.isolationDao().getConfigForPackage(pkg) }
            } catch (e: Exception) {
                Log.w(TAG, "Rule lookup failed: ${e.message}")
                null
            }
        } else null

        if (config != null) {
            return RuleSnapshot(
                configured = true,
                isolated = config.isIsolated,
                mode = config.isolationMode.name,
                whitelist = config.whitelistedFolders,
                blockPollution = config.blockRootPollution,
                pickerPolicy = globalPickerPolicy()
            )
        }

        val ctx = context
        val prefs = if (ctx != null) IsolationRuleManager.getReadableSharedPrefs(ctx) else null
        val configured = prefs?.getBoolean("${pkg}_configured", false) ?: false
        return RuleSnapshot(
            configured = configured,
            // Unconfigured apps that reach the hook are isolated by default.
            isolated = if (configured) prefs?.getBoolean("${pkg}_isolated", true) ?: true else true,
            mode = prefs?.getString("${pkg}_mode", IsolationMode.ENCRYPTED_VAULT.name) ?: IsolationMode.ENCRYPTED_VAULT.name,
            whitelist = prefs?.getString("${pkg}_whitelist", "") ?: "",
            blockPollution = prefs?.getBoolean("${pkg}_pollution", true) ?: true,
            pickerPolicy = globalPickerPolicy()
        )
    }

    private fun globalPickerPolicy(): String {
        val ctx = context ?: return "ALLOW"
        return IsolationRuleManager.getReadableSharedPrefs(ctx)
            .getString(IsolationRuleManager.KEY_PICKER_POLICY, "ALLOW") ?: "ALLOW"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf("packageName", "isIsolated", "mode", "whitelistedFolders", "blockPollution", "explicitlyDisabled")
        )
        val pkg = uri.lastPathSegment ?: selectionArgs?.firstOrNull() ?: ""
        if (!callerMayAccess(pkg)) return cursor

        val rule = loadRule(pkg)
        cursor.addRow(
            arrayOf<Any>(
                pkg,
                if (rule.isolated) 1 else 0,
                rule.mode,
                rule.whitelist,
                if (rule.blockPollution) 1 else 0,
                if (rule.configured && !rule.isolated) 1 else 0
            )
        )
        return cursor
    }

    /** True when the Binder caller is allowed to query system-wide stealth and disguise rules. */
    private fun callerMayAccessStealthTable(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) return true
        val ctx = context ?: return false
        val ownedPackages = ctx.packageManager.getPackagesForUid(uid) ?: return false
        for (pkg in ownedPackages) {
            if (pkg.startsWith("com.android.systemui") || pkg == "com.android.settings") return true
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
                .setPackage(pkg)
            if (ctx.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) return true
        }
        return false
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()

        if (method == METHOD_HEARTBEAT || method == "ping") {
            result.putBoolean("alive", true)
            result.putInt("version", 3)
            result.putLong("timestamp", System.currentTimeMillis())
            return result
        }

        if (method == METHOD_GET_STEALTH_TABLE) {
            if (!callerMayAccessStealthTable()) {
                result.putString("error", "denied")
                return result
            }
            val ctx = context ?: return result
            val prefs = IsolationRuleManager.getReadableSharedPrefs(ctx)
            val rawTable = prefs.getString(IsolationRuleManager.KEY_STEALTH_TABLE, "") ?: ""
            val rawIdentityTable = prefs.getString(IsolationRuleManager.KEY_IDENTITY_TABLE, "") ?: ""
            result.putString("stealthTable", rawTable)
            result.putString("identityTable", rawIdentityTable)
            result.putBoolean(
                "systemUiTriggerEnabled",
                prefs.getBoolean(IsolationRuleManager.KEY_SYSTEMUI_TRIGGER, true)
            )
            val iconsBundle = Bundle()
            for ((k, v) in prefs.all) {
                if (k.startsWith("stealth_icon_b64_") && v is String && v.isNotBlank()) {
                    val pkgKey = k.removePrefix("stealth_icon_b64_")
                    iconsBundle.putString(pkgKey, v)
                }
            }
            result.putBundle("customIcons", iconsBundle)
            return result
        }

        val pkg = arg ?: ""
        if (!callerMayAccess(pkg)) {
            result.putString("error", "denied")
            return result
        }

        when (method) {
            METHOD_CHECK_ISOLATED -> {
                val rule = loadRule(pkg)
                result.putBoolean("isConfigured", rule.configured)
                result.putBoolean("isIsolated", rule.isolated)
                result.putBoolean("explicitlyDisabled", rule.configured && !rule.isolated)
                result.putString("mode", rule.mode)
                result.putString("whitelistedFolders", rule.whitelist)
                result.putBoolean("blockPollution", rule.blockPollution)
                result.putString("pickerPolicy", rule.pickerPolicy)
            }

            METHOD_RECORD_AUDIT -> {
                if (extras == null) return result
                val app = context?.applicationContext as? IsoVaultApp ?: return result

                val requestedPath = extras.getString("requestedPath").orEmpty().take(MAX_FIELD_LENGTH)
                val resolvedPath = extras.getString("resolvedPath").orEmpty().take(MAX_FIELD_LENGTH)
                val operation = extras.getString("operation").orEmpty().take(64)
                val details = extras.getString("details").orEmpty().take(MAX_FIELD_LENGTH)
                val action = try {
                    AuditAction.valueOf(extras.getString("action").orEmpty())
                } catch (_: Exception) {
                    AuditAction.REDIRECTED
                }

                app.applicationScope.launch {
                    try {
                        val dao = app.database.isolationDao()
                        var cfg = dao.getConfigForPackage(pkg)
                        if (cfg == null) {
                            cfg = AppIsolationConfig(
                                packageName = pkg,
                                appName = resolveLabel(pkg),
                                isIsolated = true,
                                isolationMode = IsolationMode.ENCRYPTED_VAULT,
                                blockRootPollution = true,
                                whitelistedFolders = "",
                                totalFilesRedirected = 1,
                                lastAccessedTimestamp = System.currentTimeMillis()
                            )
                            dao.insertOrUpdateConfig(cfg)
                        } else {
                            dao.incrementRedirectCount(pkg, System.currentTimeMillis())
                        }

                        dao.insertAuditLog(
                            AccessAuditLog(
                                packageName = pkg,
                                appName = cfg.appName,
                                requestedPath = requestedPath,
                                targetResolvedPath = resolvedPath,
                                action = action,
                                operation = operation,
                                details = details
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record audit log: ${e.message}")
                    }
                }
                result.putBoolean("recorded", true)
            }

            else -> result.putString("error", "unknown_method")
        }
        return result
    }

    private fun resolveLabel(pkg: String): String {
        val pm = context?.packageManager ?: return pkg.substringAfterLast('.')
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.rule"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
