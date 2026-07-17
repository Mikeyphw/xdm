package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.RepairBlockStatus
import com.mikeyphw.xdm.android.model.SelectiveRepairPlan
import com.mikeyphw.xdm.android.model.SelectiveRepairRange
import com.mikeyphw.xdm.android.model.TrustedBlock
import com.mikeyphw.xdm.android.model.TrustedBlockManifest
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ChecksumWorkflowStore {
    suspend fun expectations(downloadId: String): List<ChecksumExpectation>
    suspend fun saveExpectation(expectation: ChecksumExpectation)
    suspend fun saveResult(result: ChecksumResult)
    suspend fun results(downloadId: String): List<ChecksumResult>
    suspend fun saveVerification(record: VerificationRecord)
    suspend fun latestVerification(downloadId: String): VerificationRecord?
    suspend fun saveTrustedManifest(manifest: TrustedBlockManifest)
    suspend fun trustedManifest(downloadId: String): TrustedBlockManifest?
}

class InMemoryChecksumWorkflowStore : ChecksumWorkflowStore {
    private val expectations = linkedMapOf<String, ChecksumExpectation>()
    private val results = linkedMapOf<String, ChecksumResult>()
    private val verifications = linkedMapOf<String, VerificationRecord>()
    private val manifests = linkedMapOf<String, TrustedBlockManifest>()

    override suspend fun expectations(downloadId: String): List<ChecksumExpectation> =
        expectations.values.filter { it.downloadId == downloadId }

    override suspend fun saveExpectation(expectation: ChecksumExpectation) {
        expectations[expectation.id] = expectation.copy(expectedHex = normalizeHex(expectation.expectedHex))
    }

    override suspend fun saveResult(result: ChecksumResult) {
        results[result.id] = result.copy(calculatedHex = normalizeHex(result.calculatedHex))
    }

    override suspend fun results(downloadId: String): List<ChecksumResult> =
        results.values.filter { it.downloadId == downloadId }

    override suspend fun saveVerification(record: VerificationRecord) {
        verifications[record.downloadId] = record
    }

    override suspend fun latestVerification(downloadId: String): VerificationRecord? = verifications[downloadId]

    override suspend fun saveTrustedManifest(manifest: TrustedBlockManifest) {
        manifests[manifest.downloadId] = manifest
    }

    override suspend fun trustedManifest(downloadId: String): TrustedBlockManifest? = manifests[downloadId]
}

class ChecksumVerificationService(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun verify(
        downloadId: String,
        file: File,
        expectation: ChecksumExpectation,
        progress: suspend (VerificationRecord) -> Unit = {},
    ): ChecksumResult = withContext(Dispatchers.IO) {
        require(file.isFile) { "Verification file is missing: ${file.absolutePath}" }
        val total = file.length()
        progress(
            VerificationRecord(
                id = "verification-$downloadId",
                downloadId = downloadId,
                status = VerificationStatus.Running,
                algorithm = expectation.algorithm,
                bytesVerified = 0,
                totalBytes = total,
                message = "Calculating ${expectation.algorithm.displayName()} checksum.",
                createdAtEpochMs = clock(),
                updatedAtEpochMs = clock(),
            ),
        )
        val calculated = digestFile(file, expectation.algorithm) { verified ->
            progress(
                VerificationRecord(
                    id = "verification-$downloadId",
                    downloadId = downloadId,
                    status = VerificationStatus.Running,
                    algorithm = expectation.algorithm,
                    bytesVerified = verified,
                    totalBytes = total,
                    message = "Verified $verified of $total bytes.",
                    createdAtEpochMs = clock(),
                    updatedAtEpochMs = clock(),
                ),
            )
        }
        val expected = normalizeHex(expectation.expectedHex)
        val matches = calculated == expected
        val now = clock()
        ChecksumResult(
            id = "${downloadId}-${expectation.algorithm.name}",
            downloadId = downloadId,
            algorithm = expectation.algorithm,
            calculatedHex = calculated,
            matchesExpectation = matches,
            verifiedAtEpochMs = now,
            bytesVerified = total,
            expectedHex = expected,
        )
    }

    suspend fun digestFile(
        file: File,
        algorithm: ChecksumAlgorithm,
        progress: suspend (Long) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(algorithm.messageDigestName())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var verified = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                verified += read.toLong()
                progress(verified)
            }
        }
        digest.digest().toHex()
    }
}

class TrustedBlockManifestService(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun create(downloadId: String, file: File, blockSize: Long = DEFAULT_BLOCK_SIZE): TrustedBlockManifest = withContext(Dispatchers.IO) {
        require(blockSize > 0) { "Block size must be positive" }
        require(file.isFile) { "Cannot create a trusted-block manifest for a missing file" }
        val length = file.length()
        val blocks = mutableListOf<TrustedBlock>()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(blockSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            var index = 0
            var start = 0L
            while (start < length) {
                val expected = minOf(buffer.size.toLong(), length - start).toInt()
                var readTotal = 0
                while (readTotal < expected) {
                    val read = input.read(buffer, readTotal, expected - readTotal)
                    if (read < 0) break
                    readTotal += read
                }
                val digest = MessageDigest.getInstance(ChecksumAlgorithm.Sha256.messageDigestName())
                digest.update(buffer, 0, readTotal)
                val end = start + readTotal - 1
                blocks += TrustedBlock(index, start, end, digest.digest().toHex())
                index += 1
                start = end + 1
            }
        }
        TrustedBlockManifest(
            id = "trusted-blocks-$downloadId",
            downloadId = downloadId,
            fileLength = length,
            blockSize = blockSize,
            algorithm = ChecksumAlgorithm.Sha256,
            blocks = blocks,
            createdAtEpochMs = clock(),
        )
    }

    suspend fun planRepair(file: File, manifest: TrustedBlockManifest): SelectiveRepairPlan = withContext(Dispatchers.IO) {
        val ranges = mutableListOf<SelectiveRepairRange>()
        if (!file.isFile) {
            manifest.blocks.forEach { block ->
                ranges += SelectiveRepairRange(block.index, block.startByte, block.endByteInclusive, RepairBlockStatus.Missing)
            }
            return@withContext SelectiveRepairPlan(manifest.downloadId, manifest.fileLength, manifest.blockSize, ranges)
        }
        RandomAccessBlockReader(file).use { reader ->
            manifest.blocks.forEach { block ->
                val status = when {
                    file.length() <= block.startByte -> RepairBlockStatus.Missing
                    reader.digest(block.startByte, block.endByteInclusive, manifest.algorithm) != normalizeHex(block.checksumHex) -> RepairBlockStatus.Corrupt
                    else -> RepairBlockStatus.Trusted
                }
                if (status != RepairBlockStatus.Trusted) {
                    ranges += SelectiveRepairRange(block.index, block.startByte, block.endByteInclusive, status)
                }
            }
        }
        SelectiveRepairPlan(manifest.downloadId, manifest.fileLength, manifest.blockSize, ranges)
    }
}

private class RandomAccessBlockReader(private val file: File) : AutoCloseable {
    private val access = java.io.RandomAccessFile(file, "r")

    fun digest(start: Long, endInclusive: Long, algorithm: ChecksumAlgorithm): String {
        val digest = MessageDigest.getInstance(algorithm.messageDigestName())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        access.seek(start)
        var remaining = endInclusive - start + 1
        while (remaining > 0) {
            val read = access.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read.toLong()
        }
        return digest.digest().toHex()
    }

    override fun close() = access.close()
}

fun normalizeHex(value: String): String = value.trim().lowercase(Locale.ROOT).filter { it in '0'..'9' || it in 'a'..'f' }
fun ChecksumAlgorithm.messageDigestName(): String = when (this) {
    ChecksumAlgorithm.Sha256 -> "SHA-256"
    ChecksumAlgorithm.Sha512 -> "SHA-512"
}
fun ChecksumAlgorithm.displayName(): String = when (this) {
    ChecksumAlgorithm.Sha256 -> "SHA-256"
    ChecksumAlgorithm.Sha512 -> "SHA-512"
}
fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
fun newChecksumExpectationId(downloadId: String, algorithm: ChecksumAlgorithm): String =
    "checksum-$downloadId-${algorithm.name}-${UUID.randomUUID()}"

private const val DEFAULT_BUFFER_SIZE = 64 * 1024
private const val DEFAULT_BLOCK_SIZE = 1024L * 1024L
