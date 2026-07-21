package com.mikeyphw.xdm.android.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.security.SecureRandom

class TermuxCommandRunner(private val context: Context) {
    data class LaunchResult(val started: Boolean, val runId: String, val executionId: Int, val error: String = "")

    companion object {
        const val TermuxPackage: String = "com.termux"
        const val RunCommandPermission: String = "com.termux.permission.RUN_COMMAND"
        private const val ServiceClass: String = "com.termux.app.RunCommandService"
        private const val ActionRunCommand: String = "com.termux.RUN_COMMAND"
        private const val ExtraPath: String = "com.termux.RUN_COMMAND_PATH"
        private const val ExtraArguments: String = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val ExtraWorkdir: String = "com.termux.RUN_COMMAND_WORKDIR"
        private const val ExtraBackground: String = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val ExtraPendingIntent: String = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val ExtraLabel: String = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val ExtraDescription: String = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
        private val ids = SecureRandom()
    }

    fun refreshStatus() = TermuxRunStore.refreshLocalStatus(context)

    fun openTermux(): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(TermuxPackage) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    fun run(command: XdmTermuxCommand, workdir: String? = null, background: Boolean = true): LaunchResult {
        TermuxRunStore.refreshLocalStatus(context)
        val current = TermuxRunStore.status.value
        if (!current.termuxInstalled) return LaunchResult(false, "", -1, "Termux is not installed")
        if (!current.runCommandPermissionGranted) return LaunchResult(false, "", -1, "RUN_COMMAND permission is not granted")
        val script = TermuxShellTemplates.scriptFor(command).trim()
        if (script.isBlank()) return LaunchResult(false, "", -1, "Generated Termux command is empty")
        if (script.length > 80_000) return LaunchResult(false, "", -1, "Generated Termux command is too long")

        val executionId = nextExecutionId()
        val runId = "xdm-termux-$executionId"
        val resultIntent = Intent(context, TermuxResultService::class.java)
            .putExtra(TermuxResultService.ExtraRunId, runId)
            .putExtra(TermuxResultService.ExtraExecutionId, executionId)
            .putExtra(TermuxResultService.ExtraOperation, command.operation)
        var flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val pendingResult = PendingIntent.getService(context, executionId, resultIntent, flags)
        val shell = resolveShell()
        val request = Intent()
            .setClassName(TermuxPackage, ServiceClass)
            .setAction(ActionRunCommand)
            .putExtra(ExtraPath, shell)
            .putExtra(ExtraArguments, shellArguments(shell, script))
            .putExtra(ExtraWorkdir, TermuxPaths.normalizeWorkdir(context, workdir))
            .putExtra(ExtraBackground, background)
            .putExtra(ExtraPendingIntent, pendingResult)
            .putExtra(ExtraLabel, "XDM Android")
            .putExtra(ExtraDescription, "Runs a typed XDM download or media tool command in Termux.")
        return try {
            context.startService(request)
            TermuxRunStore.recordStarted(runId, command.operation)
            LaunchResult(true, runId, executionId)
        } catch (error: RuntimeException) {
            LaunchResult(false, runId, executionId, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun resolveShell(): String = "${TermuxPaths.prefix(context)}/bin/sh"

    private fun shellArguments(@Suppress("UNUSED_PARAMETER") shell: String, script: String): Array<String> = arrayOf("-c", script)

    private fun nextExecutionId(): Int {
        var value: Int
        do {
            value = ids.nextInt(Int.MAX_VALUE)
        } while (value == 0)
        return value
    }
}
