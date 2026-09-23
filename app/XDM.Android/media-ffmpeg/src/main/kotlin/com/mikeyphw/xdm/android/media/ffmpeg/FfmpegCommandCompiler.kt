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
        is FfmpegOperation.RecordStream -> ffmpegCommand(operation.durationMs) {
            add(if (operation.overwrite) "-y" else "-n")
            addHeaders(operation.headers)
            addTlsVerification(operation.inputUrl, operation.tlsCaFile)
            addAll(listOf("-i", validateInput(operation.inputUrl), "-map", "0", "-c", "copy"))
            operation.durationMs?.takeIf { it > 0 }?.let { addAll(listOf("-t", formatSeconds(it))) }
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.path))
        }
        is FfmpegOperation.FinalizeAdaptive -> ffmpegCommand(operation.expectedDurationMs) {
            add(if (operation.overwrite) "-y" else "-n")
            addInput(operation.input)
            addAll(listOf("-map", "0", "-c", "copy"))
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.path))
        }
        is FfmpegOperation.ExtractRemoteAudio -> ffmpegCommand(operation.expectedDurationMs) {
            add(if (operation.overwrite) "-y" else "-n")
            addInput(operation.input)
            addAll(listOf("-map", "0:a:0?", "-vn", "-sn", "-dn", "-c:a", "copy"))
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.path))
        }
        is FfmpegOperation.FinalizeHlsSegments -> ffmpegCommand(operation.expectedDurationMs) {
            require(operation.concatFile.isFile) { "Native HLS concat manifest is missing" }
            add(if (operation.overwrite) "-y" else "-n")
            addAll(listOf("-f", "concat", "-safe", "0", "-i", validateInput(operation.concatFile.absolutePath)))
            addAll(listOf("-map", "0", "-c", "copy"))
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.absolutePath))
        }

        is FfmpegOperation.MuxRemoteTracks -> compileRemoteMux(operation)
        is FfmpegOperation.Remux -> ffmpegCommand(null) {
            add(if (operation.overwrite) "-y" else "-n")
            addAll(listOf("-i", validateInput(operation.inputFile.path), "-map", "0", "-c", "copy"))
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.path))
        }
        is FfmpegOperation.MuxTracks -> ffmpegCommand(operation.expectedDurationMs) {
            add(if (operation.overwrite) "-y" else "-n")
            addAll(listOf("-i", validateInput(operation.videoFile.path), "-i", validateInput(operation.audioFile.path)))
            operation.subtitleFile?.let { addAll(listOf("-i", validateInput(it.path))) }
            addAll(listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy"))
            if (operation.subtitleFile != null) {
                addAll(listOf("-map", "2:s:0?", "-c:s", subtitleCodec(operation.outputFile)))
            }
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.path))
        }
        is FfmpegOperation.AttachSubtitle -> ffmpegCommand(null) {
            add(if (operation.overwrite) "-y" else "-n")
            addAll(listOf("-i", validateInput(operation.inputFile.path), "-i", validateInput(operation.subtitleFile.path)))
            addAll(listOf("-map", "0", "-map", "1:s:0?", "-c", "copy", "-c:s", subtitleCodec(operation.outputFile)))
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.path))
        }
        is FfmpegOperation.ExtractAudio -> ffmpegCommand(null) {
            add(if (operation.overwrite) "-y" else "-n")
            addAll(listOf("-i", validateInput(operation.inputFile.path), "-vn", "-c:a", "copy"))
            add(validateOutput(operation.outputFile.path))
        }
        is FfmpegOperation.FastStart -> ffmpegCommand(null) {
            require(isMovFamily(operation.outputFile)) { "Faststart requires an MP4/MOV-family output container" }
            add(if (operation.overwrite) "-y" else "-n")
            addAll(listOf("-i", validateInput(operation.inputFile.path), "-map", "0", "-c", "copy", "-movflags", "+faststart", validateOutput(operation.outputFile.path)))
        }
    }

    private fun compileRemoteMux(operation: FfmpegOperation.MuxRemoteTracks): CompiledFfmpegCommand {
        require(operation.inputs.isNotEmpty()) { "At least one adaptive media input is required" }
        require(operation.inputs.count { it.kind == FfmpegInputKind.Video } <= 1) { "Only one selected video track may be muxed" }
        require(operation.inputs.count { it.kind == FfmpegInputKind.Audio } <= 1) { "Only one selected audio track may be muxed" }
        require(operation.inputs.count { it.kind == FfmpegInputKind.Subtitle } <= 1) { "Only one selected subtitle track may be muxed" }
        return ffmpegCommand(operation.expectedDurationMs) {
            add(if (operation.overwrite) "-y" else "-n")
            operation.inputs.forEach { input -> addInput(input) }
            operation.inputs.forEachIndexed { index, input ->
                when (input.kind) {
                    FfmpegInputKind.Video -> addAll(listOf("-map", "$index:v:0?"))
                    FfmpegInputKind.Audio -> addAll(listOf("-map", "$index:a:0?"))
                    FfmpegInputKind.Subtitle -> addAll(listOf("-map", "$index:s:0?"))
                    FfmpegInputKind.Generic -> addAll(listOf("-map", "$index"))
                }
            }
            addAll(listOf("-c", "copy"))
            if (operation.inputs.any { it.kind == FfmpegInputKind.Subtitle }) {
                addAll(listOf("-c:s", subtitleCodec(operation.outputFile)))
            } else {
                addAll(listOf("-c:s", "copy"))
            }
            addMovFastStart(operation.outputFile)
            add(validateOutput(operation.outputFile.path))
        }
    }

    private inline fun ffmpegCommand(expectedDurationMs: Long?, block: MutableList<String>.() -> Unit): CompiledFfmpegCommand {
        val arguments = buildList {
            addAll(listOf("-hide_banner", "-nostdin", "-progress", "pipe:1", "-nostats"))
            block()
        }
        return CompiledFfmpegCommand(
            binary = FfmpegBinary.Ffmpeg,
            arguments = arguments,
            timeoutMs = ffmpegWallClockTimeoutMs(expectedDurationMs),
            progressEnabled = true,
            expectedDurationMs = expectedDurationMs,
        )
    }

    private fun ffmpegWallClockTimeoutMs(expectedDurationMs: Long?): Long {
        val defaultTimeout = 10L * 60L * 1000L
        val durationScaled = expectedDurationMs?.takeIf { it > 0L }?.let { it * 3L + 2L * 60L * 1000L }
        return (durationScaled ?: defaultTimeout).coerceIn(5L * 60L * 1000L, 2L * 60L * 60L * 1000L)
    }

    private fun MutableList<String>.addInput(input: FfmpegInput) {
        input.formatHint?.let { addAll(listOf("-f", it.argument)) }
        addHeaders(input.headers)
        addTlsVerification(input.source, input.tlsCaFile)
        addAll(listOf("-i", validateInput(input.source)))
    }

    private fun MutableList<String>.addTlsVerification(input: String, caFile: File?) {
        if (!input.startsWith("https://", ignoreCase = true)) return
        require(caFile != null && caFile.isFile && caFile.length() > 0L) { "Verified HTTPS requires an Android CA trust bundle" }
        addAll(listOf("-tls_verify", "1", "-ca_file", validateInput(caFile.absolutePath)))
    }

    private fun MutableList<String>.addMovFastStart(file: File) {
        if (isMovFamily(file)) addAll(listOf("-movflags", "+faststart"))
    }

    private fun subtitleCodec(file: File): String = if (isMovFamily(file)) "mov_text" else "copy"

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
