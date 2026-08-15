package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind

/** User-facing media state. Internal engine and resolver stages intentionally stay out of this model. */
enum class MediaConsumerState {
    Ready,
    NeedsResolution,
    NeedsRefresh,
    Failed,
    Added,
    Protected,
}

data class MediaConsumerCaptureSummary(
    val state: MediaConsumerState,
    val selectedQuality: String,
    val trackSummary: String,
    val estimatedSizeBytes: Long?,
    val notice: String?,
    val canDownload: Boolean,
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
    ): MediaConsumerCaptureSummary {
        val plan = downloadPlanner.plan(capture, variants, selection = selection)
        val selectedVideo = variants.firstOrNull { it.id == plan.trackSelection.videoVariantId }
            ?: variants.firstOrNull { it.id == plan.selectedVariantId }
            ?: variants.firstOrNull { it.kind == MediaVariantKind.Video || it.kind == MediaVariantKind.Primary }
        val selectedAudio = variants.firstOrNull { it.id == plan.trackSelection.audioVariantId }
        val selectedSubtitle = variants.firstOrNull { it.id == plan.trackSelection.subtitleVariantId }
        val estimatedSize = estimateSizeBytes(capture.durationMs, selectedVideo?.bitrateBitsPerSecond)
        val state = when {
            plan.protectedDiagnostic.protected -> MediaConsumerState.Protected
            capture.status == MediaCaptureStatus.Expired || capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh -> MediaConsumerState.NeedsRefresh
            capture.resolutionStatus == MediaResolutionStatus.Failed -> MediaConsumerState.Failed
            capture.resolutionStatus == MediaResolutionStatus.Unresolved || variants.isEmpty() -> MediaConsumerState.NeedsResolution
            else -> MediaConsumerState.Ready
        }
        val notice = when (state) {
            MediaConsumerState.Ready -> if (capture.status == MediaCaptureStatus.DownloadCreated) {
                "This capture already has an output. Downloading again creates another output generation."
            } else null
            MediaConsumerState.Added -> "This media is already in Downloads."
            MediaConsumerState.Protected -> "This media is protected. XDM can inspect it, but does not bypass DRM."
            MediaConsumerState.NeedsRefresh -> "This media link expired. Refresh it before downloading."
            MediaConsumerState.Failed -> "XDM could not read this page. Try again or share a direct media link."
            MediaConsumerState.NeedsResolution -> "Check this page to discover available quality and track options."
        }
        return MediaConsumerCaptureSummary(
            state = state,
            selectedQuality = selectedVideo?.qualityLabel ?: "Automatic",
            trackSummary = trackSummary(selectedAudio, selectedSubtitle),
            estimatedSizeBytes = estimatedSize,
            notice = notice,
            canDownload = state == MediaConsumerState.Ready && plan.canQueueDirectly,
            primaryActionLabel = when (state) {
                MediaConsumerState.Ready -> if (capture.status == MediaCaptureStatus.DownloadCreated) "Download again" else "Download"
                MediaConsumerState.Added -> "Added"
                MediaConsumerState.NeedsRefresh -> "Refresh"
                MediaConsumerState.Failed, MediaConsumerState.NeedsResolution -> "Check media"
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
