package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
import com.mikeyphw.xdm.android.transfer.FinalizationJournalStore

class RoomFinalizationJournalStore(private val database: AppDatabase) : FinalizationJournalStore {
    private val dao get() = database.finalizationDao()

    override suspend fun save(journal: FinalizationJournal) = dao.upsert(journal.toEntity())

    override suspend fun find(downloadId: String): FinalizationJournal? = dao.findByDownload(downloadId)?.toModel()

    override suspend fun listIncomplete(): List<FinalizationJournal> = dao.listIncomplete().map(FinalizationJournalEntity::toModel)

    override suspend fun delete(downloadId: String) = dao.deleteByDownload(downloadId)
}

fun FinalizationJournalEntity.toModel() = FinalizationJournal(
    id = id,
    downloadId = downloadId,
    stage = runCatching { FinalizationJournalStage.valueOf(stage) }.getOrDefault(FinalizationJournalStage.RecoveryRequired),
    sourcePath = sourcePath,
    stagingPath = stagingPath.ifBlank { null },
    destinationUri = destinationUri,
    bytesExpected = bytesExpected,
    bytesPromoted = bytesPromoted,
    checksumAlgorithm = checksumAlgorithm?.let { runCatching { ChecksumAlgorithm.valueOf(it) }.getOrNull() },
    checksumHex = checksumHex,
    message = message,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

fun FinalizationJournal.toEntity() = FinalizationJournalEntity(
    id = id,
    downloadId = downloadId,
    stage = stage.name,
    sourcePath = sourcePath,
    destinationUri = destinationUri,
    updatedAtEpochMs = updatedAtEpochMs,
    stagingPath = stagingPath.orEmpty(),
    bytesExpected = bytesExpected,
    bytesPromoted = bytesPromoted,
    checksumAlgorithm = checksumAlgorithm?.name,
    checksumHex = checksumHex,
    message = message,
    createdAtEpochMs = createdAtEpochMs,
)
