package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Canonical FF02 local-media post processor.
 *
 * Every transform writes a sibling staging artifact, probes/verifies it, and only then atomically
 * replaces the requested output. A failed/cancelled FFmpeg process can therefore never expose a
 * partial final artifact. All operations are typed and stream-copy first.
 */
class FfmpegPostProcessor(
    private val runtime: EmbeddedFfmpegRuntime,
    private val workDirectory: File,
) {
    suspend fun remux(
        input: File,
        output: File,
        expectation: FfmpegVerificationExpectation = FfmpegVerificationExpectation(requireAnyStream = true),
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegPostProcessResult = executeStaged(output, expectation, onProgress) { staged ->
        requireReadable(input, "Input media")
        FfmpegOperation.Remux(input, staged, overwrite = true)
    }

    suspend fun muxTracks(
        video: File,
        audio: File,
        output: File,
        subtitle: File? = null,
        expectedDurationMs: Long? = null,
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegPostProcessResult = executeStaged(
        output,
        FfmpegVerificationExpectation(
            requireVideo = true,
            requireAudio = true,
            requireSubtitle = subtitle != null,
            expectedDurationMs = expectedDurationMs,
        ),
        onProgress,
    ) { staged ->
        requireReadable(video, "Video track")
        requireReadable(audio, "Audio track")
        subtitle?.let { requireReadable(it, "Subtitle track") }
        FfmpegOperation.MuxTracks(video, audio, staged, subtitle, expectedDurationMs, overwrite = true)
    }

    suspend fun attachSubtitle(
        input: File,
        subtitle: File,
        output: File,
        expectedDurationMs: Long? = null,
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegPostProcessResult = executeStaged(
        output,
        FfmpegVerificationExpectation(requireAnyStream = true, requireSubtitle = true, expectedDurationMs = expectedDurationMs),
        onProgress,
    ) { staged ->
        requireReadable(input, "Input media")
        requireReadable(subtitle, "Subtitle track")
        FfmpegOperation.AttachSubtitle(input, subtitle, staged, overwrite = true)
    }

    suspend fun extractAudio(
        input: File,
        output: File,
        expectedDurationMs: Long? = null,
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegPostProcessResult = executeStaged(
        output,
        FfmpegVerificationExpectation(requireAudio = true, expectedDurationMs = expectedDurationMs),
        onProgress,
    ) { staged ->
        requireReadable(input, "Input media")
        FfmpegOperation.ExtractAudio(input, staged, overwrite = true)
    }

    suspend fun fastStart(
        input: File,
        output: File,
        expectedDurationMs: Long? = null,
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegPostProcessResult = executeStaged(
        output,
        FfmpegVerificationExpectation(requireAnyStream = true, expectedDurationMs = expectedDurationMs),
        onProgress,
    ) { staged ->
        requireReadable(input, "Input media")
        FfmpegOperation.FastStart(input, staged, overwrite = true)
    }

    /**
     * Finalizes one ordered native-HLS media rendition after XDM has downloaded/decrypted every
     * part. Separate audio renditions are intentionally not mixed into this list; those require a
     * second ordered ledger and the adaptive mux path.
     */
    suspend fun finalizeNativeHls(
        orderedSegmentFiles: List<File>,
        output: File,
        expectedDurationMs: Long? = null,
        requireVideo: Boolean = true,
        requireAudio: Boolean = false,
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegPostProcessResult {
        require(orderedSegmentFiles.isNotEmpty()) { "Native HLS finalization requires downloaded segments" }
        orderedSegmentFiles.forEachIndexed { index, file -> requireReadable(file, "HLS segment $index") }
        workDirectory.mkdirs()
        val concat = File.createTempFile("xdm-hls-", ".ffconcat", workDirectory)
        return try {
            concat.writeText(FfmpegConcatManifest.render(orderedSegmentFiles))
            executeStaged(
                output,
                FfmpegVerificationExpectation(
                    requireVideo = requireVideo,
                    requireAudio = requireAudio,
                    expectedDurationMs = expectedDurationMs,
                ),
                onProgress,
            ) { staged ->
                FfmpegOperation.FinalizeHlsSegments(concat, staged, expectedDurationMs, overwrite = true)
            }
        } finally {
            concat.delete()
        }
    }

    private suspend fun executeStaged(
        output: File,
        expectation: FfmpegVerificationExpectation,
        onProgress: (FfmpegProgressSnapshot) -> Unit,
        operation: (File) -> FfmpegOperation,
    ): FfmpegPostProcessResult {
        output.parentFile?.mkdirs()
        val staged = stagingFile(output)
        staged.delete()
        try {
            val execution = runtime.execute(operation(staged), onProgress)
            if (!execution.success) {
                staged.delete()
                return FfmpegPostProcessResult(execution, output)
            }
            val probe = runtime.probe(staged.absolutePath).getOrElse { error ->
                staged.delete()
                return FfmpegPostProcessResult(
                    execution.copy(
                        exitCode = -1,
                        failureKind = FfmpegFailureKind.ProbeFailed,
                        message = redactFfmpegDiagnostic(error.message ?: "FFprobe verification failed"),
                    ),
                    output,
                )
            }
            val verification = FfmpegMediaVerifier.verify(staged, probe, expectation)
            if (!verification.valid) {
                staged.delete()
                return FfmpegPostProcessResult(
                    execution.copy(
                        exitCode = -1,
                        failureKind = FfmpegFailureKind.VerificationFailed,
                        message = verification.message,
                    ),
                    output,
                    verification,
                )
            }
            publishAtomically(staged, output)
            return FfmpegPostProcessResult(execution, output, verification)
        } catch (cancelled: CancellationException) {
            staged.delete()
            throw cancelled
        } catch (error: Throwable) {
            staged.delete()
            val message = redactFfmpegDiagnostic(error.message ?: error::class.java.simpleName)
            return FfmpegPostProcessResult(
                FfmpegExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    durationMs = 0L,
                    failureKind = FfmpegFailureKind.OutputFailure,
                    message = message,
                ),
                output,
            )
        }
    }

    private fun requireReadable(file: File, label: String) {
        require(file.isFile && file.length() > 0L) { "$label is missing or empty" }
    }

    private fun stagingFile(output: File): File {
        val extension = output.extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val base = if (extension == null) output.name else output.name.removeSuffix(".$extension")
        val stagedName = buildString {
            append('.').append(base).append(".xdm-stage-").append(UUID.randomUUID())
            if (extension != null) append('.').append(extension)
        }
        return File(output.parentFile ?: File("."), stagedName)
    }

    private fun publishAtomically(staged: File, output: File) {
        // Do not silently degrade to copy/non-atomic replacement. If this filesystem cannot honor
        // an atomic rename the caller receives OutputFailure and the final artifact stays intact.
        Files.move(staged.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal object FfmpegConcatManifest {
    fun render(segmentFiles: List<File>): String = buildString {
        appendLine("ffconcat version 1.0")
        segmentFiles.forEach { segment ->
            val canonical = segment.canonicalFile
            require(canonical.path.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Invalid HLS segment path" }
            append("file '")
            append(canonical.path.replace("'", "'\\''"))
            appendLine("'")
        }
    }
}
