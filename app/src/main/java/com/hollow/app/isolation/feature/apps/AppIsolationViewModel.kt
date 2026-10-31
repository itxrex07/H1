package com.hollow.app.isolation.feature.apps

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.data.repository.VaultRepository
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.mount.MountManager
import com.hollow.app.isolation.pkg.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppListFilter {
    ALL, ISOLATED_ONLY, USER_APPS, SYSTEM_APPS
}

class AppIsolationViewModel(
    private val app: Application,
    private val configRepository: IsolationConfigRepository,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(AppListFilter.ISOLATED_ONLY)
    val filter: StateFlow<AppListFilter> = _filter.asStateFlow()

    private val _selectedAppForDetail = MutableStateFlow<InstalledAppInfo?>(null)
    val selectedAppForDetail: StateFlow<InstalledAppInfo?> = _selectedAppForDetail.asStateFlow()

    val configs: StateFlow<List<AppIsolationConfig>> = configRepository.configs

    val isolatedCount: StateFlow<Int> = configRepository.isolatedCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val allInstalledApps: StateFlow<List<InstalledAppInfo>> = configRepository.allInstalledApps

    val filteredApps: StateFlow<List<InstalledAppInfo>> = combine(
        allInstalledApps,
        _searchQuery,
        _filter
    ) { merged, query, filterMode ->
        merged.filter { info ->
            val matchesQuery = query.isBlank() ||
                info.appName.contains(query, ignoreCase = true) ||
                info.packageName.contains(query, ignoreCase = true)
            val matchesFilter = when (filterMode) {
                AppListFilter.ALL -> true
                AppListFilter.ISOLATED_ONLY -> info.isIsolated
                AppListFilter.USER_APPS -> !info.isSystemApp
                AppListFilter.SYSTEM_APPS -> info.isSystemApp
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filterMode: AppListFilter) {
        _filter.value = filterMode
    }

    fun selectAppForDetail(appInfo: InstalledAppInfo?) {
        _selectedAppForDetail.value = appInfo
    }

    fun loadInstalledApps() {
        configRepository.loadInstalledApps()
    }

    fun toggleAppIsolation(appInfo: InstalledAppInfo, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(appInfo.packageName)
            val newConfig = (existing ?: AppIsolationConfig(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                customMountPath = MountManager.volumeDir(app, appInfo.packageName).absolutePath
            )).copy(
                isIsolated = enabled,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(newConfig)
            configRepository.notifyConfigChanged()

            if (enabled) {
                configRepository.publishEnabledRule(newConfig)
                settingsRepository.postStatusMessage("Isolated ${appInfo.appName}")
            } else {
                configRepository.disableRule(newConfig)
                settingsRepository.postStatusMessage("Disabled isolation for ${appInfo.appName}")
            }
            settingsRepository.refreshDiagnostics()
            refreshSelectedApp(appInfo.packageName)
        }
    }

    fun updateAppIsolationMode(packageName: String, mode: IsolationMode) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(packageName) ?: return@launch
            val updated = existing.copy(
                isolationMode = mode,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.notifyConfigChanged()
            if (updated.isIsolated) {
                configRepository.publishEnabledRule(updated)
            }
            settingsRepository.postStatusMessage("${existing.appName}: mode set to ${mode.title}")
            refreshSelectedApp(packageName)
        }
    }

    fun updateRootPollution(packageName: String, blockPollution: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(packageName) ?: return@launch
            val updated = existing.copy(
                blockRootPollution = blockPollution,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.notifyConfigChanged()
            if (updated.isIsolated) {
                configRepository.publishEnabledRule(updated)
            }
            refreshSelectedApp(packageName)
        }
    }

    fun updateEncryptMediaOnly(packageName: String, redirectMediaPicker: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(packageName) ?: return@launch
            val updated = existing.copy(
                encryptMediaOnly = redirectMediaPicker,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.notifyConfigChanged()
            if (updated.isIsolated) {
                configRepository.publishEnabledRule(updated)
            }
            settingsRepository.postStatusMessage("Picker isolation ${if (redirectMediaPicker) "enabled" else "disabled"} for ${existing.appName}")
            refreshSelectedApp(packageName)
        }
    }

    fun updateWhitelistedFolders(packageName: String, foldersCsv: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configRepository.getConfigForPackage(packageName) ?: return@launch
            val updated = existing.copy(
                whitelistedFolders = foldersCsv,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            configRepository.insertOrUpdateConfig(updated)
            configRepository.notifyConfigChanged()
            if (updated.isIsolated) {
                configRepository.publishEnabledRule(updated)
            }
            settingsRepository.postStatusMessage("Updated folder rules for ${existing.appName}")
            refreshSelectedApp(packageName)
        }
    }

    fun attachVolume(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (MountManager.isSealed(app, packageName)) {
                val unseal = MountManager.unsealVolume(app, packageName)
                settingsRepository.appendTerminalLog("${unseal.command} -> ${unseal.output}")
                if (!unseal.isSuccess) {
                    settingsRepository.postStatusMessage(unseal.output)
                    return@launch
                }
            }
            val res = MountManager.attachVolume(app, packageName)
            settingsRepository.appendTerminalLog("${res.command} -> ${res.output}")
            settingsRepository.postStatusMessage(res.output)
            settingsRepository.refreshDiagnostics()
            refreshSelectedApp(packageName)
        }
    }

    fun detachVolume(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = MountManager.detachVolume(app, packageName)
            settingsRepository.appendTerminalLog("${res.command} -> ${res.output}")
            if (settingsRepository.settings.value.encryptVolumesAtRest) {
                val seal = MountManager.sealVolume(app, packageName)
                settingsRepository.appendTerminalLog("${seal.command} -> ${seal.output}")
                settingsRepository.postStatusMessage(seal.output)
            } else {
                settingsRepository.postStatusMessage(res.output)
            }
            settingsRepository.refreshDiagnostics()
            refreshSelectedApp(packageName)
        }
    }

    fun purgeVaultForPackage(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = MountManager.purgeVolume(app, packageName)
            vaultRepository.deleteVaultFilesForPackage(packageName)
            settingsRepository.appendTerminalLog("${res.command} -> ${res.output}")
            settingsRepository.postStatusMessage(res.output)
        }
    }

    private fun refreshSelectedApp(packageName: String) {
        val current = _selectedAppForDetail.value ?: return
        if (current.packageName == packageName) {
            val updated = allInstalledApps.value.find { it.packageName == packageName }
            if (updated != null) {
                _selectedAppForDetail.value = updated
            }
        }
    }
}
