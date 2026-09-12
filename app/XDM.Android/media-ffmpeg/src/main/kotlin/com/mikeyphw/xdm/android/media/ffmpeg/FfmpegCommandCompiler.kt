package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File

object FfmpegCommandCompiler {
    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun compile(operation: FfmpegOperation): CompiledFfmpegCommand = when (operation) {
        is FfmpegOperation.Version -> CompiledFfmpegCommand(operation.binary, listOf("-hide_banner", "-version"), 15_000)
        is FfmpegOperation.Protocols -> CompiledFfmpegCommand(operation.binary, listOf("-hide_banner", "-protocols"), 15_000)
        is FfmpegOperation.Probe -> CompiledFfmpegCommand(
            binary = FfmpegBinary.Ffprobe,
            arguments = buildList {
                addAll(listOf("-v", "error", "-show_format", "-show_streams", "-of", "json"))
                addHeaders(operation.headers)
                addTlsVerification(operation.input, operation.tlsCaFile)
                add(validateInput(operation.input))
            },
            timeoutMs = operation.timeoutMs.coerceIn(1_000, 120_000),
        )
        is FfmpegOperation.RecordStream -> CompiledFfmpegCommand(
            binary = FfmpegBinary.Ffmpeg,
            arguments = buildList {
                add("-hide_banner"); add("-nostdin")
                if (operation.overwrite) add("-y") else add("-n")
                addHeaders(operation.headers)
                addTlsVerification(operation.inputUrl, operation.tlsCaFile)
                addAll(listOf("-i", validateInput(operation.inputUrl), "-map", "0", "-c", "copy"))
                operation.durationMs?.takeIf { it > 0 }?.let { addAll(listOf("-t", formatSeconds(it))) }
                if (isMovFamily(operation.outputFile)) addAll(listOf("-movflags", "+faststart"))
                add(validateOutput(operation.outputFile.path))
            },
        )
        is FfmpegOperation.Remux -> simpleCopy(operation.inputFile.path, operation.outputFile.path, operation.overwrite)
        is FfmpegOperation.MuxTracks -> CompiledFfmpegCommand(
            FfmpegBinary.Ffmpeg,
            buildList {
                add("-hide_banner"); add("-nostdin"); add(if (operation.overwrite) "-y" else "-n")
                addAll(listOf("-i", validateInput(operation.videoFile.path), "-i", validateInput(operation.audioFile.path)))
                addAll(listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", validateOutput(operation.outputFile.path)))
            },
        )
        is FfmpegOperation.ExtractAudio -> CompiledFfmpegCommand(
            FfmpegBinary.Ffmpeg,
            listOf("-hide_banner", "-nostdin", if (operation.overwrite) "-y" else "-n", "-i", validateInput(operation.inputFile.path), "-vn", "-c:a", "copy", validateOutput(operation.outputFile.path)),
        )
        is FfmpegOperation.FastStart -> CompiledFfmpegCommand(
            FfmpegBinary.Ffmpeg,
            listOf("-hide_banner", "-nostdin", if (operation.overwrite) "-y" else "-n", "-i", validateInput(operation.inputFile.path), "-map", "0", "-c", "copy", "-movflags", "+faststart", validateOutput(operation.outputFile.path)),
        )
    }

    private fun simpleCopy(input: String, output: String, overwrite: Boolean) = CompiledFfmpegCommand(
        FfmpegBinary.Ffmpeg,
        listOf("-hide_banner", "-nostdin", if (overwrite) "-y" else "-n", "-i", validateInput(input), "-map", "0", "-c", "copy", validateOutput(output)),
    )

    private fun MutableList<String>.addTlsVerification(input: String, caFile: File?) {
        if (!input.startsWith("https://", ignoreCase = true)) return
        require(caFile != null && caFile.isFile && caFile.length() > 0L) { "Verified HTTPS requires an Android CA trust bundle" }
        addAll(listOf("-tls_verify", "1", "-ca_file", validateInput(caFile.absolutePath)))
    }

    private fun isMovFamily(file: File): Boolean = file.extension.lowercase() in setOf("mp4", "m4v", "mov", "m4a", "3gp", "3g2", "mj2")

    private fun MutableList<String>.addHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val block = headers.entries.sortedBy { it.key.lowercase() }.joinToString(separator = "\r\n", postfix = "\r\n") { (name, value) ->
            require(headerName.matches(name)) { "Invalid HTTP header name" }
            require(!value.contains('\r') && !value.contains('\n') && !value.contains('\u0000')) { "HTTP header value contains a control delimiter" }
            "$name: $value"
        }
        addAll(listOf("-headers", block))
    }

    private fun validateInput(value: String): String {
        require(value.isNotBlank() && value.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Invalid FFmpeg input" }
        return value
    }

    private fun validateOutput(value: String): String {
        require(value.isNotBlank() && value.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Invalid FFmpeg output" }
        return value
    }

    private fun formatSeconds(milliseconds: Long): String = "%.3f".format(java.util.Locale.US, milliseconds / 1000.0)
}
