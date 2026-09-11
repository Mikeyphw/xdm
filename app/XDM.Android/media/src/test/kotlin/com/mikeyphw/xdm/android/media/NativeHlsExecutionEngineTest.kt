package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaManifestRole
import com.mikeyphw.xdm.android.model.MediaNativeCapability
import com.mikeyphw.xdm.android.model.MediaProtectionKind
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaThumbnailProvenance
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHlsExecutionEngineTest {
    // Parity03 fixture markers: supported lowSpace unsupported Tiny Add
    private fun hlsCapture(url: String = "https://cdn.example.test/show/master.m3u8?sig=volatile"): MediaCaptureRecord = MediaCaptureRecord(
        id = "cap-hls",
        sourceUrl = url,
        pageUrl = "https://watch.example.test/episode/1",
        title = "Episode 1",
        status = MediaCaptureStatus.MetadataReady,
        kind = MediaSourceKind.HlsPlaylist,
        mimeType = "application/vnd.apple.mpegurl",
        container = "hls",
        codecs = "avc1.64001f,mp4a.40.2",
        durationMs = 20_000L,
        thumbnailUrl = null,
        thumbnailProvenance = MediaThumbnailProvenance.Unknown,
        fileName = "episode-1.mp4",
        variantCount = 3,
        downloadId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        selectedVariantId = "cap-hls:variant:720",
        selectedVariantUrl = "https://cdn.example.test/show/720p.m3u8?sig=variant",
        manifestExpiresAtEpochMs = 2_000_000L,
        lastResolvedAtEpochMs = 1L,
        resolutionStatus = MediaResolutionStatus.Resolved,
        manifestRole = MediaManifestRole.HlsMedia,
        manifestIsLive = false,
        manifestProtected = false,
        manifestProtectionScheme = null,
        logicalMediaId = "logical:episode-1",
        canonicalMediaUrl = "https://cdn.example.test/show/master.m3u8",
        observationCount = 28,
        segmentCount = 118,
        protectionKind = MediaProtectionKind.Aes128,
        nativeCapability = MediaNativeCapability.NativeCandidate,
        logicalConfidence = 150,
    )

    @Test
    fun supportedVodHlsBecomesOneNativeSegmentedJobWithPartsTracksAndAesIvRules() {
        val engine = NativeHlsExecutionEngine()
        val capture = hlsCapture()
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:6")
            appendLine("#EXT-X-MEDIA-SEQUENCE:42")
            appendLine("#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@0\"")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"key-a.bin\",IV=0x0000000000000000000000000000002a")
            appendLine("#EXTINF:6.0,")
            appendLine("seg-00042.m4s?sig=one")
            appendLine("#EXT-X-BYTERANGE:1880@720")
            appendLine("#EXTINF:6.0,")
            appendLine("seg-00043.m4s?sig=two")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"key-b.bin\"")
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXTINF:8.0,")
            appendLine("seg-00044.m4s?sig=three")
            appendLine("#EXT-X-ENDLIST")
        }
        val variants = listOf(
            MediaVariant("cap-hls:variant:720", capture.id, capture.selectedVariantUrl!!, MediaVariantKind.Video, "application/vnd.apple.mpegurl", height = 720, bitrateBitsPerSecond = 2_200_000, displayLabel = "720p"),
            MediaVariant("cap-hls:audio:en", capture.id, "https://cdn.example.test/show/audio/en.m3u8", MediaVariantKind.Audio, "application/vnd.apple.mpegurl", language = "en", isDefault = true),
            MediaVariant("cap-hls:subs:en", capture.id, "https://cdn.example.test/show/subs/en.vtt", MediaVariantKind.Subtitle, "text/vtt", language = "en"),
        )
        val plan = engine.negotiate(capture, variants, playlist, mapOf("Referer" to "https://watch.example.test/episode/1"), MediaTrackSelection(videoVariantId = "cap-hls:variant:720", audioVariantId = "cap-hls:audio:en", subtitleVariantId = "cap-hls:subs:en"))
        assertEquals(NativeHlsSupportStatus.Supported, plan.supportStatus)
        assertEquals(3, plan.aggregatePartCount)
        assertTrue(plan.aes128)
        assertTrue(plan.keyRotation)
        assertEquals("0000000000000000000000000000002a", plan.parts[0].effectiveIvHex)
        assertEquals("0000000000000000000000000000002c", plan.parts[2].effectiveIvHex)
        assertEquals(1880L, plan.parts[1].byteRange!!.length)
        assertTrue(plan.parts[0].initMap!!.uri.endsWith("init.mp4"))
        assertTrue(plan.hasSeparateAudio)
        assertTrue(plan.hasSubtitles)
        val admission = engine.admit(capture, "content://downloads/episode-1.mp4", "episode-1.mp4", plan, selection = MediaTrackSelection(videoVariantId = "cap-hls:variant:720", audioVariantId = "cap-hls:audio:en", subtitleVariantId = "cap-hls:subs:en"), nowEpochMs = 10L)
        assertTrue(admission.created)
        assertEquals(NativeHlsExecutionStage.Admitted, admission.job.stage)
        val duplicate = engine.admit(capture, "content://downloads/episode-1.mp4", "episode-1.mp4", plan, existing = admission.job, selection = MediaTrackSelection(videoVariantId = "cap-hls:variant:720", audioVariantId = "cap-hls:audio:en", subtitleVariantId = "cap-hls:subs:en"), nowEpochMs = 11L)
        assertFalse(duplicate.created)
        val addAgain = engine.admit(capture, "content://downloads/episode-1.mp4", "episode-1.mp4", plan, existing = admission.job, selection = MediaTrackSelection(videoVariantId = "cap-hls:variant:720", audioVariantId = "cap-hls:audio:en", subtitleVariantId = "cap-hls:subs:en"), addAgain = true, nowEpochMs = 12L)
        assertTrue(addAgain.created)
        assertEquals(2L, addAgain.job.attemptGeneration)
    }

    @Test
    fun progressNeverCompletesBeforeFinalVerificationAndTinyArtifactsAreRejected() {
        val engine = NativeHlsExecutionEngine()
        val capture = hlsCapture()
        val plan = engine.negotiate(capture, emptyList(), """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:1
            #EXTINF:5,
            one.ts
            #EXTINF:5,
            two.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        val completeParts = plan.parts.map { it.copy(state = NativeHlsPartState.Complete, bytesReceived = 1024L, expectedBytes = 1024L) }
        val downloading = engine.progress(completeParts, NativeHlsExecutionStage.Downloading, previousPercent = 40)
        assertTrue(downloading.percent < 100)
        val verifying = engine.progress(completeParts, NativeHlsExecutionStage.Verifying, previousPercent = downloading.percent, finalizationPercent = 3)
        assertTrue(verifying.percent in downloading.percent..99)
        val manifestArtifact = engine.verifyCompletion("#EXTM3U\n#EXTINF:5\none.ts".toByteArray(), plan)
        assertFalse(manifestArtifact.valid)
        val htmlError = engine.verifyCompletion("<html>403 denied</html>".toByteArray(), plan)
        assertFalse(htmlError.valid)
        val validBytes = ByteArray(2048) { index -> (index % 251).toByte() }
        val ok = engine.verifyCompletion(validBytes, plan)
        assertTrue(ok.valid)
        val complete = engine.progress(completeParts, NativeHlsExecutionStage.Completed, previousPercent = verifying.percent)
        assertEquals(100, complete.percent)
    }

    @Test
    fun lowSpaceAndUnsupportedFeaturesFailBeforeCorruptNativeExecution() {
        val engine = NativeHlsExecutionEngine()
        val capture = hlsCapture()
        val llPlan = engine.negotiate(capture, emptyList(), """
            #EXTM3U
            #EXT-X-TARGETDURATION:1
            #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES
            #EXT-X-PART:DURATION=0.333,URI="part.1.ts"
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part.2.ts"
        """.trimIndent())
        assertEquals(NativeHlsSupportStatus.NativeUnsupportedFallback, llPlan.supportStatus)
        assertTrue(llPlan.unsupportedReasons.contains(NativeHlsUnsupportedReason.LowLatencyHls))
        val protectedPlan = engine.negotiate(capture.copy(protectionKind = MediaProtectionKind.SampleAes), emptyList(), """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.apple.streamingkeydelivery",URI="skd://license"
            #EXTINF:5,
            one.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        assertEquals(NativeHlsSupportStatus.ProtectedUnsupported, protectedPlan.supportStatus)
        val validPlan = engine.negotiate(capture.copy(protectionKind = MediaProtectionKind.None), emptyList(), """
            #EXTM3U
            #EXTINF:5,
            one.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        val lowSpace = engine.storagePreflight(validPlan.copy(parts = validPlan.parts.map { it.copy(expectedBytes = 8 * 1024 * 1024L) }), availableBytes = 4 * 1024 * 1024L)
        assertFalse(lowSpace.okToStart)
        assertTrue(lowSpace.message.contains("Not enough free space"))
    }
}
