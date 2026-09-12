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

class FfmpegAdaptiveExecutionFf02Test {
    @Test
    fun selectedDashVideoAndAudioBecomeVerifiedEmbeddedMuxPlan() {
        val capture = dashCapture(fileName = "manifest.mpd")
        val video = variant(capture.id, "v1080", "https://cdn.example.test/v1080.m4s", MediaVariantKind.Video, "video/mp4")
        val audio = variant(capture.id, "a-en", "https://cdn.example.test/audio-en.m4s", MediaVariantKind.Audio, "audio/mp4")
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(
            capture = capture,
            variants = listOf(video, audio),
            selection = MediaTrackSelection(video.id, audio.id),
            destinationUri = "content://downloads",
        )
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(
            spec = spec,
            enginePlan = engine,
            capture = capture,
            termuxReady = false,
            embeddedFfmpegReady = true,
        )

        assertEquals(MediaDownloadStrategy.FfmpegAdaptive, spec.strategy)
        assertEquals(MediaExecutionLane.EmbeddedFfmpegAdaptive, engine.lane)
        assertEquals(MediaPostProcessingKind.AdaptiveMux, spec.postProcessing.kind)
        assertEquals(setOf(video.id, audio.id), spec.selectedTrackIds)
        assertEquals(2, spec.selectedInputs.size)
        assertTrue(spec.fileName.endsWith(".mkv"))
        assertFalse(spec.requiresTermuxYtDlp)
        assertEquals(MediaDispatchReadiness.Ready, dispatch.readiness)
        assertTrue(dispatch.steps.any { it.kind == MediaDispatchStepKind.LaunchEmbeddedFfmpeg })
        assertTrue(engine.typedArguments.windowed(2).any { it == listOf("--verify", "ffprobe") })
    }

    @Test
    fun audioOnlyIntentIsPreservedThroughAdmissionAndUsesAudioContainer() {
        val capture = dashCapture(fileName = "manifest.mpd")
        val audio = variant(capture.id, "a-en", "https://cdn.example.test/audio-en.m4s", MediaVariantKind.Audio, "audio/mp4")
        val spec = MediaExecutionLibraryPlanner().queueSpec(
            capture = capture,
            variants = listOf(audio),
            selection = MediaTrackSelection(audioVariantId = audio.id),
            destinationUri = "content://downloads",
            intent = MediaDownloadIntent.AudioOnly,
        )

        assertEquals(MediaDownloadIntent.AudioOnly, spec.intent)
        assertEquals(MediaDownloadStrategy.FfmpegAdaptive, spec.strategy)
        assertEquals(MediaPostProcessingKind.AudioExtract, spec.postProcessing.kind)
        assertTrue(spec.fileName.endsWith(".m4a"))
        assertEquals(MediaVariantKind.Audio, spec.selectedInputs.single().kind)
    }


    @Test
    fun nonAacAudioOnlyUsesMatroskaInsteadOfForcingM4aStreamCopy() {
        val capture = dashCapture(fileName = "manifest.mpd")
        val audio = variant(
            capture.id,
            "a-opus",
            "https://cdn.example.test/audio-opus.webm",
            MediaVariantKind.Audio,
            "audio/webm",
            codecs = "opus",
        )
        val spec = MediaExecutionLibraryPlanner().queueSpec(
            capture = capture,
            variants = listOf(audio),
            selection = MediaTrackSelection(audioVariantId = audio.id),
            destinationUri = "content://downloads",
            intent = MediaDownloadIntent.AudioOnly,
        )

        assertEquals(MediaDownloadStrategy.FfmpegAdaptive, spec.strategy)
        assertEquals(MediaPostProcessingKind.AudioExtract, spec.postProcessing.kind)
        assertTrue(spec.fileName.endsWith(".mka"))
        assertFalse(spec.fileName.endsWith(".m4a"))
    }

    @Test
    fun incompleteBestVideoSelectionKeepsResolverFallbackInsteadOfProducingSilentVideo() {
        val capture = dashCapture(fileName = "manifest.mpd")
        val video = variant(capture.id, "v1080", "https://cdn.example.test/v1080.m4s", MediaVariantKind.Video, "video/mp4")
        val audio = variant(capture.id, "a-en", "https://cdn.example.test/audio-en.m4s", MediaVariantKind.Audio, "audio/mp4")
        val plan = MediaDownloadPlanner().plan(
            capture = capture,
            variants = listOf(video, audio),
            intent = MediaDownloadIntent.BestVideo,
            selection = MediaTrackSelection(videoVariantId = video.id),
        )

        assertEquals(MediaDownloadStrategy.YtDlp, plan.strategy)
        assertTrue(plan.requiresTermux)
    }

    @Test
    fun embeddedAdaptiveDispatchBlocksWhenRuntimeIsUnavailable() {
        val capture = dashCapture(fileName = "manifest.mpd")
        val video = variant(capture.id, "v", "https://cdn.example.test/video.m4s", MediaVariantKind.Video, "video/mp4")
        val spec = MediaExecutionLibraryPlanner().queueSpec(
            capture = capture,
            variants = listOf(video),
            selection = MediaTrackSelection(videoVariantId = video.id),
            destinationUri = "content://downloads",
            intent = MediaDownloadIntent.VideoOnly,
        )
        val engine = MediaExecutionLibraryPlanner().enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(
            spec = spec,
            enginePlan = engine,
            capture = capture,
            termuxReady = true,
            embeddedFfmpegReady = false,
        )
        assertEquals(MediaDispatchReadiness.NeedsEmbeddedFfmpegRuntime, dispatch.readiness)
    }

    private fun dashCapture(fileName: String) = MediaCaptureRecord(
        id = "ff02-capture",
        sourceUrl = "https://cdn.example.test/manifest.mpd",
        pageUrl = "https://watch.example.test/episode",
        title = "FF02 adaptive",
        status = MediaCaptureStatus.MetadataReady,
        kind = MediaSourceKind.DashManifest,
        mimeType = "application/dash+xml",
        container = null,
        codecs = null,
        durationMs = 90_000L,
        thumbnailUrl = null,
        fileName = fileName,
        variantCount = 2,
        downloadId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        resolutionStatus = MediaResolutionStatus.Resolved,
        nativeCapability = MediaNativeCapability.FallbackRequired,
    )

    private fun variant(
        captureId: String,
        id: String,
        url: String,
        kind: MediaVariantKind,
        mimeType: String,
        codecs: String? = if (kind == MediaVariantKind.Audio) "mp4a.40.2" else null,
    ) = MediaVariant(
        id = id,
        captureId = captureId,
        url = url,
        kind = kind,
        mimeType = mimeType,
        height = if (kind == MediaVariantKind.Video) 1080 else null,
        bitrateBitsPerSecond = if (kind == MediaVariantKind.Audio) 192_000L else 4_000_000L,
        codecs = codecs,
        language = if (kind == MediaVariantKind.Audio) "en" else null,
        displayLabel = id,
    )
}
