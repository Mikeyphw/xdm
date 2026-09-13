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

    @Query("SELECT * FROM finalization_journals WHERE downloadId = :downloadId ORDER BY attemptGeneration DESC LIMIT 1")
    suspend fun findByDownload(downloadId: String): FinalizationJournalEntity?

    @Query("SELECT * FROM finalization_journals WHERE downloadId = :downloadId AND attemptGeneration = :attemptGeneration LIMIT 1")
    suspend fun findByDownloadAttempt(downloadId: String, attemptGeneration: Long): FinalizationJournalEntity?

    @Upsert
    suspend fun upsert(entity: FinalizationJournalEntity)

    @Query("DELETE FROM finalization_journals WHERE downloadId = :downloadId")
    suspend fun deleteByDownload(downloadId: String)

    @Query("DELETE FROM finalization_journals WHERE downloadId = :downloadId AND attemptGeneration = :attemptGeneration")
    suspend fun deleteByDownloadAttempt(downloadId: String, attemptGeneration: Long): Int
}
