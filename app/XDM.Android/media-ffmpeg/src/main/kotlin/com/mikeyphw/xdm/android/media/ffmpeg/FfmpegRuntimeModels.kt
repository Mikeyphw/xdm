package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File

enum class FfmpegBinary { Ffmpeg, Ffprobe }

enum class FfmpegRuntimeHealth {
    Unknown, Ready, Missing, Corrupt, ArchitectureMismatch, ExecutionDenied, UnsupportedBuild, VersionMismatch, Failed
}

enum class FfmpegFailureKind {
    None, RuntimeMissing, RuntimeInvalid, PermissionDenied, InvalidArguments, Network, Authentication, UnsupportedMedia,
    OutputFailure, Cancelled, TimedOut, ProcessFailed, ProbeFailed, VerificationFailed
}

data class FfmpegRuntimeManifest(
    val ffmpegVersion: String,
    val opensslVersion: String,
    val abi: String,
    val androidApi: Int,
    val ndkVersion: String,
    val buildProfile: String,
    val ffmpegLicense: String,
    val opensslLicense: String,
    val gplEnabled: Boolean,
    val nonfreeEnabled: Boolean,
    val httpsRequired: Boolean,
    val requiredProtocols: Set<String> = emptySet(),
    val requiredConfigureFlags: Set<String> = emptySet(),
    val forbiddenConfigureFlags: Set<String> = emptySet(),
    val maxCombinedBinaryBytes: Long = Long.MAX_VALUE,
)

data class FfmpegRuntimeCapabilityReport(
    val health: FfmpegRuntimeHealth = FfmpegRuntimeHealth.Unknown,
    val expectedVersion: String = "9.0.1",
    val ffmpegVersion: String? = null,
    val ffprobeVersion: String? = null,
    val httpsSupported: Boolean = false,
    val tlsTrustReady: Boolean = false,
    val attestationVerified: Boolean = false,
    val buildConfigurationVerified: Boolean = false,
    val ffmpegPath: String? = null,
    val ffprobePath: String? = null,
    val detail: String = "Runtime has not been probed.",
) {
    val ready: Boolean get() = health == FfmpegRuntimeHealth.Ready && httpsSupported && tlsTrustReady && attestationVerified && buildConfigurationVerified
    val summary: String
        get() = if (ready) {
            "FFmpeg ${ffmpegVersion ?: expectedVersion} + FFprobe ready • attested • verified HTTPS • app-owned"
        } else {
            "${health.name}: $detail"
        }
}

enum class FfmpegProgressPhase { Preparing, Processing, Finalizing, Completed }

data class FfmpegProgressSnapshot(
    val phase: FfmpegProgressPhase,
    val outTimeMs: Long? = null,
    val expectedDurationMs: Long? = null,
    val totalSizeBytes: Long? = null,
    val frame: Long? = null,
    val speed: String? = null,
    val percent: Int? = null,
) {
    val userLabel: String get() = when (phase) {
        FfmpegProgressPhase.Preparing -> "Preparing media"
        FfmpegProgressPhase.Processing -> "Processing media"
        FfmpegProgressPhase.Finalizing -> "Finalizing media"
        FfmpegProgressPhase.Completed -> "Media processing complete"
    }
}

data class FfmpegExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val failureKind: FfmpegFailureKind = FfmpegFailureKind.None,
    val message: String = "",
    val lastProgress: FfmpegProgressSnapshot? = null,
) {
    val success: Boolean get() = exitCode == 0 && failureKind == FfmpegFailureKind.None
    val redactedSummary: String get() = if (success) {
        "FFmpeg operation completed in ${durationMs}ms"
    } else {
        "${failureKind.name}: ${redactFfmpegDiagnostic(message).take(240)}"
    }
}

internal fun redactFfmpegDiagnostic(value: String): String = value
    .replace(Regex("""(?i)(https?://[^\s?]+)\?[^\s]+"""), "$1?<redacted-query>")
    .replace(Regex("""(?i)(cookie|authorization|proxy-authorization|x-api-key)(\s*[:=]\s*)[^\r\n]+"""), "$1$2<redacted>")
    .replace(Regex("""(?i)(token|signature)(\s*[:=]\s*)[^\r\n\s&;]+"""), "$1$2<redacted>")
    .replace(Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+\-/=]+"""), "$1<redacted>")
    .take(1_000)

enum class FfmpegInputKind { Video, Audio, Subtitle, Generic }

data class FfmpegInput(
    val source: String,
    val kind: FfmpegInputKind = FfmpegInputKind.Generic,
    val headers: Map<String, String> = emptyMap(),
    val tlsCaFile: File? = null,
)

sealed interface FfmpegOperation {
    data class Version(val binary: FfmpegBinary) : FfmpegOperation
    data class Protocols(val binary: FfmpegBinary = FfmpegBinary.Ffmpeg) : FfmpegOperation
    data class Probe(
        val input: String,
        val headers: Map<String, String> = emptyMap(),
        val tlsCaFile: File? = null,
        val timeoutMs: Long = 30_000,
    ) : FfmpegOperation
    data class RecordStream(
        val inputUrl: String,
        val outputFile: File,
        val headers: Map<String, String> = emptyMap(),
        val tlsCaFile: File? = null,
        val durationMs: Long? = null,
        val overwrite: Boolean = false,
    ) : FfmpegOperation
    data class FinalizeAdaptive(
        val input: FfmpegInput,
        val outputFile: File,
        val expectedDurationMs: Long? = null,
        val overwrite: Boolean = false,
    ) : FfmpegOperation
    data class ExtractRemoteAudio(
        val input: FfmpegInput,
        val outputFile: File,
        val expectedDurationMs: Long? = null,
        val overwrite: Boolean = false,
    ) : FfmpegOperation

    data class FinalizeHlsSegments(
        val concatFile: File,
        val outputFile: File,
        val expectedDurationMs: Long? = null,
        val overwrite: Boolean = false,
    ) : FfmpegOperation
    data class MuxRemoteTracks(
        val inputs: List<FfmpegInput>,
        val outputFile: File,
        val expectedDurationMs: Long? = null,
        val overwrite: Boolean = false,
    ) : FfmpegOperation
    data class Remux(val inputFile: File, val outputFile: File, val overwrite: Boolean = false) : FfmpegOperation
    data class MuxTracks(
        val videoFile: File,
        val audioFile: File,
        val outputFile: File,
        val subtitleFile: File? = null,
        val expectedDurationMs: Long? = null,
        val overwrite: Boolean = false,
    ) : FfmpegOperation
    data class AttachSubtitle(
        val inputFile: File,
        val subtitleFile: File,
        val outputFile: File,
        val overwrite: Boolean = false,
    ) : FfmpegOperation
    data class ExtractAudio(val inputFile: File, val outputFile: File, val overwrite: Boolean = false) : FfmpegOperation
    data class FastStart(val inputFile: File, val outputFile: File, val overwrite: Boolean = false) : FfmpegOperation
}

data class CompiledFfmpegCommand(
    val binary: FfmpegBinary,
    val arguments: List<String>,
    val timeoutMs: Long? = null,
    val progressEnabled: Boolean = false,
    val expectedDurationMs: Long? = null,
)

data class FfprobeStream(
    val index: Int,
    val codecType: String?,
    val codecName: String?,
    val codecLongName: String?,
    val profile: String?,
    val width: Int?,
    val height: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val language: String?,
)

data class FfprobeResult(
    val formatName: String?,
    val formatLongName: String?,
    val durationSeconds: Double?,
    val sizeBytes: Long?,
    val bitRate: Long?,
    val streams: List<FfprobeStream>,
) {
    val videoStreams: List<FfprobeStream> get() = streams.filter { it.codecType == "video" }
    val audioStreams: List<FfprobeStream> get() = streams.filter { it.codecType == "audio" }
    val subtitleStreams: List<FfprobeStream> get() = streams.filter { it.codecType == "subtitle" }
}

data class FfmpegVerificationExpectation(
    val requireVideo: Boolean = false,
    val requireAudio: Boolean = false,
    val requireSubtitle: Boolean = false,
    val requireAnyStream: Boolean = true,
    val expectedDurationMs: Long? = null,
    val minimumBytes: Long = 1_024L,
)

data class FfmpegVerificationReport(
    val valid: Boolean,
    val fileBytes: Long,
    val videoStreams: Int,
    val audioStreams: Int,
    val subtitleStreams: Int,
    val durationMs: Long?,
    val message: String,
)



data class FfmpegPostProcessResult(
    val execution: FfmpegExecutionResult,
    val outputFile: File,
    val verification: FfmpegVerificationReport? = null,
) {
    val success: Boolean get() = execution.success && verification?.valid != false
    val summary: String get() = verification?.message ?: execution.redactedSummary
}
