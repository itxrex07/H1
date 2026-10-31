package com.hollow.app.isolation.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hollow.app.IsoVaultApp
import com.hollow.app.isolation.data.repository.AuditRepository
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.data.repository.VaultRepository

/**
 * Thin compatibility shell delegating to domain-specific repositories.
 * All screens now bind directly to focused ViewModels via [AppViewModelProvider.Factory]:
 * - [DashboardViewModel]
 * - [AppIsolationViewModel]
 * - [AppHiderViewModel]
 * - [DeviceSpooferViewModel]
 * - [VaultViewModel]
 * - [SettingsViewModel]
 */
class IsoVaultViewModel(application: Application) : AndroidViewModel(application) {
    private val isoApp: IsoVaultApp = application as IsoVaultApp

    val settingsRepository: SettingsRepository get() = isoApp.settingsRepository
    val configRepository: IsolationConfigRepository get() = isoApp.configRepository
    val vaultRepository: VaultRepository get() = isoApp.vaultRepository
    val auditRepository: AuditRepository get() = isoApp.auditRepository
}
