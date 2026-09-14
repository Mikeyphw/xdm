package com.mikeyphw.xdm.android.model

/**
 * XAR13: durable action contract used by the Downloads surface before it mutates state.
 * UI rows are hints only; executable actions must reload the row and bind to its current
 * attempt generation/revision before queueing, archiving, replacing, deleting, or retrying.
 */
data class DownloadActionStateSnapshot(
    val downloadId: String,
    val state: DownloadState,
    val attemptGeneration: Long,
    val rowRevision: Long,
    val backend: BackendType,
    val archived: Boolean,
    val nativeHlsOwned: Boolean = false,
) {
    val active: Boolean get() = state in DownloadActionExecutionTruth.activeStates
    val terminal: Boolean get() = state in DownloadActionExecutionTruth.terminalStates
    val mutableQueueState: Boolean get() = state in DownloadActionExecutionTruth.queueMutationStates
}

enum class DownloadActionExecutionStatus {
    Accepted,
    NoOp,
    RejectedStaleState,
    RejectedActiveOwner,
    RejectedPersistence,
    RejectedMissingHandoff,
    Partial,
}

data class DownloadActionExecutionResult(
    val downloadId: String,
    val action: DownloadActionKind,
    val status: DownloadActionExecutionStatus,
    val message: String,
    val attemptGeneration: Long? = null,
    val rowRevision: Long? = null,
) {
    val accepted: Boolean get() = status == DownloadActionExecutionStatus.Accepted
}

data class DownloadBulkActionResult(
    val action: DownloadActionKind,
    val requested: Int,
    val accepted: List<DownloadActionExecutionResult>,
    val rejected: List<DownloadActionExecutionResult>,
) {
    val acceptedCount: Int get() = accepted.size
    val rejectedCount: Int get() = rejected.size
    val partial: Boolean get() = accepted.isNotEmpty() && rejected.isNotEmpty()
    val summary: String get() = when {
        requested == 0 -> "No downloads were selected."
        rejected.isEmpty() -> "${accepted.size} of $requested download action(s) committed."
        accepted.isEmpty() -> "No selected download action could be committed. ${rejected.firstOrNull()?.message.orEmpty()}".trim()
        else -> "${accepted.size} of $requested download action(s) committed; ${rejected.size} were retained because their current state changed or an active owner still controls them."
    }
}

data class FreshRedownloadPreparation(
    val sourceDownloadId: String,
    val targetDownloadId: String,
    val sourceAttemptGeneration: Long,
    val targetAttemptGeneration: Long,
    val exactRequestUrl: String,
    val handoffClonedBeforeQueue: Boolean,
    val approvalsPreservedBecauseExactUrlMatches: Boolean,
)

object DownloadActionExecutionTruth {
    val activeStates: Set<DownloadState> = setOf(
        DownloadState.Connecting,
        DownloadState.Downloading,
        DownloadState.Verifying,
        DownloadState.Repairing,
        DownloadState.Finalizing,
    )
    val terminalStates: Set<DownloadState> = setOf(
        DownloadState.Completed,
        DownloadState.Failed,
        DownloadState.Cancelled,
        DownloadState.RecoveryRequired,
    )
    val queueMutationStates: Set<DownloadState> = setOf(
        DownloadState.Created,
        DownloadState.Queued,
        DownloadState.Paused,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
    )

    fun snapshot(download: Download, nativeHlsOwned: Boolean = false): DownloadActionStateSnapshot =
        DownloadActionStateSnapshot(
            downloadId = download.id,
            state = download.state,
            attemptGeneration = download.attemptGeneration,
            rowRevision = download.rowRevision,
            backend = download.backend,
            archived = download.archived,
            nativeHlsOwned = nativeHlsOwned,
        )

    fun sameObservedRevision(observed: Download, current: Download): Boolean =
        observed.id == current.id &&
            observed.observedAttemptGeneration == current.attemptGeneration &&
            observed.rowRevision == current.rowRevision

    fun policyOverrideFromCurrent(download: Download): Boolean =
        download.state in queueMutationStates &&
            download.errorMessage.orEmpty().startsWith("Queue policy:")

    fun completedArtifactCommitted(download: Download): Boolean =
        download.state != DownloadState.Completed ||
            (!download.completedArtifactUri.isNullOrBlank() &&
                download.completedArtifactGeneration == download.attemptGeneration &&
                download.completedArtifactBytes != null &&
                download.completedArtifactBytes >= 0L)

    fun canArchiveCurrent(download: Download, nativeHlsOwned: Boolean): Boolean =
        !nativeHlsOwned && download.state !in activeStates

    fun staleRevisionResult(download: Download, action: DownloadActionKind): DownloadActionExecutionResult =
        DownloadActionExecutionResult(
            downloadId = download.id,
            action = action,
            status = DownloadActionExecutionStatus.RejectedStaleState,
            message = "The download changed before ${action.name} could be committed. Refresh the row and try again.",
            attemptGeneration = download.attemptGeneration,
            rowRevision = download.rowRevision,
        )

    fun activeOwnerResult(download: Download, action: DownloadActionKind, ownerLabel: String): DownloadActionExecutionResult =
        DownloadActionExecutionResult(
            downloadId = download.id,
            action = action,
            status = DownloadActionExecutionStatus.RejectedActiveOwner,
            message = "$ownerLabel still owns ${download.fileName}; XDM did not hide or delete active work.",
            attemptGeneration = download.attemptGeneration,
            rowRevision = download.rowRevision,
        )
}
