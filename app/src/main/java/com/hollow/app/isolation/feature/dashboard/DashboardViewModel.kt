package com.hollow.app.isolation.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.isolation.data.entity.AccessAuditLog
import com.hollow.app.isolation.data.repository.AuditRepository
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.data.repository.VaultRepository
import com.hollow.app.isolation.model.AppSettings
import com.hollow.app.isolation.pkg.InstalledAppInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val configRepository: IsolationConfigRepository,
    private val auditRepository: AuditRepository,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val isLsposedActive: StateFlow<Boolean> = settingsRepository.isLsposedActive
    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val statusMessage: StateFlow<String?> = settingsRepository.statusMessage
    val allInstalledApps: StateFlow<List<InstalledAppInfo>> = configRepository.allInstalledApps

    val isolatedCount: StateFlow<Int> = configRepository.isolatedCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val hiddenOrDisguisedCount: StateFlow<Int> = configRepository.hiddenOrDisguisedCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val identitySpoofedCount: StateFlow<Int> = configRepository.identitySpoofedCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val recentAuditLogs: StateFlow<List<AccessAuditLog>> = auditRepository.recentAuditLogs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val totalEncryptedBytes: StateFlow<Long> = vaultRepository.totalEncryptedBytes
        .map { it ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun refreshDiagnostics() {
        viewModelScope.launch {
            settingsRepository.refreshDiagnostics()
        }
    }

    fun clearAuditLogs() {
        viewModelScope.launch {
            auditRepository.clearAuditLogs()
            settingsRepository.postStatusMessage("Cleared all audit logs")
        }
    }

    fun clearStatusMessage() {
        settingsRepository.clearStatusMessage()
    }
}
