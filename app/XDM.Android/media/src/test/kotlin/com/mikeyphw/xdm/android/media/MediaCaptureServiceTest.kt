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

}
