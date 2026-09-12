package com.mikeyphw.xdm.android.ffmpeg

import com.mikeyphw.xdm.android.media.NativeHlsManifestPlan
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegPostProcessResult
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegPostProcessor
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegProgressSnapshot
import java.io.File

/**
 * Bridge from XDM's native HLS part engine into verified embedded-FFmpeg finalization.
 *
 * Native HLS keeps ownership of playlist policy, retries, AES-128 handling and local part files;
 * FFmpeg only stream-copies the complete ordered rendition into a container, FFprobe verifies it,
 * and the post processor atomically publishes it. Separate-audio master renditions remain on the
 * adaptive selected-track mux lane until native HLS has a second ordered audio-part ledger.
 */
class NativeHlsFfmpegFinalizer(
    runtime: EmbeddedFfmpegRuntime,
    workDirectory: File,
) {
    private val processor = FfmpegPostProcessor(runtime, workDirectory)

    suspend fun finalize(
        plan: NativeHlsManifestPlan,
        completedSegmentFiles: List<File>,
        finalMediaFile: File,
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegPostProcessResult {
        require(plan.nativeExecutable) { "Only an executable native-HLS plan may enter FFmpeg finalization" }
        require(!plan.hasSeparateAudio) { "Separate-audio HLS must use the adaptive selected-track mux lane" }
        require(completedSegmentFiles.size == plan.parts.size) { "Every native HLS part must be complete before finalization" }
        return processor.finalizeNativeHls(
            orderedSegmentFiles = completedSegmentFiles,
            output = finalMediaFile,
            expectedDurationMs = plan.estimatedDurationMs.takeIf { it > 0L && plan.discontinuityCount == 0 },
            requireVideo = plan.requireVideoStream,
            requireAudio = plan.requireAudioStream,
            onProgress = onProgress,
        )
    }
}
