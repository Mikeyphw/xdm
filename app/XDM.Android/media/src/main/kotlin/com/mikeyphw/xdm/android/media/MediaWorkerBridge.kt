package com.mikeyphw.xdm.android.media

import java.util.Locale

/**
 * Phase 25 worker bridge contract.
 *
 * This is still a planning layer: it turns a ready dispatch/action plan into a durable worker
 * request shape that Android workers, Termux launchers, aria2 adapters, and yt-dlp adapters can
 * consume later. It does not start services, write files, enqueue WorkManager, or shell out.
 */
enum class MediaWorkerBridgeKind(val label: String) {
    AndroidUidt("Android UIDT worker"),
    WorkManagerForeground("WorkManager foreground worker"),
    ForegroundServiceDataSync("Foreground dataSync service"),
    NativeDirect("Native direct request"),
    Aria2Adapter("aria2 launch adapter"),
    TermuxYtDlp("Termux yt-dlp adapter"),
    BlockedDiagnostic("Blocked diagnostic"),
}

enum class MediaWorkerBridgeReadiness(val label: String) {
    Ready("Ready for worker bridge"),
    WaitingForUserAction("Waiting for user action"),
    WaitingForMetadata("Waiting for metadata refresh"),
    WaitingForTermux("Waiting for Termux setup"),
    NeedsConfirmation("Needs confirmation"),
    Blocked("Blocked"),
}

data class MediaWorkerAdapterContract(
    val executorLabel: String,
    val typedArguments: List<String>,
    val transientInputLabels: List<String>,
    val redactedPreview: String,
    val rawShellExposed: Boolean = false,
) {
    val summary: String
        get() = listOf(
            "executor=$executorLabel",
            "args=${typedArguments.size}",
            "transient=${transientInputLabels.joinToString().ifBlank { "none" }}",
            if (rawShellExposed) "raw-shell" else "typed-args",
        ).joinToString(" • ")
}

data class MediaWorkerForegroundNotificationPlan(
    val channelId: String,
    val title: String,
    val body: String,
    val foregroundServiceType: String?,
    val progressVisible: Boolean,
    val actions: List<String>,
) {
    val summary: String
        get() = listOfNotNull(
            channelId,
            title,
            body,
            foregroundServiceType?.let { "fgs=$it" },
            if (progressVisible) "progress" else "no-progress",
            actions.joinToString(),
        ).joinToString(" • ")
}

data class MediaWorkerBridgeRequest(
    val durableJobId: String,
    val captureId: String,
    val title: String,
    val kind: MediaWorkerBridgeKind,
    val readiness: MediaWorkerBridgeReadiness,
    val lane: MediaExecutionLane,
    val backgroundPolicy: MediaBackgroundExecutionPolicy,
    val adapter: MediaWorkerAdapterContract,
    val notification: MediaWorkerForegroundNotificationPlan,
    val cleanupAfterTerminal: List<String>,
    val redactedSidecarJson: String,
    val safeRunbook: List<String>,
    val secretSafe: Boolean,
) {
    val launchable: Boolean get() = readiness == MediaWorkerBridgeReadiness.Ready && secretSafe && !adapter.rawShellExposed
    val summary: String
        get() = listOf(
            durableJobId,
            kind.label,
            readiness.label,
            lane.label,
            backgroundPolicy.workKind.label,
            adapter.summary,
            if (secretSafe) "secret-safe" else "redaction review",
        ).joinToString(" • ")
}

data class MediaWorkerBridgeDashboard(
    val requests: List<MediaWorkerBridgeRequest>,
    val launchableCount: Int,
    val androidWorkerCount: Int,
    val termuxWorkerCount: Int,
    val blockedCount: Int,
    val confirmationCount: Int,
    val secretSafe: Boolean,
) {
    val empty: Boolean get() = requests.isEmpty()
    val summary: String
        get() = listOf(
            "launchable=$launchableCount",
            "android=$androidWorkerCount",
            "termux=$termuxWorkerCount",
            "blocked=$blockedCount",
            "confirm=$confirmationCount",
            if (secretSafe) "secret-safe" else "redaction review required",
        ).joinToString(" • ")
}

class MediaWorkerBridgePlanner {
    fun request(
        spec: MediaQueuedDownloadSpec,
        enginePlan: MediaExecutionEnginePlan,
        dispatchPlan: MediaDispatchPlan,
        actionPlan: MediaQueueActionPlan,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): MediaWorkerBridgeRequest {
        val kind = kindFor(enginePlan)
        val readiness = readinessFor(dispatchPlan, actionPlan, enginePlan)
        val adapter = adapterFor(enginePlan)
        val notification = notificationFor(spec, enginePlan, actionPlan)
        val sidecar = spec.sidecar.toRedactedJson()
        val runbook = runbookFor(spec, enginePlan, dispatchPlan, actionPlan, readiness, nowEpochMs)
        val safeSurfaces = mutableListOf(
            spec.safeQueuedJobSummary,
            spec.safeExplanation,
            sidecar,
            adapter.redactedPreview,
            notification.summary,
            runbook.joinToString("\n"),
        )
        val secretSafe = enginePlan.leakReport.safe && !containsKnownSecret(safeSurfaces.joinToString("\n")) && !adapter.rawShellExposed
        return MediaWorkerBridgeRequest(
            durableJobId = durableJobId(spec.captureId, enginePlan.lane),
            captureId = spec.captureId,
            title = spec.userLabel,
            kind = if (secretSafe) kind else MediaWorkerBridgeKind.BlockedDiagnostic,
            readiness = if (secretSafe) readiness else MediaWorkerBridgeReadiness.Blocked,
            lane = enginePlan.lane,
            backgroundPolicy = enginePlan.backgroundPolicy,
            adapter = adapter,
            notification = notification,
            cleanupAfterTerminal = enginePlan.cleanupActions,
            redactedSidecarJson = sidecar,
            safeRunbook = runbook.map(::redactKnownSecrets),
            secretSafe = secretSafe,
        )
    }

    fun dashboard(requests: List<MediaWorkerBridgeRequest>): MediaWorkerBridgeDashboard {
        val launchable = requests.count { it.launchable }
        val androidWorkers = requests.count { request ->
            request.kind == MediaWorkerBridgeKind.AndroidUidt ||
                request.kind == MediaWorkerBridgeKind.WorkManagerForeground ||
                request.kind == MediaWorkerBridgeKind.ForegroundServiceDataSync ||
                request.kind == MediaWorkerBridgeKind.NativeDirect ||
                request.kind == MediaWorkerBridgeKind.Aria2Adapter
        }
        val termuxWorkers = requests.count { it.kind == MediaWorkerBridgeKind.TermuxYtDlp }
        val blocked = requests.count { it.readiness == MediaWorkerBridgeReadiness.Blocked || it.kind == MediaWorkerBridgeKind.BlockedDiagnostic }
        val confirm = requests.count { it.readiness == MediaWorkerBridgeReadiness.NeedsConfirmation }
        return MediaWorkerBridgeDashboard(
            requests = requests,
            launchableCount = launchable,
            androidWorkerCount = androidWorkers,
            termuxWorkerCount = termuxWorkers,
            blockedCount = blocked,
            confirmationCount = confirm,
            secretSafe = requests.all { it.secretSafe },
        )
    }

    private fun kindFor(enginePlan: MediaExecutionEnginePlan): MediaWorkerBridgeKind = when (enginePlan.lane) {
        MediaExecutionLane.ProtectedBlocked -> MediaWorkerBridgeKind.BlockedDiagnostic
        MediaExecutionLane.YtDlpAdaptive,
        MediaExecutionLane.LiveRecording -> MediaWorkerBridgeKind.TermuxYtDlp
        MediaExecutionLane.Aria2Segmented -> MediaWorkerBridgeKind.Aria2Adapter
        MediaExecutionLane.DirectNative -> when (enginePlan.backgroundPolicy.workKind) {
            AndroidMediaWorkKind.UserInitiatedDataTransfer -> MediaWorkerBridgeKind.AndroidUidt
            AndroidMediaWorkKind.WorkManagerForeground -> MediaWorkerBridgeKind.WorkManagerForeground
            AndroidMediaWorkKind.ForegroundServiceFallback -> MediaWorkerBridgeKind.ForegroundServiceDataSync
            AndroidMediaWorkKind.TermuxExternalJob -> MediaWorkerBridgeKind.TermuxYtDlp
            AndroidMediaWorkKind.BlockedDiagnostic -> MediaWorkerBridgeKind.BlockedDiagnostic
        }
    }

    private fun readinessFor(
        dispatchPlan: MediaDispatchPlan,
        actionPlan: MediaQueueActionPlan,
        enginePlan: MediaExecutionEnginePlan,
    ): MediaWorkerBridgeReadiness = when {
        !enginePlan.leakReport.safe -> MediaWorkerBridgeReadiness.Blocked
        actionPlan.primaryAction.requiresConfirmation -> MediaWorkerBridgeReadiness.NeedsConfirmation
        dispatchPlan.readiness == MediaDispatchReadiness.NeedsMetadataRefresh -> MediaWorkerBridgeReadiness.WaitingForMetadata
        dispatchPlan.readiness == MediaDispatchReadiness.NeedsTermuxSetup -> MediaWorkerBridgeReadiness.WaitingForTermux
        dispatchPlan.readiness == MediaDispatchReadiness.AwaitingUserChoice -> MediaWorkerBridgeReadiness.WaitingForUserAction
        dispatchPlan.readiness == MediaDispatchReadiness.BlockedProtected || dispatchPlan.readiness == MediaDispatchReadiness.BlockedSecretLeak -> MediaWorkerBridgeReadiness.Blocked
        actionPlan.primaryAction.kind != MediaQueueActionKind.Launch || !actionPlan.primaryAction.enabled -> MediaWorkerBridgeReadiness.WaitingForUserAction
        else -> MediaWorkerBridgeReadiness.Ready
    }

    private fun adapterFor(enginePlan: MediaExecutionEnginePlan): MediaWorkerAdapterContract {
        val transient = mutableListOf<String>()
        enginePlan.tempCookieFile?.fileName?.let { transient += it }
        enginePlan.aria2Input?.inputFileName?.let { transient += it }
        enginePlan.aria2Input?.sessionFileName?.let { transient += it }
        val preview = redactKnownSecrets(
            listOf(
                "executor=${enginePlan.typedExecutor}",
                "args=${enginePlan.typedArguments.joinToString(" ")}",
                "transient=${transient.joinToString()}",
                "rawShell=false",
            ).joinToString("\n"),
        )
        return MediaWorkerAdapterContract(
            executorLabel = enginePlan.typedExecutor,
            typedArguments = enginePlan.typedArguments.map(::redactKnownSecrets),
            transientInputLabels = transient,
            redactedPreview = preview,
            rawShellExposed = false,
        )
    }

    private fun notificationFor(
        spec: MediaQueuedDownloadSpec,
        enginePlan: MediaExecutionEnginePlan,
        actionPlan: MediaQueueActionPlan,
    ): MediaWorkerForegroundNotificationPlan {
        val actions = mutableListOf<String>()
        actionPlan.actions.filter { it.enabled }.forEach { action -> actions += action.kind.label }
        return MediaWorkerForegroundNotificationPlan(
            channelId = "xdm_media_downloads",
            title = spec.userLabel.take(80),
            body = redactKnownSecrets("${enginePlan.lane.label} • ${enginePlan.backgroundPolicy.workKind.label} • ${actionPlan.primaryAction.kind.label}"),
            foregroundServiceType = enginePlan.backgroundPolicy.foregroundServiceType,
            progressVisible = enginePlan.lane != MediaExecutionLane.ProtectedBlocked,
            actions = actions.distinct().take(4),
        )
    }

    private fun runbookFor(
        spec: MediaQueuedDownloadSpec,
        enginePlan: MediaExecutionEnginePlan,
        dispatchPlan: MediaDispatchPlan,
        actionPlan: MediaQueueActionPlan,
        readiness: MediaWorkerBridgeReadiness,
        nowEpochMs: Long,
    ): List<String> {
        val runbook = mutableListOf<String>()
        runbook += "bridge=${readiness.label}"
        runbook += "job=${durableJobId(spec.captureId, enginePlan.lane)}"
        runbook += "policy=${enginePlan.backgroundPolicy.summary}"
        runbook += "adapter=${enginePlan.typedExecutor}; args=${enginePlan.typedArguments.size}; raw shell disabled"
        runbook += "notification=foreground progress channel xdm_media_downloads"
        runbook += "sidecar=${spec.sidecar.toRedactedJson().take(220)}"
        runbook += "action=${actionPlan.primaryAction.kind.label}"
        runbook += "dispatch=${dispatchPlan.readiness.label}; ${dispatchPlan.lane.label}"
        runbook += "cleanup=${enginePlan.cleanupActions.joinToString()}"
        runbook += "createdAt=$nowEpochMs"
        return runbook.map(::redactKnownSecrets)
    }

    private fun durableJobId(captureId: String, lane: MediaExecutionLane): String = "media-${captureId.take(12)}-${lane.name.lowercase(Locale.US)}"

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { pattern -> pattern.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private companion object {
        val secretPatterns = listOf(
            Regex("""Bearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=]\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(token|session|sid|sig|signature|auth|key)=((?!<redacted>)[^\s&#;]+)"""),
            Regex("secret-[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
