package com.mikeyphw.xdm.android.termux

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

class TermuxResultService : Service() {
    companion object {
        const val ExtraExecutionId: String = "execution_id"
        const val ExtraRunId: String = "run_id"
        const val ExtraOperation: String = "operation"
        const val ExtraJobId: String = "job_id"
        const val ExtraProcessToken: String = "process_token"

        private const val ResultBundle: String = "result"
        private const val Stdout: String = "stdout"
        private const val Stderr: String = "stderr"
        private const val StdoutOriginalLength: String = "stdout_original_length"
        private const val StderrOriginalLength: String = "stderr_original_length"
        private const val ExitCode: String = "exitCode"
        private const val InternalError: String = "err"
        private const val ErrorMessage: String = "errmsg"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleResult(intent)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handleResult(intent: Intent?) {
        if (intent == null) return
        val executionId = intent.getIntExtra(ExtraExecutionId, -1)
        val runId = intent.getStringExtra(ExtraRunId)?.takeIf(String::isNotBlank) ?: "xdm-termux-$executionId"
        val operation = intent.getStringExtra(ExtraOperation)?.takeIf(String::isNotBlank) ?: "termux_command"
        val jobId = intent.getStringExtra(ExtraJobId)?.takeIf(String::isNotBlank)
        val processToken = intent.getStringExtra(ExtraProcessToken)?.takeIf(String::isNotBlank)
        val result = intent.getBundleExtra(ResultBundle)
        val payload = if (result == null) {
            TermuxResultPayload(
                runId = runId,
                jobId = jobId,
                processToken = processToken,
                operation = operation,
                exitCode = -1,
                stdout = "",
                stderr = "",
                error = "Termux returned no result bundle",
                stdoutOriginalLength = 0,
                stderrOriginalLength = 0,
            )
        } else {
            val stdout = result.getTermuxString(Stdout)
            val stderr = result.getTermuxString(Stderr)
            val exitCode = result.getInt(ExitCode, -1)
            val internalError = result.getInt(InternalError, Activity.RESULT_OK)
            val errorMessage = result.getTermuxString(ErrorMessage)
            val error = if (internalError == Activity.RESULT_OK) "" else errorMessage.ifBlank { "Termux internal error $internalError" }
            TermuxResultPayload(
                runId = runId,
                jobId = jobId,
                processToken = processToken,
                operation = operation,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                error = error,
                stdoutOriginalLength = result.getInt(StdoutOriginalLength, stdout.length),
                stderrOriginalLength = result.getInt(StderrOriginalLength, stderr.length),
            )
        }
        TermuxRunStore.recordFinished(this, payload.runId, payload.operation, payload.exitCode, payload.stdout, payload.stderr, payload.error)
        (application as? TermuxResultRouterProvider)?.termuxResultRouter?.routeTermuxResult(payload)
    }

    private fun Bundle.getTermuxString(key: String): String = getString(key, "") ?: ""
}
