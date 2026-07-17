package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.BackendMigrationStore

class RoomBackendMigrationStore(private val database: AppDatabase) : BackendMigrationStore {
    private val dao get() = database.backendMigrationDao()

    override suspend fun save(record: BackendMigrationRecord) = dao.upsert(record.toEntity())
    override suspend fun find(id: String): BackendMigrationRecord? = dao.find(id)?.toModel()
    override suspend fun listForDownload(downloadId: String): List<BackendMigrationRecord> =
        dao.listForDownload(downloadId).map(BackendMigrationEntity::toModel)
    override suspend fun listIncomplete(): List<BackendMigrationRecord> =
        dao.listIncomplete().map(BackendMigrationEntity::toModel)
}

internal fun BackendMigrationEntity.toModel() = BackendMigrationRecord(
    id = id,
    downloadId = downloadId,
    sourceBackend = BackendType.valueOf(sourceBackend),
    targetBackend = BackendType.valueOf(targetBackend),
    sourceGeneration = sourceGeneration,
    targetGeneration = targetGeneration,
    sourceTaskId = sourceTaskId,
    targetTaskId = targetTaskId,
    stage = BackendMigrationStage.valueOf(stage),
    sourceArtifactIdentity = sourceArtifactIdentity,
    targetArtifactIdentity = targetArtifactIdentity,
    restartFromZero = restartFromZero,
    message = message,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

internal fun BackendMigrationRecord.toEntity() = BackendMigrationEntity(
    id = id,
    downloadId = downloadId,
    sourceBackend = sourceBackend.name,
    targetBackend = targetBackend.name,
    sourceGeneration = sourceGeneration,
    targetGeneration = targetGeneration,
    sourceTaskId = sourceTaskId,
    targetTaskId = targetTaskId,
    stage = stage.name,
    sourceArtifactIdentity = sourceArtifactIdentity,
    targetArtifactIdentity = targetArtifactIdentity,
    restartFromZero = restartFromZero,
    message = message,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)
