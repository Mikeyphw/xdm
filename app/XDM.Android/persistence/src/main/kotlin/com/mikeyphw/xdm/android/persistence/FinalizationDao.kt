package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FinalizationDao {
    @Query("SELECT * FROM finalization_journals ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<FinalizationJournalEntity>>

    @Query("SELECT * FROM finalization_journals WHERE stage != 'Completed' ORDER BY updatedAtEpochMs")
    suspend fun listIncomplete(): List<FinalizationJournalEntity>

    @Query("SELECT * FROM finalization_journals WHERE downloadId = :downloadId LIMIT 1")
    suspend fun findByDownload(downloadId: String): FinalizationJournalEntity?

    @Upsert
    suspend fun upsert(entity: FinalizationJournalEntity)

    @Query("DELETE FROM finalization_journals WHERE downloadId = :downloadId")
    suspend fun deleteByDownload(downloadId: String)
}
