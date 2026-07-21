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
        val runId = intent.getStringExtra(ExtraRunId)?.takeIf { it.isNotBlank() } ?: "xdm-termux-$executionId"
        val operation = intent.getStringExtra(ExtraOperation)?.takeIf { it.isNotBlank() } ?: "termux_command"
        val result = intent.getBundleExtra("result")
        if (result == null) {
            TermuxRunStore.recordFinished(this, runId, operation, -1, "", "", "Termux returned no result bundle")
            return
        }
        val stdout = result.getTermuxString("stdout")
        val stderr = result.getTermuxString("stderr")
        val exitCode = result.getInt("exitCode", -1)
        val internalError = result.getInt("err", Activity.RESULT_OK)
        val errorMessage = result.getTermuxString("errmsg")
        val error = if (internalError == Activity.RESULT_OK) "" else errorMessage.ifBlank { "Termux internal error $internalError" }
        TermuxRunStore.recordFinished(this, runId, operation, exitCode, stdout, stderr, error)
    }

    private fun Bundle.getTermuxString(key: String): String = getString(key, "") ?: ""
}
