package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadState

internal enum class DownloadWorkspaceFilter(val label: String) {
    Active("Active"),
    Queued("Queued"),
    Paused("Paused"),
    Finished("Finished"),
    All("All"),
}

internal data class DownloadWorkspaceCopy(
    val title: String,
    val subtitle: String,
    val emptyTitle: String,
    val emptyDescription: String,
)

internal data class DownloadWorkspaceMetrics(
    val activeCount: Int,
    val queuedCount: Int,
    val aggregateSpeedBytesPerSecond: Long,
    val remainingSeconds: Long?,
)

internal object DownloadsWorkspacePlanner {
    private val activeStates = setOf(
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
    private val finishedStates = setOf(
        DownloadState.Completed,
        DownloadState.Failed,
        DownloadState.Cancelled,
        DownloadState.RecoveryRequired,
    )

    fun copyFor(filter: DownloadWorkspaceFilter): DownloadWorkspaceCopy = when (filter) {
        DownloadWorkspaceFilter.Active -> DownloadWorkspaceCopy(
            title = "Active transfers",
            subtitle = "Transfers currently connecting, receiving bytes, verifying, repairing, or committing.",
            emptyTitle = "No active transfers",
            emptyDescription = "Only work currently consuming an execution owner appears here.",
        )
        DownloadWorkspaceFilter.Queued -> DownloadWorkspaceCopy(
            title = "Queued and policy-held",
            subtitle = "Downloads waiting for a queue slot, network rule, power rule, or schedule.",
            emptyTitle = "The queue is clear",
            emptyDescription = "Downloads waiting to start will appear here. Paused items have their own filter.",
        )
        DownloadWorkspaceFilter.Paused -> DownloadWorkspaceCopy(
            title = "Paused",
            subtitle = "Downloads explicitly paused by you and awaiting a resume decision.",
            emptyTitle = "Nothing is paused",
            emptyDescription = "Paused transfers will appear here without being mislabeled as up next.",
        )
        DownloadWorkspaceFilter.Finished -> DownloadWorkspaceCopy(
            title = "Finished and needs attention",
            subtitle = "Completed, failed, cancelled, and recovery-required entries.",
            emptyTitle = "No finished downloads",
            emptyDescription = "Terminal outcomes will collect here with truthful next actions.",
        )
        DownloadWorkspaceFilter.All -> DownloadWorkspaceCopy(
            title = "All downloads",
            subtitle = "One timeline across every transfer state.",
            emptyTitle = "No downloads yet",
            emptyDescription = "Use New download to add a link and review it before queueing.",
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
        val active = downloads.filter { it.state in activeStates }
        val queued = downloads.count { it.state in queuedStates }
        val receiving = downloads.filter { it.state == DownloadState.Downloading }
        val speed = receiving.sumOf { it.speedBytesPerSecond.coerceAtLeast(0L) }
        val allHaveKnownTotals = receiving.isNotEmpty() && receiving.all { it.totalBytes != null }
        val remainingBytes = if (allHaveKnownTotals) receiving.sumOf {
            ((it.totalBytes ?: 0L) - it.bytesReceived).coerceAtLeast(0L)
        } else 0L
        val remainingSeconds = speed.takeIf { it > 0L && allHaveKnownTotals }?.let { remainingBytes / it }
        return DownloadWorkspaceMetrics(active.size, queued, speed, remainingSeconds)
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
        DownloadWorkspaceFilter.Active -> download.state in activeStates
        DownloadWorkspaceFilter.Queued -> download.state in queuedStates
        DownloadWorkspaceFilter.Paused -> download.state == DownloadState.Paused
        DownloadWorkspaceFilter.Finished -> download.state in finishedStates
        DownloadWorkspaceFilter.All -> true
    }

    private fun comparatorFor(filter: DownloadWorkspaceFilter, ordering: DownloadDashboardOrdering): Comparator<Download> = when (ordering) {
        DownloadDashboardOrdering.Smart -> compareByDescending<Download> { priorityFor(it, filter) }.thenByDescending { it.updatedAtEpochMs }
        DownloadDashboardOrdering.Recent -> compareByDescending { it.updatedAtEpochMs }
        DownloadDashboardOrdering.Name -> compareBy<Download, String>(String.CASE_INSENSITIVE_ORDER) { it.fileName }
        DownloadDashboardOrdering.Progress -> compareByDescending<Download> { it.progressFraction }.thenByDescending { it.updatedAtEpochMs }
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
