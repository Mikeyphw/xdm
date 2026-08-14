package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.ChecksumVerificationService
import com.mikeyphw.xdm.android.transfer.ChecksumWorkflowStore
import com.mikeyphw.xdm.android.transfer.TrustedBlockManifestService

class CompletionVerificationCoordinator(
    private val checksumStore: ChecksumWorkflowStore,
    @Suppress("unused") private val ownershipStore: BackendOwnershipStore,
    private val artifactReader: CompletedArtifactReader = FileCompletedArtifactReader(),
    private val verifier: ChecksumVerificationService = ChecksumVerificationService(),
    private val blockManifestService: TrustedBlockManifestService = TrustedBlockManifestService(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun complete(download: Download, snapshot: BackendSnapshot): BackendSnapshot {
        if (snapshot.state != DownloadState.Completed) return snapshot
        val generation = snapshot.attemptGeneration.takeIf { it > 0L } ?: download.attemptGeneration
        val completedUri = snapshot.completedUri?.trim()?.takeIf(String::isNotBlank)
            ?: return missingArtifact(download, snapshot, generation, "Backend completed without a committed artifact URI.")
        val artifactSize = artifactReader.size(completedUri)
            ?: return missingArtifact(download, snapshot, generation, "Committed artifact size is unavailable, so XDM cannot prove the published byte count.")
        val readable = runCatching { artifactReader.open(completedUri)?.use { true } == true }.getOrDefault(false)
        if (!readable) return missingArtifact(download, snapshot, generation, "Committed artifact is no longer readable.")
        if (snapshot.totalBytes != null && snapshot.totalBytes != artifactSize) {
            return missingArtifact(
                download,
                snapshot,
                generation,
                "Committed artifact size $artifactSize does not match expected ${snapshot.totalBytes} bytes.",
                status = VerificationStatus.Failed,
            )
        }

        val expectations = checksumStore.expectations(download.id).map { expectation ->
            if (expectation.attemptGeneration == generation) expectation
            else expectation.copy(attemptGeneration = generation).also { checksumStore.saveExpectation(it) }
        }
        if (expectations.isEmpty()) {
            checksumStore.saveVerification(
                VerificationRecord(
                    id = "verification-${download.id}",
                    downloadId = download.id,
                    status = VerificationStatus.NoExpectation,
                    algorithm = null,
                    bytesVerified = artifactSize,
                    totalBytes = artifactSize,
                    message = "No checksum expectation is registered; committed artifact readability and length were validated.",
                    createdAtEpochMs = clock(),
                    updatedAtEpochMs = clock(),
                    attemptGeneration = generation,
                ),
            )
            return snapshot.copy(
                bytesReceived = artifactSize,
                totalBytes = artifactSize,
                completedUri = completedUri,
                errorMessage = null,
            )
        }

        var current = snapshot.copy(state = DownloadState.Verifying, speedBytesPerSecond = 0)
        for (expectation in expectations) {
            val result = verifier.verify(
                downloadId = download.id,
                totalBytes = artifactSize,
                openInput = { artifactReader.open(completedUri) ?: error("Committed artifact became unreadable during verification") },
                expectation = expectation,
                progress = { checksumStore.saveVerification(it) },
            )
            checksumStore.saveResult(result)
            if (result.matchesExpectation != true) {
                val file = artifactReader.asFile(completedUri)?.takeIf { it.isFile }
                val manifest = checksumStore.trustedManifest(download.id)?.takeIf { it.attemptGeneration == generation }
                val repairMessage = if (file != null && manifest != null) {
                    val plan = blockManifestService.planRepair(file, manifest)
                    "Checksum mismatch; ${plan.ranges.size} trusted block(s) need native selective repair."
                } else {
                    "Checksum mismatch; the committed provider artifact cannot be selectively repaired without trusted file-backed evidence."
                }
                checksumStore.saveVerification(
                    VerificationRecord(
                        id = "verification-${download.id}",
                        downloadId = download.id,
                        status = VerificationStatus.Failed,
                        algorithm = expectation.algorithm,
                        bytesVerified = result.bytesVerified,
                        totalBytes = artifactSize,
                        message = repairMessage,
                        createdAtEpochMs = clock(),
                        updatedAtEpochMs = clock(),
                        attemptGeneration = generation,
                    ),
                )
                return current.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = repairMessage)
            }
            current = current.copy(bytesReceived = result.bytesVerified, totalBytes = artifactSize)
        }

        artifactReader.asFile(completedUri)?.takeIf { it.isFile }?.let { file ->
            checksumStore.saveTrustedManifest(blockManifestService.create(download.id, file, attemptGeneration = generation))
        }
        checksumStore.saveVerification(
            VerificationRecord(
                id = "verification-${download.id}",
                downloadId = download.id,
                status = VerificationStatus.Passed,
                algorithm = expectations.last().algorithm,
                bytesVerified = artifactSize,
                totalBytes = artifactSize,
                message = "Checksum verification passed for the committed artifact URI.",
                createdAtEpochMs = clock(),
                updatedAtEpochMs = clock(),
                attemptGeneration = generation,
            ),
        )
        return current.copy(
            state = DownloadState.Completed,
            speedBytesPerSecond = 0,
            errorMessage = null,
            completedUri = completedUri,
            bytesReceived = artifactSize,
            totalBytes = artifactSize,
        )
    }

    private suspend fun missingArtifact(
        download: Download,
        snapshot: BackendSnapshot,
        generation: Long,
        message: String,
        status: VerificationStatus = VerificationStatus.MissingFile,
    ): BackendSnapshot {
        checksumStore.saveVerification(
            VerificationRecord(
                id = "verification-${download.id}",
                downloadId = download.id,
                status = status,
                algorithm = checksumStore.expectations(download.id).firstOrNull()?.algorithm,
                bytesVerified = 0,
                totalBytes = snapshot.totalBytes,
                message = message,
                createdAtEpochMs = clock(),
                updatedAtEpochMs = clock(),
                attemptGeneration = generation,
            ),
        )
        return snapshot.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = message)
    }
}
