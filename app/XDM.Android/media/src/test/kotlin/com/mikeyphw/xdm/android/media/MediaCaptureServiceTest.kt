package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptureServiceTest {
    @Test
    fun directMediaUrlCreatesMetadataRecord() {
        val service = MediaCaptureService(clock = { 42L })
        val records = service.detect("watch https://cdn.example.test/video/finale.mp4 now", pageTitle = "Finale")
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(MediaSourceKind.ProgressiveMedia, record.kind)
        assertEquals(MediaCaptureStatus.MetadataReady, record.status)
        assertEquals("video/mp4", record.mimeType)
        assertEquals("finale.mp4", record.fileName)
    }

    @Test
    fun hlsPlaylistVariantsAreParsedWithoutNetwork() {
        val service = MediaCaptureService(clock = { 42L })
        val variants = service.parseHlsPlaylist(
            captureId = "capture",
            playlistUrl = "https://media.example.test/live/master.m3u8",
            playlistText = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720
                720/prog_index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1920x1080
                1080/prog_index.m3u8
            """.trimIndent(),
        )
        assertEquals(2, variants.size)
        assertEquals(1_280_000L, variants.first().bitrateBitsPerSecond)
        assertTrue(variants.first().url.endsWith("/live/720/prog_index.m3u8"))
    }

    @Test
    fun dashManifestVariantsAreParsedWithoutNetwork() {
        val service = MediaCaptureService(clock = { 100L })
        val variants = service.parseDashManifest(
            captureId = "capture",
            manifestUrl = "https://media.example.test/movie/manifest.mpd",
            manifestText = """
                <MPD><Period><AdaptationSet>
                <Representation bandwidth="900000" width="1280" height="720" codecs="avc1.4d401f" mimeType="video/mp4"><BaseURL>video-720.mp4</BaseURL></Representation>
                <Representation bandwidth="160000" codecs="mp4a.40.2" mimeType="audio/mp4"><BaseURL>audio.m4a</BaseURL></Representation>
                </AdaptationSet></Period></MPD>
            """.trimIndent(),
        )
        assertEquals(2, variants.size)
        assertEquals("720p • 900 kbps • avc1.4d401f", variants.first().qualityLabel)
        assertTrue(variants.first().url.endsWith("/movie/video-720.mp4"))
    }

    @Test
    fun selectedVariantSurvivesRefreshRecord() {
        val service = MediaCaptureService(clock = { 200L })
        val record = service.detect("https://media.example.test/live/master.m3u8").single()
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=640000,RESOLUTION=854x480
                480/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
                1080/index.m3u8
            """.trimIndent(),
        )
        val selected = service.selectVariant(record, variants, variants.last().id, 200L)
        val refreshed = service.refreshRecordAfterResolution(selected, variants, nowEpochMs = 200L, maxAgeMs = 1_000L)
        assertEquals(variants.last().id, refreshed.selectedVariantId)
        assertEquals(2, refreshed.variantCount)
        assertEquals(1_200L, refreshed.manifestExpiresAtEpochMs)
    }

    @Test
    fun classifierUsesMimeTypesWhenUrlDoesNotExposeExtension() {
        val classifier = MediaCandidateClassifier()
        assertEquals(
            MediaSourceKind.HlsPlaylist,
            classifier.classify(MediaRequestFacts("https://video.example.test/session/tokenized", mimeType = "application/x-mpegurl")),
        )
        assertEquals(
            MediaSourceKind.AudioStream,
            classifier.classify(MediaRequestFacts("https://video.example.test/media?id=1", mimeType = "audio/mpeg")),
        )
    }

    @Test
    fun browserMimeHintCreatesCaptureRecordWithoutExtension() {
        val service = MediaCaptureService(clock = { 300L })
        val candidate = service.candidateFor(
            url = "https://video.example.test/session/tokenized",
            pageTitle = "Session",
            pageUrl = "https://video.example.test/watch",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )
        val record = service.recordFor(requireNotNull(candidate))
        assertEquals(MediaSourceKind.HlsPlaylist, record.kind)
        assertEquals("https://video.example.test/watch", record.pageUrl)
        assertEquals("application/vnd.apple.mpegurl", record.mimeType)
    }

    @Test
    fun plannerRoutesComplexMediaToTermuxAndDirectMediaToExistingEngines() {
        val service = MediaCaptureService(clock = { 400L })
        val hls = service.detect("https://cdn.example.test/master.m3u8").single()
        val mp4 = service.detect("https://cdn.example.test/movie.mp4").single()
        val audio = service.detect("https://cdn.example.test/song.mp3").single()
        val planner = MediaDownloadPlanner()

        assertEquals(MediaDownloadStrategy.YtDlp, planner.plan(hls, emptyList()).strategy)
        assertEquals(MediaDownloadStrategy.Aria2, planner.plan(mp4, emptyList()).strategy)
        assertEquals(MediaDownloadStrategy.Native, planner.plan(audio, emptyList(), MediaDownloadIntent.AudioOnly).strategy)
    }

    @Test
    fun hlsMediaGroupsLiveAndProtectionAreClassified() {
        val service = MediaCaptureService(clock = { 500L })
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.widevine"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English CC",LANGUAGE="en",URI="subs/en.vtt"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            video/1080.m3u8
        """.trimIndent()
        val variants = service.parseHlsPlaylist("capture", "https://media.example.test/master.m3u8", playlist)
        val summary = service.inspectHlsPlaylist(playlist)

        assertEquals(3, variants.size)
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Audio })
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Subtitle })
        assertTrue(summary.isLive)
        assertTrue(summary.hasDrm)
        assertEquals("com.widevine", summary.protectionScheme)
    }

    @Test
    fun dashAdaptationSetsExposeAudioSubtitlesAndDrm() {
        val service = MediaCaptureService(clock = { 600L })
        val manifest = """
            <MPD type="dynamic"><Period>
              <AdaptationSet contentType="video" mimeType="video/mp4"><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" />
                <Representation bandwidth="1200000" width="1280" height="720" codecs="avc1.4d401f"><BaseURL>v720.m4s</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="pt">
                <Representation bandwidth="128000" codecs="mp4a.40.2"><BaseURL>a-pt.m4s</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet contentType="text" mimeType="application/ttml+xml" lang="en">
                <Representation bandwidth="256" codecs="stpp"><BaseURL>sub-en.ttml</BaseURL></Representation>
              </AdaptationSet>
            </Period></MPD>
        """.trimIndent()
        val variants = service.parseDashManifest("capture", "https://media.example.test/movie/manifest.mpd", manifest)
        val summary = service.inspectDashManifest(manifest)

        assertEquals(3, variants.size)
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Audio })
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Subtitle })
        assertTrue(summary.isLive)
        assertTrue(summary.hasDrm)
    }

    @Test
    fun plannerPrefersPageUrlForYtDlpAndSummarizesPlaybackLibrary() {
        val service = MediaCaptureService(clock = { 700L })
        val candidate = service.candidateFor(
            url = "https://cdn.example.test/token/master.m3u8",
            pageTitle = "Episode",
            pageUrl = "https://video.example.test/watch/episode",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )
        val record = service.recordFor(requireNotNull(candidate))
        val planner = MediaDownloadPlanner()
        val plan = planner.plan(record, candidate.variants)
        val library = planner.summarizeOfflineLibrary(listOf(record), candidate.variants)

        assertEquals("https://video.example.test/watch/episode", plan.metadataProbeUrl)
        assertTrue(plan.needsCookieContext)
        assertEquals(1, library.playableCount)
        assertTrue(library.adaptiveCount >= 1)
    }

}
