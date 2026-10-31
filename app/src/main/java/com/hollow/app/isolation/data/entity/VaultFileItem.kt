package com.hollow.app.isolation.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_files")
data class VaultFileItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val relativePath: String,
    val physicalPath: String,
    val isEncrypted: Boolean = true,
    val originalSizeBytes: Long = 0,
    val encryptedSizeBytes: Long = 0,
    val algorithm: String = "AES-256-GCM",
    val mimeType: String = "application/octet-stream",
    val lastModified: Long = System.currentTimeMillis()
)
