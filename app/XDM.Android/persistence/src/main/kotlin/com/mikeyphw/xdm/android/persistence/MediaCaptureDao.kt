package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaCaptureDao {
    @Query("SELECT * FROM media_captures ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<MediaCaptureEntity>>

    @Query("SELECT * FROM media_variants ORDER BY captureId, position")
    fun observeVariants(): Flow<List<MediaVariantEntity>>

    @Query("SELECT * FROM media_outputs ORDER BY updatedAtEpochMs DESC, createdAtEpochMs DESC")
    fun observeOutputs(): Flow<List<MediaOutputEntity>>

    @Query("SELECT * FROM media_outputs WHERE captureId = :captureId ORDER BY createdAtEpochMs DESC")
    suspend fun outputsForCapture(captureId: String): List<MediaOutputEntity>

    @Query("SELECT * FROM media_outputs WHERE ownerKind = 'AppDownload' AND downloadId = :downloadId ORDER BY attemptGeneration DESC, createdAtEpochMs DESC")
    suspend fun appOutputsForDownload(downloadId: String): List<MediaOutputEntity>

    @Query("SELECT * FROM media_variants WHERE captureId = :captureId ORDER BY position")
    suspend fun variantsForCapture(captureId: String): List<MediaVariantEntity>

    @Query("SELECT * FROM media_captures WHERE id = :id")
    suspend fun findById(id: String): MediaCaptureEntity?

    @Upsert
    suspend fun upsert(entity: MediaCaptureEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MediaCaptureEntity>)

    @Upsert
    suspend fun upsertVariants(entities: List<MediaVariantEntity>)

    @Upsert
    suspend fun upsertOutput(entity: MediaOutputEntity)

    @Query("DELETE FROM media_outputs WHERE id = :id")
    suspend fun deleteOutput(id: String): Int

    @Query("UPDATE media_outputs SET state = 'Hidden', updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id AND ownerKind = 'AppDownload'")
    suspend fun hideAppOutput(id: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE media_captures SET selectedVariantId = :variantId, selectedVariantUrl = :variantUrl, resolutionStatus = :resolutionStatus, lastResolvedAtEpochMs = :updatedAtEpochMs, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :captureId")
    suspend fun selectVariant(captureId: String, variantId: String, variantUrl: String, resolutionStatus: String, updatedAtEpochMs: Long)

    @Query("UPDATE media_captures SET status = :status, downloadId = :downloadId, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id")
    suspend fun markDownloadCreated(id: String, status: String, downloadId: String, updatedAtEpochMs: Long): Int

    @Query("DELETE FROM media_captures WHERE id = :id")
    suspend fun delete(id: String)
}
