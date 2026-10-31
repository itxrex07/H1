package com.hollow.app.isolation.feature.apphider

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.LauncherVisibilityMode
import com.hollow.app.isolation.mount.MountManager
import com.hollow.app.isolation.pkg.InstalledAppInfo
import com.hollow.app.isolation.util.onFailure
import com.hollow.app.isolation.util.runHollowCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppHiderViewModel(
    private val app: Application,
    private val configRepository: IsolationConfigRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val allInstalledApps: StateFlow<List<InstalledAppInfo>> = configRepository.allInstalledApps

    val hiddenOrDisguisedCount: StateFlow<Int> = configRepository.hiddenOrDisguisedCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

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

    fun saveAppStealthConfig(
        appInfo: InstalledAppInfo,
        visibilityMode: LauncherVisibilityMode,
        customLabel: String,
        iconPreset: DisguiseIconPreset,
        hideInRecents: Boolean = true,
        hideFromPlayStore: Boolean = false,
        spoofInSettings: Boolean = true,
        customIconBase64: String = "",
        customIconDonorPkg: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(appInfo.packageName) ?: AppIsolationConfig(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                customMountPath = MountManager.volumeDir(app, appInfo.packageName).absolutePath
            )
            var resolvedBase64 = customIconBase64
            if (visibilityMode == LauncherVisibilityMode.DISGUISED &&
                iconPreset == DisguiseIconPreset.CLONED_APP &&
                customIconDonorPkg.isNotBlank() &&
                resolvedBase64.isBlank()
            ) {
                val donorApp = allInstalledApps.value.find { it.packageName == customIconDonorPkg }
                if (donorApp?.icon != null) {
                    resolvedBase64 = IsolationRuleManager.drawableToBase64Png(donorApp.icon)
                }
            }
            val isStealthActive = visibilityMode != LauncherVisibilityMode.VISIBLE || hideFromPlayStore
            val updated = existing.copy(
                isHiddenOrDisguised = isStealthActive,
                launcherVisibilityMode = visibilityMode,
                customLauncherLabel = customLabel.trim(),
                disguiseIconPreset = iconPreset,
                hideInSystemUiRecents = hideInRecents,
                hideFromPlayStore = hideFromPlayStore,
                spoofInSettings = spoofInSettings,
                customIconBase64 = resolvedBase64,
                customIconDonorPkg = customIconDonorPkg,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.syncStealthRulesToHooksNow()

            MountManager.refreshLaunchersAndStealthCaches(
                context = app,
                refreshPlayStore = hideFromPlayStore != existing.hideFromPlayStore,
                refreshSettings = spoofInSettings != existing.spoofInSettings
            )

            val summary = when (visibilityMode) {
                LauncherVisibilityMode.HIDDEN -> "Hidden from launcher"
                LauncherVisibilityMode.DISGUISED -> "Disguised as ${customLabel.ifBlank { iconPreset.suggestedName.ifBlank { appInfo.appName } }}"
                LauncherVisibilityMode.VISIBLE -> if (hideFromPlayStore) "Hidden from Play Store" else "Restored normal visibility"
            }
            settingsRepository.postStatusMessage("${appInfo.appName}: $summary")
        }
    }

    fun removeAppFromStealth(appInfo: InstalledAppInfo) {
        saveAppStealthConfig(
            appInfo = appInfo,
            visibilityMode = LauncherVisibilityMode.VISIBLE,
            customLabel = "",
            iconPreset = DisguiseIconPreset.CALCULATOR,
            hideInRecents = false,
            hideFromPlayStore = false,
            spoofInSettings = false,
            customIconBase64 = "",
            customIconDonorPkg = ""
        )
    }

    fun launchAppFromHollow(appInfo: InstalledAppInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runHollowCatching("AppHiderViewModel") {
                    app.startActivity(launchIntent)
                    settingsRepository.postStatusMessage("Launched ${appInfo.appName} from Hollow")
                }.onFailure { failure ->
                    settingsRepository.postStatusMessage("Launch error: ${failure.message}")
                }
            } else {
                settingsRepository.postStatusMessage("Cannot launch ${appInfo.appName}: no launchable activity")
            }
        }
    }

    fun refreshLauncherNow() {
        viewModelScope.launch(Dispatchers.IO) {
            configRepository.syncStealthRulesToHooksNow()
            val ok = MountManager.refreshLaunchersAndStealthCaches(
                context = app,
                refreshPlayStore = true,
                refreshSettings = true
            )
            settingsRepository.postStatusMessage(
                if (ok) "Refreshed Home Launcher & Stealth caches"
                else "Synced stealth rules to LSPosed hooks"
            )
        }
    }
}
