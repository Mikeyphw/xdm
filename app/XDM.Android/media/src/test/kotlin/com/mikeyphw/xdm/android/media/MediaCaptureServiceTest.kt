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
}
