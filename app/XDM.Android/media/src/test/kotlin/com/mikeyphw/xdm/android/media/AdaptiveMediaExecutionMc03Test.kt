package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaNativeCapability
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveMediaExecutionMc03Test {
    @Test
    fun resolvedHlsTrackSelectionUsesEmbeddedFfmpegWithoutLosingMasterLineage() {
        val capture = capture(MediaSourceKind.HlsPlaylist, "https://cdn.example.test/master.m3u8").copy(nativeCapability = MediaNativeCapability.FallbackRequired)
        val video = variant(capture.id, "video", "https://cdn.example.test/video/720.m3u8", MediaVariantKind.Video, height = 720)
        val audio = variant(capture.id, "audio", "https://cdn.example.test/audio/pt.m3u8", MediaVariantKind.Audio, language = "pt", bitrate = 128_000L)
        val subtitle = variant(capture.id, "sub", "https://cdn.example.test/sub/en.vtt", MediaVariantKind.Subtitle, language = "en")
        val plan = MediaDownloadPlanner().plan(
            capture = capture,
            variants = listOf(video, audio, subtitle),
            selection = MediaTrackSelection(video.id, audio.id, subtitle.id),
        )

        assertEquals(MediaDownloadStrategy.FfmpegAdaptive, plan.strategy)
        assertEquals(capture.sourceUrl, plan.primaryUrl)
        assertFalse(plan.requiresTermux)
        assertTrue(plan.canQueueDirectly)
        assertEquals(null, plan.ytDlpFormatSelector)
        assertEquals(video.url, plan.sessionHandoff.selectedVariantUrl)
    }

    @Test
    fun resolvedDashRepresentationUsesEmbeddedFfmpegAndKeepsMpdLineage() {
        val capture = capture(MediaSourceKind.DashManifest, "https://cdn.example.test/movie/manifest.mpd")
        val representation = variant(capture.id, "v1080", "https://cdn.example.test/movie/v1080.m4s", MediaVariantKind.Video, height = 1080)
        val plan = MediaDownloadPlanner().plan(capture, listOf(representation), selection = MediaTrackSelection(videoVariantId = representation.id))

        assertEquals(MediaDownloadStrategy.FfmpegAdaptive, plan.strategy)
        assertFalse(plan.requiresTermux)
        assertEquals("https://cdn.example.test/movie/manifest.mpd", plan.primaryUrl)
        assertFalse(plan.primaryUrl.endsWith(".m4s"))
    }



    @Test
    fun nativeHlsUsesARealFinalMediaContainerInsteadOfManifestFilename() {
        val planner = MediaExecutionLibraryPlanner()
        val videoCapture = capture(MediaSourceKind.HlsPlaylist, "https://cdn.example.test/index.m3u8")
            .copy(fileName = "index.m3u8", codecs = "avc1.64001f,mp4a.40.2", nativeCapability = MediaNativeCapability.NativeCandidate)
        val videoSpec = planner.queueSpec(videoCapture, emptyList(), destinationUri = "content://downloads/video")
        assertEquals(MediaDownloadStrategy.NativeHls, videoSpec.strategy)
        assertTrue(videoSpec.fileName.endsWith(".mkv"))
        assertFalse(videoSpec.fileName.endsWith(".m3u8"))

        val audioCapture = videoCapture.copy(id = "capture-audio-hls", fileName = "audio.m3u8", codecs = "mp4a.40.2", mimeType = "application/vnd.apple.mpegurl")
        val audioSpec = planner.queueSpec(audioCapture, emptyList(), destinationUri = "content://downloads/audio")
        assertEquals(MediaDownloadStrategy.NativeHls, audioSpec.strategy)
        assertTrue(audioSpec.fileName.endsWith(".m4a"))

        val opusCapture = videoCapture.copy(
            id = "capture-opus-hls",
            fileName = "folder\\episode.m3u8",
            codecs = "opus",
            mimeType = "audio/ogg",
        )
        val opusSpec = planner.queueSpec(opusCapture, emptyList(), destinationUri = "content://downloads/opus")
        assertEquals(MediaDownloadStrategy.NativeHls, opusSpec.strategy)
        assertTrue(opusSpec.fileName.endsWith(".mka"))
        assertFalse(opusSpec.fileName.contains('\\'))

        val longCapture = videoCapture.copy(
            id = "capture-long-hls",
            fileName = "/captured/path/" + "very-long-title-".repeat(12) + ".m3u8",
        )
        val longSpec = planner.queueSpec(longCapture, emptyList(), destinationUri = "content://downloads/long")
        assertTrue(longSpec.fileName.length <= 120)
        assertTrue(longSpec.fileName.endsWith(".mkv"))
        assertFalse(longSpec.fileName.contains('/'))
    }

    @Test
    fun subtitleOnlyRequestNeverUsesNativeHlsMediaRendition() {
        val capture = capture(MediaSourceKind.HlsPlaylist, "https://cdn.example.test/audio-or-video.m3u8")
            .copy(nativeCapability = MediaNativeCapability.NativeCandidate)
        val plan = MediaDownloadPlanner().plan(capture, emptyList(), intent = MediaDownloadIntent.Subtitles)

        assertEquals(MediaDownloadStrategy.YtDlp, plan.strategy)
        assertFalse(plan.strategy == MediaDownloadStrategy.NativeHls)
    }

    @Test
    fun siteResolverUsesPageUrlOnlyWhenPlannerMarksIt() {
        val capture = capture(
            kind = MediaSourceKind.Unknown,
            sourceUrl = "https://cdn.example.test/tokenized/video",
            pageUrl = "https://site.example.test/watch/episode",
            mimeType = "text/html",
        )
        val plan = MediaDownloadPlanner().plan(capture, emptyList(), intent = MediaDownloadIntent.Subtitles)

        assertEquals(MediaDownloadStrategy.YtDlp, plan.strategy)
        assertTrue(plan.ytDlpUsePageUrl)
        assertEquals("https://site.example.test/watch/episode", plan.primaryUrl)
    }

    private fun capture(
        kind: MediaSourceKind,
        sourceUrl: String,
        pageUrl: String? = "https://site.example.test/watch",
        mimeType: String? = when (kind) {
            MediaSourceKind.HlsPlaylist -> "application/vnd.apple.mpegurl"
            MediaSourceKind.DashManifest -> "application/dash+xml"
            else -> "video/mp4"
        },
    ) = MediaCaptureRecord(
        id = "capture-${kind.name}",
        sourceUrl = sourceUrl,
        pageUrl = pageUrl,
        title = "Adaptive media",
        status = MediaCaptureStatus.MetadataReady,
        kind = kind,
        mimeType = mimeType,
        container = null,
        codecs = null,
        durationMs = 120_000L,
        thumbnailUrl = null,
        fileName = "adaptive.mp4",
        variantCount = 3,
        downloadId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        resolutionStatus = MediaResolutionStatus.Resolved,
    )

    private fun variant(
        captureId: String,
        id: String,
        url: String,
        kind: MediaVariantKind,
        height: Int? = null,
        language: String? = null,
        bitrate: Long? = null,
    ) = MediaVariant(
        id = id,
        captureId = captureId,
        url = url,
        kind = kind,
        mimeType = null,
        height = height,
        bitrateBitsPerSecond = bitrate,
        language = language,
        displayLabel = id,
    )
}
