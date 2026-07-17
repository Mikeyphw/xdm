package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import com.mikeyphw.xdm.android.transfer.Aria2TaskMappingStore

class RoomAria2TaskMappingStore(private val database: AppDatabase) : Aria2TaskMappingStore {
    private val dao get() = database.aria2SessionMappingDao()

    override suspend fun upsert(mapping: Aria2TaskMapping) = dao.upsert(mapping.toEntity())
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
