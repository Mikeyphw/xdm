package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaManifestRole
import com.mikeyphw.xdm.android.model.MediaNativeCapability
import com.mikeyphw.xdm.android.model.MediaProtectionKind
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicalMediaGraphEngineTest {
    @Test
    fun masterChildSegmentsPosterAndRepeatedObserversCollapseToOneLogicalVideo() {
        val graph = LogicalMediaGraphEngine(maxLogicalItems = 16, maxEvidence = 96, maxAliases = 256)
        val child = "https://cdn.example.test/show/720p.m3u8?sig=child-one"
        val childBody = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:6")
            repeat(25) { index ->
                appendLine("#EXTINF:6.0,")
                appendLine("part-${index + 1}.ts?token=p${index + 1}")
            }
            appendLine("#EXT-X-ENDLIST")
        }
        graph.observe(
            MediaObservation(
                url = child,
                mimeType = "application/vnd.apple.mpegurl",
                bodyPrefix = childBody,
                pageUrl = "https://watch.example.test/episode/1",
                source = MediaSniffingSource.NetworkObservation,
                initiator = "fetch-body",
            ),
        )

        // The poster observed by a media-ish initiator remains hard non-media evidence.
        graph.observe(
            MediaObservation(
                url = "https://img.example.test/poster.jpg",
                mimeType = "image/jpeg",
                pageUrl = "https://watch.example.test/episode/1",
                source = MediaSniffingSource.NetworkObservation,
                initiator = "video",
            ),
        )

        val master = "https://cdn.example.test/show/master.m3u8?sig=master-one"
        val masterBody = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1280x720,AUDIO="audio",SUBTITLES="subs"
            720p.m3u8?sig=child-one
        """.trimIndent()
        graph.observe(
            MediaObservation(
                url = master,
                mimeType = "application/vnd.apple.mpegurl",
                bodyPrefix = masterBody,
                pageUrl = "https://watch.example.test/episode/1",
                source = MediaSniffingSource.NetworkObservation,
                initiator = "xhr-body",
            ),
        )

        // Repeated observer paths and signed refreshes must update evidence, not multiply rows.
        repeat(20) { index ->
            graph.observe(
                MediaObservation(
                    url = "https://cdn.example.test/show/master.m3u8?sig=rotate-$index",
                    mimeType = "application/vnd.apple.mpegurl",
                    pageUrl = "https://watch.example.test/episode/1",
                    source = if (index % 2 == 0) MediaSniffingSource.NetworkObservation else MediaSniffingSource.AppPageProbe,
                    initiator = if (index % 2 == 0) "fetch" else "performance",
                ),
            )
        }

        val snapshot = graph.snapshot()
        assertEquals(1, snapshot.userVisibleCount)
        val item = snapshot.items.single()
        assertEquals(MediaSourceKind.HlsPlaylist, item.record.kind)
        assertEquals(MediaManifestRole.HlsMaster, item.record.manifestRole)
        assertTrue(item.canonicalUrl.contains("master.m3u8"))
        assertFalse(item.canonicalUrl.contains("master-one"))
        assertTrue(item.segmentCount >= 25)
        assertTrue(item.observationCount >= 22)
        assertTrue(item.variants.any { it.kind == MediaVariantKind.Video })
        assertTrue(item.variants.any { it.kind == MediaVariantKind.Audio })
        assertTrue(item.variants.any { it.kind == MediaVariantKind.Subtitle })
        assertTrue(snapshot.evidence.any { it.semanticKind == "image" })
        assertFalse(snapshot.items.any { it.record.mimeType == "image/jpeg" })
    }

    @Test
    fun aes128IsEncryptedButNativeCandidateWhileSampleAesIsProtected() {
        val aesGraph = LogicalMediaGraphEngine()
        aesGraph.observe(
            MediaObservation(
                url = "https://cdn.example.test/aes/index.m3u8",
                mimeType = "application/vnd.apple.mpegurl",
                bodyPrefix = """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                    #EXTINF:6,
                    one.ts
                    #EXT-X-ENDLIST
                """.trimIndent(),
            ),
        )
        val aes = aesGraph.snapshot().items.single().record
        assertEquals(MediaProtectionKind.Aes128, aes.protectionKind)
        assertEquals(MediaNativeCapability.NativeCandidate, aes.nativeCapability)
        assertFalse(aes.manifestProtected)

        val protectedGraph = LogicalMediaGraphEngine()
        protectedGraph.observe(
            MediaObservation(
                url = "https://cdn.example.test/drm/index.m3u8",
                mimeType = "application/vnd.apple.mpegurl",
                bodyPrefix = """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.apple.streamingkeydelivery",URI="skd://license"
                    #EXTINF:6,
                    one.ts
                """.trimIndent(),
            ),
        )
        val protected = protectedGraph.snapshot().items.single().record
        assertTrue(protected.protectionKind == MediaProtectionKind.SampleAes || protected.protectionKind == MediaProtectionKind.Drm)
        assertEquals(MediaNativeCapability.ProtectedUnsupported, protected.nativeCapability)
        assertTrue(protected.manifestProtected)
    }

    @Test
    fun stressKeepsLogicalGraphAndEvidenceBoundedWithoutSignedUrlExplosion() {
        val graph = LogicalMediaGraphEngine(maxLogicalItems = 8, maxEvidence = 64, maxAliases = 128)
        repeat(1000) { index ->
            graph.observe(
                MediaObservation(
                    url = "https://cdn.example.test/live/master.m3u8?sig=$index&expires=${1000 + index}",
                    mimeType = "application/vnd.apple.mpegurl",
                    pageUrl = "https://watch.example.test/live",
                    source = if (index % 3 == 0) MediaSniffingSource.AppPageProbe else MediaSniffingSource.NetworkObservation,
                    initiator = listOf("fetch", "xhr", "performance")[index % 3],
                    observedAtEpochMs = index.toLong() + 1,
                ),
            )
        }
        val snapshot = graph.snapshot()
        assertEquals(1000L, snapshot.rawObservationCount)
        assertEquals(1, snapshot.userVisibleCount)
        assertEquals(1000, snapshot.items.single().observationCount)
        assertTrue(snapshot.retainedEvidenceCount <= 64)
        assertFalse(snapshot.items.single().canonicalUrl.contains("sig="))
        assertFalse(snapshot.items.single().canonicalUrl.contains("expires="))
        val cloudCanonical = LogicalMediaGraphEngine.identityUrl("https://cdn.example.test/live/master.m3u8?X-Amz-Credential=abc&X-Amz-Signature=deadbeef&quality=hd")
        assertFalse(cloudCanonical.contains("X-Amz", ignoreCase = true))
        assertTrue(cloudCanonical.contains("quality=hd"))
    }
}
