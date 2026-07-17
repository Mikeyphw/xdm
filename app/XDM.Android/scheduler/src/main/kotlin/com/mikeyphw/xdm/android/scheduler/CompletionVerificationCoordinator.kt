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
import java.io.File
import java.net.URI
import java.nio.file.Paths

class CompletionVerificationCoordinator(
    private val checksumStore: ChecksumWorkflowStore,
    private val ownershipStore: BackendOwnershipStore,
    private val verifier: ChecksumVerificationService = ChecksumVerificationService(),
    private val blockManifestService: TrustedBlockManifestService = TrustedBlockManifestService(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun complete(download: Download, snapshot: BackendSnapshot): BackendSnapshot {
        if (snapshot.state != DownloadState.Completed) return snapshot
        val expectations = checksumStore.expectations(download.id)
        if (expectations.isEmpty()) {
            checksumStore.saveVerification(
                VerificationRecord(
                    id = "verification-${download.id}",
                    downloadId = download.id,
                    status = VerificationStatus.NoExpectation,
                    algorithm = null,
                    bytesVerified = snapshot.bytesReceived,
                    totalBytes = snapshot.totalBytes,
                    message = "No checksum expectation is registered; completion remains length-based.",
                    createdAtEpochMs = clock(),
                    updatedAtEpochMs = clock(),
                ),
            )
            return snapshot
        }
        val file = resolveCompletedFile(download, snapshot)
        if (file == null || !file.isFile) {
            val message = "Completed file is unavailable for checksum verification."
            checksumStore.saveVerification(
                VerificationRecord(
                    id = "verification-${download.id}",
                    downloadId = download.id,
                    status = VerificationStatus.MissingFile,
                    algorithm = expectations.firstOrNull()?.algorithm,
                    bytesVerified = 0,
                    totalBytes = snapshot.totalBytes,
                    message = message,
                    createdAtEpochMs = clock(),
                    updatedAtEpochMs = clock(),
                ),
            )
            return snapshot.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = message)
        }
        val started = snapshot.copy(state = DownloadState.Verifying, speedBytesPerSecond = 0)
        var current = started
        for (expectation in expectations) {
            val result = verifier.verify(download.id, file, expectation) { progress -> checksumStore.saveVerification(progress) }
            checksumStore.saveResult(result)
            if (result.matchesExpectation != true) {
                val manifest = checksumStore.trustedManifest(download.id)
                val repairMessage = if (manifest != null) {
                    val plan = blockManifestService.planRepair(file, manifest)
                    "Checksum mismatch; ${plan.ranges.size} trusted block(s) need native selective repair."
                } else {
                    "Checksum mismatch; no trusted block manifest exists yet, so restart or repair from a validated source."
                }
                checksumStore.saveVerification(
                    VerificationRecord(
                        id = "verification-${download.id}",
                        downloadId = download.id,
                        status = VerificationStatus.Failed,
                        algorithm = expectation.algorithm,
                        bytesVerified = result.bytesVerified,
                        totalBytes = file.length(),
                        message = repairMessage,
                        createdAtEpochMs = clock(),
                        updatedAtEpochMs = clock(),
                    ),
                )
                return current.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = repairMessage)
            }
            current = current.copy(bytesReceived = file.length(), totalBytes = snapshot.totalBytes ?: file.length())
        }
        val manifest = blockManifestService.create(download.id, file)
        checksumStore.saveTrustedManifest(manifest)
        checksumStore.saveVerification(
            VerificationRecord(
                id = "verification-${download.id}",
                downloadId = download.id,
                status = VerificationStatus.Passed,
                algorithm = expectations.last().algorithm,
                bytesVerified = file.length(),
                totalBytes = file.length(),
                message = "Checksum verification passed and trusted block manifest was recorded.",
                createdAtEpochMs = clock(),
                updatedAtEpochMs = clock(),
            ),
        )
        return current.copy(state = DownloadState.Completed, speedBytesPerSecond = 0, errorMessage = null, completedUri = file.toURI().toString())
    }

    private suspend fun resolveCompletedFile(download: Download, snapshot: BackendSnapshot): File? {
        snapshot.completedUri?.toFileOrNull()?.let { return it }
        download.destinationUri.toFileOrNull()?.let { destination ->
            if (destination.isDirectory) return File(destination, download.fileName)
            return destination
        }
        val ownership = ownershipStore.findByDownload(download.id)
        return ownership?.artifacts?.all()?.firstNotNullOfOrNull { it.toFileOrNull()?.takeIf(File::isFile) }
    }
}

private fun String.toFileOrNull(): File? = runCatching {
    val uri = URI(this)
    if (!uri.scheme.equals("file", ignoreCase = true)) null else Paths.get(uri).toFile()
}.getOrNull()
