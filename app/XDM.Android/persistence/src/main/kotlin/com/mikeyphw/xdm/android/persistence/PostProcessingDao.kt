package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PostProcessingDao {
    @Query("SELECT * FROM post_processing_jobs ORDER BY updatedAtEpochMs DESC")
    fun observeJobs(): Flow<List<PostProcessingJobEntity>>

    @Query("SELECT * FROM post_processing_claims ORDER BY createdAtEpochMs DESC")
    fun observeClaims(): Flow<List<PostProcessingClaimEntity>>

    @Query("SELECT * FROM post_processing_jobs WHERE id = :jobId LIMIT 1")
    suspend fun findJob(jobId: String): PostProcessingJobEntity?

    @Query("SELECT * FROM post_processing_jobs WHERE runId = :runId LIMIT 1")
    suspend fun findJobByRunId(runId: String): PostProcessingJobEntity?

    @Query("SELECT * FROM post_processing_claims WHERE claimKey = :claimKey LIMIT 1")
    suspend fun findClaim(claimKey: String): PostProcessingClaimEntity?

    @Query("SELECT * FROM post_processing_jobs WHERE status IN ('Queued', 'WaitingForPrerequisites', 'Preparing', 'Running', 'Publishing', 'Paused', 'Cancelling') ORDER BY createdAtEpochMs")
    suspend fun activeJobs(): List<PostProcessingJobEntity>


    @Query("SELECT * FROM post_processing_jobs WHERE status = 'WaitingForPrerequisites' ORDER BY createdAtEpochMs")
    suspend fun waitingJobs(): List<PostProcessingJobEntity>

    @Query("SELECT * FROM post_processing_jobs WHERE downloadId = :downloadId ORDER BY createdAtEpochMs")
    suspend fun jobsForDownload(downloadId: String): List<PostProcessingJobEntity>

    @Query("""SELECT * FROM post_processing_jobs
        WHERE downloadId = :downloadId
           OR captureId IN (SELECT id FROM media_captures WHERE downloadId = :downloadId)
        ORDER BY createdAtEpochMs""")
    suspend fun jobsForDownloadGraph(downloadId: String): List<PostProcessingJobEntity>

    @Query("""UPDATE post_processing_jobs
        SET inputBridgeUri = NULL,
            outputBridgeUri = NULL,
            ownerBridgeUri = NULL,
            progressBridgeUri = NULL,
            metadataBridgeUri = NULL,
            payloadBridgeUri = NULL,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Completed', 'Failed', 'Cancelled', 'TimedOut')""")
    suspend fun clearTerminalBridgeUris(jobId: String, updatedAtEpochMs: Long): Int


    @Query("""UPDATE post_processing_jobs
        SET status = 'Preparing',
            requestedControl = NULL,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Queued', 'WaitingForPrerequisites', 'RecoveryRequired')""")
    suspend fun reserveLaunch(jobId: String, message: String, updatedAtEpochMs: Long): Int

    @Query("SELECT * FROM post_processing_jobs WHERE status = 'Failed' ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun latestFailedJob(): PostProcessingJobEntity?

    @Query("SELECT MAX(attemptGeneration) FROM post_processing_jobs WHERE rootJobId = :rootJobId")
    suspend fun maxAttemptGeneration(rootJobId: String): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClaimIgnore(entity: PostProcessingClaimEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(entity: PostProcessingJobEntity)

    @Upsert
    suspend fun upsertJob(entity: PostProcessingJobEntity)

    @Query("DELETE FROM post_processing_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)

    @Transaction
    suspend fun claimAndInsert(claim: PostProcessingClaimEntity, job: PostProcessingJobEntity): Boolean {
        if (findClaim(claim.claimKey) != null) return false
        insertJob(job)
        if (insertClaimIgnore(claim) == -1L) {
            deleteJob(job.id)
            return false
        }
        return true
    }

    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            stagedInputPath = :stagedInputPath,
            inputBridgeUri = :inputBridgeUri,
            stagedOutputPath = :stagedOutputPath,
            outputBridgeUri = :outputBridgeUri,
            ownerBridgeUri = :ownerBridgeUri,
            progressBridgeUri = :progressBridgeUri,
            metadataBridgeUri = :metadataBridgeUri,
            payloadBridgeUri = :payloadBridgeUri,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Queued', 'WaitingForPrerequisites', 'Preparing', 'RecoveryRequired')""")
    suspend fun attachPreparedArtifacts(
        jobId: String,
        status: String,
        stagedInputPath: String?,
        inputBridgeUri: String?,
        stagedOutputPath: String?,
        outputBridgeUri: String?,
        ownerBridgeUri: String?,
        progressBridgeUri: String?,
        metadataBridgeUri: String?,
        payloadBridgeUri: String?,
        message: String,
        updatedAtEpochMs: Long,
    ): Int


    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            timeoutAtEpochMs = :timeoutAtEpochMs,
            startedAtEpochMs = :startedAtEpochMs,
            requestedControl = NULL,
            message = :message,
            updatedAtEpochMs = :startedAtEpochMs
        WHERE id = :jobId AND status IN ('Queued', 'WaitingForPrerequisites', 'Preparing', 'RecoveryRequired')""")
    suspend fun startLocalJob(
        jobId: String,
        status: String,
        timeoutAtEpochMs: Long?,
        startedAtEpochMs: Long,
        message: String,
    ): Int

    @Query("""UPDATE post_processing_jobs
        SET processToken = :processToken,
            timeoutAtEpochMs = :timeoutAtEpochMs,
            startedAtEpochMs = :startedAtEpochMs,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Preparing', 'Cancelling')""")
    suspend fun reserveProcessOwnership(
        jobId: String,
        processToken: String,
        timeoutAtEpochMs: Long?,
        startedAtEpochMs: Long,
        message: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            runId = :runId,
            executionId = :executionId,
            processToken = :processToken,
            timeoutAtEpochMs = :timeoutAtEpochMs,
            startedAtEpochMs = :startedAtEpochMs,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Queued', 'WaitingForPrerequisites', 'Preparing', 'Cancelling', 'RecoveryRequired')""")
    suspend fun attachRun(
        jobId: String,
        status: String,
        runId: String,
        executionId: Int,
        processToken: String,
        timeoutAtEpochMs: Long?,
        startedAtEpochMs: Long,
        message: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            processId = :processId,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND processToken = :processToken""")
    suspend fun recordProcessOwnership(jobId: String, processToken: String, processId: Int, status: String, message: String, updatedAtEpochMs: Long): Int

    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            progressPercent = :progressPercent,
            progressBytes = :progressBytes,
            progressTotalBytes = :progressTotalBytes,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Preparing', 'Running', 'Publishing', 'Paused', 'Cancelling')""")
    suspend fun updateProgress(
        jobId: String,
        status: String,
        progressPercent: Int,
        progressBytes: Long,
        progressTotalBytes: Long?,
        message: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            controlGeneration = controlGeneration + 1,
            requestedControl = :requestedControl,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Queued', 'WaitingForPrerequisites', 'Preparing', 'Running', 'Paused', 'Cancelling', 'RecoveryRequired')""")
    suspend fun requestControl(jobId: String, status: String, requestedControl: String, message: String, updatedAtEpochMs: Long): Int

    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            requestedControl = NULL,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId
          AND status NOT IN ('Completed', 'Failed', 'Cancelled', 'TimedOut')""")
    suspend fun acknowledgeControl(jobId: String, status: String, message: String, updatedAtEpochMs: Long): Int

    @Query("""UPDATE post_processing_jobs
        SET status = 'Publishing',
            publicationState = 'Prepared',
            publicationDisplayName = :displayName,
            publicationExpectedBytes = :expectedBytes,
            publicationExpectedSha256 = :expectedSha256,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND status IN ('Running', 'Publishing', 'RecoveryRequired')""")
    suspend fun markPublicationPrepared(
        jobId: String,
        displayName: String,
        expectedBytes: Long,
        expectedSha256: String,
        message: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query("""UPDATE post_processing_jobs
        SET status = 'Publishing',
            publicationState = 'Committed',
            committedOutputUri = :committedUri,
            committedBytes = :committedBytes,
            committedSha256 = :committedSha256,
            finalOutputUri = :committedUri,
            actualSha256 = :committedSha256,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId AND publicationState IN ('Prepared', 'Committed')""")
    suspend fun markPublicationCommitted(
        jobId: String,
        committedUri: String,
        committedBytes: Long,
        committedSha256: String,
        message: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query("""UPDATE post_processing_jobs
        SET publicationState = 'Reconciled',
            sideEffectOutcome = :sideEffectOutcome,
            message = :message,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :jobId""")
    suspend fun markPublicationReconciled(
        jobId: String,
        sideEffectOutcome: String?,
        message: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query("""UPDATE post_processing_jobs
        SET status = :status,
            finalOutputUri = :finalOutputUri,
            actualSha256 = :actualSha256,
            metadataJson = :metadataJson,
            toolVersionsJson = :toolVersionsJson,
            resultStdoutLength = :resultStdoutLength,
            resultStderrLength = :resultStderrLength,
            requestedControl = NULL,
            message = :message,
            finishedAtEpochMs = :finishedAtEpochMs,
            updatedAtEpochMs = :finishedAtEpochMs
        WHERE id = :jobId
          AND status NOT IN ('Completed', 'Failed', 'Cancelled', 'TimedOut')""")
    suspend fun finishJob(
        jobId: String,
        status: String,
        finalOutputUri: String?,
        actualSha256: String?,
        metadataJson: String?,
        toolVersionsJson: String,
        resultStdoutLength: Int,
        resultStderrLength: Int,
        message: String,
        finishedAtEpochMs: Long,
    ): Int

    @Query("""DELETE FROM post_processing_jobs
        WHERE status IN ('Completed', 'Failed', 'Cancelled', 'TimedOut')
          AND claimKey IS NULL""")
    suspend fun clearManualTerminalJobs(): Int

    @Query("DELETE FROM post_processing_jobs WHERE downloadId = :downloadId")
    suspend fun deleteForDownload(downloadId: String)

    @Query("DELETE FROM post_processing_jobs WHERE captureId = :captureId")
    suspend fun deleteForCapture(captureId: String)
}
