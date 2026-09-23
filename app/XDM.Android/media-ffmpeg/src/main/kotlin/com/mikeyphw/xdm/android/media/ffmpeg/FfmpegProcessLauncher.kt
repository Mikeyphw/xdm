package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class FfmpegProcessLauncher(
    private val maxCapturedChars: Int = 256_000,
) {
    suspend fun launch(
        executable: File,
        command: CompiledFfmpegCommand,
        onProgress: (FfmpegProgressSnapshot) -> Unit = {},
    ): FfmpegExecutionResult = withContext(Dispatchers.IO) {
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
                val lastProgress = AtomicReference<FfmpegProgressSnapshot?>(null)
                val stdoutJob = async(Dispatchers.IO) {
                    val captured = StringBuilder()
                    val parser = FfmpegProgressParser(command.expectedDurationMs)
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            appendBounded(captured, line + "\n")
                            if (command.progressEnabled) {
                                parser.accept(line)?.let { snapshot ->
                                    lastProgress.set(snapshot)
                                    runCatching { onProgress(snapshot) }
                                }
                            }
                        }
                    }
                    captured.toString()
                }
                val stderrJob = async(Dispatchers.IO) {
                    val captured = StringBuilder()
                    process.errorStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            appendBounded(captured, line + "\n")
                        }
                    }
                    captured.toString()
                }
                val deadline = command.timeoutMs?.let { started + it }
                var completed = false
                while (!completed) {
                    currentCoroutineContext().ensureActive()
                    if (deadline != null && System.currentTimeMillis() >= deadline) break
                    val waitMs = deadline?.let { (it - System.currentTimeMillis()).coerceIn(1L, 250L) } ?: 250L
                    completed = process.waitFor(waitMs, TimeUnit.MILLISECONDS)
                }
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
                        lastProgress = lastProgress.get(),
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
                    message = if (exitCode == 0) "" else summarizeFailure(stderr),
                    lastProgress = lastProgress.get(),
                )
            }
        } catch (cancelled: CancellationException) {
            process.destroy()
            if (!process.waitFor(750, TimeUnit.MILLISECONDS) && process.isAlive) process.destroyForcibly()
            throw cancelled
        }
    }

    private fun appendBounded(target: StringBuilder, text: String) {
        target.append(text)
        if (target.length > maxCapturedChars) {
            target.delete(0, target.length - maxCapturedChars)
        }
    }

    private fun classify(stderr: String): FfmpegFailureKind {
        val text = stderr.lowercase()
        return when {
            Regex("(?:http\\s+(?:error\\s+)?)?(?:401|403)\\b|server returned (?:401|403)").containsMatchIn(text) -> FfmpegFailureKind.Authentication
            "network is unreachable" in text || "connection timed out" in text || "connection refused" in text ||
                Regex("server returned 5\\d\\d").containsMatchIn(text) -> FfmpegFailureKind.Network
            "permission denied" in text -> FfmpegFailureKind.PermissionDenied
            "invalid argument" in text -> FfmpegFailureKind.InvalidArguments
            "unknown format" in text || "unsupported" in text || "decoder not found" in text -> FfmpegFailureKind.UnsupportedMedia
            "no such file or directory" in text || "error opening output" in text || "could not write header" in text -> FfmpegFailureKind.OutputFailure
            else -> FfmpegFailureKind.ProcessFailed
        }
    }

    private fun summarizeFailure(stderr: String): String {
        val lines = stderr.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return "FFmpeg exited without a diagnostic message"
        val signal = Regex(
            "(?i)(error|failed|forbidden|unauthorized|server returned|http|invalid data|permission denied|not found|timed out|connection|tls|certificate)",
        )
        val selected = lines.filter { signal.containsMatchIn(it) }.takeLast(5).ifEmpty { lines.takeLast(5) }
        return redactFfmpegDiagnostic(selected.joinToString(" | ")).take(700)
    }
}
