package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NativeHlsDao {
    @Query("SELECT * FROM native_hls_jobs ORDER BY updatedAtEpochMs DESC")
    fun observeJobs(): Flow<List<NativeHlsJobEntity>>

    @Query("SELECT * FROM native_hls_jobs WHERE admissionKey = :admissionKey AND stage != 'Cancelled' ORDER BY attemptGeneration DESC LIMIT 1")
    suspend fun findActiveByAdmissionKey(admissionKey: String): NativeHlsJobEntity?

    @Query("SELECT * FROM native_hls_jobs WHERE id = :jobId")
    suspend fun findJob(jobId: String): NativeHlsJobEntity?

    @Query("SELECT * FROM native_hls_parts WHERE jobId = :jobId ORDER BY partIndex ASC")
    suspend fun partsForJob(jobId: String): List<NativeHlsPartEntity>

    @Upsert
    suspend fun upsertJob(job: NativeHlsJobEntity)

    @Upsert
    suspend fun upsertParts(parts: List<NativeHlsPartEntity>)

    @Query("UPDATE native_hls_jobs SET stage = :stage, finalizationState = :finalizationState, progressPercent = :progressPercent, message = :message, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :jobId")
    suspend fun updateStage(jobId: String, stage: String, finalizationState: String, progressPercent: Int, message: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE native_hls_parts SET state = :state, bytesReceived = :bytesReceived, expectedBytes = :expectedBytes, sha256Hex = :sha256Hex, retryCount = :retryCount, lastError = :lastError, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :partId")
    suspend fun updatePart(partId: String, state: String, bytesReceived: Long, expectedBytes: Long?, sha256Hex: String?, retryCount: Int, lastError: String?, updatedAtEpochMs: Long): Int

    @Query("DELETE FROM native_hls_jobs WHERE stage IN ('Completed','Cancelled') AND updatedAtEpochMs < :olderThanEpochMs")
    suspend fun pruneTerminalJobs(olderThanEpochMs: Long): Int
}
