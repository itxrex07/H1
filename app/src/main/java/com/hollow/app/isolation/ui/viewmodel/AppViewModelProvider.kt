package com.hollow.app.isolation.ui.viewmodel

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hollow.app.IsoVaultApp
import com.hollow.app.isolation.feature.apphider.AppHiderViewModel
import com.hollow.app.isolation.feature.apps.AppIsolationViewModel
import com.hollow.app.isolation.feature.dashboard.DashboardViewModel
import com.hollow.app.isolation.feature.devicespoofer.DeviceSpooferViewModel
import com.hollow.app.isolation.feature.files.FileManagerViewModel
import com.hollow.app.isolation.feature.gallery.GalleryViewModel
import com.hollow.app.isolation.feature.settings.SettingsViewModel
import com.hollow.app.isolation.feature.vault.VaultViewModel

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            val app = isoVaultApplication()
            DashboardViewModel(
                configRepository = app.configRepository,
                auditRepository = app.auditRepository,
                vaultRepository = app.vaultRepository,
                settingsRepository = app.settingsRepository
            )
        }
        initializer {
            val app = isoVaultApplication()
            AppIsolationViewModel(
                app = app,
                configRepository = app.configRepository,
                vaultRepository = app.vaultRepository,
                settingsRepository = app.settingsRepository
            )
        }
        initializer {
            val app = isoVaultApplication()
            AppHiderViewModel(
                app = app,
                configRepository = app.configRepository,
                settingsRepository = app.settingsRepository
            )
        }
        initializer {
            val app = isoVaultApplication()
            DeviceSpooferViewModel(
                app = app,
                configRepository = app.configRepository,
                settingsRepository = app.settingsRepository
            )
        }
        initializer {
            val app = isoVaultApplication()
            VaultViewModel(
                vaultRepository = app.vaultRepository,
                configRepository = app.configRepository,
                settingsRepository = app.settingsRepository
            )
        }
        initializer {
            val app = isoVaultApplication()
            SettingsViewModel(
                app = app,
                settingsRepository = app.settingsRepository,
                configRepository = app.configRepository
            )
        }
        initializer {
            FileManagerViewModel(isoVaultApplication())
        }
        initializer {
            GalleryViewModel(isoVaultApplication())
        }
    }
}

fun CreationExtras.isoVaultApplication(): IsoVaultApp =
    (this[APPLICATION_KEY] as IsoVaultApp)
