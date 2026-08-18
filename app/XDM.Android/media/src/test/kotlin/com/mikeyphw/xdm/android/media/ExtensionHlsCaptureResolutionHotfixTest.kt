package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionHlsCaptureResolutionHotfixTest {
    @Test
    fun hlsBodyFromExtensionCaptureCreatesRealTrackVariants() {
        val engine = MediaSniffingEngine(MediaCaptureService(clock = { 5_000L }))
        val plan = engine.sniff(
            MediaSniffingInput(
                url = "https://cdn.example.test/api/video?id=42&signature=keep",
                mimeType = "application/vnd.apple.mpegurl",
                pageUrl = "https://watch.example.test/episode/42",
                pageTitle = "Episode 42",
                bodyPrefix = """
                    #EXTM3U
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1,mp4a"
                    video/720.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1,mp4a"
                    video/1080.m3u8
                """.trimIndent(),
                source = MediaSniffingSource.BrowserExtension,
            ),
        )

        assertEquals(MediaSourceKind.HlsPlaylist, plan.candidates.single().kind)
        assertTrue(plan.variants.any { it.kind == MediaVariantKind.Audio && it.url == "https://cdn.example.test/api/audio/en.m3u8" })
        assertTrue(plan.variants.any { it.kind == MediaVariantKind.Video && it.height == 1080 })
        assertTrue(plan.diagnostics.joinToString("\n").contains("manifest-resolved-inline"))
    }

    @Test
    fun libraryDoesNotShowCapturedOnlyExtensionHlsPlaceholder() {
        val service = MediaCaptureService(clock = { 6_000L })
        val record = service.recordFor(
            requireNotNull(
                service.candidateFor(
                    url = "https://cdn.example.test/master.m3u8",
                    pageTitle = "Captured HLS",
                    pageUrl = "https://watch.example.test/video",
                    mimeTypeHint = "application/vnd.apple.mpegurl",
                ),
            ),
        )
        val completedDownload = Download(
            id = "download-1",
            fileName = "complete.mp4",
            sourceUrl = "https://cdn.example.test/complete.mp4",
            destinationUri = "content://downloads/public_downloads",
            state = DownloadState.Completed,
            backend = BackendType.Native,
            bytesReceived = 1L,
            totalBytes = 1L,
            speedBytesPerSecond = 0L,
            queueId = "default",
            priority = 0,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            completedArtifactUri = "content://downloads/public_downloads/complete.mp4",
            completedArtifactGeneration = 1L,
            completedArtifactBytes = 1L,
        )

        val planner = MediaExecutionLibraryPlanner()
        val inProgressDownload = completedDownload.copy(state = DownloadState.Finalizing)

        assertTrue(planner.offlineLibraryItems(listOf(record), emptyList(), emptyList()).isEmpty())
        assertTrue(
            "Library must not show unavailable/finishing placeholders before a completed artifact exists",
            planner.offlineLibraryItems(listOf(record.copy(downloadId = inProgressDownload.id)), listOf(inProgressDownload), emptyList()).isEmpty(),
        )
        val playable = planner.offlineLibraryItems(listOf(record.copy(downloadId = completedDownload.id)), listOf(completedDownload), emptyList())
        assertEquals(1, playable.size)
        assertTrue("Completed HLS output should be playable from its committed file", playable.single().canPlayDirect)
    }
}
