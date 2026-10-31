package com.hollow.app.isolation.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hollow.app.isolation.data.dao.IsolationDao
import com.hollow.app.isolation.data.entity.AccessAuditLog
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.data.entity.VaultFileItem
import com.hollow.app.isolation.model.AuditAction
import com.hollow.app.isolation.model.DeviceProfilePreset
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.model.LauncherVisibilityMode

class IsoVaultConverters {
    @TypeConverter
    fun fromIsolationMode(mode: IsolationMode): String = mode.name

    @TypeConverter
    fun toIsolationMode(name: String): IsolationMode = try {
        IsolationMode.valueOf(name)
    } catch (_: Exception) {
        IsolationMode.SHARED_WORKSPACE
    }

    @TypeConverter
    fun fromAuditAction(action: AuditAction): String = action.name

    @TypeConverter
    fun toAuditAction(name: String): AuditAction = try {
        AuditAction.valueOf(name)
    } catch (_: Exception) {
        AuditAction.REDIRECTED
    }

    @TypeConverter
    fun fromLauncherVisibilityMode(mode: LauncherVisibilityMode): String = mode.name

    @TypeConverter
    fun toLauncherVisibilityMode(name: String): LauncherVisibilityMode = try {
        LauncherVisibilityMode.valueOf(name)
    } catch (_: Exception) {
        LauncherVisibilityMode.VISIBLE
    }

    @TypeConverter
    fun fromDisguiseIconPreset(preset: DisguiseIconPreset): String = preset.name

    @TypeConverter
    fun toDisguiseIconPreset(name: String): DisguiseIconPreset = try {
        DisguiseIconPreset.valueOf(name)
    } catch (_: Exception) {
        DisguiseIconPreset.CALCULATOR
    }

    @TypeConverter
    fun fromDeviceProfilePreset(preset: DeviceProfilePreset): String = preset.name

    @TypeConverter
    fun toDeviceProfilePreset(name: String): DeviceProfilePreset = try {
        DeviceProfilePreset.valueOf(name)
    } catch (_: Exception) {
        DeviceProfilePreset.REAL_HARDWARE
    }
}

@Database(
    entities = [
        AppIsolationConfig::class,
        AccessAuditLog::class,
        VaultFileItem::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(IsoVaultConverters::class)
abstract class IsoVaultDatabase : RoomDatabase() {
    abstract fun isolationDao(): IsolationDao

    companion object {
        @Volatile
        private var INSTANCE: IsoVaultDatabase? = null

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val alterStatements = listOf(
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofManufacturer` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofProduct` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofBoard` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofHardware` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofBootloader` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofFingerprint` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofGpuVendor` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofGpuRenderer` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofCpuCores` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofRamGb` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofScreenResolution` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofScreenDpi` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofTimezoneId` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofLocaleTag` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `customUserAgent` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofWebRtc` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofCanvasNoise` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofAudioContext` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofClientHints` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofFontEnumeration` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `vpnProxyEnabled` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `proxyServerId` TEXT NOT NULL DEFAULT 'us_ny_vless'",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `requireVpnForNetwork` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `matchTimezoneWithIp` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `webViewProxyEnabled` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `trustAllCerts` INTEGER NOT NULL DEFAULT 0"
                )
                for (sql in alterStatements) {
                    try {
                        db.execSQL(sql)
                    } catch (_: Exception) {}
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val alterStatements = listOf(
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `privacyProfileId` TEXT NOT NULL DEFAULT 'ghost_cloak'",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `deadmanSwitchEnabled` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofDrmWidevine` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `sensorBiasNeutralizer` INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE `app_isolation_configs` ADD COLUMN `spoofWifiBssid` INTEGER NOT NULL DEFAULT 1"
                )
                for (sql in alterStatements) {
                    try {
                        db.execSQL(sql)
                    } catch (_: Exception) {}
                }
            }
        }

        fun getDatabase(context: Context): IsoVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IsoVaultDatabase::class.java,
                    "isovault_database"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
