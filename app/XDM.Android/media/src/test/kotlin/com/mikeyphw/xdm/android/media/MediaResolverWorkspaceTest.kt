package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class MediaResolverWorkspaceTest {
    @Test
    fun richFormatsExposeQualityCodecSizeAndRecommendations() {
        val capture = capture(durationMs = 600_000L)
        val variants = listOf(
            variant("av1-1080", MediaVariantKind.Video, height = 1080, bitrate = 2_500_000, codecs = "av01.0.08M.08", mime = "video/mp4"),
            variant("h264-1080", MediaVariantKind.Video, height = 1080, bitrate = 4_000_000, codecs = "avc1.640028", mime = "video/mp4"),
            variant("h264-720", MediaVariantKind.Video, height = 720, bitrate = 1_100_000, codecs = "avc1.4d401f", mime = "video/mp4"),
        )
        val workspace = MediaResolverWorkspacePlanner().workspace(
            capture,
            variants,
            MediaTrackSelection(videoVariantId = "av1-1080"),
        )

        assertEquals(MediaResolverStage.Ready, workspace.stage)
        assertEquals(3, workspace.formats.size)
        assertTrue(workspace.formats.first { it.variantId == "av1-1080" }.recommendations.contains("Efficient"))
        assertTrue(workspace.formats.first { it.variantId == "h264-1080" }.recommendations.contains("Compatible"))
        assertNotNull(workspace.formats.first().estimatedSizeBytes)
        assertTrue(workspace.comparisonNotes.isNotEmpty())
    }

    @Test
    fun audioSubtitleSelectionAndCodecRoundTripPersistWithoutRoom() {
        val selection = MediaTrackSelection("video|id", "audio id", "subtitle/pt-BR")
        val encoded = MediaTrackSelectionCodec.encode(selection)
        assertEquals(selection, MediaTrackSelectionCodec.decode(encoded))

        val variants = listOf(
            variant("video", MediaVariantKind.Video, height = 1080, bitrate = 2_000_000, codecs = "avc1", mime = "video/mp4"),
            variant("audio", MediaVariantKind.Audio, bitrate = 128_000, codecs = "mp4a.40.2", mime = "audio/mp4", language = "pt"),
            variant("subtitle", MediaVariantKind.Subtitle, bitrate = 256, codecs = "wvtt", mime = "text/vtt", language = "en", label = "English forced"),
        )
        val workspace = MediaResolverWorkspacePlanner().workspace(
            capture(),
            variants,
            MediaTrackSelection("video", "audio", "subtitle"),
        )
        assertTrue(workspace.audioTracks.single().selected)
        assertTrue(workspace.subtitleTracks.single().selected)
        assertTrue(workspace.subtitleTracks.single().forced)
        assertTrue(workspace.selectedSummary.contains("Audio pt"))
        assertTrue(workspace.selectedSummary.contains("Subtitles en"))
    }

    @Test
    fun resolvedVariantsStopAtSelectionUntilUserChoosesTracks() {
        val variants = listOf(
            variant("video-1080", MediaVariantKind.Video, height = 1080, bitrate = 2_000_000, codecs = "avc1", mime = "video/mp4"),
            variant("video-720", MediaVariantKind.Video, height = 720, bitrate = 1_000_000, codecs = "avc1", mime = "video/mp4"),
        )
        val workspace = MediaResolverWorkspacePlanner().workspace(capture(), variants, MediaTrackSelection())
        assertEquals(MediaResolverStage.Selection, workspace.stage)
        assertTrue(workspace.readyToQueue)
        assertTrue(workspace.selectedSummary.contains("Video"))
    }

    @Test
    fun adaptiveSourceNeedsProbeUntilStreamsExist() {
        val unresolved = capture(
            kind = MediaSourceKind.HlsPlaylist,
            resolution = MediaResolutionStatus.Unresolved,
        )
        val workspace = MediaResolverWorkspacePlanner().workspace(unresolved, emptyList())
        assertEquals(MediaResolverStage.Probe, workspace.stage)
        assertFalse(workspace.readyToQueue)
        assertTrue(workspace.probe.warnings.any { it.contains("no resolved streams") })
    }

    @Test
    fun protectedMediaIsDiagnosticOnly() {
        val protectedCapture = capture(container = "widevine cenc")
        val workspace = MediaResolverWorkspacePlanner().workspace(protectedCapture, emptyList())
        assertEquals(MediaResolverStage.Protected, workspace.stage)
        assertFalse(workspace.readyToQueue)
        assertTrue(workspace.protectedDiagnostic.protected)
        assertTrue(workspace.protectedDiagnostic.allowedAction.contains("does not bypass DRM"))
    }

    @Test
    fun sessionDashboardNeverExposesCookieOrAuthorizationValues() {
        val headers = listOf(
            MediaSessionHeader("Cookie", "SID=secret-cookie"),
            MediaSessionHeader("Authorization", "Bearer secret-auth"),
            MediaSessionHeader("User-Agent", "XDM test"),
        )
        val workspace = MediaResolverWorkspacePlanner().workspace(capture(), emptyList(), sessionHeaders = headers)
        assertTrue(workspace.session.cookiesAvailable)
        assertTrue(workspace.session.authorizationAvailable)
        assertTrue(workspace.session.userAgentAvailable)
        assertFalse(workspace.session.redactedSummary.contains("secret-cookie"))
        assertFalse(workspace.session.redactedSummary.contains("secret-auth"))
    }

    @Test
    fun historyUsesCaptureRecordsWithoutCreatingBrowserPersistence() {
        val captures = listOf(
            capture(id = "old", title = "Old", updated = 10L),
            capture(id = "new", title = "New", updated = 20L),
        )
        val history = MediaResolverWorkspacePlanner().history(captures, emptyList())
        assertEquals(listOf("new", "old"), history.map { it.captureId })
        assertEquals("Resolved", history.first().statusLabel)
    }

    private fun capture(
        id: String = "capture",
        title: String = "Resolver sample",
        durationMs: Long? = 300_000L,
        kind: MediaSourceKind = MediaSourceKind.ProgressiveMedia,
        resolution: MediaResolutionStatus = MediaResolutionStatus.Resolved,
        container: String? = "mp4",
        updated: Long = 2L,
    ) = MediaCaptureRecord(
        id = id,
        sourceUrl = "https://cdn.example.test/video/master.m3u8?token=secret",
        pageUrl = "https://video.example.test/watch/item?session=secret",
        title = title,
        status = MediaCaptureStatus.MetadataReady,
        kind = kind,
        mimeType = "video/mp4",
        container = container,
        codecs = "avc1",
        durationMs = durationMs,
        thumbnailUrl = null,
        fileName = "video.mp4",
        variantCount = 0,
        downloadId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = updated,
        resolutionStatus = resolution,
    )

    private fun variant(
        id: String,
        kind: MediaVariantKind,
        height: Int? = null,
        bitrate: Long? = null,
        codecs: String? = null,
        mime: String? = null,
        language: String? = null,
        label: String = "",
    ) = MediaVariant(
        id = id,
        captureId = "capture",
        url = "https://cdn.example.test/$id",
        kind = kind,
        mimeType = mime,
        width = height?.let { it * 16 / 9 },
        height = height,
        bitrateBitsPerSecond = bitrate,
        codecs = codecs,
        language = language,
        displayLabel = label,
    )
}
