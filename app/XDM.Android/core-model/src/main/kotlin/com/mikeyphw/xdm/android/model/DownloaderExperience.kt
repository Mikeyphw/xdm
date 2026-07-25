package com.mikeyphw.xdm.android.model

/** Review-first state for Add Download. No state in this model starts a transfer. */
enum class DownloadReviewReadiness {
    MissingLink,
    InvalidLink,
    ChooseDestination,
    ChoiceRecommended,
    Ready,
}

data class DownloadReviewStep(
    val label: String,
    val complete: Boolean,
    val detail: String,
)

data class DownloadReviewPlan(
    val normalizedUrl: String?,
    val kind: DownloadIntakeKind?,
    val readiness: DownloadReviewReadiness,
    val title: String,
    val guidance: String,
    val primaryActionLabel: String,
    val canStartDirectly: Boolean,
    val canInspectAsMedia: Boolean,
    val mediaInspectionRecommended: Boolean,
    val steps: List<DownloadReviewStep>,
)

/** Pure review planner shared by manual entry and explicit external handoff. */
object DownloadReviewPlanner {
    fun plan(
        url: String,
        fileName: String = "",
        mimeType: String? = null,
        destinationUri: String = "",
    ): DownloadReviewPlan {
        val hasInput = url.isNotBlank()
        val normalized = ExternalUrlPolicy.normalizedUrl(url)
        val kind = normalized?.let { DownloadIntakeClassifier.classify(it, fileName, mimeType) }
        val destinationReady = destinationUri.isNotBlank()
        val inspectable = kind in setOf(
            DownloadIntakeKind.DirectMedia,
            DownloadIntakeKind.AdaptiveMedia,
            DownloadIntakeKind.PageOrUnknown,
        )
        val inspectionRecommended = kind in setOf(
            DownloadIntakeKind.AdaptiveMedia,
            DownloadIntakeKind.PageOrUnknown,
        )
        val readiness = when {
            !hasInput -> DownloadReviewReadiness.MissingLink
            normalized == null -> DownloadReviewReadiness.InvalidLink
            !destinationReady -> DownloadReviewReadiness.ChooseDestination
            inspectionRecommended -> DownloadReviewReadiness.ChoiceRecommended
            else -> DownloadReviewReadiness.Ready
        }
        val title = when (readiness) {
            DownloadReviewReadiness.MissingLink -> "Add a download link"
            DownloadReviewReadiness.InvalidLink -> "Link needs attention"
            DownloadReviewReadiness.ChooseDestination -> "Choose a destination"
            DownloadReviewReadiness.ChoiceRecommended -> when (kind) {
                DownloadIntakeKind.AdaptiveMedia -> "Playlist detected"
                else -> "Page or unknown endpoint detected"
            }
            DownloadReviewReadiness.Ready -> when (kind) {
                DownloadIntakeKind.DirectFile -> "Direct file ready"
                DownloadIntakeKind.DirectMedia -> "Direct media ready"
                DownloadIntakeKind.Torrent -> "Torrent handoff ready"
                DownloadIntakeKind.AdaptiveMedia -> "Playlist ready"
                DownloadIntakeKind.PageOrUnknown, null -> "Download ready"
            }
        }
        val guidance = when (readiness) {
            DownloadReviewReadiness.MissingLink -> "Paste or enter an HTTP, HTTPS, or FTP URL. Nothing is queued until you review and confirm it."
            DownloadReviewReadiness.InvalidLink -> "XDM could not normalize this URL. Check the scheme and host before continuing."
            DownloadReviewReadiness.ChooseDestination -> "The link is recognized. Choose where the completed file should be saved."
            DownloadReviewReadiness.ChoiceRecommended -> when (kind) {
                DownloadIntakeKind.AdaptiveMedia -> "Inspect the HLS or DASH playlist to choose video, audio, and subtitle tracks, or start it directly only when that is intentional."
                else -> "This looks like a page rather than a file. Inspect it in Media for a yt-dlp probe, or continue only when the URL itself is downloadable."
            }
            DownloadReviewReadiness.Ready -> when (kind) {
                DownloadIntakeKind.DirectFile -> "Review the inferred filename, destination, and backend, then add it to the queue."
                DownloadIntakeKind.DirectMedia -> "Download the media URL directly or inspect it first when track selection is useful."
                DownloadIntakeKind.Torrent -> "Review destination and backend compatibility before adding the torrent handoff."
                else -> "Review the request and add it to the queue when ready."
            }
        }
        val primaryAction = when {
            inspectionRecommended -> "Review choice"
            kind == DownloadIntakeKind.Torrent -> "Add torrent handoff"
            else -> "Add to queue"
        }
        return DownloadReviewPlan(
            normalizedUrl = normalized,
            kind = kind,
            readiness = readiness,
            title = title,
            guidance = guidance,
            primaryActionLabel = primaryAction,
            canStartDirectly = normalized != null && destinationReady,
            canInspectAsMedia = normalized != null && inspectable,
            mediaInspectionRecommended = inspectionRecommended,
            steps = listOf(
                DownloadReviewStep("Link", normalized != null, if (normalized != null) kind?.displayLabel.orEmpty() else "Enter a supported URL"),
                DownloadReviewStep("Destination", destinationReady, if (destinationReady) "Selected" else "Choose a folder"),
                DownloadReviewStep("Review", normalized != null && destinationReady, if (inspectionRecommended) "Choose direct or media inspection" else "Confirm before queueing"),
            ),
        )
    }

    private val DownloadIntakeKind.displayLabel: String
        get() = when (this) {
            DownloadIntakeKind.DirectFile -> "Direct file"
            DownloadIntakeKind.DirectMedia -> "Direct media"
            DownloadIntakeKind.AdaptiveMedia -> "HLS / DASH"
            DownloadIntakeKind.Torrent -> "Torrent"
            DownloadIntakeKind.PageOrUnknown -> "Page or unknown"
        }
}

enum class DownloadDashboardOrdering(val label: String) {
    Smart("Smart"),
    Recent("Newest"),
    Name("Name"),
    Progress("Progress"),
}

enum class DownloadDashboardBucket(val label: String) {
    NeedsAttention("Needs attention"),
    Active("Active"),
    Queued("Queued"),
    Completed("Completed"),
    History("History"),
}

enum class DownloadAttentionKind {
    Authentication,
    Storage,
    Permission,
    Network,
    Verification,
    Recovery,
    Retry,
}

data class DownloadAttentionSignal(
    val kind: DownloadAttentionKind,
    val label: String,
    val guidance: String,
)

data class DownloadDashboardSection(
    val bucket: DownloadDashboardBucket,
    val downloads: List<Download>,
    val description: String,
) {
    val count: Int get() = downloads.size
}

data class DownloadDashboardSummary(
    val total: Int,
    val active: Int,
    val queued: Int,
    val needsAttention: Int,
    val completed: Int,
    val history: Int,
    val aggregateSpeedBytesPerSecond: Long,
)

data class DownloadDashboard(
    val summary: DownloadDashboardSummary,
    val sections: List<DownloadDashboardSection>,
)

/** Stable grouping and health classification for the Downloads control center. */
object DownloadDashboardPlanner {
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
        DownloadState.Paused,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
    )
    private val attentionStates = setOf(DownloadState.Failed, DownloadState.RecoveryRequired)

    fun bucketFor(download: Download): DownloadDashboardBucket = when (download.state) {
        in attentionStates -> DownloadDashboardBucket.NeedsAttention
        in activeStates -> DownloadDashboardBucket.Active
        in queuedStates -> DownloadDashboardBucket.Queued
        DownloadState.Completed -> DownloadDashboardBucket.Completed
        DownloadState.Cancelled -> DownloadDashboardBucket.History
        else -> DownloadDashboardBucket.History
    }

    fun plan(
        downloads: List<Download>,
        ordering: DownloadDashboardOrdering = DownloadDashboardOrdering.Smart,
    ): DownloadDashboard {
        val grouped = downloads.groupBy(::bucketFor)
        val sections = DownloadDashboardBucket.entries.mapNotNull { bucket ->
            val items = grouped[bucket].orEmpty().sortedFor(bucket, ordering)
            items.takeIf { it.isNotEmpty() }?.let {
                DownloadDashboardSection(bucket, it, bucket.description)
            }
        }
        return DownloadDashboard(
            summary = DownloadDashboardSummary(
                total = downloads.size,
                active = grouped[DownloadDashboardBucket.Active].orEmpty().size,
                queued = grouped[DownloadDashboardBucket.Queued].orEmpty().size,
                needsAttention = grouped[DownloadDashboardBucket.NeedsAttention].orEmpty().size,
                completed = grouped[DownloadDashboardBucket.Completed].orEmpty().size,
                history = grouped[DownloadDashboardBucket.History].orEmpty().size,
                aggregateSpeedBytesPerSecond = downloads.filter { it.state == DownloadState.Downloading }.sumOf { it.speedBytesPerSecond.coerceAtLeast(0L) },
            ),
            sections = sections,
        )
    }

    fun attentionSignal(download: Download): DownloadAttentionSignal? {
        if (bucketFor(download) != DownloadDashboardBucket.NeedsAttention) return null
        val message = download.errorMessage.orEmpty().lowercase()
        return when {
            download.state == DownloadState.RecoveryRequired -> DownloadAttentionSignal(
                DownloadAttentionKind.Recovery,
                "Recovery required",
                "Open Activity → Recovery to validate partial data and choose a safe action.",
            )
            listOf("401", "403", "auth", "credential", "login", "cookie").any(message::contains) -> DownloadAttentionSignal(
                DownloadAttentionKind.Authentication,
                "Authentication required",
                "Refresh the source session or share the URL again with valid access context.",
            )
            listOf("space", "storage", "disk", "enospc", "no space").any(message::contains) -> DownloadAttentionSignal(
                DownloadAttentionKind.Storage,
                "Storage issue",
                "Free space or choose another destination before retrying.",
            )
            listOf("permission", "denied", "read-only", "readonly").any(message::contains) -> DownloadAttentionSignal(
                DownloadAttentionKind.Permission,
                "Destination permission required",
                "Re-authorize the destination or choose another writable folder.",
            )
            listOf("checksum", "verification", "hash mismatch", "integrity").any(message::contains) -> DownloadAttentionSignal(
                DownloadAttentionKind.Verification,
                "Verification failed",
                "Review integrity diagnostics before retrying or repairing the file.",
            )
            listOf("network", "timeout", "dns", "connection", "offline").any(message::contains) -> DownloadAttentionSignal(
                DownloadAttentionKind.Network,
                "Network issue",
                "Check connectivity and retry when the source is reachable.",
            )
            else -> DownloadAttentionSignal(
                DownloadAttentionKind.Retry,
                "Retry available",
                "Review the failure details, then resume or switch backend when compatible.",
            )
        }
    }

    private fun List<Download>.sortedFor(
        bucket: DownloadDashboardBucket,
        ordering: DownloadDashboardOrdering,
    ): List<Download> = when (ordering) {
        DownloadDashboardOrdering.Recent -> sortedByDescending { it.updatedAtEpochMs }
        DownloadDashboardOrdering.Name -> sortedBy { it.fileName.lowercase() }
        DownloadDashboardOrdering.Progress -> sortedByDescending { it.progressFraction }
        DownloadDashboardOrdering.Smart -> when (bucket) {
            DownloadDashboardBucket.NeedsAttention -> sortedWith(compareByDescending<Download> { it.state == DownloadState.RecoveryRequired }.thenByDescending { it.updatedAtEpochMs })
            DownloadDashboardBucket.Active -> sortedWith(compareByDescending<Download> { it.priority }.thenByDescending { it.speedBytesPerSecond }.thenByDescending { it.updatedAtEpochMs })
            DownloadDashboardBucket.Queued -> sortedWith(compareByDescending<Download> { it.priority }.thenBy { it.createdAtEpochMs })
            DownloadDashboardBucket.Completed,
            DownloadDashboardBucket.History -> sortedByDescending { it.updatedAtEpochMs }
        }
    }

    private val DownloadDashboardBucket.description: String
        get() = when (this) {
            DownloadDashboardBucket.NeedsAttention -> "Failures and recovery work that need a decision."
            DownloadDashboardBucket.Active -> "Transfers currently connecting, downloading, verifying, repairing, or finishing."
            DownloadDashboardBucket.Queued -> "Ready, paused, or waiting for network and power constraints."
            DownloadDashboardBucket.Completed -> "Finished downloads available in their destination."
            DownloadDashboardBucket.History -> "Cancelled records retained for reference."
        }
}
