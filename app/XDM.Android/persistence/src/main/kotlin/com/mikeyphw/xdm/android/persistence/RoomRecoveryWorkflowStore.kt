package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.transfer.RecoveryWorkflowStore

class RoomRecoveryWorkflowStore(private val database: AppDatabase) : RecoveryWorkflowStore {
    private val dao get() = database.recoveryDao()

    override suspend fun saveRecovery(record: RecoveryRecord) = dao.upsert(record.toEntity())

    override suspend fun saveRecovery(records: List<RecoveryRecord>) = dao.upsertAll(records.map(RecoveryRecord::toEntity))

    override suspend fun listRecovery(): List<RecoveryRecord> = dao.listAll().map(RecoveryRecordEntity::toModel)

    override suspend fun deleteRecovery(id: String) = dao.delete(id)
}

fun RecoveryRecordEntity.toModel() = RecoveryRecord(
    id = id,
    downloadId = downloadId,
    artifactPath = artifactPath,
    classification = runCatching { RecoveryClassification.valueOf(classification) }.getOrDefault(RecoveryClassification.NeedsRemoteValidation),
    reason = reason,
    createdAtEpochMs = createdAtEpochMs,
    recommendedAction = runCatching { RecoveryAction.valueOf(recommendedAction) }.getOrDefault(RecoveryAction.Validate),
    safeToResume = safeToResume,
)

fun RecoveryRecord.toEntity() = RecoveryRecordEntity(
    id = id,
    downloadId = downloadId,
    artifactPath = artifactPath,
    classification = classification.name,
    reason = reason,
    createdAtEpochMs = createdAtEpochMs,
    recommendedAction = recommendedAction.name,
    safeToResume = safeToResume,
)
