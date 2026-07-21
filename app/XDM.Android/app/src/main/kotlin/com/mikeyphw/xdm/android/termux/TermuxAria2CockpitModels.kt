package com.mikeyphw.xdm.android.termux

import java.util.Locale

enum class TermuxAria2DaemonState(val label: String) {
    Disabled("Disabled"),
    Stopped("Stopped"),
    Starting("Starting"),
    Running("Running"),
    Stopping("Stopping"),
    Failed("Failed"),
}

data class TermuxAria2RpcConfig(
    val port: Int,
    val secret: String,
    val downloadDir: String,
    val sessionFile: String,
    val logFile: String,
) {
    val endpoint: String get() = "http://127.0.0.1:$port/jsonrpc"
    val redactedEndpoint: String get() = "127.0.0.1:$port"
    val redactedSecret: String get() = if (secret.length <= 8) "••••" else "${secret.take(4)}…${secret.takeLast(4)}"
}

data class TermuxAria2TaskRow(
    val gid: String,
    val status: String,
    val fileName: String,
    val completedLength: Long = 0,
    val totalLength: Long = 0,
    val speedBytesPerSecond: Long = 0,
) {
    val progressLabel: String get() = when {
        totalLength > 0L -> "${completedLength * 100L / totalLength}%"
        completedLength > 0L -> "$completedLength bytes"
        else -> "Waiting"
    }
}

data class TermuxAria2CockpitStatus(
    val enabled: Boolean = false,
    val daemonState: TermuxAria2DaemonState = TermuxAria2DaemonState.Disabled,
    val config: TermuxAria2RpcConfig? = null,
    val lastHealth: String = "Termux aria2 has not been checked yet.",
    val lastAction: String = "No daemon action has been run yet.",
    val taskRows: List<TermuxAria2TaskRow> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
) {
    val canStart: Boolean get() = enabled && daemonState != TermuxAria2DaemonState.Starting && daemonState != TermuxAria2DaemonState.Running
    val canStop: Boolean get() = enabled && daemonState == TermuxAria2DaemonState.Running
    val canProbe: Boolean get() = enabled
    val canControlTasks: Boolean get() = enabled && daemonState == TermuxAria2DaemonState.Running

    val readinessLabel: String get() = when {
        !enabled -> "Termux aria2 disabled"
        daemonState == TermuxAria2DaemonState.Running -> "RPC daemon ready"
        daemonState == TermuxAria2DaemonState.Failed -> "Needs attention"
        else -> daemonState.label
    }

    fun diagnosticsSummary(): String = buildString {
        appendLine("Termux aria2: $readinessLabel")
        appendLine("Enabled: $enabled")
        appendLine("State: ${daemonState.label}")
        appendLine("Endpoint: ${config?.redactedEndpoint ?: "not generated"}")
        appendLine("Secret: ${config?.redactedSecret ?: "not generated"}")
        appendLine("Download dir: ${config?.downloadDir ?: "not generated"}")
        appendLine("Session file: ${config?.sessionFile ?: "not generated"}")
        appendLine("Log file: ${config?.logFile ?: "not generated"}")
        appendLine("Last health: $lastHealth")
        appendLine("Last action: $lastAction")
        taskRows.take(5).forEach { task ->
            appendLine("Task ${task.gid}: ${task.status.lowercase(Locale.US)} ${task.progressLabel} ${task.fileName}")
        }
    }.trim()
}
