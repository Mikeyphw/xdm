package com.mikeyphw.xdm.android.media

/**
 * Phase 23 queue observability model.
 *
 * The dispatcher decides whether a job may leave the Media inbox. This planner turns those
 * dispatch plans and the current execution rows into a user-visible, secret-safe telemetry deck.
 * It is deliberately pure Kotlin: no Android services, no Room migration, no process launches,
 * and no raw cookie/header/token values.
 */
enum class MediaQueueTelemetryTone(val label: String) {
    Stable("Stable"),
    Active("Active"),
    Attention("Needs attention"),
    Blocked("Blocked"),
}

data class MediaQueueTelemetryRow(
    val captureId: String,
    val title: String,
    val laneLabel: String,
    val readinessLabel: String,
    val stageLabel: String,
    val progressLabel: String,
    val nextActionLabel: String,
    val cleanupArmed: Boolean,
    val stalled: Boolean,
    val secretSafe: Boolean,
    val tone: MediaQueueTelemetryTone,
    val safeDiagnostic: String,
) {
    val summary: String
        get() = listOf(
            title,
            laneLabel,
            readinessLabel,
            stageLabel,
            progressLabel,
            nextActionLabel,
            if (cleanupArmed) "cleanup armed" else "cleanup not required yet",
            if (secretSafe) "secret-safe" else "redaction review",
        ).joinToString(" • ")
}

data class MediaQueueTelemetryDeck(
    val rows: List<MediaQueueTelemetryRow>,
    val readyToLaunchCount: Int,
    val activeCount: Int,
    val needsAttentionCount: Int,
    val cleanupArmedCount: Int,
    val terminalCount: Int,
    val secretSafe: Boolean,
) {
    val empty: Boolean get() = rows.isEmpty()
    val summary: String
        get() = listOf(
            "ready=$readyToLaunchCount",
            "active=$activeCount",
            "attention=$needsAttentionCount",
            "cleanup=$cleanupArmedCount",
            "terminal=$terminalCount",
            if (secretSafe) "secret-safe" else "redaction review required",
        ).joinToString(" • ")
}

class MediaQueueTelemetryPlanner {
    fun deck(dispatchPlans: List<MediaDispatchPlan>, executionJobs: List<MediaExecutionJob>): MediaQueueTelemetryDeck {
        val jobsByCapture = linkedMapOf<String, MediaExecutionJob>()
        executionJobs.forEach { job -> jobsByCapture[job.captureId] = job }
        val rows = dispatchPlans.map { plan -> rowFor(plan, jobsByCapture[plan.captureId]) }
        val ready = rows.count { it.tone == MediaQueueTelemetryTone.Stable && it.nextActionLabel == "Launch queue" }
        val active = rows.count { it.tone == MediaQueueTelemetryTone.Active }
        val attention = rows.count { it.tone == MediaQueueTelemetryTone.Attention || it.tone == MediaQueueTelemetryTone.Blocked }
        val cleanup = rows.count { row -> row.cleanupArmed }
        val terminal = rows.count { row -> row.stageLabel == MediaExecutionStage.Completed.label || row.stageLabel == MediaExecutionStage.Failed.label || row.stageLabel == MediaExecutionStage.Blocked.label }
        return MediaQueueTelemetryDeck(
            rows = rows,
            readyToLaunchCount = ready,
            activeCount = active,
            needsAttentionCount = attention,
            cleanupArmedCount = cleanup,
            terminalCount = terminal,
            secretSafe = rows.none { !it.secretSafe },
        )
    }

    fun rowFor(plan: MediaDispatchPlan, job: MediaExecutionJob?): MediaQueueTelemetryRow {
        val stage = job?.stage
        val cleanupArmed = plan.steps.any { step -> step.terminalCleanup }
        val secretSafe = plan.readiness != MediaDispatchReadiness.BlockedSecretLeak && !containsKnownSecret(plan.safeDiagnostics)
        val stalled = isStalled(plan, job)
        val progress = progressLabelFor(plan, job)
        val nextAction = nextActionFor(plan, job)
        val tone = toneFor(plan, stage, stalled, secretSafe)
        return MediaQueueTelemetryRow(
            captureId = plan.captureId,
            title = plan.title,
            laneLabel = plan.lane.label,
            readinessLabel = plan.readiness.label,
            stageLabel = stage?.label ?: "Not queued",
            progressLabel = progress,
            nextActionLabel = nextAction,
            cleanupArmed = cleanupArmed,
            stalled = stalled,
            secretSafe = secretSafe,
            tone = tone,
            safeDiagnostic = safeDiagnostic(plan, job, progress, nextAction, cleanupArmed, stalled, secretSafe),
        )
    }

    private fun progressLabelFor(plan: MediaDispatchPlan, job: MediaExecutionJob?): String {
        val stage = job?.stage
        if (stage != null) {
            return when (stage) {
                MediaExecutionStage.Probing -> "Metadata probe in progress"
                MediaExecutionStage.Queued -> "Waiting for execution lane"
                MediaExecutionStage.Downloading -> plan.progressSignals.firstOrNull { it.userVisible }?.label ?: "Download running"
                MediaExecutionStage.Completed -> "Completed and ready for library"
                MediaExecutionStage.Failed -> "Failed; review retry policy"
                MediaExecutionStage.Blocked -> "Blocked before transfer"
            }
        }
        return when (plan.readiness) {
            MediaDispatchReadiness.Ready -> plan.progressSignals.firstOrNull()?.label ?: "Ready for dispatch"
            MediaDispatchReadiness.AwaitingUserChoice -> "Waiting for variant or track choice"
            MediaDispatchReadiness.NeedsMetadataRefresh -> "Waiting for metadata refresh"
            MediaDispatchReadiness.NeedsTermuxSetup -> "Waiting for Termux media pipeline"
            MediaDispatchReadiness.BlockedProtected -> "Protected media diagnostic only"
            MediaDispatchReadiness.BlockedSecretLeak -> "Blocked by redaction guard"
        }
    }

    private fun nextActionFor(plan: MediaDispatchPlan, job: MediaExecutionJob?): String {
        val stage = job?.stage
        if (stage != null) {
            return when (stage) {
                MediaExecutionStage.Probing -> "Wait for probe"
                MediaExecutionStage.Queued -> "Watch queue"
                MediaExecutionStage.Downloading -> "Monitor progress"
                MediaExecutionStage.Completed -> "Open library"
                MediaExecutionStage.Failed -> if (job.canRetry) "Retry media" else "Review failure"
                MediaExecutionStage.Blocked -> "Review diagnostics"
            }
        }
        return when (plan.readiness) {
            MediaDispatchReadiness.Ready -> "Launch queue"
            MediaDispatchReadiness.AwaitingUserChoice -> "Choose tracks"
            MediaDispatchReadiness.NeedsMetadataRefresh -> "Refresh metadata"
            MediaDispatchReadiness.NeedsTermuxSetup -> "Open Termux setup"
            MediaDispatchReadiness.BlockedProtected -> "View protected-media diagnostics"
            MediaDispatchReadiness.BlockedSecretLeak -> "Review redaction failure"
        }
    }

    private fun toneFor(
        plan: MediaDispatchPlan,
        stage: MediaExecutionStage?,
        stalled: Boolean,
        secretSafe: Boolean,
    ): MediaQueueTelemetryTone = when {
        !secretSafe || plan.blocked || stage == MediaExecutionStage.Blocked -> MediaQueueTelemetryTone.Blocked
        stalled || plan.readiness == MediaDispatchReadiness.NeedsMetadataRefresh || plan.readiness == MediaDispatchReadiness.NeedsTermuxSetup || stage == MediaExecutionStage.Failed -> MediaQueueTelemetryTone.Attention
        stage == MediaExecutionStage.Probing || stage == MediaExecutionStage.Queued || stage == MediaExecutionStage.Downloading -> MediaQueueTelemetryTone.Active
        else -> MediaQueueTelemetryTone.Stable
    }

    private fun isStalled(plan: MediaDispatchPlan, job: MediaExecutionJob?): Boolean {
        if (job?.stage == MediaExecutionStage.Failed || job?.stage == MediaExecutionStage.Blocked) return true
        if (plan.readiness == MediaDispatchReadiness.NeedsMetadataRefresh || plan.readiness == MediaDispatchReadiness.NeedsTermuxSetup) return true
        return plan.warnings.any { warning -> warning.contains("refresh", ignoreCase = true) || warning.contains("Termux", ignoreCase = true) }
    }

    private fun safeDiagnostic(
        plan: MediaDispatchPlan,
        job: MediaExecutionJob?,
        progress: String,
        nextAction: String,
        cleanupArmed: Boolean,
        stalled: Boolean,
        secretSafe: Boolean,
    ): String {
        val lines = mutableListOf<String>()
        lines += "capture=${plan.captureId}"
        lines += "lane=${plan.lane.label}"
        lines += "readiness=${plan.readiness.label}"
        lines += "stage=${job?.stage?.label ?: "Not queued"}"
        lines += "progress=$progress"
        lines += "next=$nextAction"
        lines += "cleanup=${if (cleanupArmed) "armed" else "not-required-yet"}"
        lines += "stalled=$stalled"
        lines += "secretSafe=$secretSafe"
        job?.detail?.takeIf { it.isNotBlank() }?.let { detail -> lines += "job=${detail.take(180)}" }
        return redactKnownSecrets(lines.joinToString("\n"))
    }

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { pattern -> pattern.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private companion object {
        val secretPatterns = listOf(
            Regex("Bearer\\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})", RegexOption.IGNORE_CASE),
            Regex("Cookie\\s*[:=](?!\\s*<redacted(?:-[A-Za-z]+)?>)\\s*[^\\n;]+", RegexOption.IGNORE_CASE),
            Regex("(?i)(?<![-A-Za-z])(token|session|sid|sig|signature|auth|key)=((?!<redacted>|referer=|none\\b|available\\b|redacted\\b)[^\\s&#;]+)"),
            Regex("\\b(?:super-)?secret-(?!(?:safe|bearing|free)\\b)[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
