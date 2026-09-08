package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveMediaExecutionMc03Test {
    @Test
    fun hlsTrackSelectionKeepsMasterAsExecutableInput() {
        val capture = capture(MediaSourceKind.HlsPlaylist, "https://cdn.example.test/master.m3u8")
        val video = variant(capture.id, "video", "https://cdn.example.test/video/720.m3u8", MediaVariantKind.Video, height = 720)
        val audio = variant(capture.id, "audio", "https://cdn.example.test/audio/pt.m3u8", MediaVariantKind.Audio, language = "pt", bitrate = 128_000L)
        val subtitle = variant(capture.id, "sub", "https://cdn.example.test/sub/en.vtt", MediaVariantKind.Subtitle, language = "en")
        val plan = MediaDownloadPlanner().plan(
            capture = capture,
            variants = listOf(video, audio, subtitle),
            selection = MediaTrackSelection(video.id, audio.id, subtitle.id),
        )

        assertEquals(MediaDownloadStrategy.YtDlp, plan.strategy)
        assertEquals(capture.sourceUrl, plan.primaryUrl)
        assertFalse(plan.ytDlpUsePageUrl)
        assertTrue(requireNotNull(plan.ytDlpFormatSelector).contains("[height=720]"))
        assertTrue(requireNotNull(plan.ytDlpFormatSelector).contains("[language^=pt]"))
        assertEquals(listOf("--write-subs", "--sub-langs", "en", "--embed-subs"), plan.ytDlpExtraArguments)
        assertEquals(video.url, plan.sessionHandoff.selectedVariantUrl)
    }

    @Test
    fun dashRepresentationNeverReplacesMpdInput() {
        val capture = capture(MediaSourceKind.DashManifest, "https://cdn.example.test/movie/manifest.mpd")
        val representation = variant(capture.id, "v1080", "https://cdn.example.test/movie/v1080.m4s", MediaVariantKind.Video, height = 1080)
        val plan = MediaDownloadPlanner().plan(capture, listOf(representation), selection = MediaTrackSelection(videoVariantId = representation.id))

        assertEquals(MediaDownloadStrategy.YtDlp, plan.strategy)
        assertEquals("https://cdn.example.test/movie/manifest.mpd", plan.primaryUrl)
        assertFalse(plan.primaryUrl.endsWith(".m4s"))
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
