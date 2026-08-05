package com.mikeyphw.xdm.android.persistence

import androidx.room.withTransaction
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandRecord
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
import com.mikeyphw.xdm.android.model.AutomationRejectionReason
import com.mikeyphw.xdm.android.model.ClipboardInboxItem
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.TrustedBlockManifest
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DestinationRule
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.DuplicateUrlRule
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.DestinationType
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.model.SavedSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

class DownloadRepository(private val database: AppDatabase) {
    val downloads: Flow<List<Download>> = database.downloadDao().observeAll().map { rows -> rows.map { it.toModel() } }
    val queues: Flow<List<QueueDefinition>> = database.queueDao().observeAll().map { rows -> rows.map { it.toModel() } }
    val schedules: Flow<List<ScheduleRule>> = database.scheduleDao().observeAll().map { rows -> rows.map { it.toModel() } }
    val recoveryRecords: Flow<List<RecoveryRecord>> = database.recoveryDao().observeAll().map { rows -> rows.map { it.toModel() } }
    val destinationPermissions: Flow<List<DestinationPermission>> = database.destinationPermissionDao().observeAll().map { rows -> rows.map { it.toModel() } }
    val backendMigrations: Flow<List<BackendMigrationRecord>> = database.backendMigrationDao().observeAll().map { rows -> rows.map(BackendMigrationEntity::toModel) }
    val checksumResults: Flow<List<ChecksumResult>> = database.checksumDao().observeResults().map { rows -> rows.map(ChecksumResultEntity::toModel) }
    val verificationRecords: Flow<List<VerificationRecord>> = database.checksumDao().observeVerifications().map { rows -> rows.map(VerificationRecordEntity::toModel) }
    val finalizationJournals: Flow<List<FinalizationJournal>> = database.finalizationDao().observeAll().map { rows -> rows.map(FinalizationJournalEntity::toModel) }
    val mediaCaptures: Flow<List<MediaCaptureRecord>> = database.mediaCaptureDao().observeAll().map { rows -> rows.map(MediaCaptureEntity::toModel) }
    val mediaVariants: Flow<List<MediaVariant>> = database.mediaCaptureDao().observeVariants().map { rows -> rows.map(MediaVariantEntity::toModel) }
    val automationCommands: Flow<List<AutomationCommandRecord>> = database.automationCommandDao().observeAll().map { rows -> rows.map(AutomationCommandEntity::toModel) }
    val tags: Flow<List<DownloadTag>> = database.organizationDao().observeTags().map { rows -> rows.map(TagEntity::toModel) }
    val tagAssignments: Flow<List<DownloadTagAssignment>> = database.organizationDao().observeTagAssignments().map { rows -> rows.map { DownloadTagAssignment(it.downloadId, it.tagId) } }
    val savedSearches: Flow<List<SavedSearch>> = database.organizationDao().observeSavedSearches().map { rows -> rows.map(SavedSearchEntity::toModel) }
    val destinationRules: Flow<List<DestinationRule>> = database.organizationDao().observeDestinationRules().map { rows -> rows.map(DestinationRuleEntity::toModel) }
    val duplicateRules: Flow<List<DuplicateUrlRule>> = database.organizationDao().observeDuplicateRules().map { rows -> rows.map(DuplicateUrlRuleEntity::toModel) }
    val clipboardInbox: Flow<List<ClipboardInboxItem>> = database.organizationDao().observeClipboardInbox().map { rows -> rows.map(ClipboardInboxEntity::toModel) }

    suspend fun countDownloads(): Int = database.downloadDao().count()
    suspend fun countQueues(): Int = database.queueDao().count()
    suspend fun save(download: Download): Boolean = database.downloadGraphTransactionDao().upsertDownloadPreservingNewerState(download.redactedForPersistence().toEntity())
    suspend fun saveAll(downloads: List<Download>) { downloads.forEach { save(it) } }
    suspend fun saveQueue(queue: QueueDefinition) = database.queueDao().upsertAll(listOf(queue.toEntity()))
    suspend fun saveQueues(queues: List<QueueDefinition>) = database.queueDao().upsertAll(queues.map { it.toEntity() })
    suspend fun deleteQueue(id: String) = database.downloadGraphTransactionDao().deleteQueueIfUnreferenced(id)
    suspend fun reassignQueueThenDelete(id: String, replacementQueueId: String) = database.downloadGraphTransactionDao().reassignQueueThenDelete(id, replacementQueueId, System.currentTimeMillis())
    suspend fun saveSchedule(rule: ScheduleRule) = database.scheduleDao().upsertAll(listOf(rule.toEntity()))
    suspend fun saveSchedules(rules: List<ScheduleRule>) = database.scheduleDao().upsertAll(rules.map { it.toEntity() })
    suspend fun deleteSchedule(id: String) = database.scheduleDao().delete(id)
    suspend fun saveRecovery(records: List<RecoveryRecord>) = database.recoveryDao().upsertAll(records.map { it.toEntity() })
    suspend fun saveRecovery(record: RecoveryRecord) = database.recoveryDao().upsert(record.toEntity())
    suspend fun deleteRecovery(id: String) = database.recoveryDao().delete(id)
    suspend fun deleteRecoveryForDownload(downloadId: String) = database.recoveryDao().deleteByDownload(downloadId)
    suspend fun saveFinalizationJournal(journal: FinalizationJournal) = database.finalizationDao().upsert(journal.toEntity())
    suspend fun finalizationForDownload(downloadId: String): FinalizationJournal? = database.finalizationDao().findByDownload(downloadId)?.toModel()
    suspend fun deleteFinalizationForDownload(downloadId: String) = database.finalizationDao().deleteByDownload(downloadId)
    suspend fun saveChecksumExpectation(expectation: ChecksumExpectation) = database.checksumDao().upsertExpectation(expectation.toEntity())
    suspend fun checksumExpectations(downloadId: String): List<ChecksumExpectation> = database.checksumDao().expectations(downloadId).map(ChecksumExpectationEntity::toModel)
    suspend fun saveChecksumResult(result: ChecksumResult) = database.checksumDao().upsertResult(result.toEntity())
    suspend fun saveVerificationRecord(record: VerificationRecord) = database.checksumDao().upsertVerification(record.toEntity())
    suspend fun saveTrustedManifest(manifest: TrustedBlockManifest) = database.checksumDao().upsertTrustedManifest(manifest.toEntity())
    suspend fun saveMediaCapture(record: MediaCaptureRecord) = database.mediaCaptureDao().upsert(record.redactedForPersistence().toEntity())
    suspend fun saveMediaCaptures(records: List<MediaCaptureRecord>) = database.mediaCaptureDao().upsertAll(records.map { it.redactedForPersistence().toEntity() })
    suspend fun saveMediaVariants(records: List<MediaVariant>) = database.mediaCaptureDao().upsertVariants(records.map { it.redactedForPersistence().toEntity() })
    suspend fun replaceMediaVariants(records: List<MediaVariant>) = database.downloadGraphTransactionDao().replaceMediaVariantsForCaptures(records.map { it.redactedForPersistence().toEntity() }, System.currentTimeMillis())
    suspend fun saveMediaCaptureWithVariants(record: MediaCaptureRecord, variants: List<MediaVariant>, updatedAtEpochMs: Long = System.currentTimeMillis()) = database.withTransaction {
        database.mediaCaptureDao().upsert(record.redactedForPersistence().toEntity())
        if (variants.isNotEmpty()) {
            database.downloadGraphTransactionDao().replaceMediaVariantsForCaptures(variants.map { it.redactedForPersistence().toEntity() }, updatedAtEpochMs)
        }
    }
    suspend fun saveMediaCapturesWithVariants(records: List<MediaCaptureRecord>, variants: List<MediaVariant>, updatedAtEpochMs: Long = System.currentTimeMillis()) = database.withTransaction {
        if (records.isNotEmpty()) database.mediaCaptureDao().upsertAll(records.map { it.redactedForPersistence().toEntity() })
        if (variants.isNotEmpty()) {
            database.downloadGraphTransactionDao().replaceMediaVariantsForCaptures(variants.map { it.redactedForPersistence().toEntity() }, updatedAtEpochMs)
        }
    }
    suspend fun variantsForMediaCapture(captureId: String): List<MediaVariant> = database.mediaCaptureDao().variantsForCapture(captureId).map { it.toModel() }
    suspend fun selectMediaVariant(captureId: String, variant: MediaVariant, updatedAtEpochMs: Long = System.currentTimeMillis()) = database.mediaCaptureDao().selectVariant(captureId, variant.id, ExternalUrlPolicy.persistableUrl(variant.url) ?: variant.url.substringBefore('?'), MediaResolutionStatus.Resolved.name, updatedAtEpochMs)
    suspend fun findMediaCapture(id: String): MediaCaptureRecord? = database.mediaCaptureDao().findById(id)?.toModel()
    suspend fun markMediaDownloadCreated(captureId: String, downloadId: String, updatedAtEpochMs: Long = System.currentTimeMillis()) = database.mediaCaptureDao().markDownloadCreated(captureId, MediaCaptureStatus.DownloadCreated.name, downloadId, updatedAtEpochMs)
    suspend fun deleteMediaCapture(id: String) = database.mediaCaptureDao().delete(id)
    suspend fun findAutomationCommand(id: String): AutomationCommandRecord? = database.automationCommandDao().findById(id)?.toModel()
    suspend fun findAutomationCommandByKey(idempotencyKey: String): AutomationCommandRecord? = database.automationCommandDao().findByIdempotencyKey(idempotencyKey)?.toModel()
    suspend fun saveAutomationCommand(record: AutomationCommandRecord) = database.downloadGraphTransactionDao().upsertAutomationCommandStatefully(record.redactedForPersistence().toEntity())
    suspend fun transitionAutomationCommand(id: String, from: List<AutomationCommandStatus>, to: AutomationCommandStatus, message: String): Int = database.downloadGraphTransactionDao().transitionAutomationCommand(id, from.map { it.name }, to.name, message, System.currentTimeMillis())
    suspend fun markAutomationCommandExecuting(id: String): Boolean = database.downloadGraphTransactionDao().markAutomationCommandExecuting(id, System.currentTimeMillis())
    suspend fun findDownload(id: String): Download? = database.downloadDao().findById(id)?.toModel()
    suspend fun deleteDownload(id: String) = database.downloadGraphTransactionDao().deleteDownloadGraph(id)
    suspend fun deleteDownloadEntryIfTerminal(download: Download, terminalStates: Set<DownloadState>): Boolean =
        database.downloadGraphTransactionDao().deleteDownloadGraphIfTerminal(
            download.id,
            download.updatedAtEpochMs,
            terminalStates.map { it.name },
        )

    suspend fun clonePostProcessingJobsForRedownload(sourceDownloadId: String, targetDownloadId: String, now: Long): Int {
        val rows = database.postProcessingDao().jobsForDownload(sourceDownloadId)
        var cloned = 0
        rows.forEach { source ->
            val specJson = rewritePostProcessingSpecForRedownload(source.immutableSpecJson, targetDownloadId, now)
            val jobId = "post-${UUID.randomUUID()}"
            database.postProcessingDao().insertJob(
                source.copy(
                    id = jobId,
                    rootJobId = jobId,
                    parentJobId = source.id,
                    attemptGeneration = 1,
                    claimKey = null,
                    subjectId = targetDownloadId,
                    subjectGeneration = now,
                    downloadId = targetDownloadId,
                    captureId = null,
                    status = "WaitingForPrerequisites",
                    inputUri = "xdm://downloads/$targetDownloadId/completed-artifact",
                    stagedInputPath = null,
                    inputBridgeUri = null,
                    stagedOutputPath = null,
                    outputBridgeUri = null,
                    ownerBridgeUri = null,
                    progressBridgeUri = null,
                    metadataBridgeUri = null,
                    payloadBridgeUri = null,
                    finalOutputUri = null,
                    publicationState = "None",
                    publicationDisplayName = null,
                    publicationExpectedBytes = null,
                    publicationExpectedSha256 = null,
                    committedOutputUri = null,
                    committedBytes = null,
                    committedSha256 = null,
                    sideEffectOutcome = null,
                    immutableSpecJson = specJson,
                    actualSha256 = null,
                    runId = null,
                    executionId = null,
                    processToken = null,
                    processId = null,
                    controlGeneration = 0L,
                    requestedControl = null,
                    progressPercent = 0,
                    progressBytes = 0L,
                    progressTotalBytes = null,
                    timeoutAtEpochMs = null,
                    resultStdoutLength = 0,
                    resultStderrLength = 0,
                    metadataJson = null,
                    message = "Cloned from ${source.id}; waiting for redownload $targetDownloadId to commit before post-processing starts.",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    startedAtEpochMs = null,
                    finishedAtEpochMs = null,
                ),
            )
            cloned += 1
        }
        return cloned
    }

    suspend fun updateDownloadCompareAndSwap(download: Download, expectedUpdatedAtEpochMs: Long): Boolean = database.downloadGraphTransactionDao().updateDownloadCompareAndSwap(download.id, expectedUpdatedAtEpochMs, download.state.name, download.bytesReceived, download.totalBytes, download.speedBytesPerSecond, download.errorMessage, download.updatedAtEpochMs) == 1
    suspend fun setArchived(ids: List<String>, archived: Boolean) {
        if (ids.isNotEmpty()) database.downloadDao().setArchived(ids, archived, System.currentTimeMillis())
    }
    suspend fun saveTag(tag: DownloadTag) = database.organizationDao().upsertTag(tag.toEntity())
    suspend fun assignTag(downloadId: String, tagId: String) = database.organizationDao().upsertTagAssignment(DownloadTagCrossRef(downloadId, tagId))
    suspend fun removeTag(downloadId: String, tagId: String) = database.organizationDao().deleteTagAssignment(downloadId, tagId)
    suspend fun saveSavedSearch(search: SavedSearch) = database.organizationDao().upsertSavedSearch(search.toEntity())
    suspend fun deleteSavedSearch(id: String) = database.organizationDao().deleteSavedSearch(id)
    suspend fun saveDestinationRule(rule: DestinationRule) = database.organizationDao().upsertDestinationRule(rule.toEntity())
    suspend fun saveDuplicateRule(rule: DuplicateUrlRule) = database.organizationDao().upsertDuplicateRule(rule.toEntity())
    suspend fun saveClipboardItems(items: List<ClipboardInboxItem>) = database.organizationDao().upsertClipboardItems(items.map { it.copy(url = ExternalUrlPolicy.persistableUrl(it.url) ?: it.url.substringBefore('?')).toEntity() })
    suspend fun saveClipboardItem(item: ClipboardInboxItem) = database.organizationDao().upsertClipboardItem(item.copy(url = ExternalUrlPolicy.persistableUrl(item.url) ?: item.url.substringBefore('?')).toEntity())
    suspend fun findDownloadsByStates(states: Set<DownloadState>): List<Download> =
        if (states.isEmpty()) emptyList() else database.downloadDao().findByStates(states.map { it.name }).map { it.toModel() }

    suspend fun attemptGenerationForDownload(downloadId: String): Long? =
        database.backendTaskDao().findByDownload(downloadId)?.ownershipGeneration?.takeIf { it > 0L }

    suspend fun recoveryArtifactForDownload(downloadId: String): String? =
        database.recoveryDao().listAll()
            .firstOrNull { it.downloadId == downloadId && it.artifactPath.isNotBlank() }
            ?.artifactPath

    suspend fun hasDurableResumeEvidence(downloadId: String): Boolean {
        val recoveryReady = database.recoveryDao().listAll().any {
            it.downloadId == downloadId && it.safeToResume && it.artifactPath.isNotBlank()
        }
        if (recoveryReady) return true
        if (database.downloadDao().countDurableCheckpoints(downloadId) > 0) return true
        val backendTask = database.backendTaskDao().findByDownload(downloadId)
        return backendTask != null &&
            backendTask.partialIdentity.isNotBlank() &&
            backendTask.ownershipGeneration > 0L &&
            backendTask.ownershipStatus !in setOf("Released", "Cancelled", "Completed")
    }

    suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) {
        database.backendTaskDao().upsert(
            BackendTaskEntity(
                id = "$downloadId:${ownership.generation}",
                downloadId = downloadId,
                backend = backend.name,
                backendTaskId = backendTaskId,
                destinationKey = ownership.destinationKey,
                partialIdentity = ownership.artifacts.primary,
                artifactFormat = ownership.artifacts.format,
                companionArtifactIdentities = ownership.artifacts.companions.joinToString("\n"),
                backendInstanceId = ownership.runtimeIdentity.instanceId,
                backendSessionId = ownership.runtimeIdentity.sessionId,
                ownershipGeneration = ownership.generation,
                ownershipStatus = ownership.status.name,
                reconciliation = ownership.reconciliation.name,
                reconciliationMessage = ownership.reconciliationMessage,
                reconciledAtEpochMs = ownership.reconciledAtEpochMs,
                lastSynchronizedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteBackendTask(downloadId: String) = database.backendTaskDao().deleteByDownload(downloadId)
    suspend fun saveDestinationPermission(permission: DestinationPermission) = database.destinationPermissionDao().upsert(permission.toEntity())
    suspend fun deleteDestinationPermission(uri: String) = database.destinationPermissionDao().delete(uri)
}


private fun rewritePostProcessingSpecForRedownload(rawJson: String, targetDownloadId: String, now: Long): String = runCatching {
    val json = JSONObject(rawJson)
    json.put("subjectId", targetDownloadId)
    json.put("subjectType", "Download")
    json.put("subjectGeneration", now)
    json.put("downloadId", targetDownloadId)
    json.remove("captureId")
    json.put("trigger", "DownloadCompleted")
    json.put("inputUri", "xdm://downloads/$targetDownloadId/completed-artifact")
    json.toString()
}.getOrDefault(rawJson)

private fun Download.redactedForPersistence(): Download = copy(
    sourceUrl = ExternalUrlPolicy.persistableUrl(sourceUrl) ?: sourceUrl.substringBefore('?'),
)

private fun AutomationCommandRecord.redactedForPersistence(): AutomationCommandRecord = copy(
    url = ExternalUrlPolicy.persistableUrl(url),
    pageUrl = ExternalUrlPolicy.persistableUrl(pageUrl),
)

private fun MediaCaptureRecord.redactedForPersistence(): MediaCaptureRecord = copy(
    sourceUrl = ExternalUrlPolicy.persistableUrl(sourceUrl) ?: sourceUrl.substringBefore('?'),
    pageUrl = ExternalUrlPolicy.persistableUrl(pageUrl),
    thumbnailUrl = ExternalUrlPolicy.persistableUrl(thumbnailUrl),
    selectedVariantUrl = ExternalUrlPolicy.persistableUrl(selectedVariantUrl),
)

private fun MediaVariant.redactedForPersistence(): MediaVariant = copy(
    url = ExternalUrlPolicy.persistableUrl(url) ?: url.substringBefore('?'),
)


private fun AutomationCommandEntity.toModel() = AutomationCommandRecord(
    id = id,
    idempotencyKey = idempotencyKey,
    source = safeEnum(source, AutomationCommandSource.Internal),
    action = safeEnum(action, AutomationCommandAction.Unknown),
    url = url,
    fileName = fileName,
    pageTitle = pageTitle,
    pageUrl = pageUrl,
    mediaCaptureId = mediaCaptureId,
    downloadId = downloadId,
    status = safeEnum(status, AutomationCommandStatus.Failed),
    resultMessage = resultMessage,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    originPackage = originPackage,
    claimedOriginPackage = claimedOriginPackage,
    verifiedIntegrationId = verifiedIntegrationId,
    authorization = safeEnum(authorization, ExternalCommandAuthorization.Untrusted),
    privateNetworkApproved = privateNetworkApproved,
    cleartextCredentialsApproved = cleartextCredentialsApproved,
    originHost = originHost,
    sanitizedHeaders = sanitizedHeaders,
    rejectionReason = safeEnum(rejectionReason, AutomationRejectionReason.None),
)

private fun AutomationCommandRecord.toEntity() = AutomationCommandEntity(
    id = id,
    idempotencyKey = idempotencyKey,
    source = source.name,
    action = action.name,
    url = url,
    fileName = fileName,
    pageTitle = pageTitle,
    pageUrl = pageUrl,
    mediaCaptureId = mediaCaptureId,
    downloadId = downloadId,
    status = status.name,
    resultMessage = resultMessage,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    originPackage = originPackage,
    claimedOriginPackage = claimedOriginPackage,
    verifiedIntegrationId = verifiedIntegrationId,
    authorization = authorization.name,
    privateNetworkApproved = privateNetworkApproved,
    cleartextCredentialsApproved = cleartextCredentialsApproved,
    originHost = originHost,
    sanitizedHeaders = sanitizedHeaders,
    rejectionReason = rejectionReason.name,
)

private fun DownloadEntity.toModel() = Download(
    id = id,
    fileName = fileName,
    sourceUrl = sourceUrl,
    destinationUri = destinationUri,
    state = safeEnum(state, DownloadState.RecoveryRequired),
    backend = safeEnum(backend, BackendType.Automatic),
    bytesReceived = bytesReceived,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    queueId = queueId,
    priority = priority,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    errorMessage = errorMessage,
    userLabel = userLabel,
    conflictPolicy = safeEnum(conflictPolicy, FilenameConflictPolicy.Rename),
    mimeType = mimeType,
    requestedBackend = safeEnum(requestedBackend, BackendType.Automatic),
    backendSelectionReason = safeEnum(backendSelectionReason, BackendSelectionReason.DefaultNative),
    backendSelectionExplanation = backendSelectionExplanation,
    allowBackendFallback = allowBackendFallback,
    archived = archived,
)
private fun Download.toEntity() = DownloadEntity(
    id = id,
    fileName = fileName,
    sourceUrl = sourceUrl,
    destinationUri = destinationUri,
    state = state.name,
    backend = backend.name,
    requestedBackend = requestedBackend.name,
    backendSelectionReason = backendSelectionReason.name,
    backendSelectionExplanation = backendSelectionExplanation,
    allowBackendFallback = allowBackendFallback,
    bytesReceived = bytesReceived,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    queueId = queueId,
    priority = priority,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    errorMessage = errorMessage,
    userLabel = userLabel,
    conflictPolicy = conflictPolicy.name,
    mimeType = mimeType,
    archived = archived,
)
private fun QueueEntity.toModel() = QueueDefinition(id, name, isEnabled, maxConcurrent, createdAtEpochMs)
private fun QueueDefinition.toEntity() = QueueEntity(id, name, isEnabled, maxConcurrent, createdAtEpochMs)
private fun ScheduleRuleEntity.toModel() = ScheduleRule(id, queueId, name, enabled, constraintsJson)
private fun ScheduleRule.toEntity() = ScheduleRuleEntity(id, queueId, name, enabled, constraintsJson)
private fun DestinationPermissionEntity.toModel() = DestinationPermission(
    uri = uri,
    displayName = displayName,
    type = safeEnum(providerType, DestinationType.SafTree),
    persistedRead = persistedRead,
    persistedWrite = persistedWrite,
    status = safeEnum(status, DestinationHealthStatus.Unknown),
    lastValidatedAtEpochMs = lastValidatedAtEpochMs,
    lastError = lastError,
)
private fun DestinationPermission.toEntity() = DestinationPermissionEntity(
    uri = uri,
    displayName = displayName,
    providerType = type.name,
    persistedRead = persistedRead,
    persistedWrite = persistedWrite,
    status = status.name,
    lastValidatedAtEpochMs = lastValidatedAtEpochMs,
    lastError = lastError,
)

private fun TagEntity.toModel() = DownloadTag(id, name, colorArgb)
private fun DownloadTag.toEntity() = TagEntity(id, name, colorArgb)
private fun SavedSearchEntity.toModel() = SavedSearch(
    id = id,
    name = name,
    query = query,
    state = safeEnumOrNull(state),
    includeArchived = includeArchived,
    createdAtEpochMs = createdAtEpochMs,
)
private fun SavedSearch.toEntity() = SavedSearchEntity(id, name, query, state?.name, includeArchived, createdAtEpochMs)
private fun DuplicateUrlRuleEntity.toModel() = DuplicateUrlRule(
    id = id,
    hostPattern = hostPattern,
    action = safeEnum(action, DuplicateUrlAction.Ask),
    enabled = enabled,
)
private fun DuplicateUrlRule.toEntity() = DuplicateUrlRuleEntity(id, hostPattern, action.name, enabled)
private fun DestinationRuleEntity.toModel() = DestinationRule(
    id = id,
    name = name,
    match = safeEnum(match, DestinationRuleMatch.Host),
    pattern = pattern,
    destinationUri = destinationUri,
    enabled = enabled,
    priority = priority,
)
private fun DestinationRule.toEntity() = DestinationRuleEntity(id, name, match.name, pattern, destinationUri, enabled, priority)
private fun ClipboardInboxEntity.toModel() = ClipboardInboxItem(id, url, title, sourceTextHash, status, createdAtEpochMs, updatedAtEpochMs)
private fun ClipboardInboxItem.toEntity() = ClipboardInboxEntity(id, url, title, sourceTextHash, status, createdAtEpochMs, updatedAtEpochMs)

private fun MediaCaptureEntity.toModel() = MediaCaptureRecord(
    id = id,
    sourceUrl = sourceUrl,
    pageUrl = pageUrl,
    title = title,
    status = safeEnum(status, MediaCaptureStatus.Captured),
    kind = safeEnum(kind, MediaSourceKind.Unknown),
    mimeType = mimeType,
    container = container,
    codecs = codecs,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
    fileName = fileName,
    variantCount = variantCount,
    downloadId = downloadId,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    selectedVariantId = selectedVariantId,
    selectedVariantUrl = selectedVariantUrl,
    manifestExpiresAtEpochMs = manifestExpiresAtEpochMs,
    lastResolvedAtEpochMs = lastResolvedAtEpochMs,
    resolutionStatus = safeEnum(resolutionStatus, MediaResolutionStatus.Unresolved),
)

private fun MediaCaptureRecord.toEntity() = MediaCaptureEntity(
    id = id,
    sourceUrl = sourceUrl,
    pageUrl = pageUrl,
    title = title,
    status = status.name,
    kind = kind.name,
    mimeType = mimeType,
    container = container,
    codecs = codecs,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
    fileName = fileName,
    variantCount = variantCount,
    downloadId = downloadId,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    selectedVariantId = selectedVariantId,
    selectedVariantUrl = selectedVariantUrl,
    manifestExpiresAtEpochMs = manifestExpiresAtEpochMs,
    lastResolvedAtEpochMs = lastResolvedAtEpochMs,
    resolutionStatus = resolutionStatus.name,
)

private fun MediaVariantEntity.toModel() = MediaVariant(
    id = id,
    captureId = captureId,
    url = url,
    kind = safeEnum(kind, MediaVariantKind.Primary),
    mimeType = mimeType,
    width = width,
    height = height,
    bitrateBitsPerSecond = bitrateBitsPerSecond,
    codecs = codecs,
    language = language,
    position = position,
    displayLabel = displayLabel,
    expiresAtEpochMs = expiresAtEpochMs,
)

private fun MediaVariant.toEntity() = MediaVariantEntity(
    id = id,
    captureId = captureId,
    url = url,
    kind = kind.name,
    mimeType = mimeType,
    width = width,
    height = height,
    bitrateBitsPerSecond = bitrateBitsPerSecond,
    codecs = codecs,
    language = language,
    position = position,
    displayLabel = displayLabel,
    expiresAtEpochMs = expiresAtEpochMs,
)


private inline fun <reified T : Enum<T>> safeEnum(raw: String?, fallback: T): T = raw
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
    ?: fallback

private inline fun <reified T : Enum<T>> safeEnumOrNull(raw: String?): T? = raw
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
