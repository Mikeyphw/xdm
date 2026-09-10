package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadUiTruth
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary

internal enum class DownloadWorkspaceFilter(val label: String) {
    All("All"),
    Downloading("Downloading"),
    Waiting("Waiting"),
    Finished("Finished"),
}

internal data class DownloadWorkspaceCopy(
    val title: String,
    val subtitle: String,
    val emptyTitle: String,
    val emptyDescription: String,
)

internal data class DownloadWorkspaceMetrics(
    val downloadingCount: Int,
    val waitingCount: Int,
    val queuedCount: Int,
    val aggregateSpeedBytesPerSecond: Long,
    val remainingSeconds: Long?,
)

internal enum class DownloadQueueIssueKind {
    Storage,
    Network,
    Power,
    Schedule,
    Retry,
    Review,
}

internal data class DownloadQueueIssue(
    val kind: DownloadQueueIssueKind,
    val affectedCount: Int,
    val title: String,
    val detail: String,
    val actionLabel: String = "Retry check",
)

internal object DownloadsWorkspacePlanner {
    private val downloadingStates = setOf(
        DownloadState.Connecting,
        DownloadState.Downloading,
        DownloadState.Verifying,
        DownloadState.Repairing,
        DownloadState.Finalizing,
    )
    private val queuedStates = setOf(
        DownloadState.Created,
        DownloadState.Queued,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
    )
    private val waitingStates = queuedStates + DownloadState.Paused
    private val finishedStates = setOf(
        DownloadState.Completed,
        DownloadState.Failed,
        DownloadState.Cancelled,
        DownloadState.RecoveryRequired,
    )

    fun copyFor(filter: DownloadWorkspaceFilter): DownloadWorkspaceCopy = when (filter) {
        DownloadWorkspaceFilter.All -> DownloadWorkspaceCopy(
            title = "All downloads",
            subtitle = "Current transfers, waiting work, and recent outcomes in one place.",
            emptyTitle = "No downloads yet",
            emptyDescription = "Use New download to add a link. XDM will show progress and anything that needs action here.",
        )
        DownloadWorkspaceFilter.Downloading -> DownloadWorkspaceCopy(
            title = "Downloading",
            subtitle = "Transfers currently connecting, receiving, verifying, repairing, or saving.",
            emptyTitle = "Nothing is downloading",
            emptyDescription = "Active transfers will appear here as soon as they start.",
        )
        DownloadWorkspaceFilter.Waiting -> DownloadWorkspaceCopy(
            title = "Waiting",
            subtitle = "Queued, paused, or condition-held downloads that are not transferring right now.",
            emptyTitle = "Nothing is waiting",
            emptyDescription = "Queued and paused downloads will appear here until they can continue.",
        )
        DownloadWorkspaceFilter.Finished -> DownloadWorkspaceCopy(
            title = "Finished",
            subtitle = "Completed downloads and terminal outcomes that may still need review.",
            emptyTitle = "No finished downloads",
            emptyDescription = "Completed, failed, cancelled, and recovery-required downloads will appear here.",
        )
    }

    fun visibleDownloads(
        downloads: List<Download>,
        filter: DownloadWorkspaceFilter,
        query: String,
        includeArchived: Boolean,
        ordering: DownloadDashboardOrdering,
    ): List<Download> = downloads.asSequence()
        .filter { includeArchived || !it.archived }
        .filter { matchesFilter(it, filter) }
        .filter { query.isBlank() || matchesQuery(it, query) }
        .sortedWith(comparatorFor(filter, ordering))
        .toList()

    fun metrics(downloads: List<Download>): DownloadWorkspaceMetrics {
        val downloading = downloads.filter { it.state in downloadingStates }
        val waiting = downloads.filter { it.state in waitingStates }
        val queued = downloads.count { it.state in queuedStates }
        val receiving = downloads.filter { it.state == DownloadState.Downloading }
        val speed = receiving.sumOf { it.speedBytesPerSecond.coerceAtLeast(0L) }
        val allHaveKnownTotals = receiving.isNotEmpty() && receiving.all { it.totalBytes != null }
        val remainingBytes = if (allHaveKnownTotals) receiving.sumOf {
            ((it.totalBytes ?: 0L) - it.bytesReceived).coerceAtLeast(0L)
        } else 0L
        val remainingSeconds = speed.takeIf { it > 0L && allHaveKnownTotals }?.let { remainingBytes / it }
        return DownloadWorkspaceMetrics(
            downloadingCount = downloading.size,
            waitingCount = waiting.size,
            queuedCount = queued,
            aggregateSpeedBytesPerSecond = speed,
            remainingSeconds = remainingSeconds,
        )
    }

    fun queueIssue(summary: QueueIntelligenceSummary): DownloadQueueIssue? = when {
        summary.heldForStorage > 0 -> DownloadQueueIssue(
            DownloadQueueIssueKind.Storage,
            summary.heldForStorage,
            affectedTitle(summary.heldForStorage, "download is waiting for storage", "downloads are waiting for storage"),
            "XDM will retry when the destination is writable and has enough free space.",
        )
        summary.heldForNetwork > 0 -> DownloadQueueIssue(
            DownloadQueueIssueKind.Network,
            summary.heldForNetwork,
            affectedTitle(summary.heldForNetwork, "download is waiting for a network", "downloads are waiting for a network"),
            "The current connection does not match this queue's network requirements.",
        )
        summary.heldForPower > 0 -> DownloadQueueIssue(
            DownloadQueueIssueKind.Power,
            summary.heldForPower,
            affectedTitle(summary.heldForPower, "download is waiting for power", "downloads are waiting for power"),
            "The queue is waiting for its configured charging or battery condition.",
        )
        summary.heldForSchedule > 0 -> DownloadQueueIssue(
            DownloadQueueIssueKind.Schedule,
            summary.heldForSchedule,
            affectedTitle(summary.heldForSchedule, "download is outside its schedule", "downloads are outside their schedule"),
            "They will be reconsidered in an enabled schedule window.",
            actionLabel = "Check now",
        )
        summary.waitingForRetry > 0 -> DownloadQueueIssue(
            DownloadQueueIssueKind.Retry,
            summary.waitingForRetry,
            affectedTitle(summary.waitingForRetry, "download is waiting to retry", "downloads are waiting to retry"),
            "XDM is preserving retry policy instead of repeatedly restarting the transfer.",
            actionLabel = "Check now",
        )
        summary.manualReviewRequired > 0 || summary.retryLimitReached > 0 -> {
            val count = summary.manualReviewRequired + summary.retryLimitReached
            DownloadQueueIssue(
                DownloadQueueIssueKind.Review,
                count,
                affectedTitle(count, "download needs action", "downloads need action"),
                "Review the affected downloads for a recovery or retry decision.",
                actionLabel = "Check now",
            )
        }
        else -> null
    }

    fun rowStatus(download: Download, truth: DownloadUiTruth): String {
        val rawPolicy = download.errorMessage.orEmpty()
            .takeIf { it.startsWith("Queue policy:") }
            ?.removePrefix("Queue policy:")
            ?.trim()
            .orEmpty()
        val policy = when {
            rawPolicy.contains("Destination storage unavailable", ignoreCase = true) -> "Storage check failed"
            rawPolicy.contains("storage", ignoreCase = true) -> "Waiting for storage"
            rawPolicy.contains("wi-fi", ignoreCase = true) || rawPolicy.contains("network", ignoreCase = true) -> "Waiting for network"
            rawPolicy.contains("power", ignoreCase = true) || rawPolicy.contains("charging", ignoreCase = true) -> "Waiting for power"
            rawPolicy.contains("schedule", ignoreCase = true) -> "Waiting for schedule"
            rawPolicy.isNotBlank() -> firstUsefulLine(rawPolicy)
            else -> null
        }
        return when {
            policy != null -> "Waiting — $policy"
            download.state == DownloadState.Failed && !download.errorMessage.isNullOrBlank() ->
                "Failed — ${firstUsefulLine(download.errorMessage.orEmpty())}"
            download.state == DownloadState.RecoveryRequired && !download.errorMessage.isNullOrBlank() ->
                "Needs action — ${firstUsefulLine(download.errorMessage.orEmpty())}"
            else -> truth.status
        }
    }

    fun firstPolicyHeldDownload(downloads: List<Download>): Download? = downloads.firstOrNull { download ->
        download.errorMessage.orEmpty().startsWith("Queue policy:") && download.state in queuedStates
    }

    private fun matchesQuery(download: Download, query: String): Boolean {
        val needle = query.trim().lowercase()
        return listOf(
            download.fileName,
            hostFromUrl(download.sourceUrl),
            destinationUiLabel(download.destinationUri),
            download.userLabel.orEmpty(),
            download.backend.name,
            download.state.name,
        ).any { it.lowercase().contains(needle) }
    }

    private fun matchesFilter(download: Download, filter: DownloadWorkspaceFilter): Boolean = when (filter) {
        DownloadWorkspaceFilter.All -> true
        DownloadWorkspaceFilter.Downloading -> download.state in downloadingStates
        DownloadWorkspaceFilter.Waiting -> download.state in waitingStates
        DownloadWorkspaceFilter.Finished -> download.state in finishedStates
    }

    private fun comparatorFor(filter: DownloadWorkspaceFilter, ordering: DownloadDashboardOrdering): Comparator<Download> = when (ordering) {
        DownloadDashboardOrdering.Smart -> compareByDescending<Download> { priorityFor(it, filter) }.thenByDescending { it.createdAtEpochMs }.thenBy { it.id }
        DownloadDashboardOrdering.Recent -> compareByDescending<Download> { it.createdAtEpochMs }.thenBy { it.id }
        DownloadDashboardOrdering.Name -> compareBy<Download, String>(String.CASE_INSENSITIVE_ORDER) { it.fileName }.thenBy { it.id }
        DownloadDashboardOrdering.Progress -> compareByDescending<Download> { it.progressFraction }.thenByDescending { it.createdAtEpochMs }.thenBy { it.id }
    }

    private fun priorityFor(download: Download, filter: DownloadWorkspaceFilter): Int = when {
        download.state == DownloadState.RecoveryRequired -> 60
        download.state == DownloadState.Failed -> 55
        download.state == DownloadState.Downloading -> 50
        download.state in setOf(DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing) -> 45
        download.state == DownloadState.Connecting -> 40
        download.state == DownloadState.Queued -> 30
        download.state == DownloadState.Paused -> 25
        download.state == DownloadState.Completed && filter == DownloadWorkspaceFilter.Finished -> 20
        else -> 10
    }

    private fun affectedTitle(count: Int, singular: String, plural: String): String = "$count ${if (count == 1) singular else plural}"

    private fun firstUsefulLine(value: String): String = value
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
        .substringBefore(". ")
        .take(120)
        .ifBlank { "See details" }
}

internal fun formatRemainingTime(seconds: Long?): String = when {
    seconds == null -> "Unknown"
    seconds < 60L -> "< 1 min"
    seconds < 3_600L -> "${(seconds + 59L) / 60L} min"
    else -> {
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        if (minutes == 0L) "${hours} hr" else "${hours} hr ${minutes} min"
    }
}
