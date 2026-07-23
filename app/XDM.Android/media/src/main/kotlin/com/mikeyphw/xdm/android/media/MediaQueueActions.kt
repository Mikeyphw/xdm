package com.mikeyphw.xdm.android.media

/**
 * Phase 24 queue action eligibility planner.
 *
 * This layer deliberately does not execute work. It turns Phase 22 dispatch plans and Phase 23
 * telemetry rows into action affordances that the UI can render safely: launch, pause, resume,
 * retry, cancel, cleanup, refresh, choose tracks, open Termux setup, open library, and diagnostics.
 * No raw shell commands, cookies, Authorization headers, or signed URLs are persisted here.
 */
enum class MediaQueueActionKind(val label: String) {
    Launch("Launch queue"),
    Pause("Pause media"),
    Resume("Resume media"),
    Retry("Retry media"),
    Cancel("Cancel media"),
    CleanupTerminal("Cleanup finished"),
    RefreshMetadata("Refresh metadata"),
    ChooseTracks("Choose tracks"),
    OpenTermuxSetup("Open Termux setup"),
    ViewDiagnostics("View diagnostics"),
    OpenLibrary("Open library"),
}

enum class MediaQueueActionAvailability(val label: String) {
    Available("Available"),
    Disabled("Disabled"),
    ConfirmationRequired("Confirm first"),
    Hidden("Hidden"),
}

data class MediaQueueAction(
    val kind: MediaQueueActionKind,
    val availability: MediaQueueActionAvailability,
    val reason: String,
    val destructive: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val secretSafe: Boolean = true,
) {
    val enabled: Boolean get() = availability == MediaQueueActionAvailability.Available || availability == MediaQueueActionAvailability.ConfirmationRequired
    val summary: String
        get() = listOf(
            kind.label,
            availability.label,
            reason,
            if (destructive) "destructive" else "safe",
            if (requiresConfirmation) "confirmation required" else "no confirmation",
            if (secretSafe) "secret-safe" else "redaction review",
        ).joinToString(" • ")
}

data class MediaQueueActionPlan(
    val captureId: String,
    val title: String,
    val primaryAction: MediaQueueAction,
    val actions: List<MediaQueueAction>,
    val unavailableReasons: List<String>,
    val safeSummary: String,
) {
    val availableActions: List<MediaQueueAction> get() = actions.filter { it.enabled }
    val needsAttention: Boolean get() = unavailableReasons.isNotEmpty() || actions.any { !it.secretSafe }
}

data class MediaBulkQueueAction(
    val kind: MediaQueueActionKind,
    val availableCount: Int,
    val requiresConfirmation: Boolean,
    val safeSummary: String,
) {
    val label: String get() = "${kind.label}: $availableCount"
}

data class MediaQueueActionDashboard(
    val plans: List<MediaQueueActionPlan>,
    val launchableCount: Int,
    val pausableCount: Int,
    val retryableCount: Int,
    val cancellableCount: Int,
    val cleanupCount: Int,
    val attentionCount: Int,
    val bulkActions: List<MediaBulkQueueAction>,
    val secretSafe: Boolean,
) {
    val empty: Boolean get() = plans.isEmpty()
    val summary: String
        get() = listOf(
            "launch=$launchableCount",
            "pause=$pausableCount",
            "retry=$retryableCount",
            "cancel=$cancellableCount",
            "cleanup=$cleanupCount",
            "attention=$attentionCount",
            if (secretSafe) "secret-safe" else "redaction review required",
        ).joinToString(" • ")
}

class MediaQueueActionPlanner {
    fun dashboard(
        telemetry: MediaQueueTelemetryDeck,
        dispatchPlans: List<MediaDispatchPlan>,
        executionJobs: List<MediaExecutionJob>,
    ): MediaQueueActionDashboard {
        val dispatchByCapture = linkedMapOf<String, MediaDispatchPlan>()
        dispatchPlans.forEach { plan -> dispatchByCapture[plan.captureId] = plan }
        val jobsByCapture = linkedMapOf<String, MediaExecutionJob>()
        executionJobs.forEach { job -> jobsByCapture[job.captureId] = job }
        val actionPlans = telemetry.rows.mapNotNull { row ->
            val dispatch = dispatchByCapture[row.captureId] ?: return@mapNotNull null
            actionPlan(dispatch, jobsByCapture[row.captureId], row)
        }
        return dashboardFor(actionPlans)
    }

    fun dashboardFor(plans: List<MediaQueueActionPlan>): MediaQueueActionDashboard {
        val launchable = plans.count { plan -> plan.actions.anyAvailable(MediaQueueActionKind.Launch) }
        val pausable = plans.count { plan -> plan.actions.anyAvailable(MediaQueueActionKind.Pause) }
        val retryable = plans.count { plan -> plan.actions.anyAvailable(MediaQueueActionKind.Retry) || plan.actions.anyAvailable(MediaQueueActionKind.Resume) }
        val cancellable = plans.count { plan -> plan.actions.anyAvailable(MediaQueueActionKind.Cancel) }
        val cleanup = plans.count { plan -> plan.actions.anyAvailable(MediaQueueActionKind.CleanupTerminal) }
        val attention = plans.count { it.needsAttention }
        val bulkActions = mutableListOf<MediaBulkQueueAction>()
        if (retryable > 0) bulkActions += MediaBulkQueueAction(MediaQueueActionKind.Retry, retryable, false, "Retry failed or resumable media jobs without exposing session data.")
        if (cleanup > 0) bulkActions += MediaBulkQueueAction(MediaQueueActionKind.CleanupTerminal, cleanup, true, "Cleanup finished terminal artifacts after redaction verification.")
        if (cancellable > 0) bulkActions += MediaBulkQueueAction(MediaQueueActionKind.Cancel, cancellable, true, "Cancel active media jobs after confirmation; transient files remain cleanup-owned.")
        return MediaQueueActionDashboard(
            plans = plans,
            launchableCount = launchable,
            pausableCount = pausable,
            retryableCount = retryable,
            cancellableCount = cancellable,
            cleanupCount = cleanup,
            attentionCount = attention,
            bulkActions = bulkActions,
            secretSafe = plans.all { plan -> plan.actions.all { it.secretSafe } && !containsKnownSecret(plan.safeSummary) },
        )
    }

    fun actionPlan(
        dispatch: MediaDispatchPlan,
        job: MediaExecutionJob?,
        telemetryRow: MediaQueueTelemetryRow? = null,
    ): MediaQueueActionPlan {
        val actions = mutableListOf<MediaQueueAction>()
        val unavailable = mutableListOf<String>()
        val secretSafe = dispatch.readiness != MediaDispatchReadiness.BlockedSecretLeak && !containsKnownSecret(dispatch.safeDiagnostics)
        val stage = job?.stage
        if (!secretSafe) {
            actions += action(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionAvailability.Available, "Redaction guard blocked queue actions; diagnostics are redacted.", secretSafe = true)
            unavailable += "Blocked until redaction review passes."
        } else if (stage == null) {
            actions += preQueueAction(dispatch)
            actions += action(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionAvailability.Available, "Review safe dispatch diagnostics before launch.")
            addPreQueueUnavailable(dispatch, unavailable)
        } else {
            addJobActions(stage, job, actions, unavailable)
            actions += action(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionAvailability.Available, "Review safe job diagnostics and progress pulse.")
        }
        telemetryRow?.let { row ->
            if (row.cleanupArmed && stage == null && actions.none { it.kind == MediaQueueActionKind.CleanupTerminal }) {
                actions += action(MediaQueueActionKind.CleanupTerminal, MediaQueueActionAvailability.Disabled, "Cleanup waits for a terminal job state.")
            }
            if (!row.secretSafe) unavailable += "Telemetry requested redaction review."
        }
        val primary = choosePrimary(actions, dispatch, stage)
        val summary = safeSummary(dispatch, job, actions, unavailable, telemetryRow)
        return MediaQueueActionPlan(
            captureId = dispatch.captureId,
            title = dispatch.title,
            primaryAction = primary,
            actions = actions,
            unavailableReasons = unavailable.distinct(),
            safeSummary = summary,
        )
    }

    private fun preQueueAction(dispatch: MediaDispatchPlan): MediaQueueAction = when (dispatch.readiness) {
        MediaDispatchReadiness.Ready -> action(MediaQueueActionKind.Launch, MediaQueueActionAvailability.Available, "Resolver choice is ready for dispatch.")
        MediaDispatchReadiness.AwaitingUserChoice -> action(MediaQueueActionKind.ChooseTracks, MediaQueueActionAvailability.Available, "Select video, audio, or subtitle tracks before launch.")
        MediaDispatchReadiness.NeedsMetadataRefresh -> action(MediaQueueActionKind.RefreshMetadata, MediaQueueActionAvailability.Available, "Refresh manifest and page metadata before launch.")
        MediaDispatchReadiness.NeedsTermuxSetup -> action(MediaQueueActionKind.OpenTermuxSetup, MediaQueueActionAvailability.Available, "Enable or repair the Termux media pipeline.")
        MediaDispatchReadiness.BlockedProtected -> action(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionAvailability.Available, "Protected media stays diagnostic-only; no bypass action is available.")
        MediaDispatchReadiness.BlockedSecretLeak -> action(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionAvailability.Available, "Redaction guard blocked dispatch.")
    }

    private fun addPreQueueUnavailable(dispatch: MediaDispatchPlan, unavailable: MutableList<String>) {
        when (dispatch.readiness) {
            MediaDispatchReadiness.Ready -> Unit
            MediaDispatchReadiness.AwaitingUserChoice -> unavailable += "Launch queue waits for track selection."
            MediaDispatchReadiness.NeedsMetadataRefresh -> unavailable += "Launch queue waits for metadata refresh."
            MediaDispatchReadiness.NeedsTermuxSetup -> unavailable += "Launch queue waits for Termux setup."
            MediaDispatchReadiness.BlockedProtected -> unavailable += "Protected media cannot be downloaded or bypassed."
            MediaDispatchReadiness.BlockedSecretLeak -> unavailable += "Launch queue waits for redaction review."
        }
    }

    private fun addJobActions(
        stage: MediaExecutionStage,
        job: MediaExecutionJob,
        actions: MutableList<MediaQueueAction>,
        unavailable: MutableList<String>,
    ) {
        when (stage) {
            MediaExecutionStage.Probing,
            MediaExecutionStage.Queued,
            MediaExecutionStage.Downloading -> {
                actions += action(MediaQueueActionKind.Pause, MediaQueueActionAvailability.Available, "Pause the visible media transfer.")
                actions += action(MediaQueueActionKind.Cancel, MediaQueueActionAvailability.ConfirmationRequired, "Cancel active media work and keep cleanup ownership.", destructive = true, requiresConfirmation = true)
                actions += action(MediaQueueActionKind.CleanupTerminal, MediaQueueActionAvailability.Disabled, "Cleanup waits until the job reaches a terminal state.")
            }
            MediaExecutionStage.Completed -> {
                actions += action(MediaQueueActionKind.OpenLibrary, MediaQueueActionAvailability.Available, "Open completed media in the offline library.")
                actions += action(MediaQueueActionKind.CleanupTerminal, MediaQueueActionAvailability.ConfirmationRequired, "Remove transient execution files after redaction verification.", requiresConfirmation = true)
                unavailable += "Retry is unavailable because the job completed."
            }
            MediaExecutionStage.Failed -> {
                val retryAvailability = if (job.canRetry) MediaQueueActionAvailability.Available else MediaQueueActionAvailability.Disabled
                actions += action(MediaQueueActionKind.Retry, retryAvailability, if (job.canRetry) "Retry failed media with the saved redacted plan." else "Retry policy marked this failure non-retryable.")
                val resumeAvailability = if (job.canResume) MediaQueueActionAvailability.Available else MediaQueueActionAvailability.Hidden
                actions += action(MediaQueueActionKind.Resume, resumeAvailability, if (job.canResume) "Resume reusable partial data." else "No resumable partial is available.")
                actions += action(MediaQueueActionKind.CleanupTerminal, MediaQueueActionAvailability.ConfirmationRequired, "Clean failed transient files after review.", requiresConfirmation = true)
            }
            MediaExecutionStage.Blocked -> {
                actions += action(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionAvailability.Available, "Review why execution was blocked.")
                actions += action(MediaQueueActionKind.CleanupTerminal, MediaQueueActionAvailability.ConfirmationRequired, "Clean blocked transient files after review.", requiresConfirmation = true)
                unavailable += "Launch is blocked by diagnostics."
            }
        }
    }

    private fun choosePrimary(actions: List<MediaQueueAction>, dispatch: MediaDispatchPlan, stage: MediaExecutionStage?): MediaQueueAction {
        val priority = when (stage) {
            MediaExecutionStage.Completed -> listOf(MediaQueueActionKind.OpenLibrary, MediaQueueActionKind.CleanupTerminal)
            MediaExecutionStage.Failed -> listOf(MediaQueueActionKind.Retry, MediaQueueActionKind.Resume, MediaQueueActionKind.ViewDiagnostics)
            MediaExecutionStage.Probing,
            MediaExecutionStage.Queued,
            MediaExecutionStage.Downloading -> listOf(MediaQueueActionKind.Pause, MediaQueueActionKind.Cancel)
            MediaExecutionStage.Blocked -> listOf(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionKind.CleanupTerminal)
            null -> listOf(preQueueAction(dispatch).kind, MediaQueueActionKind.ViewDiagnostics)
        }
        priority.forEach { kind -> actions.firstOrNull { it.kind == kind && it.enabled }?.let { return it } }
        return actions.firstOrNull { it.enabled } ?: action(MediaQueueActionKind.ViewDiagnostics, MediaQueueActionAvailability.Disabled, "No safe action is currently available.")
    }

    private fun safeSummary(
        dispatch: MediaDispatchPlan,
        job: MediaExecutionJob?,
        actions: List<MediaQueueAction>,
        unavailable: List<String>,
        telemetryRow: MediaQueueTelemetryRow?,
    ): String {
        val lines = mutableListOf<String>()
        lines += "capture=${dispatch.captureId}"
        lines += "readiness=${dispatch.readiness.label}"
        lines += "lane=${dispatch.lane.label}"
        lines += "stage=${job?.stage?.label ?: "Not queued"}"
        lines += "primary=${actions.firstOrNull { it.enabled }?.kind?.label ?: "No action"}"
        lines += "actions=${actions.filter { it.enabled }.joinToString { it.kind.label }}"
        if (unavailable.isNotEmpty()) lines += "unavailable=${unavailable.joinToString(" | ")}"
        telemetryRow?.let { lines += "telemetry=${it.progressLabel} -> ${it.nextActionLabel}" }
        return redactKnownSecrets(lines.joinToString("\n")).take(1200)
    }

    private fun action(
        kind: MediaQueueActionKind,
        availability: MediaQueueActionAvailability,
        reason: String,
        destructive: Boolean = false,
        requiresConfirmation: Boolean = false,
        secretSafe: Boolean = true,
    ): MediaQueueAction = MediaQueueAction(kind, availability, reason, destructive, requiresConfirmation, secretSafe && !containsKnownSecret(reason))

    private fun List<MediaQueueAction>.anyAvailable(kind: MediaQueueActionKind): Boolean = any { it.kind == kind && it.enabled }

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
