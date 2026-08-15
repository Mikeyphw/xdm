package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaOutputOwnerKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaConsumerWorkspaceTest {
    private val planner = MediaConsumerWorkspacePlanner()

    @Test
    fun readyCaptureSummarizesSelectedQualityTracksAndEstimatedSize() {
        val capture = capture()
        val variants = listOf(
            variant("video-1080", MediaVariantKind.Video, "1080p", bitrate = 4_000_000L),
            variant("audio-en", MediaVariantKind.Audio, "English", language = "en"),
            variant("sub-pt", MediaVariantKind.Subtitle, "Português", language = "pt-BR"),
        )

        val summary = planner.summarizeCapture(
            capture,
            variants,
            MediaTrackSelection("video-1080", "audio-en", "sub-pt"),
        )

        assertEquals(MediaConsumerState.Ready, summary.state)
        assertEquals("1080p", summary.selectedQuality)
        assertEquals("en audio • pt-BR subtitles", summary.trackSummary)
        assertEquals(240_000_000L, summary.estimatedSizeBytes)
        assertTrue(summary.canDownload)
        assertEquals("Download", summary.primaryActionLabel)
    }

    @Test
    fun captureWithExistingDownloadRemainsReadyForAnotherOutputGeneration() {
        val capture = capture(status = MediaCaptureStatus.DownloadCreated)
        val variants = listOf(variant("video-1080", MediaVariantKind.Video, "1080p"))

        val summary = planner.summarizeCapture(capture, variants, MediaTrackSelection(videoVariantId = "video-1080"))

        assertEquals(MediaConsumerState.Ready, summary.state)
        assertTrue(summary.canDownload)
        assertEquals("Download again", summary.primaryActionLabel)
        assertTrue(summary.notice.orEmpty().contains("another output generation"))
    }

    @Test
    fun expiredCaptureUsesSafeRefreshCopyWithoutLeakingUrlsOrTokens() {
        val capture = capture(
            sourceUrl = "https://cdn.example.test/video.mp4?token=secret-value",
            status = MediaCaptureStatus.Expired,
            resolutionStatus = MediaResolutionStatus.RequiresRefresh,
        )

        val summary = planner.summarizeCapture(capture, listOf(variant("video", MediaVariantKind.Video, "720p")), MediaTrackSelection())
        val visibleCopy = listOfNotNull(summary.notice, summary.selectedQuality, summary.trackSummary, summary.primaryActionLabel).joinToString(" ")

        assertEquals(MediaConsumerState.NeedsRefresh, summary.state)
        assertEquals("Refresh", summary.primaryActionLabel)
        assertFalse(visibleCopy.contains("secret-value"))
        assertFalse(visibleCopy.contains("https://"))
    }

    @Test
    fun libraryFiltersMediaTypeAndRecentItemsThenSortsNewestFirst() {
        val now = 2_000_000_000_000L
        val video = libraryItem("video", "movie.mp4", "video/mp4", now - 1_000L)
        val audio = libraryItem("audio", "album.m4a", "audio/mp4", now - 2_000L, MediaSourceKind.AudioStream)
        val oldVideo = libraryItem("old", "archive.mp4", "video/mp4", now - 40L * 24L * 60L * 60L * 1000L)
        val items = listOf(oldVideo, audio, video)

        assertEquals(listOf("video", "audio", "old"), planner.filterLibrary(items, MediaLibraryFilter.All, now).map { it.captureId })
        assertEquals(listOf("video", "old"), planner.filterLibrary(items, MediaLibraryFilter.Video, now).map { it.captureId })
        assertEquals(listOf("audio"), planner.filterLibrary(items, MediaLibraryFilter.Audio, now).map { it.captureId })
        assertEquals(listOf("video", "audio"), planner.filterLibrary(items, MediaLibraryFilter.RecentlyAdded, now).map { it.captureId })
    }

    private fun capture(
        sourceUrl: String = "https://cdn.example.test/video.mp4",
        status: MediaCaptureStatus = MediaCaptureStatus.MetadataReady,
        resolutionStatus: MediaResolutionStatus = MediaResolutionStatus.Resolved,
    ) = MediaCaptureRecord(
        id = "capture",
        sourceUrl = sourceUrl,
        pageUrl = "https://example.test/watch",
        title = "Example movie",
        status = status,
        kind = MediaSourceKind.ProgressiveMedia,
        mimeType = "video/mp4",
        container = "mp4",
        codecs = "avc1",
        durationMs = 480_000L,
        thumbnailUrl = null,
        fileName = "movie.mp4",
        variantCount = 3,
        downloadId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        resolutionStatus = resolutionStatus,
    )

    private fun variant(
        id: String,
        kind: MediaVariantKind,
        label: String,
        bitrate: Long? = null,
        language: String? = null,
    ) = MediaVariant(
        id = id,
        captureId = "capture",
        url = "https://cdn.example.test/$id",
        kind = kind,
        mimeType = when (kind) {
            MediaVariantKind.Audio -> "audio/mp4"
            MediaVariantKind.Subtitle -> "text/vtt"
            else -> "video/mp4"
        },
        bitrateBitsPerSecond = bitrate,
        language = language,
        displayLabel = label,
    )

    private fun libraryItem(
        id: String,
        fileName: String,
        mimeType: String,
        completedAt: Long,
        kind: MediaSourceKind = MediaSourceKind.ProgressiveMedia,
    ): OfflineMediaLibraryItem {
        val sidecar = OfflineMediaSidecarMetadata(
            captureId = id,
            downloadId = "download-$id",
            title = id,
            fileName = fileName,
            sourceHost = "cdn.example.test",
            pageHost = "example.test",
            redactedSourceUrl = "https://cdn.example.test/<redacted>",
            durationMs = 60_000L,
            thumbnailUrl = null,
            kind = kind,
            mimeType = mimeType,
            selectedTrackIds = emptySet(),
            completedAtEpochMs = completedAt,
        )
        return OfflineMediaLibraryItem(
            outputId = "output-$id",
            captureId = id,
            ownerKind = MediaOutputOwnerKind.AppDownload,
            ownerId = "download-$id",
            attemptGeneration = 1L,
            downloadId = "download-$id",
            title = id,
            fileName = fileName,
            sourceHost = "cdn.example.test",
            pageHost = "example.test",
            durationLabel = "1:00",
            thumbnailUrl = null,
            state = DownloadState.Completed,
            detail = "Complete",
            playbackUrl = "content://downloads/$id",
            isCompleted = true,
            canPlayDirect = true,
            canResume = false,
            canRetry = false,
            sidecar = sidecar,
        )
    }
}
