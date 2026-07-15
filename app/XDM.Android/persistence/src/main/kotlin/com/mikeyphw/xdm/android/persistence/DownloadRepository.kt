package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.DestinationType
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryClassification
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

    suspend fun countDownloads(): Int = database.downloadDao().count()
    suspend fun countQueues(): Int = database.queueDao().count()
    suspend fun save(download: Download) = database.downloadDao().upsert(download.toEntity())
    suspend fun saveAll(downloads: List<Download>) = database.downloadDao().upsertAll(downloads.map { it.toEntity() })
    suspend fun saveQueues(queues: List<QueueDefinition>) = database.queueDao().upsertAll(queues.map { it.toEntity() })
    suspend fun saveSchedules(rules: List<ScheduleRule>) = database.scheduleDao().upsertAll(rules.map { it.toEntity() })
    suspend fun saveRecovery(records: List<RecoveryRecord>) = database.recoveryDao().upsertAll(records.map { it.toEntity() })
    suspend fun findDownload(id: String): Download? = database.downloadDao().findById(id)?.toModel()
    suspend fun findDownloadsByStates(states: Set<DownloadState>): List<Download> =
        if (states.isEmpty()) emptyList() else database.downloadDao().findByStates(states.map { it.name }).map { it.toModel() }

    suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) {
        database.backendTaskDao().upsert(
            BackendTaskEntity(
                id = downloadId,
                downloadId = downloadId,
                backend = backend.name,
                backendTaskId = backendTaskId,
                destinationKey = ownership.destinationKey,
                partialIdentity = ownership.partialIdentity,
                ownershipGeneration = ownership.generation,
                ownershipStatus = ownership.status.name,
                lastSynchronizedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteBackendTask(downloadId: String) = database.backendTaskDao().deleteByDownload(downloadId)
    suspend fun saveDestinationPermission(permission: DestinationPermission) = database.destinationPermissionDao().upsert(permission.toEntity())
    suspend fun deleteDestinationPermission(uri: String) = database.destinationPermissionDao().delete(uri)
}

private fun DownloadEntity.toModel() = Download(id, fileName, sourceUrl, destinationUri, DownloadState.valueOf(state), BackendType.valueOf(backend), bytesReceived, totalBytes, speedBytesPerSecond, queueId, priority, createdAtEpochMs, updatedAtEpochMs, errorMessage, userLabel, runCatching { FilenameConflictPolicy.valueOf(conflictPolicy) }.getOrDefault(FilenameConflictPolicy.Rename), mimeType)
private fun Download.toEntity() = DownloadEntity(id, fileName, sourceUrl, destinationUri, state.name, backend.name, bytesReceived, totalBytes, speedBytesPerSecond, queueId, priority, createdAtEpochMs, updatedAtEpochMs, errorMessage, userLabel, conflictPolicy.name, mimeType)
private fun QueueEntity.toModel() = QueueDefinition(id, name, isEnabled, maxConcurrent, createdAtEpochMs)
private fun QueueDefinition.toEntity() = QueueEntity(id, name, isEnabled, maxConcurrent, createdAtEpochMs)
private fun ScheduleRuleEntity.toModel() = ScheduleRule(id, queueId, name, enabled, constraintsJson)
private fun ScheduleRule.toEntity() = ScheduleRuleEntity(id, queueId, name, enabled, constraintsJson)
private fun RecoveryRecordEntity.toModel() = RecoveryRecord(id, downloadId, artifactPath, RecoveryClassification.valueOf(classification), reason, createdAtEpochMs)
private fun RecoveryRecord.toEntity() = RecoveryRecordEntity(id, downloadId, artifactPath, classification.name, reason, createdAtEpochMs)

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
