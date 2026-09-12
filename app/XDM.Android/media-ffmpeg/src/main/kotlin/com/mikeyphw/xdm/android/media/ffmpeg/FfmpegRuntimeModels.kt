package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File

enum class FfmpegBinary { Ffmpeg, Ffprobe }

enum class FfmpegRuntimeHealth {
    Unknown, Ready, Missing, Corrupt, ArchitectureMismatch, ExecutionDenied, UnsupportedBuild, VersionMismatch, Failed
}

enum class FfmpegFailureKind {
    None, RuntimeMissing, RuntimeInvalid, PermissionDenied, InvalidArguments, Network, Authentication, UnsupportedMedia,
    OutputFailure, Cancelled, TimedOut, ProcessFailed, ProbeFailed
}

data class FfmpegRuntimeManifest(
    val ffmpegVersion: String,
    val opensslVersion: String,
    val abi: String,
    val androidApi: Int,
    val ndkVersion: String,
    val ffmpegLicense: String,
    val opensslLicense: String,
    val gplEnabled: Boolean,
    val nonfreeEnabled: Boolean,
    val httpsRequired: Boolean,
)

data class FfmpegRuntimeCapabilityReport(
    val health: FfmpegRuntimeHealth = FfmpegRuntimeHealth.Unknown,
    val expectedVersion: String = "9.0.1",
    val ffmpegVersion: String? = null,
    val ffprobeVersion: String? = null,
    val httpsSupported: Boolean = false,
    val tlsTrustReady: Boolean = false,
    val ffmpegPath: String? = null,
    val ffprobePath: String? = null,
    val detail: String = "Runtime has not been probed.",
) {
    val ready: Boolean get() = health == FfmpegRuntimeHealth.Ready && httpsSupported && tlsTrustReady
    val summary: String
        get() = if (ready) {
            "FFmpeg ${ffmpegVersion ?: expectedVersion} + FFprobe ready • verified HTTPS • app-owned"
        } else {
            "${health.name}: $detail"
        }
}

data class FfmpegExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val failureKind: FfmpegFailureKind = FfmpegFailureKind.None,
    val message: String = "",
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
    data class Remux(val inputFile: File, val outputFile: File, val overwrite: Boolean = false) : FfmpegOperation
    data class MuxTracks(val videoFile: File, val audioFile: File, val outputFile: File, val overwrite: Boolean = false) : FfmpegOperation
    data class ExtractAudio(val inputFile: File, val outputFile: File, val overwrite: Boolean = false) : FfmpegOperation
    data class FastStart(val inputFile: File, val outputFile: File, val overwrite: Boolean = false) : FfmpegOperation
}

data class CompiledFfmpegCommand(
    val binary: FfmpegBinary,
    val arguments: List<String>,
    val timeoutMs: Long? = null,
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
