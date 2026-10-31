package com.hollow.app.isolation.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hollow.app.isolation.model.AuditAction

@Entity(tableName = "access_audit_logs")
data class AccessAuditLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val requestedPath: String,
    val targetResolvedPath: String,
    val action: AuditAction,
    val operation: String = "OPEN", // OPEN, WRITE, LIST, STAT, UNLINK
    val timestamp: Long = System.currentTimeMillis(),
    val details: String = ""
)
