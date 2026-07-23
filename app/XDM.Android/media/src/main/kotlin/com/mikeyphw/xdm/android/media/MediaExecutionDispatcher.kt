package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import java.util.Locale

/**
 * Phase 22 dispatch planner for media execution.
 *
 * This layer deliberately stays pure Kotlin. It does not start Android work, write files, or call
 * Termux directly. Instead it creates a redacted runbook that UI, workers, and Termux bridges can
 * consume without copying cookies, Authorization headers, or tokenized URLs into durable logs.
 */
enum class MediaDispatchReadiness(val label: String) {
    Ready("Ready"),
    AwaitingUserChoice("Awaiting user choice"),
    NeedsMetadataRefresh("Needs metadata refresh"),
    NeedsTermuxSetup("Needs Termux setup"),
    BlockedProtected("Blocked protected"),
    BlockedSecretLeak("Blocked secret leak"),
}

enum class MediaDispatchStepKind(val label: String) {
    Preflight("Preflight"),
    PrepareTransientFiles("Prepare transient files"),
    QueueBackgroundWork("Queue background work"),
    LaunchTermuxJob("Launch Termux job"),
    PersistRedactedSidecar("Persist redacted sidecar"),
    RegisterCleanup("Register cleanup"),
    VerifyRedaction("Verify redaction"),
    NotifyUser("Notify user"),
}

data class MediaDispatchStep(
    val kind: MediaDispatchStepKind,
    val title: String,
    val detail: String,
    val secretSafe: Boolean = true,
    val terminalCleanup: Boolean = false,
) {
    val summary: String get() = listOf(kind.label, title, detail).joinToString(" • ")
}

data class MediaRetryPolicy(
    val maxAttempts: Int,
    val backoffSeconds: List<Int>,
    val retryableReasons: List<String>,
    val nonRetryableReasons: List<String>,
) {
    val summary: String
        get() = "maxAttempts=$maxAttempts • backoff=${backoffSeconds.joinToString("/")}s • retryable=${retryableReasons.joinToString()}"
}

data class MediaProgressSignal(
    val label: String,
    val source: String,
    val userVisible: Boolean,
) {
    val summary: String get() = "$label from $source${if (userVisible) " visible" else " internal"}"
}

data class MediaDispatchPlan(
    val captureId: String,
    val title: String,
    val readiness: MediaDispatchReadiness,
    val lane: MediaExecutionLane,
    val primaryActionLabel: String,
    val queueButtonEnabled: Boolean,
    val backgroundPolicySummary: String,
    val steps: List<MediaDispatchStep>,
    val retryPolicy: MediaRetryPolicy,
    val progressSignals: List<MediaProgressSignal>,
    val warnings: List<String>,
    val safeDiagnostics: String,
) {
    val blocked: Boolean get() = readiness.name.startsWith("Blocked")
    val summary: String
        get() = listOf(
            readiness.label,
            lane.label,
            primaryActionLabel,
            backgroundPolicySummary,
            "steps=${steps.size}",
            retryPolicy.summary,
        ).joinToString("; ")
}

class MediaExecutionDispatcher {
    fun dispatchPlan(
        spec: MediaQueuedDownloadSpec,
        enginePlan: MediaExecutionEnginePlan,
        capture: MediaCaptureRecord? = null,
        termuxReady: Boolean = true,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): MediaDispatchPlan {
        val warnings = mutableListOf<String>()
        val requiresChoice = spec.selectedTrackIds.isEmpty() && (spec.requiresTermuxYtDlp || spec.strategy == MediaDownloadStrategy.YtDlp)
        val captureNeedsRefresh = capture?.needsManifestRefresh(nowEpochMs) == true || capture?.resolutionStatus == MediaResolutionStatus.RequiresRefresh
        val needsRefresh = captureNeedsRefresh || (capture == null && spec.isExpiringUrl)
        val leakSafe = enginePlan.leakReport.safe
        val readiness = when {
            !leakSafe -> MediaDispatchReadiness.BlockedSecretLeak
            enginePlan.lane == MediaExecutionLane.ProtectedBlocked -> MediaDispatchReadiness.BlockedProtected
            needsRefresh -> MediaDispatchReadiness.NeedsMetadataRefresh
            requiresChoice -> MediaDispatchReadiness.AwaitingUserChoice
            (enginePlan.lane == MediaExecutionLane.YtDlpAdaptive || enginePlan.lane == MediaExecutionLane.LiveRecording) && !termuxReady -> MediaDispatchReadiness.NeedsTermuxSetup
            else -> MediaDispatchReadiness.Ready
        }
        if (needsRefresh) warnings += "Refresh metadata before enqueue so expiring manifests, page cookies, and selected variants are current."
        if (requiresChoice) warnings += "Select a variant or explicit yt-dlp format before launching adaptive media."
        if (!termuxReady && (enginePlan.lane == MediaExecutionLane.YtDlpAdaptive || enginePlan.lane == MediaExecutionLane.LiveRecording)) {
            warnings += "Termux media pipeline is required for this resolver lane."
        }
        if (!leakSafe) warnings += "Potential secret leak detected in one or more execution surfaces."
        val steps = dispatchSteps(spec, enginePlan, readiness)
        val diagnostics = safeDiagnostics(spec, enginePlan, readiness, warnings, steps)
        return MediaDispatchPlan(
            captureId = spec.captureId,
            title = spec.userLabel,
            readiness = readiness,
            lane = enginePlan.lane,
            primaryActionLabel = primaryActionLabel(readiness, enginePlan.lane),
            queueButtonEnabled = readiness == MediaDispatchReadiness.Ready,
            backgroundPolicySummary = enginePlan.backgroundPolicy.summary,
            steps = steps,
            retryPolicy = retryPolicyFor(enginePlan.lane, readiness),
            progressSignals = progressSignalsFor(enginePlan.lane),
            warnings = warnings,
            safeDiagnostics = diagnostics,
        )
    }

    fun aggregate(plans: List<MediaDispatchPlan>): MediaDispatchDashboard {
        val ready = plans.count { it.readiness == MediaDispatchReadiness.Ready }
        val blocked = plans.count { it.blocked }
        val refresh = plans.count { it.readiness == MediaDispatchReadiness.NeedsMetadataRefresh }
        val termux = plans.count { it.readiness == MediaDispatchReadiness.NeedsTermuxSetup }
        val secretSafe = plans.none { it.readiness == MediaDispatchReadiness.BlockedSecretLeak }
        val laneCounts = linkedMapOf<String, Int>()
        plans.forEach { plan -> laneCounts[plan.lane.label] = (laneCounts[plan.lane.label] ?: 0) + 1 }
        return MediaDispatchDashboard(
            readyCount = ready,
            blockedCount = blocked,
            refreshCount = refresh,
            termuxSetupCount = termux,
            secretSafe = secretSafe,
            laneCounts = laneCounts,
        )
    }

    private fun dispatchSteps(
        spec: MediaQueuedDownloadSpec,
        enginePlan: MediaExecutionEnginePlan,
        readiness: MediaDispatchReadiness,
    ): List<MediaDispatchStep> {
        val steps = mutableListOf<MediaDispatchStep>()
        steps += MediaDispatchStep(
            kind = MediaDispatchStepKind.Preflight,
            title = "Check resolver readiness",
            detail = "${readiness.label}; ${spec.strategy.displayName}; ${spec.selectedTrackIds.size} selected track(s).",
        )
        if (readiness.blockingReason() != null) {
            steps += MediaDispatchStep(
                kind = MediaDispatchStepKind.NotifyUser,
                title = "Hold dispatch",
                detail = readiness.blockingReason().orEmpty(),
            )
            steps += MediaDispatchStep(
                kind = MediaDispatchStepKind.VerifyRedaction,
                title = "Keep diagnostics redacted",
                detail = enginePlan.leakReport.summary,
                secretSafe = enginePlan.leakReport.safe,
            )
            return steps
        }
        if (enginePlan.tempCookieFile != null || enginePlan.aria2Input != null) {
            val fileLabels = listOfNotNull(
                enginePlan.tempCookieFile?.fileName,
                enginePlan.aria2Input?.inputFileName,
                enginePlan.aria2Input?.sessionFileName,
            ).joinToString()
            steps += MediaDispatchStep(
                kind = MediaDispatchStepKind.PrepareTransientFiles,
                title = "Prepare process-scoped files",
                detail = fileLabels.ifBlank { "No transient files required." },
            )
        }
        when (enginePlan.lane) {
            MediaExecutionLane.DirectNative,
            MediaExecutionLane.Aria2Segmented -> steps += MediaDispatchStep(
                kind = MediaDispatchStepKind.QueueBackgroundWork,
                title = "Queue visible transfer",
                detail = enginePlan.backgroundPolicy.summary,
            )
            MediaExecutionLane.YtDlpAdaptive,
            MediaExecutionLane.LiveRecording -> steps += MediaDispatchStep(
                kind = MediaDispatchStepKind.LaunchTermuxJob,
                title = "Launch typed Termux media job",
                detail = "executor=${enginePlan.typedExecutor}; args=${enginePlan.typedArguments.size}; raw shell disabled.",
            )
            MediaExecutionLane.ProtectedBlocked -> steps += MediaDispatchStep(
                kind = MediaDispatchStepKind.NotifyUser,
                title = "Protected media diagnostic only",
                detail = "No bypass attempt will be queued.",
            )
        }
        steps += MediaDispatchStep(
            kind = MediaDispatchStepKind.PersistRedactedSidecar,
            title = "Persist library sidecar",
            detail = spec.sidecar.toRedactedJson().take(220),
        )
        steps += MediaDispatchStep(
            kind = MediaDispatchStepKind.RegisterCleanup,
            title = "Register terminal cleanup",
            detail = enginePlan.cleanupActions.joinToString(),
            terminalCleanup = true,
        )
        steps += MediaDispatchStep(
            kind = MediaDispatchStepKind.VerifyRedaction,
            title = "Verify no durable secrets",
            detail = enginePlan.leakReport.summary,
            secretSafe = enginePlan.leakReport.safe,
        )
        steps += MediaDispatchStep(
            kind = MediaDispatchStepKind.NotifyUser,
            title = "Surface progress",
            detail = progressSignalsFor(enginePlan.lane).joinToString { it.label },
        )
        return steps
    }

    private fun primaryActionLabel(readiness: MediaDispatchReadiness, lane: MediaExecutionLane): String = when (readiness) {
        MediaDispatchReadiness.Ready -> when (lane) {
            MediaExecutionLane.DirectNative -> "Queue direct media"
            MediaExecutionLane.Aria2Segmented -> "Queue aria2 media"
            MediaExecutionLane.YtDlpAdaptive -> "Launch yt-dlp media"
            MediaExecutionLane.LiveRecording -> "Start live recording"
            MediaExecutionLane.ProtectedBlocked -> "View diagnostics"
        }
        MediaDispatchReadiness.AwaitingUserChoice -> "Choose variant / tracks"
        MediaDispatchReadiness.NeedsMetadataRefresh -> "Refresh metadata"
        MediaDispatchReadiness.NeedsTermuxSetup -> "Open Termux setup"
        MediaDispatchReadiness.BlockedProtected -> "View protected-media diagnostics"
        MediaDispatchReadiness.BlockedSecretLeak -> "Review redaction failure"
    }

    private fun retryPolicyFor(lane: MediaExecutionLane, readiness: MediaDispatchReadiness): MediaRetryPolicy {
        if (readiness.blockingReason() != null) {
            return MediaRetryPolicy(
                maxAttempts = 0,
                backoffSeconds = emptyList(),
                retryableReasons = emptyList(),
                nonRetryableReasons = listOf(readiness.label),
            )
        }
        return when (lane) {
            MediaExecutionLane.DirectNative -> MediaRetryPolicy(3, listOf(5, 20, 60), listOf("network", "server timeout", "resume token valid"), listOf("protected media", "invalid destination"))
            MediaExecutionLane.Aria2Segmented -> MediaRetryPolicy(4, listOf(5, 15, 45, 120), listOf("segment timeout", "temporary 5xx", "network switch"), listOf("expired cookie", "tokenized URL expired"))
            MediaExecutionLane.YtDlpAdaptive -> MediaRetryPolicy(2, listOf(10, 60), listOf("extractor transient failure", "metadata refresh available"), listOf("unsupported extractor", "DRM protected"))
            MediaExecutionLane.LiveRecording -> MediaRetryPolicy(1, listOf(30), listOf("live connection dropped"), listOf("live ended", "protected media"))
            MediaExecutionLane.ProtectedBlocked -> MediaRetryPolicy(0, emptyList(), emptyList(), listOf("protected media"))
        }
    }

    private fun progressSignalsFor(lane: MediaExecutionLane): List<MediaProgressSignal> = when (lane) {
        MediaExecutionLane.DirectNative -> listOf(
            MediaProgressSignal("bytes downloaded", "native worker", true),
            MediaProgressSignal("notification progress", "foreground policy", true),
            MediaProgressSignal("sidecar finalization", "library writer", false),
        )
        MediaExecutionLane.Aria2Segmented -> listOf(
            MediaProgressSignal("segment progress", "aria2 status", true),
            MediaProgressSignal("session save", "aria2 transient session", false),
            MediaProgressSignal("cleanup verification", "handoff store", false),
        )
        MediaExecutionLane.YtDlpAdaptive -> listOf(
            MediaProgressSignal("extractor status", "yt-dlp", true),
            MediaProgressSignal("download fragment progress", "yt-dlp", true),
            MediaProgressSignal("merge/finalize", "yt-dlp/FFmpeg", true),
        )
        MediaExecutionLane.LiveRecording -> listOf(
            MediaProgressSignal("recording duration", "yt-dlp/FFmpeg", true),
            MediaProgressSignal("live heartbeat", "Termux media pipeline", true),
            MediaProgressSignal("terminal cleanup", "handoff store", false),
        )
        MediaExecutionLane.ProtectedBlocked -> listOf(
            MediaProgressSignal("diagnostic summary", "resolver", true),
        )
    }

    private fun safeDiagnostics(
        spec: MediaQueuedDownloadSpec,
        enginePlan: MediaExecutionEnginePlan,
        readiness: MediaDispatchReadiness,
        warnings: List<String>,
        steps: List<MediaDispatchStep>,
    ): String {
        val body = mutableListOf<String>()
        body += "capture=${spec.captureId}"
        body += "readiness=${readiness.label}"
        body += "lane=${enginePlan.lane.label}"
        body += "policy=${enginePlan.backgroundPolicy.summary}"
        body += "executor=${enginePlan.typedExecutor}"
        body += "args=${enginePlan.typedArguments.size} typed argument(s)"
        body += "tracks=${spec.selectedTrackIds.size}"
        body += "source=${spec.sidecar.redactedSourceUrl}"
        if (warnings.isNotEmpty()) body += "warnings=${warnings.joinToString(" | ")}"
        body += "steps=${steps.joinToString(" -> ") { it.kind.label }}"
        body += "leaks=${enginePlan.leakReport.summary}"
        return body.joinToString("\n").redactKnownSecrets()
    }

    private fun MediaDispatchReadiness.blockingReason(): String? = when (this) {
        MediaDispatchReadiness.Ready -> null
        MediaDispatchReadiness.AwaitingUserChoice -> null
        MediaDispatchReadiness.NeedsMetadataRefresh -> "Metadata refresh is required before dispatch."
        MediaDispatchReadiness.NeedsTermuxSetup -> "Termux media pipeline is not ready for this lane."
        MediaDispatchReadiness.BlockedProtected -> "Protected media is diagnostic-only."
        MediaDispatchReadiness.BlockedSecretLeak -> "Secret redaction failed; dispatch is blocked."
    }

    private fun String.redactKnownSecrets(): String {
        var redacted = this
        val patterns = listOf(
            Regex("(?i)(cookie=)[^\\s;]+"),
            Regex("(?i)(authorization=)[^\\s;]+"),
            Regex("(?i)(token=)[^\\s&]+"),
            Regex("(?i)(signature=)[^\\s&]+"),
            Regex("(?i)(x-goog-signature=)[^\\s&]+"),
        )
        patterns.forEach { pattern -> redacted = pattern.replace(redacted) { match -> match.groupValues[1] + "<redacted>" } }
        return redacted
    }
}

data class MediaDispatchDashboard(
    val readyCount: Int,
    val blockedCount: Int,
    val refreshCount: Int,
    val termuxSetupCount: Int,
    val secretSafe: Boolean,
    val laneCounts: Map<String, Int>,
) {
    val summary: String
        get() {
            val lanes = laneCounts.entries.joinToString { (lane, count) -> "$lane=$count" }.ifBlank { "no lanes" }
            val safety = if (secretSafe) "secret-safe" else "redaction review required"
            return "ready=$readyCount • blocked=$blockedCount • refresh=$refreshCount • termuxSetup=$termuxSetupCount • $safety • $lanes"
        }
}

private val MediaDownloadStrategy.displayName: String
    get() = name.lowercase(Locale.US).replaceFirstChar { char -> char.titlecase(Locale.US) }
