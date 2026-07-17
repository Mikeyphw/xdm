package com.mikeyphw.xdm.android.persistence

import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.ChecksumSource
import com.mikeyphw.xdm.android.model.RepairBlockStatus
import com.mikeyphw.xdm.android.model.TrustedBlock
import com.mikeyphw.xdm.android.model.TrustedBlockManifest
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import com.mikeyphw.xdm.android.transfer.ChecksumWorkflowStore

class RoomChecksumWorkflowStore(private val database: AppDatabase) : ChecksumWorkflowStore {
    private val dao get() = database.checksumDao()

    override suspend fun expectations(downloadId: String): List<ChecksumExpectation> =
        dao.expectations(downloadId).map(ChecksumExpectationEntity::toModel)

    override suspend fun saveExpectation(expectation: ChecksumExpectation) = dao.upsertExpectation(expectation.toEntity())

    override suspend fun saveResult(result: ChecksumResult) = dao.upsertResult(result.toEntity())

    override suspend fun results(downloadId: String): List<ChecksumResult> = dao.results(downloadId).map(ChecksumResultEntity::toModel)

    override suspend fun saveVerification(record: VerificationRecord) = dao.upsertVerification(record.toEntity())

    override suspend fun latestVerification(downloadId: String): VerificationRecord? = dao.latestVerification(downloadId)?.toModel()

    override suspend fun saveTrustedManifest(manifest: TrustedBlockManifest) = dao.upsertTrustedManifest(manifest.toEntity())

    override suspend fun trustedManifest(downloadId: String): TrustedBlockManifest? = dao.trustedManifest(downloadId)?.toModel()
}

fun ChecksumExpectationEntity.toModel() = ChecksumExpectation(
    id = id,
    downloadId = downloadId,
    algorithm = runCatching { ChecksumAlgorithm.valueOf(algorithm) }.getOrDefault(ChecksumAlgorithm.Sha256),
    expectedHex = expectedHex,
    source = runCatching { ChecksumSource.valueOf(source) }.getOrDefault(ChecksumSource.UserInput),
    createdAtEpochMs = 0L,
)

fun ChecksumExpectation.toEntity() = ChecksumExpectationEntity(
    id = id,
    downloadId = downloadId,
    algorithm = algorithm.name,
    expectedHex = expectedHex,
    source = source.name,
)

fun ChecksumResultEntity.toModel() = ChecksumResult(
    id = id,
    downloadId = downloadId,
    algorithm = runCatching { ChecksumAlgorithm.valueOf(algorithm) }.getOrDefault(ChecksumAlgorithm.Sha256),
    calculatedHex = calculatedHex,
    matchesExpectation = matchesExpectation,
    verifiedAtEpochMs = verifiedAtEpochMs,
    bytesVerified = bytesVerified,
    expectedHex = expectedHex,
)

fun ChecksumResult.toEntity() = ChecksumResultEntity(
    id = id,
    downloadId = downloadId,
    algorithm = algorithm.name,
    calculatedHex = calculatedHex,
    matchesExpectation = matchesExpectation,
    verifiedAtEpochMs = verifiedAtEpochMs,
    bytesVerified = bytesVerified,
    expectedHex = expectedHex,
)

fun VerificationRecordEntity.toModel() = VerificationRecord(
    id = id,
    downloadId = downloadId,
    status = runCatching { VerificationStatus.valueOf(status) }.getOrDefault(VerificationStatus.Pending),
    algorithm = algorithm?.let { runCatching { ChecksumAlgorithm.valueOf(it) }.getOrNull() },
    bytesVerified = bytesVerified,
    totalBytes = totalBytes,
    message = message,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

fun VerificationRecord.toEntity() = VerificationRecordEntity(
    id = id,
    downloadId = downloadId,
    status = status.name,
    algorithm = algorithm?.name,
    bytesVerified = bytesVerified,
    totalBytes = totalBytes,
    message = message,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

fun TrustedBlockManifestEntity.toModel() = TrustedBlockManifest(
    id = id,
    downloadId = downloadId,
    fileLength = fileLength,
    blockSize = blockSize,
    algorithm = runCatching { ChecksumAlgorithm.valueOf(algorithm) }.getOrDefault(ChecksumAlgorithm.Sha256),
    blocks = blocksJson.lineSequence().filter(String::isNotBlank).mapNotNull(::parseBlock).toList(),
    createdAtEpochMs = createdAtEpochMs,
)

fun TrustedBlockManifest.toEntity() = TrustedBlockManifestEntity(
    id = id,
    downloadId = downloadId,
    fileLength = fileLength,
    blockSize = blockSize,
    algorithm = algorithm.name,
    blocksJson = blocks.joinToString("\n") { block ->
        listOf(block.index, block.startByte, block.endByteInclusive, block.checksumHex, block.status.name).joinToString("|")
    },
    createdAtEpochMs = createdAtEpochMs,
)

private fun parseBlock(line: String): TrustedBlock? {
    val parts = line.split('|')
    if (parts.size != 5) return null
    return runCatching {
        TrustedBlock(
            index = parts[0].toInt(),
            startByte = parts[1].toLong(),
            endByteInclusive = parts[2].toLong(),
            checksumHex = parts[3],
            status = RepairBlockStatus.valueOf(parts[4]),
        )
    }.getOrNull()
}
