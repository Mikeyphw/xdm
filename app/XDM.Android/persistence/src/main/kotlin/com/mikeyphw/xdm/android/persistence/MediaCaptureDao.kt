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

    @Query("SELECT * FROM media_observations ORDER BY lastObservedAtEpochMs DESC LIMIT :limit")
    fun observeObservationEvidence(limit: Int = 384): Flow<List<MediaObservationEntity>>

    @Query("SELECT * FROM media_observations ORDER BY lastObservedAtEpochMs DESC LIMIT :limit")
    suspend fun listObservationEvidence(limit: Int = 384): List<MediaObservationEntity>

    @Query("SELECT * FROM media_outputs ORDER BY updatedAtEpochMs DESC, createdAtEpochMs DESC")
    fun observeOutputs(): Flow<List<MediaOutputEntity>>

    @Query("SELECT * FROM media_outputs WHERE captureId = :captureId ORDER BY createdAtEpochMs DESC")
    suspend fun outputsForCapture(captureId: String): List<MediaOutputEntity>

    @Query("SELECT * FROM media_outputs WHERE id = :id LIMIT 1")
    suspend fun findOutputById(id: String): MediaOutputEntity?

    @Query("SELECT * FROM media_outputs WHERE ownerKind = 'AppDownload' AND downloadId = :downloadId ORDER BY attemptGeneration DESC, createdAtEpochMs DESC")
    suspend fun appOutputsForDownload(downloadId: String): List<MediaOutputEntity>

    @Query("SELECT * FROM media_variants WHERE captureId = :captureId ORDER BY position")
    suspend fun variantsForCapture(captureId: String): List<MediaVariantEntity>

    @Query("SELECT * FROM media_captures WHERE id = :id")
    suspend fun findById(id: String): MediaCaptureEntity?

    @Query("SELECT * FROM media_captures ORDER BY updatedAtEpochMs DESC")
    suspend fun listAll(): List<MediaCaptureEntity>

    @Upsert
    suspend fun upsert(entity: MediaCaptureEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MediaCaptureEntity>)

    @Upsert
    suspend fun upsertVariants(entities: List<MediaVariantEntity>)

    @Upsert
    suspend fun upsertObservationEvidence(entities: List<MediaObservationEntity>)

    @Query("DELETE FROM media_observations WHERE id NOT IN (SELECT id FROM media_observations ORDER BY lastObservedAtEpochMs DESC LIMIT :limit)")
    suspend fun pruneObservationEvidence(limit: Int = 384): Int

    @Upsert
    suspend fun upsertOutput(entity: MediaOutputEntity)

    @Query("""
        UPDATE media_outputs SET
            state = :nextState,
            completedArtifactUri = CASE WHEN :replaceArtifact THEN :completedArtifactUri ELSE completedArtifactUri END,
            completedArtifactGeneration = CASE WHEN :replaceArtifact THEN :completedArtifactGeneration ELSE completedArtifactGeneration END,
            updatedAtEpochMs = :nextRevision
        WHERE id = :id
          AND ownerKind = :ownerKind
          AND ownerId = :ownerId
          AND attemptGeneration = :attemptGeneration
          AND state = :expectedState
          AND updatedAtEpochMs = :expectedRevision
    """)
    suspend fun transitionOutputIfCurrent(
        id: String,
        ownerKind: String,
        ownerId: String,
        attemptGeneration: Long,
        expectedState: String,
        expectedRevision: Long,
        nextState: String,
        completedArtifactUri: String?,
        completedArtifactGeneration: Long?,
        replaceArtifact: Boolean,
        nextRevision: Long,
    ): Int

    @Query("DELETE FROM media_outputs WHERE id = :id")
    suspend fun deleteOutput(id: String): Int

    @Query("UPDATE media_outputs SET state = 'Hidden', updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id AND ownerKind = 'AppDownload'")
    suspend fun hideAppOutput(id: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE media_outputs SET state = 'Hidden', updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id")
    suspend fun hideOutput(id: String, updatedAtEpochMs: Long): Int

    @Query("""UPDATE media_captures
        SET selectedVariantId = :variantId,
            selectedVariantUrl = :variantUrl,
            resolutionStatus = :resolutionStatus,
            lastResolvedAtEpochMs = :updatedAtEpochMs,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :captureId
          AND EXISTS(SELECT 1 FROM media_variants WHERE id = :variantId AND captureId = :captureId)""")
    suspend fun selectVariant(captureId: String, variantId: String, variantUrl: String, resolutionStatus: String, updatedAtEpochMs: Long)

    @Query("""UPDATE media_captures
        SET selectedVariantId = :variantId,
            selectedVariantUrl = :variantUrl,
            resolutionStatus = :resolutionStatus,
            lastResolvedAtEpochMs = :updatedAtEpochMs,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :captureId
          AND (:variantId IS NULL OR EXISTS(SELECT 1 FROM media_variants WHERE id = :variantId AND captureId = :captureId))""")
    suspend fun selectVariantIfVariantExists(captureId: String, variantId: String?, variantUrl: String?, resolutionStatus: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE media_captures SET status = :status, downloadId = :downloadId, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id")
    suspend fun markDownloadCreated(id: String, status: String, downloadId: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE media_captures SET status = :status, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id")
    suspend fun markOutputCreated(id: String, status: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE media_captures SET status = :status, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id")
    suspend fun archive(id: String, status: String, updatedAtEpochMs: Long): Int

    @Query("DELETE FROM media_captures WHERE id = :id")
    suspend fun delete(id: String)
}
