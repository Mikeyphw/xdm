package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaManifestRole
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestResolverHardeningMc04Test {
    private val service = MediaCaptureService(clock = { 10_000L })

    @Test
    fun hlsMasterIsNotMisclassifiedAsLiveAndKeepsTrackRelationships() {
        val master = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.widevine"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-pt",NAME="Portuguese",LANGUAGE="pt",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="audio/pt.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",FORCED=NO,URI="subs/en.vtt"
            #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="CC1",LANGUAGE="en",INSTREAM-ID="CC1",AUTOSELECT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud-pt",SUBTITLES="subs",CLOSED-CAPTIONS="cc"
            video/720.m3u8
        """.trimIndent()

        val summary = service.inspectHlsPlaylist(master)
        val variants = service.parseHlsPlaylist("cap", "https://cdn.example.test/show/master.m3u8", master)
        val video = variants.single { it.kind == MediaVariantKind.Video }
        val audio = variants.single { it.kind == MediaVariantKind.Audio }
        val subtitle = variants.first { it.kind == MediaVariantKind.Subtitle && it.inStreamId == null }
        val captions = variants.single { it.inStreamId == "CC1" }

        assertEquals(MediaManifestRole.HlsMaster, summary.role)
        assertNull(summary.isLive)
        assertTrue(summary.hasDrm)
        assertEquals("com.widevine", summary.protectionScheme)
        assertEquals("aud-pt", video.audioGroupId)
        assertEquals("subs", video.subtitleGroupId)
        assertEquals("aud-pt", audio.groupId)
        assertTrue(audio.isDefault)
        assertTrue(audio.isAutoselect)
        assertEquals("2", audio.channels)
        assertEquals("subs", subtitle.groupId)
        assertEquals("cc", captions.groupId)
        assertEquals("CC1", captions.inStreamId)
        assertEquals("https://cdn.example.test/show/master.m3u8", captions.url)
    }

    @Test
    fun hlsMediaPlaylistOwnsLiveVodAndExtXKeyFacts() {
        val live = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6,
            0001.ts
        """.trimIndent()
        val vod = live + "\n#EXT-X-ENDLIST"

        val liveSummary = service.inspectHlsPlaylist(live)
        val vodSummary = service.inspectHlsPlaylist(vod)
        assertEquals(MediaManifestRole.HlsMedia, liveSummary.role)
        assertEquals(true, liveSummary.isLive)
        assertFalse(liveSummary.hasDrm)
        assertEquals("AES-128", liveSummary.protectionScheme)
        assertEquals(false, vodSummary.isLive)
    }

    @Test
    fun dashUsesXmlInheritanceSegmentTemplateAndContentProtection() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
              <BaseURL>root/</BaseURL>
              <Period id="p0">
                <BaseURL>period/</BaseURL>
                <AdaptationSet id="video-main" contentType="video" mimeType="video/mp4" codecs="avc1.4d401f">
                  <BaseURL>video/</BaseURL>
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                  <SegmentTemplate timescale="1000" media="chunk-${'$'}Number${'$'}.m4s" initialization="init.mp4"/>
                  <Representation id="v720" bandwidth="1800000" width="1280" height="720">
                    <BaseURL>720/</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet id="audio-pt" contentType="audio" mimeType="audio/mp4" lang="pt" codecs="mp4a.40.2">
                  <Representation id="a1" bandwidth="128000"><BaseURL>audio.m4a</BaseURL></Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val variants = service.parseDashManifest("cap", "https://cdn.example.test/movie/manifest.mpd", mpd)
        val summary = service.inspectDashManifest(mpd)
        val video = variants.single { it.kind == MediaVariantKind.Video }
        val audio = variants.single { it.kind == MediaVariantKind.Audio }

        assertEquals(MediaManifestRole.DashMpd, summary.role)
        assertEquals(true, summary.isLive)
        assertTrue(summary.hasDrm)
        assertEquals("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", summary.protectionScheme)
        assertEquals("https://cdn.example.test/movie/root/period/video/720/", video.url)
        assertEquals("video-main", video.groupId)
        assertTrue(video.displayLabel.contains("segmented"))
        assertEquals("pt", audio.language)
        assertEquals("audio-pt", audio.groupId)
    }

    @Test
    fun plannerHonorsStructuredProtectionLiveAndHlsGroupCompatibility() {
        val capture = capture().copy(
            manifestRole = MediaManifestRole.HlsMaster,
            manifestIsLive = false,
            manifestProtected = true,
            manifestProtectionScheme = "com.widevine",
        )
        val video = variant("v", MediaVariantKind.Video, groupId = null, audioGroupId = "aud-pt")
        val wrongAudio = variant("a-en", MediaVariantKind.Audio, groupId = "aud-en", language = "en", isDefault = true)
        val rightAudio = variant("a-pt", MediaVariantKind.Audio, groupId = "aud-pt", language = "pt", isDefault = true)
        val plan = MediaDownloadPlanner().plan(
            capture,
            listOf(video, wrongAudio, rightAudio),
            selection = MediaTrackSelection(videoVariantId = video.id, audioVariantId = wrongAudio.id),
        )

        assertEquals(MediaDownloadStrategy.UnsupportedProtected, plan.strategy)
        assertFalse(plan.canQueueDirectly)
        assertTrue(plan.protectedDiagnostic.protected)
        assertEquals("com.widevine", plan.protectedDiagnostic.scheme)
        assertEquals(rightAudio.id, plan.trackSelection.audioVariantId)
    }

    @Test
    fun fragmentFilterKeepsLegitimateInitChunkAndPartMp4Files() {
        val engine = MediaSniffingEngine(MediaCaptureService(clock = { 1L }))
        listOf("init.mp4", "chunk.mp4", "part1.mp4").forEach { file ->
            val plan = engine.sniff(
                MediaSniffingInput(
                    url = "https://cdn.example.test/files/$file",
                    mimeType = "video/mp4",
                    contentLength = 20L * 1024L * 1024L,
                    source = MediaSniffingSource.NetworkObservation,
                ),
            )
            assertTrue("$file must remain a real media candidate", plan.candidates.any { it.url.endsWith("/$file") })
        }
        val segment = engine.sniff(
            MediaSniffingInput(
                url = "https://cdn.example.test/hls/segment-42.ts",
                mimeType = "video/mp2t",
                contentLength = 512_000L,
                source = MediaSniffingSource.NetworkObservation,
            ),
        )
        assertTrue(segment.candidates.isEmpty())
    }

    private fun capture() = MediaCaptureRecord(
        id = "capture",
        sourceUrl = "https://cdn.example.test/master.m3u8",
        pageUrl = "https://site.example.test/watch",
        title = "Media",
        status = MediaCaptureStatus.MetadataReady,
        kind = MediaSourceKind.HlsPlaylist,
        mimeType = "application/vnd.apple.mpegurl",
        container = "hls",
        codecs = null,
        durationMs = null,
        thumbnailUrl = null,
        fileName = "master.m3u8",
        variantCount = 3,
        downloadId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        resolutionStatus = MediaResolutionStatus.Resolved,
    )

    private fun variant(
        id: String,
        kind: MediaVariantKind,
        groupId: String? = null,
        audioGroupId: String? = null,
        language: String? = null,
        isDefault: Boolean = false,
    ) = MediaVariant(
        id = id,
        captureId = "capture",
        url = "https://cdn.example.test/$id",
        kind = kind,
        mimeType = null,
        language = language,
        displayLabel = id,
        groupId = groupId,
        audioGroupId = audioGroupId,
        isDefault = isDefault,
    )
}
