package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface DownloadGraphTransactionDao {
    @Transaction
    suspend fun deleteDownloadGraphIfTerminal(downloadId: String, expectedUpdatedAtEpochMs: Long, terminalStates: List<String>): Boolean {
        val row = findDownloadRowForGraphDeletion(downloadId) ?: return true
        if (row.updatedAtEpochMs != expectedUpdatedAtEpochMs || row.state !in terminalStates) return false
        if (countActivePostProcessingForDownload(downloadId) > 0) return false
        deleteDownloadGraph(downloadId)
        return findDownloadRowForGraphDeletion(downloadId) == null
    }

    /**
     * Removes transfer-owned state while retaining review/recovery history. Foreign keys cascade
     * true child rows (segments/checksums/finalization) and SET NULL on review records.
     */
    @Transaction
    suspend fun deleteDownloadGraph(downloadId: String) {
        if (countActivePostProcessingForDownload(downloadId) > 0) return
        detachPostProcessingForDownload(downloadId)
        detachRecoveryForDownload(downloadId)
        deleteBackendMigrationsForDownload(downloadId)
        deleteAria2MappingsForDownload(downloadId)
        deleteDestinationClaimsForDownload(downloadId)
        deleteBackendTasksForDownload(downloadId)
        deleteDownloadRow(downloadId)
    }

    @Query("""SELECT COUNT(*) FROM post_processing_jobs
        WHERE (downloadId = :downloadId OR captureId IN (SELECT id FROM media_captures WHERE downloadId = :downloadId))
          AND status NOT IN ('Completed', 'Failed', 'Cancelled', 'TimedOut')""")
    suspend fun countActivePostProcessingForDownload(downloadId: String): Int

    @Query("""UPDATE post_processing_jobs SET downloadId = NULL, captureId = NULL
        WHERE downloadId = :downloadId OR captureId IN (SELECT id FROM media_captures WHERE downloadId = :downloadId)""")
    suspend fun detachPostProcessingForDownload(downloadId: String)
    @Query("UPDATE recovery_records SET downloadId = NULL WHERE downloadId = :downloadId")
    suspend fun detachRecoveryForDownload(downloadId: String)
    @Query("DELETE FROM backend_migrations WHERE downloadId = :downloadId")
    suspend fun deleteBackendMigrationsForDownload(downloadId: String)
    @Query("DELETE FROM aria2_session_mappings WHERE downloadId = :downloadId")
    suspend fun deleteAria2MappingsForDownload(downloadId: String)
    @Query("DELETE FROM destination_claims WHERE downloadId = :downloadId")
    suspend fun deleteDestinationClaimsForDownload(downloadId: String)
    @Query("DELETE FROM backend_tasks WHERE downloadId = :downloadId")
    suspend fun deleteBackendTasksForDownload(downloadId: String)
    @Query("DELETE FROM downloads WHERE id = :downloadId")
    suspend fun deleteDownloadRow(downloadId: String)

    @Query("SELECT * FROM downloads WHERE id = :downloadId LIMIT 1")
    suspend fun findDownloadRowForGraphDeletion(downloadId: String): DownloadEntity?



    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownloadIgnore(entity: DownloadEntity): Long

    @Transaction
    suspend fun upsertDownloadPreservingNewerState(entity: DownloadEntity): Boolean {
        val inserted = insertDownloadIgnore(entity)
        if (inserted != -1L) return true
        return updateDownloadIfNotNewer(
            id = entity.id,
            fileName = entity.fileName,
            sourceUrl = entity.sourceUrl,
            destinationUri = entity.destinationUri,
            state = entity.state,
            backend = entity.backend,
            requestedBackend = entity.requestedBackend,
            backendSelectionReason = entity.backendSelectionReason,
            backendSelectionExplanation = entity.backendSelectionExplanation,
            allowBackendFallback = entity.allowBackendFallback,
            bytesReceived = entity.bytesReceived,
            totalBytes = entity.totalBytes,
            speedBytesPerSecond = entity.speedBytesPerSecond,
            queueId = entity.queueId,
            priority = entity.priority,
            createdAtEpochMs = entity.createdAtEpochMs,
            updatedAtEpochMs = entity.updatedAtEpochMs,
            errorMessage = entity.errorMessage,
            userLabel = entity.userLabel,
            conflictPolicy = entity.conflictPolicy,
            mimeType = entity.mimeType,
            archived = entity.archived,
            attemptGeneration = entity.attemptGeneration,
        ) == 1
    }

    @Transaction
    suspend fun upsertDownloadsPreservingNewerState(entities: List<DownloadEntity>): Boolean {
        // Preflight the whole batch before mutating anything. Returning false after partial writes
        // would still commit a Room transaction, so all stale-write checks must happen first.
        if (entities.any { !canAcceptDownloadWrite(it.id, it.attemptGeneration, it.updatedAtEpochMs) }) return false
        entities.forEach { entity ->
            check(upsertDownloadPreservingNewerState(entity)) { "Download batch changed after transactional preflight" }
        }
        return true
    }

    @Query("""SELECT CASE
        WHEN NOT EXISTS(SELECT 1 FROM downloads WHERE id = :id) THEN 1
        WHEN EXISTS(SELECT 1 FROM downloads WHERE id = :id AND (
            attemptGeneration < :attemptGeneration OR
            (attemptGeneration = :attemptGeneration AND updatedAtEpochMs < :updatedAtEpochMs)
        )) THEN 1
        ELSE 0 END""")
    suspend fun canAcceptDownloadWrite(id: String, attemptGeneration: Long, updatedAtEpochMs: Long): Boolean

    @Query("""UPDATE downloads
        SET fileName = :fileName,
            sourceUrl = :sourceUrl,
            destinationUri = :destinationUri,
            state = :state,
            backend = :backend,
            requestedBackend = :requestedBackend,
            backendSelectionReason = :backendSelectionReason,
            backendSelectionExplanation = :backendSelectionExplanation,
            allowBackendFallback = :allowBackendFallback,
            bytesReceived = :bytesReceived,
            totalBytes = :totalBytes,
            speedBytesPerSecond = :speedBytesPerSecond,
            queueId = :queueId,
            priority = :priority,
            createdAtEpochMs = :createdAtEpochMs,
            updatedAtEpochMs = :updatedAtEpochMs,
            errorMessage = :errorMessage,
            userLabel = :userLabel,
            conflictPolicy = :conflictPolicy,
            mimeType = :mimeType,
            archived = :archived,
            attemptGeneration = :attemptGeneration
        WHERE id = :id AND (
            attemptGeneration < :attemptGeneration OR
            (attemptGeneration = :attemptGeneration AND updatedAtEpochMs < :updatedAtEpochMs)
        )""")
    suspend fun updateDownloadIfNotNewer(
        id: String,
        fileName: String,
        sourceUrl: String,
        destinationUri: String,
        state: String,
        backend: String,
        requestedBackend: String,
        backendSelectionReason: String,
        backendSelectionExplanation: String,
        allowBackendFallback: Boolean,
        bytesReceived: Long,
        totalBytes: Long?,
        speedBytesPerSecond: Long,
        queueId: String?,
        priority: Int,
        createdAtEpochMs: Long,
        updatedAtEpochMs: Long,
        errorMessage: String?,
        userLabel: String?,
        conflictPolicy: String,
        mimeType: String?,
        archived: Boolean,
        attemptGeneration: Long,
    ): Int

    @Query("""UPDATE downloads
        SET state = :state,
            bytesReceived = :bytesReceived,
            totalBytes = :totalBytes,
            speedBytesPerSecond = :speedBytesPerSecond,
            errorMessage = :errorMessage,
            updatedAtEpochMs = :newUpdatedAtEpochMs
        WHERE id = :downloadId AND updatedAtEpochMs = :expectedUpdatedAtEpochMs""")
    suspend fun updateDownloadCompareAndSwap(
        downloadId: String,
        expectedUpdatedAtEpochMs: Long,
        state: String,
        bytesReceived: Long,
        totalBytes: Long?,
        speedBytesPerSecond: Long,
        errorMessage: String?,
        newUpdatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun replaceMediaVariantsForCapture(captureId: String, variants: List<MediaVariantEntity>, updatedAtEpochMs: Long) {
        require(variants.all { it.captureId == captureId }) { "Variant belongs to another capture" }
        deleteMediaVariantsForCaptures(listOf(captureId))
        if (variants.isNotEmpty()) upsertMediaVariants(variants)
        reconcileCaptureAfterVariantReplacement(captureId, updatedAtEpochMs)
    }

    @Transaction
    suspend fun replaceMediaVariantsForCaptures(variants: List<MediaVariantEntity>, updatedAtEpochMs: Long) {
        val captureIds = variants.map { it.captureId }.distinct()
        if (captureIds.isEmpty()) return
        deleteMediaVariantsForCaptures(captureIds)
        upsertMediaVariants(variants)
        captureIds.forEach { captureId -> reconcileCaptureAfterVariantReplacement(captureId, updatedAtEpochMs) }
    }

    @Query("DELETE FROM media_variants WHERE captureId IN (:captureIds)")
    suspend fun deleteMediaVariantsForCaptures(captureIds: List<String>)
    @Upsert
    suspend fun upsertMediaVariants(variants: List<MediaVariantEntity>)
    @Query("""UPDATE media_captures
        SET variantCount = (SELECT COUNT(*) FROM media_variants WHERE captureId = :captureId),
            selectedVariantId = CASE
                WHEN selectedVariantId IS NULL THEN NULL
                WHEN EXISTS(SELECT 1 FROM media_variants WHERE id = selectedVariantId AND captureId = :captureId) THEN selectedVariantId
                ELSE NULL
            END,
            selectedVariantUrl = CASE
                WHEN selectedVariantId IS NULL THEN NULL
                WHEN EXISTS(SELECT 1 FROM media_variants WHERE id = selectedVariantId AND captureId = :captureId) THEN selectedVariantUrl
                ELSE NULL
            END,
            resolutionStatus = CASE
                WHEN selectedVariantId IS NOT NULL AND NOT EXISTS(SELECT 1 FROM media_variants WHERE id = selectedVariantId AND captureId = :captureId)
                THEN 'RequiresRefresh'
                ELSE resolutionStatus
            END,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :captureId""")
    suspend fun reconcileCaptureAfterVariantReplacement(captureId: String, updatedAtEpochMs: Long)

    @Query("DELETE FROM queues WHERE id = :queueId AND NOT EXISTS(SELECT 1 FROM downloads WHERE queueId = :queueId) AND NOT EXISTS(SELECT 1 FROM schedule_rules WHERE queueId = :queueId)")
    suspend fun deleteQueueIfUnreferenced(queueId: String): Int
    @Query("UPDATE downloads SET queueId = :replacementQueueId, updatedAtEpochMs = :updatedAtEpochMs WHERE queueId = :queueId")
    suspend fun reassignQueueDownloads(queueId: String, replacementQueueId: String, updatedAtEpochMs: Long): Int
    @Query("DELETE FROM schedule_rules WHERE queueId = :queueId")
    suspend fun deleteScheduleRulesForQueue(queueId: String)
    @Transaction
    suspend fun reassignQueueThenDelete(queueId: String, replacementQueueId: String, updatedAtEpochMs: Long): Int {
        reassignQueueDownloads(queueId, replacementQueueId, updatedAtEpochMs)
        deleteScheduleRulesForQueue(queueId)
        return deleteQueueIfUnreferenced(queueId)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAutomationIgnore(entity: AutomationCommandEntity): Long

    @Transaction
    suspend fun upsertAutomationCommandStatefully(entity: AutomationCommandEntity): Boolean {
        val durable = entity.withDurableStatus()
        val inserted = insertAutomationIgnore(durable)
        if (inserted != -1L) return true
        return updateAutomationCommandFields(
            id = durable.id,
            url = durable.url,
            fileName = durable.fileName,
            pageTitle = durable.pageTitle,
            pageUrl = durable.pageUrl,
            mediaCaptureId = durable.mediaCaptureId,
            downloadId = durable.downloadId,
            status = durable.status,
            resultMessage = durable.resultMessage,
            updatedAtEpochMs = durable.updatedAtEpochMs,
            originPackage = durable.originPackage,
            claimedOriginPackage = durable.claimedOriginPackage,
            verifiedIntegrationId = durable.verifiedIntegrationId,
            authorization = durable.authorization,
            privateNetworkApproved = durable.privateNetworkApproved,
            cleartextCredentialsApproved = durable.cleartextCredentialsApproved,
            originHost = durable.originHost,
            sanitizedHeaders = durable.sanitizedHeaders,
            rejectionReason = durable.rejectionReason,
            metadataJson = durable.metadataJson,
        ) == 1
    }

    @Query("""UPDATE automation_commands
        SET url = :url,
            fileName = :fileName,
            pageTitle = :pageTitle,
            pageUrl = :pageUrl,
            mediaCaptureId = :mediaCaptureId,
            downloadId = :downloadId,
            status = :status,
            resultMessage = :resultMessage,
            updatedAtEpochMs = :updatedAtEpochMs,
            originPackage = :originPackage,
            claimedOriginPackage = :claimedOriginPackage,
            verifiedIntegrationId = :verifiedIntegrationId,
            authorization = :authorization,
            privateNetworkApproved = :privateNetworkApproved,
            cleartextCredentialsApproved = :cleartextCredentialsApproved,
            originHost = :originHost,
            sanitizedHeaders = :sanitizedHeaders,
            rejectionReason = :rejectionReason,
            metadataJson = :metadataJson
        WHERE id = :id""")
    suspend fun updateAutomationCommandFields(
        id: String,
        url: String?,
        fileName: String?,
        pageTitle: String?,
        pageUrl: String?,
        mediaCaptureId: String?,
        downloadId: String?,
        status: String,
        resultMessage: String,
        updatedAtEpochMs: Long,
        originPackage: String?,
        claimedOriginPackage: String?,
        verifiedIntegrationId: String?,
        authorization: String,
        privateNetworkApproved: Boolean,
        cleartextCredentialsApproved: Boolean,
        originHost: String?,
        sanitizedHeaders: String?,
        rejectionReason: String,
        metadataJson: String?,
    ): Int


    @Transaction
    suspend fun markAutomationCommandExecuting(id: String, updatedAtEpochMs: Long): Boolean {
        if (transitionAutomationCommand(id, listOf("Received", "Accepted"), "Claimed", "Command claimed for execution.", updatedAtEpochMs) != 1) return false
        return transitionAutomationCommand(id, listOf("Claimed"), "Executing", "Command side effects executing.", updatedAtEpochMs) == 1
    }

    @Query("SELECT status FROM automation_commands WHERE id = :id")
    suspend fun automationCommandStatus(id: String): String?

    @Query("""UPDATE automation_commands SET status = :toStatus, resultMessage = :resultMessage, updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :id AND status IN (:fromStatuses)""")
    suspend fun transitionAutomationCommand(id: String, fromStatuses: List<String>, toStatus: String, resultMessage: String, updatedAtEpochMs: Long): Int
}

private fun AutomationCommandEntity.withDurableStatus(): AutomationCommandEntity = copy(
    status = when (status) {
        "Accepted" -> "Received"
        "Executed" -> "Applied"
        "Received", "Claimed", "Executing", "Applied", "Failed", "Rejected", "Duplicate" -> status
        else -> "Failed"
    },
)
