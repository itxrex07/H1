package com.hollow.app.isolation.data.repository

import com.hollow.app.isolation.data.dao.IsolationDao
import com.hollow.app.isolation.data.entity.AccessAuditLog
import com.hollow.app.isolation.util.HollowResult
import com.hollow.app.isolation.util.runHollowCatching
import kotlinx.coroutines.flow.Flow

class AuditRepository(
    private val dao: IsolationDao
) {
    val recentAuditLogs: Flow<List<AccessAuditLog>> = dao.getRecentAuditLogs(100)
    val totalAuditCount: Flow<Int> = dao.getTotalAuditCount()

    fun getLogsForPackage(packageName: String, limit: Int = 50): Flow<List<AccessAuditLog>> =
        dao.getLogsForPackage(packageName, limit)

    suspend fun insertAuditLog(log: AccessAuditLog): HollowResult<Unit> =
        runHollowCatching("AuditRepository") {
            dao.insertAuditLog(log)
        }

    suspend fun clearAuditLogs(): HollowResult<Unit> =
        runHollowCatching("AuditRepository") {
            dao.clearAuditLogs()
        }
}
