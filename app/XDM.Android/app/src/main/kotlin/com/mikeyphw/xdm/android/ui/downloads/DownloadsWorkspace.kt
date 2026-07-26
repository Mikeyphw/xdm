package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering

internal enum class DownloadWorkspaceFilter(val label: String) {
    Active("Active"),
    Queued("Queued"),
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
        DownloadState.Failed,
        DownloadState.RecoveryRequired,
    )

    private val queuedStates = setOf(
        DownloadState.Created,
        DownloadState.Queued,
        DownloadState.Paused,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
    )

    fun copyFor(filter: DownloadWorkspaceFilter): DownloadWorkspaceCopy = when (filter) {
        DownloadWorkspaceFilter.Active -> DownloadWorkspaceCopy(
            title = "In progress",
            subtitle = "Live progress and the next useful action.",
            emptyTitle = "Nothing is moving",
            emptyDescription = "Active downloads and items needing a retry will appear here.",
        )
        DownloadWorkspaceFilter.Queued -> DownloadWorkspaceCopy(
            title = "Up next",
            subtitle = "Downloads waiting for a slot, connection, schedule, or user action.",
            emptyTitle = "The queue is clear",
            emptyDescription = "Downloads waiting to start will appear here.",
        )
        DownloadWorkspaceFilter.Finished -> DownloadWorkspaceCopy(
            title = "Finished",
            subtitle = "Completed files, ready to open or manage.",
            emptyTitle = "No finished downloads",
            emptyDescription = "Completed files will collect here without crowding active work.",
        )
        DownloadWorkspaceFilter.All -> DownloadWorkspaceCopy(
            title = "All downloads",
            subtitle = "One clean timeline across every state.",
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
    ): List<Download> = downloads
        .asSequence()
        .filter { includeArchived || !it.archived }
        .filter { matchesFilter(it, filter) }
        .filter { query.isBlank() || matchesQuery(it, query) }
        .sortedWith(comparatorFor(filter, ordering))
        .toList()

    fun metrics(downloads: List<Download>): DownloadWorkspaceMetrics {
        val active = downloads.filter { it.state in activeStates }
        val queued = downloads.count { it.state in queuedStates }
        val speed = downloads
            .filter { it.state == DownloadState.Downloading }
            .sumOf { it.speedBytesPerSecond.coerceAtLeast(0L) }
        val remainingBytes = downloads
            .filter { it.state == DownloadState.Downloading }
            .sumOf { download ->
                val total = download.totalBytes ?: return@sumOf 0L
                (total - download.bytesReceived).coerceAtLeast(0L)
            }
        val remainingSeconds = speed.takeIf { it > 0L }?.let { remainingBytes / it }
        return DownloadWorkspaceMetrics(
            activeCount = active.size,
            queuedCount = queued,
            aggregateSpeedBytesPerSecond = speed,
            remainingSeconds = remainingSeconds,
        )
    }

    fun firstPolicyHeldDownload(downloads: List<Download>): Download? = downloads.firstOrNull { download ->
        download.errorMessage.orEmpty().startsWith("Queue policy:") &&
            download.state !in setOf(
                DownloadState.Downloading,
                DownloadState.Connecting,
                DownloadState.Completed,
                DownloadState.Cancelled,
            )
    }

    private fun matchesQuery(download: Download, query: String): Boolean {
        val needle = query.trim().lowercase()
        return listOf(
            download.fileName,
            download.sourceUrl,
            download.destinationUri,
            download.userLabel.orEmpty(),
            download.backend.name,
            download.state.name,
        ).any { it.lowercase().contains(needle) }
    }

    private fun matchesFilter(download: Download, filter: DownloadWorkspaceFilter): Boolean = when (filter) {
        DownloadWorkspaceFilter.Active -> download.state in activeStates
        DownloadWorkspaceFilter.Queued -> download.state in queuedStates
        DownloadWorkspaceFilter.Finished -> download.state == DownloadState.Completed
        DownloadWorkspaceFilter.All -> true
    }

    private fun comparatorFor(
        filter: DownloadWorkspaceFilter,
        ordering: DownloadDashboardOrdering,
    ): Comparator<Download> = when (ordering) {
        DownloadDashboardOrdering.Smart -> compareByDescending<Download> { priorityFor(it, filter) }.thenByDescending { it.updatedAtEpochMs }
        DownloadDashboardOrdering.Recent -> compareByDescending { it.updatedAtEpochMs }
        DownloadDashboardOrdering.Name -> compareBy<Download, String>(String.CASE_INSENSITIVE_ORDER) { it.fileName }
        DownloadDashboardOrdering.Progress -> compareByDescending<Download> { it.progressFraction }.thenByDescending { it.updatedAtEpochMs }
    }

    private fun priorityFor(download: Download, filter: DownloadWorkspaceFilter): Int = when {
        download.state == DownloadState.Failed || download.state == DownloadState.RecoveryRequired -> 50
        download.state == DownloadState.Downloading -> 40
        download.state == DownloadState.Connecting -> 35
        download.state == DownloadState.Queued -> 30
        download.state == DownloadState.Paused -> 25
        download.state == DownloadState.Completed && filter == DownloadWorkspaceFilter.Finished -> 20
        else -> 10
    }
}

internal fun formatRemainingTime(seconds: Long?): String = when {
    seconds == null -> "Up next"
    seconds < 60L -> "< 1 min"
    seconds < 3_600L -> "${(seconds + 59L) / 60L} min"
    else -> {
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        if (minutes == 0L) "${hours} hr" else "${hours} hr ${minutes} min"
    }
}
