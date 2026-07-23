package com.mikeyphw.xdm.android.media

import java.util.Locale

/**
 * Phase 26 Termux runtime adapter.
 *
 * This is the first concrete external-runtime contract for media jobs. It converts Phase 25
 * worker bridge requests into typed Termux launch plans, transient file plans, capability probes,
 * and cleanup verification. It does not auto-install tools, execute shell text, or persist cookies,
 * headers, bearer tokens, or tokenized URLs.
 */
enum class TermuxMediaRuntimeTool(val binaryName: String, val installHint: String) {
    YtDlp("yt-dlp", "Install yt-dlp in Termux, then run the XDM media probe again."),
    Aria2c("aria2c", "Install aria2 in Termux for segmented downloads."),
    Ffmpeg("ffmpeg", "Install ffmpeg in Termux for live recording and post-processing."),
    Ffprobe("ffprobe", "Install ffmpeg/ffprobe in Termux for media inspection."),
}

enum class TermuxMediaRuntimeCapabilityStatus(val label: String) {
    Available("Available"),
    Missing("Missing"),
    Unknown("Unknown"),
}

data class TermuxMediaRuntimeToolProbe(
    val tool: TermuxMediaRuntimeTool,
    val status: TermuxMediaRuntimeCapabilityStatus,
    val versionLabel: String?,
    val installHint: String,
) {
    val ready: Boolean get() = status == TermuxMediaRuntimeCapabilityStatus.Available
    val summary: String get() = listOfNotNull(tool.binaryName, status.label, versionLabel).joinToString(" • ")
}

data class TermuxMediaRuntimeCapabilityReport(
    val probes: List<TermuxMediaRuntimeToolProbe>,
) {
    fun ready(tool: TermuxMediaRuntimeTool): Boolean = probes.firstOrNull { it.tool == tool }?.ready == true
    val missingTools: List<TermuxMediaRuntimeTool> get() = probes.filter { !it.ready }.map { it.tool }
    val summary: String get() = probes.joinToString(" • ") { probe -> "${probe.tool.binaryName}=${probe.status.label}" }
    val installHints: List<String> get() = probes.filter { !it.ready }.map { it.installHint }.distinct()
}

enum class TermuxRuntimeLaunchKind(val label: String) {
    YtDlpDownload("yt-dlp download"),
    YtDlpLiveRecording("yt-dlp live recording"),
    Aria2Download("aria2 download"),
    MetadataProbe("metadata probe"),
    BlockedDiagnostic("blocked diagnostic"),
}

enum class TermuxRuntimeTransientKind(val label: String) {
    NetscapeCookies("Netscape cookie file"),
    Aria2Input("aria2 input file"),
    Aria2Session("aria2 session file"),
    HeaderManifest("redacted header manifest"),
}

data class TermuxRuntimeTransientFile(
    val fileName: String,
    val kind: TermuxRuntimeTransientKind,
    val redactedPreview: String,
    val deleteAfterTerminalState: Boolean,
    val verifierLabel: String,
) {
    val summary: String get() = listOf(fileName, kind.label, if (deleteAfterTerminalState) "delete-after-terminal" else "retain").joinToString(" • ")
}

data class TermuxRuntimeCleanupStep(
    val label: String,
    val required: Boolean,
    val verifierLabel: String,
) {
    val summary: String get() = listOf(label, if (required) "required" else "optional", verifierLabel).joinToString(" • ")
}

data class TermuxRuntimeLaunchPlan(
    val captureId: String,
    val durableJobId: String,
    val kind: TermuxRuntimeLaunchKind,
    val executor: String,
    val typedArguments: List<String>,
    val transientFiles: List<TermuxRuntimeTransientFile>,
    val cleanupSteps: List<TermuxRuntimeCleanupStep>,
    val capabilityReport: TermuxMediaRuntimeCapabilityReport,
    val redactedPreview: String,
    val launchable: Boolean,
    val noRawShell: Boolean,
    val secretSafe: Boolean,
) {
    val missingToolHints: List<String> get() = capabilityReport.installHints
    val summary: String get() = listOf(
        kind.label,
        executor,
        "args=${typedArguments.size}",
        "transient=${transientFiles.size}",
        if (launchable) "launchable" else "not-ready",
        if (secretSafe) "secret-safe" else "redaction-review",
        if (noRawShell) "typed-args" else "raw-shell-blocked",
    ).joinToString(" • ")
}

data class TermuxRuntimeDashboard(
    val plans: List<TermuxRuntimeLaunchPlan>,
    val launchableCount: Int,
    val missingToolCount: Int,
    val cleanupArmedCount: Int,
    val secretSafe: Boolean,
) {
    val summary: String get() = listOf(
        "launchable=$launchableCount",
        "missingTools=$missingToolCount",
        "cleanup=$cleanupArmedCount",
        if (secretSafe) "secret-safe" else "redaction review",
    ).joinToString(" • ")
}

class MediaTermuxRuntimeAdapter {
    fun capabilityReport(
        availableTools: Set<String>,
        versions: Map<String, String> = emptyMap(),
    ): TermuxMediaRuntimeCapabilityReport {
        val normalized = availableTools.map { it.lowercase(Locale.US) }.toSet()
        val probes = TermuxMediaRuntimeTool.entries.map { tool ->
            val ready = normalized.contains(tool.binaryName.lowercase(Locale.US)) || normalized.contains(tool.name.lowercase(Locale.US))
            TermuxMediaRuntimeToolProbe(
                tool = tool,
                status = if (ready) TermuxMediaRuntimeCapabilityStatus.Available else TermuxMediaRuntimeCapabilityStatus.Missing,
                versionLabel = versions[tool.binaryName],
                installHint = tool.installHint,
            )
        }
        return TermuxMediaRuntimeCapabilityReport(probes)
    }

    fun launchPlan(
        request: MediaWorkerBridgeRequest,
        availableTools: Set<String>,
        versions: Map<String, String> = emptyMap(),
    ): TermuxRuntimeLaunchPlan {
        val report = capabilityReport(availableTools, versions)
        val kind = kindFor(request)
        val requiredTools = requiredToolsFor(kind)
        val toolsReady = requiredTools.all(report::ready)
        val transientFiles = transientFilesFor(request)
        val cleanup = cleanupStepsFor(request, transientFiles)
        val typedArguments = request.adapter.typedArguments.map(::redactKnownSecrets)
        val noRawShell = !request.adapter.rawShellExposed && typedArguments.none { it.contains("; sh") || it.contains("&&") || it.contains("|") }
        val preview = previewFor(request, kind, typedArguments, transientFiles, cleanup)
        val secretSafe = request.secretSafe && !containsKnownSecret(preview) && transientFiles.none { containsKnownSecret(it.redactedPreview) }
        return TermuxRuntimeLaunchPlan(
            captureId = request.captureId,
            durableJobId = request.durableJobId,
            kind = if (secretSafe) kind else TermuxRuntimeLaunchKind.BlockedDiagnostic,
            executor = if (kind == TermuxRuntimeLaunchKind.BlockedDiagnostic) "diagnostics-only" else request.adapter.executorLabel,
            typedArguments = typedArguments,
            transientFiles = transientFiles,
            cleanupSteps = cleanup,
            capabilityReport = report,
            redactedPreview = preview,
            launchable = request.launchable && toolsReady && noRawShell && secretSafe && kind != TermuxRuntimeLaunchKind.BlockedDiagnostic,
            noRawShell = noRawShell,
            secretSafe = secretSafe,
        )
    }

    fun dashboard(plans: List<TermuxRuntimeLaunchPlan>): TermuxRuntimeDashboard = TermuxRuntimeDashboard(
        plans = plans,
        launchableCount = plans.count { it.launchable },
        missingToolCount = plans.sumOf { plan -> plan.capabilityReport.missingTools.size },
        cleanupArmedCount = plans.count { plan -> plan.cleanupSteps.any { it.required } },
        secretSafe = plans.all { it.secretSafe },
    )

    private fun kindFor(request: MediaWorkerBridgeRequest): TermuxRuntimeLaunchKind = when {
        request.kind == MediaWorkerBridgeKind.BlockedDiagnostic || request.lane == MediaExecutionLane.ProtectedBlocked -> TermuxRuntimeLaunchKind.BlockedDiagnostic
        request.lane == MediaExecutionLane.LiveRecording -> TermuxRuntimeLaunchKind.YtDlpLiveRecording
        request.lane == MediaExecutionLane.YtDlpAdaptive || request.kind == MediaWorkerBridgeKind.TermuxYtDlp -> TermuxRuntimeLaunchKind.YtDlpDownload
        request.lane == MediaExecutionLane.Aria2Segmented || request.kind == MediaWorkerBridgeKind.Aria2Adapter -> TermuxRuntimeLaunchKind.Aria2Download
        else -> TermuxRuntimeLaunchKind.MetadataProbe
    }

    private fun requiredToolsFor(kind: TermuxRuntimeLaunchKind): Set<TermuxMediaRuntimeTool> = when (kind) {
        TermuxRuntimeLaunchKind.YtDlpDownload -> setOf(TermuxMediaRuntimeTool.YtDlp)
        TermuxRuntimeLaunchKind.YtDlpLiveRecording -> setOf(TermuxMediaRuntimeTool.YtDlp, TermuxMediaRuntimeTool.Ffmpeg)
        TermuxRuntimeLaunchKind.Aria2Download -> setOf(TermuxMediaRuntimeTool.Aria2c)
        TermuxRuntimeLaunchKind.MetadataProbe -> setOf(TermuxMediaRuntimeTool.YtDlp, TermuxMediaRuntimeTool.Ffprobe)
        TermuxRuntimeLaunchKind.BlockedDiagnostic -> emptySet()
    }

    private fun transientFilesFor(request: MediaWorkerBridgeRequest): List<TermuxRuntimeTransientFile> {
        val files = mutableListOf<TermuxRuntimeTransientFile>()
        request.adapter.transientInputLabels.forEach { label ->
            val kind = when {
                label.endsWith(".cookies.txt") -> TermuxRuntimeTransientKind.NetscapeCookies
                label.endsWith(".aria2.input") -> TermuxRuntimeTransientKind.Aria2Input
                label.endsWith(".aria2.session") -> TermuxRuntimeTransientKind.Aria2Session
                else -> TermuxRuntimeTransientKind.HeaderManifest
            }
            val preview = when (kind) {
                TermuxRuntimeTransientKind.NetscapeCookies -> "# Netscape HTTP Cookie File\n# redacted cookie rows only\n# delete after terminal state"
                TermuxRuntimeTransientKind.Aria2Input -> "# aria2 input file\n# redacted URI and header options\n# typed adapter writes file immediately before launch"
                TermuxRuntimeTransientKind.Aria2Session -> "# aria2 session file\n# no credentials persisted\n# delete after terminal state"
                TermuxRuntimeTransientKind.HeaderManifest -> "# transient header manifest\n# values redacted\n# process scoped"
            }
            files += TermuxRuntimeTransientFile(
                fileName = label,
                kind = kind,
                redactedPreview = preview,
                deleteAfterTerminalState = true,
                verifierLabel = "verify-${kind.name.lowercase(Locale.US)}-forgotten",
            )
        }
        return files
    }

    private fun cleanupStepsFor(
        request: MediaWorkerBridgeRequest,
        transientFiles: List<TermuxRuntimeTransientFile>,
    ): List<TermuxRuntimeCleanupStep> {
        val steps = mutableListOf<TermuxRuntimeCleanupStep>()
        transientFiles.forEach { file ->
            steps += TermuxRuntimeCleanupStep("delete ${file.kind.label} ${file.fileName}", required = true, verifierLabel = file.verifierLabel)
        }
        request.cleanupAfterTerminal.forEach { label ->
            steps += TermuxRuntimeCleanupStep(redactKnownSecrets(label), required = true, verifierLabel = "bridge-cleanup")
        }
        steps += TermuxRuntimeCleanupStep("verify no cookies, Authorization headers, tokens, or signed URLs reached logs or sidecars", required = true, verifierLabel = "redaction-scan")
        return steps.distinctBy { it.summary }
    }

    private fun previewFor(
        request: MediaWorkerBridgeRequest,
        kind: TermuxRuntimeLaunchKind,
        typedArguments: List<String>,
        transientFiles: List<TermuxRuntimeTransientFile>,
        cleanup: List<TermuxRuntimeCleanupStep>,
    ): String = redactKnownSecrets(
        listOf(
            "Termux runtime adapter",
            "kind=${kind.label}",
            "job=${request.durableJobId}",
            "executor=${request.adapter.executorLabel}",
            "args=${typedArguments.joinToString(" ")}",
            "transient=${transientFiles.joinToString { it.summary }}",
            "cleanup=${cleanup.joinToString { it.label }}",
            "rawShell=false",
        ).joinToString("\n"),
    )

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { pattern -> pattern.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private companion object {
        val secretPatterns = listOf(
            Regex("""Bearer\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=](?!\s*<redacted(?:-[A-Za-z]+)?>)\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(?<![-A-Za-z])(token|session|sid|sig|signature|auth|key)=((?!<redacted>|referer=|none\b|available\b|redacted\b)[^\s&#;]+)"""),
            Regex("\\b(?:super-)?secret-(?!(?:safe|bearing|free)\\b)[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
