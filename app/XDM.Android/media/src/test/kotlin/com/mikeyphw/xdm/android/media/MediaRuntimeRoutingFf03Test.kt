package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaNativeCapability
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRuntimeRoutingFf03Test {
    @Test fun automaticPrefersEmbeddedRuntime() {
        val decision = MediaFfmpegRuntimeRoutingPolicy.decide(
            MediaFfmpegRuntimeContext(
                preference = MediaFfmpegRuntimePreference.Automatic,
                embeddedReady = true,
                termuxBridgeReady = true,
                termuxFfmpegReady = true,
                termuxFfprobeReady = true,
                termuxNetworkFallbackEligible = true,
            ),
        )
        assertEquals(MediaFfmpegRuntimeSource.Embedded, decision.source)
        assertFalse(decision.fallbackUsed)
        assertTrue(decision.runnable)
    }

    @Test fun automaticFallsBackOnlyForVerifiedPublicHeaderFreeSession() {
        val spec = adaptiveSpec("https://cdn.example.test/video.m4s")
        assertTrue(MediaFfmpegRuntimeRoutingPolicy.termuxFallbackEligible(spec))
        val decision = MediaFfmpegRuntimeRoutingPolicy.decide(
            MediaFfmpegRuntimeContext(
                embeddedReady = false,
                termuxBridgeReady = true,
                termuxFfmpegReady = true,
                termuxFfprobeReady = true,
                termuxNetworkFallbackEligible = true,
            ),
        )
        assertEquals(MediaFfmpegRuntimeSource.Termux, decision.source)
        assertTrue(decision.fallbackUsed)
        assertTrue(decision.runnable)
    }

    @Test fun signedUrlAndHeadersNeverCrossTermuxBoundary() {
        assertFalse(MediaFfmpegRuntimeRoutingPolicy.termuxFallbackEligible(
            adaptiveSpec("https://cdn.example.test/video.m4s?token=secret-value"),
        ))
        assertFalse(MediaFfmpegRuntimeRoutingPolicy.termuxFallbackEligible(
            adaptiveSpec(
                "https://cdn.example.test/video.m4s",
                headers = listOf(MediaSessionHeader("Cookie", "session=secret")),
            ),
        ))
    }

    @Test fun explicitEmbeddedNeverFallsBack() {
        val decision = MediaFfmpegRuntimeRoutingPolicy.decide(
            MediaFfmpegRuntimeContext(
                preference = MediaFfmpegRuntimePreference.Embedded,
                embeddedReady = false,
                termuxBridgeReady = true,
                termuxFfmpegReady = true,
                termuxFfprobeReady = true,
                termuxNetworkFallbackEligible = true,
            ),
        )
        assertEquals(MediaFfmpegRuntimeSource.Unavailable, decision.source)
        assertFalse(decision.fallbackUsed)
        assertFalse(decision.runnable)
    }

    @Test fun explicitTermuxRequiresBothVerifiedToolsAndSafeSession() {
        val missingProbe = MediaFfmpegRuntimeRoutingPolicy.decide(
            MediaFfmpegRuntimeContext(
                preference = MediaFfmpegRuntimePreference.Termux,
                embeddedReady = true,
                termuxBridgeReady = true,
                termuxFfmpegReady = true,
                termuxFfprobeReady = false,
                termuxNetworkFallbackEligible = true,
            ),
        )
        assertFalse(missingProbe.runnable)
        val unsafe = MediaFfmpegRuntimeRoutingPolicy.decide(
            MediaFfmpegRuntimeContext(
                preference = MediaFfmpegRuntimePreference.Termux,
                embeddedReady = true,
                termuxBridgeReady = true,
                termuxFfmpegReady = true,
                termuxFfprobeReady = true,
                termuxNetworkFallbackEligible = false,
            ),
        )
        assertFalse(unsafe.runnable)
    }

    @Test fun explicitTermuxUnavailableIsBlockedInsteadOfSilentlyUsingEmbedded() {
        val spec = adaptiveSpec("https://cdn.example.test/video.m4s")
        val engine = MediaExecutionLibraryPlanner().enginePlan(
            spec,
            androidSdkInt = 35,
            ffmpegRuntimeContext = MediaFfmpegRuntimeContext(
                preference = MediaFfmpegRuntimePreference.Termux,
                embeddedReady = true,
                termuxBridgeReady = false,
                termuxFfmpegReady = false,
                termuxFfprobeReady = false,
                termuxNetworkFallbackEligible = true,
            ),
        )
        val dispatch = MediaExecutionDispatcher().dispatchPlan(
            spec, engine, capture(), termuxReady = false, embeddedFfmpegReady = true, termuxFfmpegReady = false,
        )
        assertEquals(MediaFfmpegRuntimeSource.Unavailable, engine.ffmpegRuntimeDecision?.source)
        assertEquals(MediaDispatchReadiness.NeedsTermuxSetup, dispatch.readiness)
        assertFalse(dispatch.queueButtonEnabled)
    }

    @Test fun privateNetworkTargetNeverCrossesTermuxBoundary() {
        assertFalse(MediaFfmpegRuntimeRoutingPolicy.termuxFallbackEligible(
            adaptiveSpec("http://192.168.1.10/video.m4s"),
        ))
    }

    @Test fun engineAndDispatcherRouteSafeFallbackToTermuxLane() {
        val spec = adaptiveSpec("https://cdn.example.test/video.m4s")
        val engine = MediaExecutionLibraryPlanner().enginePlan(
            spec,
            androidSdkInt = 35,
            ffmpegRuntimeContext = MediaFfmpegRuntimeContext(
                preference = MediaFfmpegRuntimePreference.Automatic,
                embeddedReady = false,
                termuxBridgeReady = true,
                termuxFfmpegReady = true,
                termuxFfprobeReady = true,
                termuxNetworkFallbackEligible = true,
            ),
        )
        assertEquals(MediaExecutionLane.TermuxFfmpegAdaptive, engine.lane)
        assertEquals("termux-ffmpeg", engine.typedExecutor)
        assertTrue(engine.typedArguments.windowed(2).any { it == listOf("--session-safe", "true") })
        val dispatch = MediaExecutionDispatcher().dispatchPlan(
            spec = spec,
            enginePlan = engine,
            capture = capture(),
            termuxReady = true,
            embeddedFfmpegReady = false,
            termuxFfmpegReady = true,
        )
        assertEquals(MediaDispatchReadiness.Ready, dispatch.readiness)
        assertTrue(dispatch.steps.any { it.kind == MediaDispatchStepKind.LaunchTermuxJob })
    }

    @Test fun unsafeAutomaticFallbackFailsClosedToEmbeddedRepair() {
        val spec = adaptiveSpec("https://cdn.example.test/video.m4s?sig=secret")
        val engine = MediaExecutionLibraryPlanner().enginePlan(
            spec,
            androidSdkInt = 35,
            ffmpegRuntimeContext = MediaFfmpegRuntimeContext(
                embeddedReady = false,
                termuxBridgeReady = true,
                termuxFfmpegReady = true,
                termuxFfprobeReady = true,
                termuxNetworkFallbackEligible = true,
            ),
        )
        assertEquals(MediaFfmpegRuntimeSource.Unavailable, engine.ffmpegRuntimeDecision?.source)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(
            spec, engine, capture(), termuxReady = true, embeddedFfmpegReady = false, termuxFfmpegReady = true,
        )
        assertEquals(MediaDispatchReadiness.NeedsEmbeddedFfmpegRuntime, dispatch.readiness)
    }

    private fun adaptiveSpec(url: String, headers: List<MediaSessionHeader> = emptyList()): MediaQueuedDownloadSpec {
        val c = capture()
        val video = MediaVariant(
            id = "video", captureId = c.id, url = url, kind = MediaVariantKind.Video,
            mimeType = "video/mp4", height = 1080, bitrateBitsPerSecond = 4_000_000L, displayLabel = "1080p",
        )
        return MediaExecutionLibraryPlanner().queueSpec(
            capture = c,
            variants = listOf(video),
            selection = MediaTrackSelection(videoVariantId = video.id),
            destinationUri = "content://downloads",
            intent = MediaDownloadIntent.VideoOnly,
            sessionHeaders = headers,
        )
    }

    private fun capture() = MediaCaptureRecord(
        id = "ff03-capture",
        sourceUrl = "https://cdn.example.test/manifest.mpd",
        pageUrl = "https://watch.example.test/episode",
        title = "FF03 routing",
        status = MediaCaptureStatus.MetadataReady,
        kind = MediaSourceKind.DashManifest,
        mimeType = "application/dash+xml",
        container = null,
        codecs = null,
        durationMs = 120_000L,
        thumbnailUrl = null,
        fileName = "manifest.mpd",
        variantCount = 1,
        downloadId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        resolutionStatus = MediaResolutionStatus.Resolved,
        nativeCapability = MediaNativeCapability.FallbackRequired,
    )
}
