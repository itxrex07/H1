package com.hollow.app

import android.app.Application
import com.hollow.app.isolation.data.IsoVaultDatabase
import com.hollow.app.isolation.data.repository.AuditRepository
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.data.repository.VaultRepository
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.SettingsStore
import com.hollow.app.isolation.pkg.AppPackageRepository
import com.hollow.app.isolation.service.RootDeadmanWatchdog
import com.hollow.app.isolation.util.runHollowCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IsoVaultApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { IsoVaultDatabase.getDatabase(this) }
    val packageRepository by lazy { AppPackageRepository(this) }

    // Repository layer (DI container singletons)
    val settingsRepository by lazy {
        SettingsRepository(this, applicationScope)
    }
    val auditRepository by lazy {
        AuditRepository(database.isolationDao())
    }
    val vaultRepository by lazy {
        VaultRepository(this, database.isolationDao())
    }
    val configRepository by lazy {
        IsolationConfigRepository(
            context = this,
            dao = database.isolationDao(),
            pkgRepo = packageRepository,
            settingsRepository = settingsRepository,
            appScope = applicationScope
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        RootDeadmanWatchdog.init(this)

        // Re-publish every stored rule so hooked apps see the current state after a reboot or update.
        applicationScope.launch { republishRules() }
    }

    private suspend fun republishRules() {
        runHollowCatching("IsoVaultApp") {
            IsolationRuleManager.publishPickerPolicy(SettingsStore.load(this).pickerPolicy)
            for (cfg in configRepository.configs.first()) {
                val whitelist = cfg.whitelistedFolders.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                if (cfg.isIsolated) {
                    IsolationRuleManager.updateRule(
                        packageName = cfg.packageName,
                        mode = cfg.isolationMode,
                        blockRootPollution = cfg.blockRootPollution,
                        whitelistedFolders = whitelist
                    )
                } else {
                    IsolationRuleManager.disableRule(
                        packageName = cfg.packageName,
                        mode = cfg.isolationMode,
                        blockRootPollution = cfg.blockRootPollution,
                        whitelistedFolders = whitelist
                    )
                }
            }
        }
    }

    companion object {
        lateinit var instance: IsoVaultApp
            private set

        fun instanceOrNull(): IsoVaultApp? = if (::instance.isInitialized) instance else null
    }
}
