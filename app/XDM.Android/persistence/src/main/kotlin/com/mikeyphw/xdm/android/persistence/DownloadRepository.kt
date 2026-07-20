package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandRecord
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.AutomationRejectionReason
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.TrustedBlockManifest
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    suspend fun countDownloads(): Int = database.downloadDao().count()
    suspend fun countQueues(): Int = database.queueDao().count()
    suspend fun save(download: Download) = database.downloadDao().upsert(download.toEntity())
    suspend fun saveAll(downloads: List<Download>) = database.downloadDao().upsertAll(downloads.map { it.toEntity() })
    suspend fun saveQueue(queue: QueueDefinition) = database.queueDao().upsertAll(listOf(queue.toEntity()))
    suspend fun saveQueues(queues: List<QueueDefinition>) = database.queueDao().upsertAll(queues.map { it.toEntity() })
    suspend fun deleteQueue(id: String) = database.queueDao().delete(id)
    suspend fun saveSchedule(rule: ScheduleRule) = database.scheduleDao().upsertAll(listOf(rule.toEntity()))
    suspend fun saveSchedules(rules: List<ScheduleRule>) = database.scheduleDao().upsertAll(rules.map { it.toEntity() })
    suspend fun deleteSchedule(id: String) = database.scheduleDao().delete(id)
    suspend fun saveRecovery(records: List<RecoveryRecord>) = database.recoveryDao().upsertAll(records.map { it.toEntity() })
    suspend fun saveRecovery(record: RecoveryRecord) = database.recoveryDao().upsert(record.toEntity())
    suspend fun deleteRecovery(id: String) = database.recoveryDao().delete(id)
    suspend fun saveFinalizationJournal(journal: FinalizationJournal) = database.finalizationDao().upsert(journal.toEntity())
    suspend fun saveChecksumExpectation(expectation: ChecksumExpectation) = database.checksumDao().upsertExpectation(expectation.toEntity())
    suspend fun saveChecksumResult(result: ChecksumResult) = database.checksumDao().upsertResult(result.toEntity())
    suspend fun saveVerificationRecord(record: VerificationRecord) = database.checksumDao().upsertVerification(record.toEntity())
    suspend fun saveTrustedManifest(manifest: TrustedBlockManifest) = database.checksumDao().upsertTrustedManifest(manifest.toEntity())
    suspend fun saveMediaCapture(record: MediaCaptureRecord) = database.mediaCaptureDao().upsert(record.toEntity())
    suspend fun saveMediaCaptures(records: List<MediaCaptureRecord>) = database.mediaCaptureDao().upsertAll(records.map { it.toEntity() })
    suspend fun saveMediaVariants(records: List<MediaVariant>) = database.mediaCaptureDao().upsertVariants(records.map { it.toEntity() })
    suspend fun variantsForMediaCapture(captureId: String): List<MediaVariant> = database.mediaCaptureDao().variantsForCapture(captureId).map { it.toModel() }
    suspend fun selectMediaVariant(captureId: String, variant: MediaVariant, updatedAtEpochMs: Long = System.currentTimeMillis()) = database.mediaCaptureDao().selectVariant(captureId, variant.id, variant.url, MediaResolutionStatus.Resolved.name, updatedAtEpochMs)
    suspend fun findMediaCapture(id: String): MediaCaptureRecord? = database.mediaCaptureDao().findById(id)?.toModel()
    suspend fun markMediaDownloadCreated(captureId: String, downloadId: String, updatedAtEpochMs: Long = System.currentTimeMillis()) = database.mediaCaptureDao().markDownloadCreated(captureId, MediaCaptureStatus.DownloadCreated.name, downloadId, updatedAtEpochMs)
    suspend fun deleteMediaCapture(id: String) = database.mediaCaptureDao().delete(id)
    suspend fun findAutomationCommandByKey(idempotencyKey: String): AutomationCommandRecord? = database.automationCommandDao().findByIdempotencyKey(idempotencyKey)?.toModel()
    suspend fun saveAutomationCommand(record: AutomationCommandRecord) = database.automationCommandDao().upsert(record.toEntity())
    suspend fun findDownload(id: String): Download? = database.downloadDao().findById(id)?.toModel()
    suspend fun deleteDownload(id: String) = database.downloadDao().delete(id)
    suspend fun findDownloadsByStates(states: Set<DownloadState>): List<Download> =
        if (states.isEmpty()) emptyList() else database.downloadDao().findByStates(states.map { it.name }).map { it.toModel() }

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


private fun AutomationCommandEntity.toModel() = AutomationCommandRecord(
    id = id,
    idempotencyKey = idempotencyKey,
    source = runCatching { AutomationCommandSource.valueOf(source) }.getOrDefault(AutomationCommandSource.Internal),
    action = runCatching { AutomationCommandAction.valueOf(action) }.getOrDefault(AutomationCommandAction.Unknown),
    url = url,
    fileName = fileName,
    pageTitle = pageTitle,
    pageUrl = pageUrl,
    mediaCaptureId = mediaCaptureId,
    downloadId = downloadId,
    status = runCatching { AutomationCommandStatus.valueOf(status) }.getOrDefault(AutomationCommandStatus.Failed),
    resultMessage = resultMessage,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    originPackage = originPackage,
    originHost = originHost,
    sanitizedHeaders = sanitizedHeaders,
    rejectionReason = runCatching { AutomationRejectionReason.valueOf(rejectionReason) }.getOrDefault(AutomationRejectionReason.None),
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
    originHost = originHost,
    sanitizedHeaders = sanitizedHeaders,
    rejectionReason = rejectionReason.name,
)

private fun DownloadEntity.toModel() = Download(
    id = id,
    fileName = fileName,
    sourceUrl = sourceUrl,
    destinationUri = destinationUri,
    state = DownloadState.valueOf(state),
    backend = BackendType.valueOf(backend),
    bytesReceived = bytesReceived,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    queueId = queueId,
    priority = priority,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    errorMessage = errorMessage,
    userLabel = userLabel,
    conflictPolicy = runCatching { FilenameConflictPolicy.valueOf(conflictPolicy) }.getOrDefault(FilenameConflictPolicy.Rename),
    mimeType = mimeType,
    requestedBackend = runCatching { BackendType.valueOf(requestedBackend) }.getOrDefault(BackendType.Automatic),
    backendSelectionReason = runCatching { BackendSelectionReason.valueOf(backendSelectionReason) }.getOrDefault(BackendSelectionReason.DefaultNative),
    backendSelectionExplanation = backendSelectionExplanation,
    allowBackendFallback = allowBackendFallback,
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
)
private fun QueueEntity.toModel() = QueueDefinition(id, name, isEnabled, maxConcurrent, createdAtEpochMs)
private fun QueueDefinition.toEntity() = QueueEntity(id, name, isEnabled, maxConcurrent, createdAtEpochMs)
private fun ScheduleRuleEntity.toModel() = ScheduleRule(id, queueId, name, enabled, constraintsJson)
private fun ScheduleRule.toEntity() = ScheduleRuleEntity(id, queueId, name, enabled, constraintsJson)
private fun DestinationPermissionEntity.toModel() = DestinationPermission(
    uri = uri,
    displayName = displayName,
    type = runCatching { DestinationType.valueOf(providerType) }.getOrDefault(DestinationType.SafTree),
    persistedRead = persistedRead,
    persistedWrite = persistedWrite,
    status = runCatching { DestinationHealthStatus.valueOf(status) }.getOrDefault(DestinationHealthStatus.Unknown),
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

private fun MediaCaptureEntity.toModel() = MediaCaptureRecord(
    id = id,
    sourceUrl = sourceUrl,
    pageUrl = pageUrl,
    title = title,
    status = runCatching { MediaCaptureStatus.valueOf(status) }.getOrDefault(MediaCaptureStatus.Captured),
    kind = runCatching { MediaSourceKind.valueOf(kind) }.getOrDefault(MediaSourceKind.Unknown),
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
    resolutionStatus = runCatching { MediaResolutionStatus.valueOf(resolutionStatus) }.getOrDefault(MediaResolutionStatus.Unresolved),
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
    kind = runCatching { MediaVariantKind.valueOf(kind) }.getOrDefault(MediaVariantKind.Primary),
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
