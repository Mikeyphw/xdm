package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationCommandDao {
    @Query("SELECT * FROM automation_commands ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<AutomationCommandEntity>>

    @Query("SELECT * FROM automation_commands WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(idempotencyKey: String): AutomationCommandEntity?

    @Query("SELECT * FROM automation_commands WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AutomationCommandEntity?

    @Query("""SELECT * FROM automation_commands
        WHERE status IN ('Received', 'Claimed', 'Executing')
          AND downloadId IS NULL AND mediaCaptureId IS NULL
        ORDER BY createdAtEpochMs ASC LIMIT :limit""")
    suspend fun findPending(limit: Int): List<AutomationCommandEntity>

    // XAR03: direct blind upserts are intentionally absent. Mutations must flow through
    // DownloadGraphTransactionDao.upsertAutomationCommandStatefully/transitionAutomationCommand
    // so a replayed automation command cannot regress a newer durable status.
}
