package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.BackendMigrationStore

class RoomBackendMigrationStore(private val database: AppDatabase) : BackendMigrationStore {
    private val dao get() = database.backendMigrationDao()

    override suspend fun tryCreate(record: BackendMigrationRecord): Boolean = dao.insertIfAbsent(record.toEntity()) != -1L
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
    sourceBackend = safeEnum(sourceBackend, BackendType.Automatic),
    targetBackend = safeEnum(targetBackend, BackendType.Automatic),
    sourceGeneration = sourceGeneration,
    targetGeneration = targetGeneration,
    sourceTaskId = sourceTaskId,
    targetTaskId = targetTaskId,
    stage = safeEnum(stage, BackendMigrationStage.RecoveryRequired),
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
    activeClaim = if (stage in setOf(BackendMigrationStage.Completed, BackendMigrationStage.Failed)) null else ACTIVE_MIGRATION_CLAIM,
)

private const val ACTIVE_MIGRATION_CLAIM = "active"

private inline fun <reified T : Enum<T>> safeEnum(raw: String?, fallback: T): T = raw
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
    ?: fallback
