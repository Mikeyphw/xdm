package com.mikeyphw.xdm.android.persistence

import androidx.room.withTransaction
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import com.mikeyphw.xdm.android.transfer.Aria2TaskMappingStore

class RoomAria2TaskMappingStore(private val database: AppDatabase) : Aria2TaskMappingStore {
    private val dao get() = database.aria2SessionMappingDao()

    override suspend fun upsert(mapping: Aria2TaskMapping) = database.withTransaction {
        dao.findByGid(mapping.gid)?.takeIf { it.downloadId != mapping.downloadId }?.let {
            error("aria2 GID ${mapping.gid} is already mapped to ${it.downloadId}")
        }
        dao.findByDownload(mapping.downloadId)?.let { current ->
            require(mapping.ownershipGeneration >= current.ownershipGeneration) { "aria2 mapping cannot regress ownership generation" }
            if (mapping.ownershipGeneration == current.ownershipGeneration) {
                require(mapping.updatedAtEpochMs > current.updatedAtEpochMs || mapping.toEntity() == current) { "aria2 mapping writes must advance monotonically within a generation" }
                require(current.status !in TERMINAL_MAPPING_STATES || mapping.status == current.status) { "aria2 mapping cannot leave a terminal state in the same generation" }
            }
        }
        dao.upsert(mapping.toEntity())
    }
    override suspend fun findByDownload(downloadId: String): Aria2TaskMapping? = dao.findByDownload(downloadId)?.toModel()
    override suspend fun findByGid(gid: String): Aria2TaskMapping? = dao.findByGid(gid)?.toModel()
    override suspend fun listAll(): List<Aria2TaskMapping> = dao.listAll().map(Aria2SessionMappingEntity::toModel)
    override suspend fun deleteByDownload(downloadId: String) = dao.deleteByDownload(downloadId)
    override suspend fun deleteByGid(gid: String) = dao.deleteByGid(gid)
}

private fun Aria2TaskMapping.toEntity() = Aria2SessionMappingEntity(
    id = downloadId,
    downloadId = downloadId,
    gid = gid,
    sourceUrl = sourceUrl,
    mirrorUrls = mirrorUrls.joinToString("\n"),
    destinationUri = destinationUri,
    destinationKey = destinationKey,
    fileName = fileName,
    conflictPolicy = conflictPolicy,
    mimeType = mimeType,
    outputPath = outputPath,
    controlPath = controlPath,
    ownershipMetadataPath = ownershipMetadataPath,
    sessionFilePath = sessionFilePath,
    expectedLength = expectedLength,
    ownershipGeneration = ownershipGeneration,
    backendInstanceId = backendInstanceId,
    backendSessionId = backendSessionId,
    status = status,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    lastSynchronizedAtEpochMs = lastSynchronizedAtEpochMs,
    lastErrorCode = lastErrorCode,
    lastErrorMessage = lastErrorMessage,
)

private fun Aria2SessionMappingEntity.toModel() = Aria2TaskMapping(
    downloadId = downloadId,
    gid = gid,
    sourceUrl = sourceUrl,
    mirrorUrls = mirrorUrls.lineSequence().filter(String::isNotBlank).toList(),
    destinationUri = destinationUri,
    destinationKey = destinationKey,
    fileName = fileName,
    conflictPolicy = conflictPolicy,
    mimeType = mimeType,
    outputPath = outputPath,
    controlPath = controlPath,
    ownershipMetadataPath = ownershipMetadataPath,
    sessionFilePath = sessionFilePath,
    expectedLength = expectedLength,
    ownershipGeneration = ownershipGeneration,
    backendInstanceId = backendInstanceId,
    backendSessionId = backendSessionId,
    status = status,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    lastSynchronizedAtEpochMs = lastSynchronizedAtEpochMs,
    lastErrorCode = lastErrorCode,
    lastErrorMessage = lastErrorMessage,
)


private val TERMINAL_MAPPING_STATES = setOf("Completed", "Removed", "Error", "FinalizationFailed")
