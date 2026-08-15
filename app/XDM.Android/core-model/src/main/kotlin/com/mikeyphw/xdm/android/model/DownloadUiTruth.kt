package com.mikeyphw.xdm.android.model

enum class CompletedArtifactHealth {
    Present,
    Missing,
    PermissionLost,
    ProviderChanged,
    SizeMismatch,
    Unknown,
}

data class CompletedArtifactCapabilities(
    val health: CompletedArtifactHealth = CompletedArtifactHealth.Unknown,
    val readable: Boolean = false,
    val shareable: Boolean = false,
    val renameable: Boolean = false,
    val deletable: Boolean = false,
    val locationBrowsable: Boolean = false,
    val friendlyLocation: String = "Saved destination",
    val androidUri: String? = null,
    val providerLabel: String = "Unknown provider",
    val sizeBytes: Long? = null,
    val detail: String = "Artifact capability has not been checked yet.",
) {
    val present: Boolean get() = health == CompletedArtifactHealth.Present
}

data class DownloadActionContext(
    val queuePosition: Int? = null,
    val queueSize: Int = 0,
    val latestVerification: VerificationRecord? = null,
    val latestChecksum: ChecksumResult? = null,
    val validatedPartialAvailable: Boolean = false,
    val artifact: CompletedArtifactCapabilities = CompletedArtifactCapabilities(),
    val backendMigrationAvailable: Boolean = false,
    val postProcessingInputAvailable: Boolean = false,
    val publicSourceUrl: String? = null,
    val exactRequestReplayAvailable: Boolean = false,
) {
    fun canMoveUp(): Boolean = queuePosition != null && queuePosition > 1
    fun canMoveDown(): Boolean = queuePosition != null && queuePosition < queueSize
    fun verificationFailed(): Boolean = latestVerification?.status == VerificationStatus.Failed || latestChecksum?.matchesExpectation == false
    fun verificationPassed(): Boolean = !verificationFailed() && (
        latestVerification?.status == VerificationStatus.Passed || latestChecksum?.matchesExpectation == true
    )
}

data class DownloadUiTruth(
    val badge: String,
    val status: String,
    val supportingText: String,
    val byteProgressText: String,
    val overallProgressText: String,
    val trailingText: String,
    val verificationText: String,
    val storageText: String,
    val resumeText: String,
)

object DownloadUiTruthPlanner {
    fun contextFor(
        download: Download,
        downloads: List<Download>,
        verificationRecords: List<VerificationRecord> = emptyList(),
        checksumResults: List<ChecksumResult> = emptyList(),
        artifact: CompletedArtifactCapabilities = CompletedArtifactCapabilities(),
        backendMigrationAvailable: Boolean = false,
        postProcessingInputAvailable: Boolean = false,
        validatedPartialAvailable: Boolean = false,
        exactRequestReplayAvailable: Boolean = false,
    ): DownloadActionContext {
        val queue = downloads
            .filter { (it.queueId ?: "default") == (download.queueId ?: "default") && it.state in queueStates }
            .sortedWith(compareByDescending<Download> { it.priority }.thenBy { it.createdAtEpochMs })
        val position = queue.indexOfFirst { it.id == download.id }.takeIf { it >= 0 }?.plus(1)
        return DownloadActionContext(
            queuePosition = position,
            queueSize = queue.size,
            latestVerification = verificationRecords.filter { it.downloadId == download.id }.maxByOrNull { it.updatedAtEpochMs },
            latestChecksum = checksumResults.filter { it.downloadId == download.id }.maxByOrNull { it.verifiedAtEpochMs },
            validatedPartialAvailable = validatedPartialAvailable && download.state in resumableStates,
            artifact = artifact,
            backendMigrationAvailable = backendMigrationAvailable,
            postProcessingInputAvailable = postProcessingInputAvailable,
            publicSourceUrl = ExternalUrlPolicy.persistableUrl(download.sourceUrl),
            exactRequestReplayAvailable = exactRequestReplayAvailable,
        )
    }

    fun truth(download: Download, context: DownloadActionContext): DownloadUiTruth {
        val queueText = context.queuePosition?.let { "Queue position $it of ${context.queueSize}" }
        val policyReason = download.errorMessage.orEmpty()
            .takeIf { it.startsWith("Queue policy:") }
            ?.removePrefix("Queue policy:")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val status = when (download.state) {
            DownloadState.Created -> "Ready to queue"
            DownloadState.Queued -> queueText ?: "Waiting in queue"
            DownloadState.Connecting -> "Connecting"
            DownloadState.Downloading -> "Downloading"
            DownloadState.Paused -> "Paused by you"
            DownloadState.WaitingForNetwork -> "Waiting for an allowed network"
            DownloadState.WaitingForPower -> "Waiting for required power conditions"
            DownloadState.Verifying -> "Verifying file integrity"
            DownloadState.Repairing -> "Repairing verified damaged ranges"
            DownloadState.Finalizing -> "Committing the completed file"
            DownloadState.Completed -> completedStatus(context)
            DownloadState.Failed -> "Transfer failed"
            DownloadState.Cancelled -> "Cancelled"
            DownloadState.RecoveryRequired -> "Recovery review required"
        }
        val badge = when (download.state) {
            DownloadState.Created -> "Ready"
            DownloadState.Queued -> "Queued"
            DownloadState.Connecting -> "Connecting"
            DownloadState.Downloading -> "Downloading"
            DownloadState.Paused -> "Paused"
            DownloadState.WaitingForNetwork -> "Network hold"
            DownloadState.WaitingForPower -> "Power hold"
            DownloadState.Verifying -> "Verifying"
            DownloadState.Repairing -> "Repairing"
            DownloadState.Finalizing -> "Finalizing"
            DownloadState.Completed -> completedBadge(context)
            DownloadState.Failed -> "Failed"
            DownloadState.Cancelled -> "Cancelled"
            DownloadState.RecoveryRequired -> "Recovery"
        }
        val byteProgress = when {
            download.totalBytes != null && download.totalBytes > 0L ->
                "${download.bytesReceived.coerceAtLeast(0L).coerceAtMost(download.totalBytes)} of ${download.totalBytes} bytes"
            download.bytesReceived > 0L -> "${download.bytesReceived.coerceAtLeast(0L)} bytes received"
            else -> "No payload bytes recorded"
        }
        val overall = when (download.state) {
            DownloadState.Verifying -> "Payload received; integrity verification in progress"
            DownloadState.Repairing -> "Payload received; trusted repair in progress"
            DownloadState.Finalizing -> "Payload received; destination commit in progress"
            DownloadState.Completed -> status
            else -> status
        }
        val supporting = when {
            download.state == DownloadState.Completed -> completedStatus(context)
            policyReason != null -> policyReason
            download.state == DownloadState.Queued -> queueText ?: status
            download.state == DownloadState.Failed && !download.errorMessage.isNullOrBlank() -> download.errorMessage.orEmpty()
            download.state == DownloadState.RecoveryRequired && !download.errorMessage.isNullOrBlank() -> download.errorMessage.orEmpty()
            else -> status
        }
        val trailing = when {
            download.state == DownloadState.Downloading && download.speedBytesPerSecond > 0L -> "${download.speedBytesPerSecond} B/s"
            download.state == DownloadState.Queued -> queueText ?: "Queued"
            else -> badge
        }
        val verification = when {
            context.verificationPassed() -> "Passed"
            context.verificationFailed() -> "Failed"
            context.latestVerification?.status == VerificationStatus.Running -> "In progress"
            else -> "Not confirmed"
        }
        val storage = listOf(context.artifact.friendlyLocation, context.artifact.providerLabel, context.artifact.health.name)
            .filter(String::isNotBlank).distinct().joinToString(" • ")
        val resume = if (context.validatedPartialAvailable) {
            "Validated partial data is available for a guarded resume."
        } else {
            "Resume availability will be decided from durable validators and the current partial artifact."
        }
        return DownloadUiTruth(badge, status, supporting, byteProgress, overall, trailing, verification, storage, resume)
    }

    private fun completedStatus(context: DownloadActionContext): String = when (context.artifact.health) {
        CompletedArtifactHealth.Missing -> "Completed record; saved file is missing"
        CompletedArtifactHealth.PermissionLost -> "Completed record; storage permission was lost"
        CompletedArtifactHealth.ProviderChanged -> "Completed record; storage provider changed"
        CompletedArtifactHealth.SizeMismatch -> "Completed file size no longer matches the committed artifact"
        CompletedArtifactHealth.Unknown -> "Download complete; saved file health is not confirmed"
        CompletedArtifactHealth.Present -> when {
            context.verificationFailed() -> "Completed file failed verification"
            context.verificationPassed() -> "Verified and ready"
            else -> "Download complete; verification not confirmed"
        }
    }

    private fun completedBadge(context: DownloadActionContext): String = when (context.artifact.health) {
        CompletedArtifactHealth.Missing -> "File missing"
        CompletedArtifactHealth.PermissionLost -> "Access lost"
        CompletedArtifactHealth.ProviderChanged -> "Storage changed"
        CompletedArtifactHealth.SizeMismatch -> "Size mismatch"
        CompletedArtifactHealth.Unknown -> "Needs check"
        CompletedArtifactHealth.Present -> when {
            context.verificationFailed() -> "Verification failed"
            context.verificationPassed() -> "Verified"
            else -> "Complete"
        }
    }

    private val queueStates = setOf(
        DownloadState.Created,
        DownloadState.Queued,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
    )
    private val resumableStates = setOf(
        DownloadState.Paused,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
        DownloadState.Failed,
        DownloadState.RecoveryRequired,
    )
}
