package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationCommandDao {
    @Query("SELECT * FROM automation_commands ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<AutomationCommandEntity>>

    @Query("SELECT * FROM automation_commands WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(idempotencyKey: String): AutomationCommandEntity?

    @Upsert
    suspend fun upsert(entity: AutomationCommandEntity)
}
