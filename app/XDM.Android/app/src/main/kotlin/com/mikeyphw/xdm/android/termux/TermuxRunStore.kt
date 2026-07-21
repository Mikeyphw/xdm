package com.mikeyphw.xdm.android.termux

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object TermuxRunStore {
    private const val TermuxPackage = "com.termux"
    private const val RunCommandPermission = "com.termux.permission.RUN_COMMAND"
    private const val PreferencesName = "xdm_termux_bridge"
    private const val RootModeKey = "root_mode"
    private const val MaxPreviewCharacters = 1_600
    private const val MaxRecentRuns = 8

    private val statusFlow = MutableStateFlow(TermuxBridgeStatus())
    val status: StateFlow<TermuxBridgeStatus> = statusFlow

    fun refreshLocalStatus(context: Context) {
        val installed = isPackageInstalled(context, TermuxPackage)
        val permission = context.checkSelfPermission(RunCommandPermission) == PackageManager.PERMISSION_GRANTED
        val rootMode = rootMode(context)
        statusFlow.update {
            it.copy(
                termuxInstalled = installed,
                runCommandPermissionGranted = permission,
                rootMode = rootMode,
                lastMessage = when {
                    !installed -> "Termux is not installed."
                    !permission -> "RUN_COMMAND permission is not granted."
                    else -> "Termux RUN_COMMAND bridge is ready for a probe."
                },
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun setRootMode(context: Context, mode: TermuxRootMode) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(RootModeKey, mode.name)
            .apply()
        statusFlow.update { it.copy(rootMode = mode, updatedAtEpochMs = System.currentTimeMillis()) }
    }

    fun recordStarted(runId: String, operation: String) {
        val now = System.currentTimeMillis()
        val record = TermuxRunRecord(runId, operation, TermuxRunStatus.Started, now)
        statusFlow.update {
            it.copy(
                recentRuns = (listOf(record) + it.recentRuns.filterNot { old -> old.runId == runId }).take(MaxRecentRuns),
                lastMessage = "Started Termux $operation.",
                updatedAtEpochMs = now,
            )
        }
    }

    fun recordLaunchFailure(context: Context, operation: String, error: String) {
        refreshLocalStatus(context)
        val now = System.currentTimeMillis()
        val record = TermuxRunRecord(
            runId = "termux-launch-$now",
            operation = operation,
            status = TermuxRunStatus.Failed,
            startedAtEpochMs = now,
            finishedAtEpochMs = now,
            exitCode = -1,
            error = error,
        )
        statusFlow.update {
            it.copy(
                recentRuns = (listOf(record) + it.recentRuns).take(MaxRecentRuns),
                lastMessage = error,
                updatedAtEpochMs = now,
            )
        }
    }

    fun recordFinished(context: Context, runId: String, operation: String, exitCode: Int, stdout: String, stderr: String, error: String) {
        refreshLocalStatus(context)
        val now = System.currentTimeMillis()
        val failed = exitCode != 0 || error.isNotBlank()
        val updatedRecord = TermuxRunRecord(
            runId = runId,
            operation = operation,
            status = if (failed) TermuxRunStatus.Failed else TermuxRunStatus.Succeeded,
            startedAtEpochMs = statusFlow.value.recentRuns.firstOrNull { it.runId == runId }?.startedAtEpochMs ?: now,
            finishedAtEpochMs = now,
            exitCode = exitCode,
            stdoutPreview = stdout.preview(),
            stderrPreview = stderr.preview(),
            error = error.preview(),
        )
        val parsedTools = parseProbe(stdout)
        val rootAvailable = stdout.lineSequence().any { it.trim() == "XDM_ROOT	available" }
        statusFlow.update { current ->
            current.copy(
                toolRows = if (parsedTools.isEmpty()) current.toolRows else parsedTools,
                rootAvailable = if (parsedTools.isEmpty()) current.rootAvailable else rootAvailable,
                recentRuns = (listOf(updatedRecord) + current.recentRuns.filterNot { it.runId == runId }).take(MaxRecentRuns),
                lastMessage = if (failed) error.ifBlank { stderr.preview().ifBlank { "Termux command failed with exit $exitCode." } } else "Termux $operation completed.",
                updatedAtEpochMs = now,
            )
        }
    }

    private fun rootMode(context: Context): TermuxRootMode {
        val value = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).getString(RootModeKey, TermuxRootMode.Off.name)
        return TermuxRootMode.entries.firstOrNull { it.name == value } ?: TermuxRootMode.Off
    }

    private fun parseProbe(stdout: String): List<TermuxToolProbeRow> {
        val parsed = stdout.lineSequence()
            .map { it.split('\t') }
            .filter { it.size >= 5 && it[0] == "XDM_TOOL" }
            .associate { parts ->
                parts[1] to TermuxToolProbeRow(
                    tool = ExternalTool.entries.firstOrNull { it.binaryName == parts[1] } ?: ExternalTool.Aria2,
                    available = parts[2] == "available",
                    executablePath = parts[3],
                    versionLine = parts[4].ifBlank { if (parts[2] == "available") "Installed" else "Missing" },
                )
            }
        if (parsed.isEmpty()) return emptyList()
        return ExternalTool.entries.map { tool -> parsed[tool.binaryName] ?: TermuxToolProbeRow(tool) }
    }

    private fun String.preview(): String = trim().take(MaxPreviewCharacters)

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess
}
