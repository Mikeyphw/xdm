package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class FfmpegProcessLauncher(
    private val maxCapturedChars: Int = 256_000,
) {
    suspend fun launch(executable: File, command: CompiledFfmpegCommand): FfmpegExecutionResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        require(executable.isFile) { "Runtime executable missing: ${executable.name}" }
        val process = try {
            ProcessBuilder(buildList { add(executable.absolutePath); addAll(command.arguments) })
                .redirectErrorStream(false)
                .start()
        } catch (error: SecurityException) {
            return@withContext FfmpegExecutionResult(-1, "", "", 0, FfmpegFailureKind.PermissionDenied, redactFfmpegDiagnostic(error.message ?: "execution denied"))
        } catch (error: Throwable) {
            return@withContext FfmpegExecutionResult(-1, "", "", 0, FfmpegFailureKind.ProcessFailed, redactFfmpegDiagnostic(error.message ?: error::class.java.simpleName))
        }
        try {
            coroutineScope {
                val stdoutJob = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText().takeLast(maxCapturedChars) } }
                val stderrJob = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText().takeLast(maxCapturedChars) } }
                val completed = command.timeoutMs?.let { process.waitFor(it, TimeUnit.MILLISECONDS) } ?: run { process.waitFor(); true }
                if (!completed) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    return@coroutineScope FfmpegExecutionResult(
                        exitCode = -1,
                        stdout = stdoutJob.await(),
                        stderr = stderrJob.await(),
                        durationMs = System.currentTimeMillis() - started,
                        failureKind = FfmpegFailureKind.TimedOut,
                        message = "FFmpeg operation timed out",
                    )
                }
                val stdout = stdoutJob.await()
                val stderr = stderrJob.await()
                val exitCode = process.exitValue()
                FfmpegExecutionResult(
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    durationMs = System.currentTimeMillis() - started,
                    failureKind = if (exitCode == 0) FfmpegFailureKind.None else classify(stderr),
                    message = if (exitCode == 0) "" else redactFfmpegDiagnostic(stderr.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()).take(240),
                )
            }
        } catch (cancelled: CancellationException) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            throw cancelled
        }
    }

    private fun classify(stderr: String): FfmpegFailureKind {
        val text = stderr.lowercase()
        return when {
            "401 unauthorized" in text || "403 forbidden" in text -> FfmpegFailureKind.Authentication
            "network is unreachable" in text || "connection timed out" in text || "connection refused" in text || "server returned 5" in text -> FfmpegFailureKind.Network
            "permission denied" in text -> FfmpegFailureKind.PermissionDenied
            "invalid argument" in text -> FfmpegFailureKind.InvalidArguments
            "unknown format" in text || "unsupported" in text || "decoder not found" in text -> FfmpegFailureKind.UnsupportedMedia
            "no such file or directory" in text || "error opening output" in text -> FfmpegFailureKind.OutputFailure
            else -> FfmpegFailureKind.ProcessFailed
        }
    }
}
