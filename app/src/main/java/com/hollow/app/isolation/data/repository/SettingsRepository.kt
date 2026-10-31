package com.hollow.app.isolation.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import com.hollow.app.MainActivity
import com.hollow.app.isolation.model.AppSettings
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.HollowIconTheme
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.LauncherVisibilityMode
import com.hollow.app.isolation.model.PrivacyProfile
import com.hollow.app.isolation.model.PrivacyProfileStore
import com.hollow.app.isolation.model.RealDeviceHardware
import com.hollow.app.isolation.model.RealHardwareSnapshot
import com.hollow.app.isolation.model.SettingsStore
import com.hollow.app.isolation.mount.MountEntry
import com.hollow.app.isolation.mount.MountManager
import com.hollow.app.isolation.util.runHollowCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared reactive repository for application settings, diagnostics, root status,
 * privacy profiles, status messages, and terminal logs across all focused ViewModels.
 */
class SettingsRepository(
    private val context: Context,
    private val appScope: CoroutineScope
) {
    private val _settings = MutableStateFlow(SettingsStore.load(context))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<String>>(emptyList())
    val terminalLogs: StateFlow<List<String>> = _terminalLogs.asStateFlow()

    private val _systemMounts = MutableStateFlow<List<MountEntry>>(emptyList())
    val systemMounts: StateFlow<List<MountEntry>> = _systemMounts.asStateFlow()

    private val _selinuxStatus = MutableStateFlow("Unknown")
    val selinuxStatus: StateFlow<String> = _selinuxStatus.asStateFlow()

    private val _hookStatus = MutableStateFlow(com.hollow.app.isolation.hook.IsoVaultHookBridge.checkStatus(context))
    val hookStatus: StateFlow<com.hollow.app.isolation.hook.IsoVaultHookBridge.FrameworkStatus> = _hookStatus.asStateFlow()

    private val _isLsposedActive = MutableStateFlow(
        com.hollow.app.isolation.hook.IsoVaultHookBridge.isHookActive() ||
            MainActivity.isLsposedModuleActiveReal() ||
            IsolationRuleManager.isXSharedPrefsHooked(context)
    )
    val isLsposedActive: StateFlow<Boolean> = _isLsposedActive.asStateFlow()

    private val _rootStatus = MutableStateFlow("Not checked")
    val rootStatus: StateFlow<String> = _rootStatus.asStateFlow()

    private val _privacyProfiles = MutableStateFlow(PrivacyProfileStore.loadProfiles(context))
    val privacyProfiles: StateFlow<List<PrivacyProfile>> = _privacyProfiles.asStateFlow()

    val realHardware: RealHardwareSnapshot by lazy { RealDeviceHardware.read(context) }

    init {
        refreshDiagnostics()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun postStatusMessage(msg: String) {
        _statusMessage.value = msg
    }

    fun appendTerminalLog(log: String) {
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        _terminalLogs.update { (it + "[$stamp] $log").takeLast(150) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        _settings.value = next
        appScope.launch(Dispatchers.IO) {
            runHollowCatching("SettingsRepository") {
                SettingsStore.save(context, next)
            }
        }
        return next
    }

    fun refreshDiagnostics() {
        appScope.launch(Dispatchers.IO) {
            runHollowCatching("SettingsRepository") {
                val status = com.hollow.app.isolation.hook.IsoVaultHookBridge.checkStatus(context)
                _hookStatus.value = status
                _systemMounts.value = MountManager.readSystemMounts()
                _selinuxStatus.value = MountManager.getSelinuxStatus()
                _isLsposedActive.value = status.isHookActive
            }
        }
    }

    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        _rootStatus.value = "Checking..."
        val diag = runHollowCatching("SettingsRepository") {
            MountManager.getDetailedRootDiagnostics()
        }.getOrNull()
        val granted = diag?.isRootAvailable ?: false
        _rootStatus.value = if (granted) "Root UID 0 verified (${diag?.suVersion})" else "No root access"
        if (diag != null) {
            appendTerminalLog("Root Diagnostic:\n${diag.details}")
        }
        granted
    }

    suspend fun decodeImageUriToBase64(uri: Uri): String? = withContext(Dispatchers.IO) {
        runHollowCatching("SettingsRepository") {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val rawBmp = BitmapFactory.decodeStream(stream)
                if (rawBmp != null) {
                    IsolationRuleManager.bitmapToBase64Png(rawBmp, 144)
                } else null
            }
        }.getOrNull()
    }

    fun applyNativeHollowAlias(
        visibilityMode: LauncherVisibilityMode,
        iconPreset: DisguiseIconPreset,
        iconTheme: HollowIconTheme = _settings.value.hollowIconTheme
    ) {
        runHollowCatching("SettingsRepository") {
            val pm = context.packageManager
            val mainComp = ComponentName(context, "com.hollow.app.MainActivity")
            val cyberAlias = ComponentName(context, "com.hollow.app.MainActivityCyberAlias")
            val crimsonAlias = ComponentName(context, "com.hollow.app.MainActivityCrimsonAlias")
            val minimalAlias = ComponentName(context, "com.hollow.app.MainActivityMinimalAlias")
            val calcAlias = ComponentName(context, "com.hollow.app.MainActivityCalculatorAlias")
            val notesAlias = ComponentName(context, "com.hollow.app.MainActivityNotesAlias")

            val allThemeAliases = listOf(cyberAlias, crimsonAlias, minimalAlias)
            val allDisguiseAliases = listOf(calcAlias, notesAlias)

            when (visibilityMode) {
                LauncherVisibilityMode.VISIBLE -> {
                    allDisguiseAliases.forEach {
                        pm.setComponentEnabledSetting(
                            it,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                    val targetAliasClass = iconTheme.aliasClassName
                    if (targetAliasClass == null) {
                        pm.setComponentEnabledSetting(
                            mainComp,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        allThemeAliases.forEach {
                            pm.setComponentEnabledSetting(
                                it,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP
                            )
                        }
                    } else {
                        pm.setComponentEnabledSetting(
                            mainComp,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        allThemeAliases.forEach { alias ->
                            val state = if (alias.className == targetAliasClass) {
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            } else {
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            }
                            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
                        }
                    }
                }
                LauncherVisibilityMode.DISGUISED -> {
                    allThemeAliases.forEach {
                        pm.setComponentEnabledSetting(
                            it,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                    val useCustomHookIcon = iconPreset == DisguiseIconPreset.CUSTOM_IMAGE ||
                        iconPreset == DisguiseIconPreset.CLONED_APP
                    if (useCustomHookIcon) {
                        pm.setComponentEnabledSetting(
                            mainComp,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        allDisguiseAliases.forEach {
                            pm.setComponentEnabledSetting(
                                it,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP
                            )
                        }
                    } else {
                        val useNotes = iconPreset == DisguiseIconPreset.NOTES
                        pm.setComponentEnabledSetting(
                            mainComp,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        pm.setComponentEnabledSetting(
                            calcAlias,
                            if (useNotes) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        pm.setComponentEnabledSetting(
                            notesAlias,
                            if (useNotes) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
                LauncherVisibilityMode.HIDDEN -> {
                    pm.setComponentEnabledSetting(
                        mainComp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    (allThemeAliases + allDisguiseAliases).forEach {
                        pm.setComponentEnabledSetting(
                            it,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
            }
        }
    }

    fun savePrivacyProfile(profile: PrivacyProfile) {
        val current = _privacyProfiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile
        } else {
            current.add(profile)
        }
        _privacyProfiles.value = current
        PrivacyProfileStore.saveProfiles(context, current)
        postStatusMessage("Privacy Profile '${profile.name}' saved.")
    }

    fun deletePrivacyProfile(profileId: String) {
        val current = _privacyProfiles.value.filterNot { it.id == profileId && !it.isBuiltIn }
        _privacyProfiles.value = current
        PrivacyProfileStore.saveProfiles(context, current)
        postStatusMessage("Profile removed.")
    }

    fun resetPrivacyProfilesToDefaults() {
        val defaults = PrivacyProfileStore.resetToDefaults(context)
        _privacyProfiles.value = defaults
        postStatusMessage("Reset all Privacy Profiles to default presets.")
    }
}
