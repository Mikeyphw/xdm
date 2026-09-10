package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaOutputState
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind

/** User-facing media state. Internal engine and resolver stages intentionally stay out of this model. */
enum class MediaConsumerState {
    Captured,
    Ready,
    Downloading,
    Downloaded,
    RefreshNeeded,
    Unavailable,
    Protected,
}

data class MediaConsumerCaptureSummary(
    val state: MediaConsumerState,
    val selectedQuality: String,
    val trackSummary: String,
    val estimatedSizeBytes: Long?,
    val notice: String?,
    val canDownload: Boolean,
    val canOpen: Boolean,
    val primaryActionLabel: String,
)

enum class MediaLibraryFilter(val label: String) {
    All("All"),
    Video("Video"),
    Audio("Audio"),
    RecentlyAdded("Recently added"),
}

class MediaConsumerWorkspacePlanner(
    private val downloadPlanner: MediaDownloadPlanner = MediaDownloadPlanner(),
) {
    fun summarizeCapture(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        selection: MediaTrackSelection,
        latestOutput: MediaOutputRecord? = null,
    ): MediaConsumerCaptureSummary {
        val plan = downloadPlanner.plan(capture, variants, selection = selection)
        val selectedVideo = variants.firstOrNull { it.id == plan.trackSelection.videoVariantId }
            ?: variants.firstOrNull { it.id == plan.selectedVariantId }
            ?: variants.firstOrNull { it.kind == MediaVariantKind.Video || it.kind == MediaVariantKind.Primary }
        val selectedAudio = variants.firstOrNull { it.id == plan.trackSelection.audioVariantId }
        val selectedSubtitle = variants.firstOrNull { it.id == plan.trackSelection.subtitleVariantId }
        val estimatedSize = estimateSizeBytes(capture.durationMs, selectedVideo?.bitrateBitsPerSecond)
        val previousOutputFailed = latestOutput?.state in setOf(
            MediaOutputState.Failed,
            MediaOutputState.Cancelled,
            MediaOutputState.RecoveryRequired,
        )
        val state = when {
            latestOutput?.state == MediaOutputState.Completed && !latestOutput.completedArtifactUri.isNullOrBlank() -> MediaConsumerState.Downloaded
            latestOutput?.state in setOf(MediaOutputState.Queued, MediaOutputState.Active) -> MediaConsumerState.Downloading
            plan.protectedDiagnostic.protected -> MediaConsumerState.Protected
            capture.status == MediaCaptureStatus.Expired || capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh -> MediaConsumerState.RefreshNeeded
            capture.resolutionStatus == MediaResolutionStatus.Failed -> MediaConsumerState.Unavailable
            capture.resolutionStatus == MediaResolutionStatus.Unresolved -> MediaConsumerState.Captured
            variants.isEmpty() && plan.transferShape in setOf(
                com.mikeyphw.xdm.android.model.MediaTransferShape.AdaptivePlaylist,
                com.mikeyphw.xdm.android.model.MediaTransferShape.SiteResolver,
            ) -> MediaConsumerState.Captured
            else -> MediaConsumerState.Ready
        }
        val notice = when (state) {
            MediaConsumerState.Captured -> "Check this capture to discover available quality and track options."
            MediaConsumerState.Ready -> if (previousOutputFailed) {
                "A previous download did not finish. You can download this media again."
            } else null
            MediaConsumerState.Downloading -> "This media is already being downloaded. Progress is available in Downloads."
            MediaConsumerState.Downloaded -> "Already downloaded. Open the saved file or download another copy."
            MediaConsumerState.RefreshNeeded -> "This media link expired. Refresh it before downloading."
            MediaConsumerState.Unavailable -> "XDM could not prepare a downloadable media request. Check the page again or use Live locator."
            MediaConsumerState.Protected -> "This media is protected. XDM can inspect it, but does not bypass DRM."
        }
        return MediaConsumerCaptureSummary(
            state = state,
            selectedQuality = selectedVideo?.qualityLabel ?: if (plan.transferShape == com.mikeyphw.xdm.android.model.MediaTransferShape.DirectMedia) "Direct" else "Automatic",
            trackSummary = trackSummary(selectedAudio, selectedSubtitle),
            estimatedSizeBytes = estimatedSize,
            notice = notice,
            canDownload = state == MediaConsumerState.Ready && plan.canQueueDirectly,
            canOpen = state == MediaConsumerState.Downloaded && !latestOutput?.completedArtifactUri.isNullOrBlank(),
            primaryActionLabel = when (state) {
                MediaConsumerState.Captured -> "Check media"
                MediaConsumerState.Ready -> "Download"
                MediaConsumerState.Downloading -> "Downloading"
                MediaConsumerState.Downloaded -> "Open"
                MediaConsumerState.RefreshNeeded -> "Refresh"
                MediaConsumerState.Unavailable -> "Check media"
                MediaConsumerState.Protected -> "View details"
            },
        )
    }

    fun filterLibrary(
        items: List<OfflineMediaLibraryItem>,
        filter: MediaLibraryFilter,
        nowEpochMs: Long,
    ): List<OfflineMediaLibraryItem> {
        val recentCutoff = nowEpochMs - RECENT_WINDOW_MS
        return items
            .asSequence()
            .filter { item ->
                when (filter) {
                    MediaLibraryFilter.All -> true
                    MediaLibraryFilter.Video -> mediaType(item) == "video"
                    MediaLibraryFilter.Audio -> mediaType(item) == "audio"
                    MediaLibraryFilter.RecentlyAdded -> (item.sidecar.completedAtEpochMs ?: 0L) >= recentCutoff
                }
            }
            .sortedByDescending { it.sidecar.completedAtEpochMs ?: 0L }
            .toList()
    }

    fun mediaType(item: OfflineMediaLibraryItem): String = when {
        item.sidecar.kind == MediaSourceKind.AudioStream -> "audio"
        item.sidecar.mimeType?.startsWith("audio/", ignoreCase = true) == true -> "audio"
        item.fileName.endsWith(".mp3", ignoreCase = true) || item.fileName.endsWith(".m4a", ignoreCase = true) || item.fileName.endsWith(".flac", ignoreCase = true) -> "audio"
        else -> "video"
    }

    fun libraryStateLabel(item: OfflineMediaLibraryItem): String = when {
        item.toPlaybackCandidate() != null -> "Ready to play"
        item.canResume -> "Download paused"
        item.canRetry || item.state == DownloadState.Failed -> "Download failed"
        item.isCompleted -> "File unavailable"
        else -> "Finishing download"
    }

    private fun trackSummary(audio: MediaVariant?, subtitle: MediaVariant?): String {
        val audioLabel = audio?.language?.takeIf(String::isNotBlank)?.let { "$it audio" } ?: "Default audio"
        val subtitleLabel = subtitle?.language?.takeIf(String::isNotBlank)?.let { "$it subtitles" } ?: "No subtitles"
        return "$audioLabel • $subtitleLabel"
    }

    private fun estimateSizeBytes(durationMs: Long?, bitrateBitsPerSecond: Long?): Long? {
        if (durationMs == null || durationMs <= 0L || bitrateBitsPerSecond == null || bitrateBitsPerSecond <= 0L) return null
        return ((durationMs.toDouble() / 1000.0) * bitrateBitsPerSecond.toDouble() / 8.0).toLong().coerceAtLeast(1L)
    }

    private companion object {
        const val RECENT_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
