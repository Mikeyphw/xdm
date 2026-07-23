package com.mikeyphw.xdm.android.termux

import java.util.Locale

enum class ExternalTool(val binaryName: String, val displayName: String, val versionArguments: List<String>) {
    Aria2("aria2c", "aria2", listOf("--version")),
    Ffmpeg("ffmpeg", "FFmpeg", listOf("-version")),
    Ffprobe("ffprobe", "FFprobe", listOf("-version")),
    YtDlp("yt-dlp", "yt-dlp", listOf("--version")),
    Python("python", "Python", listOf("--version")),
    Curl("curl", "curl", listOf("--version")),
}

enum class TermuxRootMode(val label: String, val description: String) {
    Off("Off", "Never request privileged operations."),
    AskEachTime("Ask each time", "Gate every privileged file or process operation behind confirmation."),
    TrustedActions("Trusted actions", "Allow only typed XDM root actions that are already confirmed and logged."),
}

enum class RootActionRisk {
    Low,
    Medium,
    Destructive,
}

sealed class XdmRootAction(open val risk: RootActionRisk, open val label: String) {
    data class FixFilePermissions(val path: String) : XdmRootAction(RootActionRisk.Medium, "Fix output permissions")
    data class KillOwnedProcess(val pid: Int) : XdmRootAction(RootActionRisk.Medium, "Kill owned process")
    data class KillTermuxAria2Daemon(val port: Int) : XdmRootAction(RootActionRisk.Medium, "Kill stuck Termux aria2")
    data class MoveCompletedFile(val from: String, val to: String) : XdmRootAction(RootActionRisk.Medium, "Move completed file")
    data class CollectProcessDiagnostics(val packageName: String) : XdmRootAction(RootActionRisk.Low, "Collect root process diagnostics")
}

data class RootActionAuditRecord(
    val runId: String,
    val actionLabel: String,
    val risk: RootActionRisk,
    val status: TermuxRunStatus,
    val message: String,
    val createdAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
) {
    val summary: String get() = "$actionLabel • ${status.name.lowercase(Locale.US)} • $message"
}

sealed class XdmTermuxCommand(val operation: String) {
    data class ProbeTool(val tool: ExternalTool) : XdmTermuxCommand("probe_${tool.binaryName}")
    data object ProbeAllTools : XdmTermuxCommand("probe_all_tools")
    data class Aria2Download(val url: String, val destination: String, val fileName: String?) : XdmTermuxCommand("aria2_download")
    data class YtDlpMetadata(val url: String, val extraArguments: List<String> = emptyList()) : XdmTermuxCommand("ytdlp_metadata")
    data class YtDlpDownload(val url: String, val destination: String, val outputTemplate: String, val format: String?, val extraArguments: List<String> = emptyList()) : XdmTermuxCommand("ytdlp_download")
    data class FfprobeInspect(val path: String) : XdmTermuxCommand("ffprobe_inspect")
    data class FfmpegConvert(val input: String, val output: String, val preset: String) : XdmTermuxCommand("ffmpeg_convert")
    data class PostProcess(val plan: TermuxPostProcessingPlan) : XdmTermuxCommand("post_process_${plan.kind.name.lowercase(Locale.US)}")
    data class Aria2StartDaemon(val config: TermuxAria2RpcConfig) : XdmTermuxCommand("aria2_daemon_start")
    data class Aria2StopDaemon(val config: TermuxAria2RpcConfig) : XdmTermuxCommand("aria2_daemon_stop")
    data class Aria2ProbeDaemon(val config: TermuxAria2RpcConfig) : XdmTermuxCommand("aria2_daemon_probe")
    data class Aria2SaveSession(val config: TermuxAria2RpcConfig) : XdmTermuxCommand("aria2_session_save")
    data class Aria2TellActive(val config: TermuxAria2RpcConfig) : XdmTermuxCommand("aria2_tasks_active")
    data class Aria2PauseAll(val config: TermuxAria2RpcConfig) : XdmTermuxCommand("aria2_tasks_pause_all")
    data class Aria2ResumeAll(val config: TermuxAria2RpcConfig) : XdmTermuxCommand("aria2_tasks_resume_all")
    data object RootProbe : XdmTermuxCommand("root_probe")
    data class RootAction(val action: XdmRootAction) : XdmTermuxCommand("root_${action.label.lowercase(Locale.US).replace(" ", "_")}")
}

data class TermuxToolProbeRow(
    val tool: ExternalTool,
    val available: Boolean = false,
    val executablePath: String = "",
    val versionLine: String = "Not probed yet",
) {
    val statusLabel: String get() = if (available) "Available" else "Missing"
}

data class TermuxRunRecord(
    val runId: String,
    val operation: String,
    val status: TermuxRunStatus,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val exitCode: Int? = null,
    val stdoutPreview: String = "",
    val stderrPreview: String = "",
    val error: String = "",
) {
    val summary: String get() = when (status) {
        TermuxRunStatus.Started -> "Running $operation"
        TermuxRunStatus.Succeeded -> "$operation finished"
        TermuxRunStatus.Failed -> "$operation failed"
    }
}

enum class TermuxRunStatus {
    Started,
    Succeeded,
    Failed,
}

data class TermuxBridgeStatus(
    val termuxInstalled: Boolean = false,
    val runCommandPermissionGranted: Boolean = false,
    val rootMode: TermuxRootMode = TermuxRootMode.Off,
    val rootAvailable: Boolean = false,
    val toolRows: List<TermuxToolProbeRow> = ExternalTool.entries.map { TermuxToolProbeRow(it) },
    val recentRuns: List<TermuxRunRecord> = emptyList(),
    val rootAudit: List<RootActionAuditRecord> = emptyList(),
    val lastRootMessage: String = "Root actions have not run yet.",
    val lastMessage: String = "Termux has not been checked yet.",
    val updatedAtEpochMs: Long = 0L,
) {
    val canRunProbe: Boolean get() = termuxInstalled && runCommandPermissionGranted
    val rootProbeSucceeded: Boolean get() = rootAudit.any { it.actionLabel == "Probe root" && it.status == TermuxRunStatus.Succeeded }
    val canRunRootProbe: Boolean get() = canRunProbe
    val canRunRootAction: Boolean get() = canRunProbe && rootMode != TermuxRootMode.Off && rootAvailable && rootProbeSucceeded

    val readinessLabel: String get() = when {
        !termuxInstalled -> "Termux missing"
        !runCommandPermissionGranted -> "Permission needed"
        toolRows.any { it.available } -> "Tools detected"
        else -> "Ready to probe"
    }

    val summary: String get() = when {
        !termuxInstalled -> "Install Termux, then enable external command support from Termux settings."
        !runCommandPermissionGranted -> "Grant com.termux.permission.RUN_COMMAND so XDM can launch typed tool commands."
        else -> "RUN_COMMAND bridge is available. Root mode is ${rootMode.label.lowercase(Locale.US)}."
    }

    fun diagnosticsSummary(): String = buildString {
        appendLine("Termux bridge: $readinessLabel")
        appendLine("Termux installed: $termuxInstalled")
        appendLine("RUN_COMMAND granted: $runCommandPermissionGranted")
        appendLine("Root mode: ${rootMode.label}")
        appendLine("Root available: $rootAvailable")
        appendLine("Last root action: $lastRootMessage")
        toolRows.forEach { row ->
            appendLine("${row.tool.displayName}: ${row.statusLabel} ${row.versionLine}".trim())
        }
        rootAudit.take(4).forEach { audit ->
            appendLine("Root ${audit.runId}: ${audit.summary}")
        }
        recentRuns.take(3).forEach { run ->
            appendLine("Run ${run.runId}: ${run.summary} exit=${run.exitCode ?: "pending"}")
        }
    }.trim()
}
