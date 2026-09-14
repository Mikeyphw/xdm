package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaThumbnailProvenance
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar11ManifestResolutionContractTest {
    private val service = MediaCaptureService(clock = { 90_000L })

    @Test
    fun dashSegmentTemplateProducesExecutableSegmentUrlsNotBaseUrlOnly() {
        val variants = service.parseDashManifest("cap", "https://cdn.example.test/movie/manifest.mpd", """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period id="p0">
                <AdaptationSet id="v" contentType="video" mimeType="video/mp4">
                  <SegmentTemplate initialization="init-${'$'}RepresentationID${'$'}.mp4" media="chunk-${'$'}RepresentationID${'$'}-${'$'}Number%05d${'$'}.m4s" />
                  <Representation id="v720" bandwidth="1200000" width="1280" height="720" codecs="avc1.4d401f" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent())

        val video = variants.single { it.kind == MediaVariantKind.Video }
        assertEquals("https://cdn.example.test/movie/chunk-v720-00001.m4s", video.url)
        assertEquals("https://cdn.example.test/movie/init-v720.mp4", video.manifestInitializationUrl)
        assertNotEquals("https://cdn.example.test/movie/", video.url)
        assertTrue(video.displayLabel.contains("segmented"))
        assertTrue(video.manifestTimelineGroupId!!.startsWith("dash-period:0"))
    }

    @Test
    fun dashBestVideoAutoSelectsSamePeriodAudio() {
        val capture = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/movie/manifest.mpd",
            pageTitle = "Movie",
            mimeTypeHint = "application/dash+xml",
        )))
        val variants = service.parseDashManifest(capture.id, capture.sourceUrl, """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period id="p0">
                <AdaptationSet id="v" contentType="video" mimeType="video/mp4">
                  <SegmentTemplate media="p0/video-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s" />
                  <Representation id="v0" bandwidth="1200000" height="720" codecs="avc1" />
                </AdaptationSet>
                <AdaptationSet id="a" contentType="audio" mimeType="audio/mp4" lang="en">
                  <SegmentTemplate media="p0/audio-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s" />
                  <Representation id="a0" bandwidth="128000" codecs="mp4a.40.2" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent())
        val video = variants.single { it.kind == MediaVariantKind.Video }
        val plan = MediaDownloadPlanner().plan(capture.copy(selectedVariantId = video.id), variants)

        assertEquals(video.id, plan.trackSelection.videoVariantId)
        assertTrue(plan.trackSelection.audioVariantId?.contains(":dash:0:") == true)
        assertEquals(MediaDownloadStrategy.FfmpegAdaptive, plan.strategy)
    }

    @Test
    fun hlsClosedCaptionsAreInBandMetadataAndAlternativeVideoIsParsed() {
        val variants = service.parseHlsPlaylist("cap", "https://cdn.example.test/show/master.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="English CC",INSTREAM-ID="CC1",LANGUAGE="en"
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="alt",NAME="Camera 2",URI="cam2.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720,VIDEO="alt",CLOSED-CAPTIONS="cc"
            video/main.m3u8
        """.trimIndent())

        val cc = variants.single { it.inStreamId == "CC1" }
        val alt = variants.first { it.displayLabel.contains("Camera 2") }
        assertFalse(cc.requiresNetworkFetch)
        assertTrue(cc.url.startsWith("inband://"))
        assertEquals("hls-inband-closed-caption", cc.manifestRoleLabel)
        assertEquals(MediaVariantKind.Video, alt.kind)
        assertEquals("https://cdn.example.test/show/cam2.m3u8", alt.url)
    }

    @Test
    fun selectedCredentialAndExpiryContextComesOnlyFromSelectedChildren() {
        val capture = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/show/master.m3u8",
            pageTitle = "Show",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        ))).copy(kind = MediaSourceKind.HlsPlaylist)
        val safe = com.mikeyphw.xdm.android.model.MediaVariant(
            id = "safe-video", captureId = capture.id, url = "https://cdn.example.test/video.m3u8", kind = MediaVariantKind.Video, mimeType = "application/vnd.apple.mpegurl"
        )
        val unselectedSecret = com.mikeyphw.xdm.android.model.MediaVariant(
            id = "secret-audio", captureId = capture.id, url = "https://cdn.example.test/audio.m3u8?token=secret", kind = MediaVariantKind.Audio, mimeType = "application/vnd.apple.mpegurl"
        )
        val plan = MediaDownloadPlanner().plan(capture.copy(selectedVariantId = safe.id), listOf(safe, unselectedSecret), selection = MediaTrackSelection(videoVariantId = safe.id))
        assertFalse(plan.needsCookieContext)

        val expiringPlan = MediaExecutionLibraryPlanner().queueSpec(
            capture = capture.copy(selectedVariantId = safe.id, manifestExpiresAtEpochMs = 80_000L, resolutionStatus = MediaResolutionStatus.RequiresRefresh),
            variants = listOf(safe, unselectedSecret),
            selection = MediaTrackSelection(videoVariantId = safe.id),
            destinationUri = "content://downloads/show.mp4",
        )
        assertTrue(expiringPlan.isExpiringUrl)
    }

    @Test
    fun workspaceReadyIsFalseWhenSelectedChildExpired() {
        val capture = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/show/master.m3u8",
            pageTitle = "Show",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        ))).copy(kind = MediaSourceKind.HlsPlaylist, resolutionStatus = MediaResolutionStatus.Resolved)
        val expired = com.mikeyphw.xdm.android.model.MediaVariant(
            id = "expired-video", captureId = capture.id, url = "https://cdn.example.test/video.m3u8", kind = MediaVariantKind.Video, mimeType = "application/vnd.apple.mpegurl", expiresAtEpochMs = 1L
        )
        val workspace = MediaResolverWorkspacePlanner().workspace(capture.copy(selectedVariantId = expired.id), listOf(expired), MediaTrackSelection(videoVariantId = expired.id))
        assertFalse(workspace.readyToQueue)
        assertTrue(workspace.probe.warnings.any { it.contains("selected child URL") })
    }

    @Test
    fun incompleteBoundedManifestProbeIsNotAuthoritative() {
        val engine = MediaSniffingEngine(service)
        val longIncompleteMpd = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet><Representation id="v" bandwidth="1"/>""" + " ".repeat(768 * 1024)
        val plan = engine.sniff(MediaSniffingInput(
            url = "https://cdn.example.test/manifest.mpd",
            mimeType = "application/dash+xml",
            bodyPrefix = longIncompleteMpd,
            thumbnailProvenance = MediaThumbnailProvenance.Resolver,
        ))
        assertTrue(plan.variants.isEmpty())
        assertTrue(plan.records.isNotEmpty())
    }
}
