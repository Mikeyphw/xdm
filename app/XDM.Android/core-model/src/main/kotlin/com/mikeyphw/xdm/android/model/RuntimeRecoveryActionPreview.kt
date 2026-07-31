package com.mikeyphw.xdm.android.model

/**
 * Phase59 user-facing preview layer for recovery actions.
 *
 * The preview is deliberately pure and redacted. It translates Phase58 execution decisions into
 * copy the details screen can show before a tap. It does not start transfers, migrate methods,
 * delete files, persist browser context, or expose raw request data.
 */
data class RuntimeRecoveryActionPreview(
    val actionLabel: String,
    val outcomeLabel: String,
    val reviewLabel: String,
    val safetyLabel: String,
    val primary: Boolean = false,
)

object RuntimeRecoveryActionPreviewPlanner {
    fun build(
        download: Download,
        plan: RuntimeFailureRecoveryPlan,
        decisions: List<RuntimeRecoveryExecutionDecision> = plan.actions.map { action ->
            RuntimeRecoveryExecutionGuard.decide(download, action.kind)
        },
    ): List<RuntimeRecoveryActionPreview> {
        val decisionsByKind = decisions.associateBy { it.actionKind }
        return plan.actions.map { action ->
            val decision = decisionsByKind[action.kind] ?: RuntimeRecoveryExecutionGuard.decide(download, action.kind)
            RuntimeRecoveryActionPreview(
                actionLabel = decision.buttonLabel,
                outcomeLabel = decision.outcomeLabel(),
                reviewLabel = decision.reviewLabel(),
                safetyLabel = decision.safeSafetyLabel(),
                primary = action.primary,
            )
        }
    }

    fun summary(previews: List<RuntimeRecoveryActionPreview>): String = when {
        previews.any { it.reviewLabel.contains("Recovery Doctor", ignoreCase = true) } ->
            "Some actions open Recovery Doctor before another attempt."
        previews.any { it.reviewLabel.contains("Review", ignoreCase = true) } ->
            "Some actions require review before retrying."
        previews.any { it.outcomeLabel.contains("guidance", ignoreCase = true) } ->
            "Some actions only show guidance and do not start work."
        previews.isEmpty() -> "No recovery actions are available."
        else -> "Every action waits for your explicit tap."
    }

    fun redactedReportSection(previews: List<RuntimeRecoveryActionPreview>): String {
        if (previews.isEmpty()) return "Action previews: none"
        val lines = previews.take(6).joinToString("\n") { preview ->
            "- ${preview.actionLabel}: ${preview.outcomeLabel}; ${preview.reviewLabel}"
        }
        return "Action previews:\n$lines\nPrivate values remain redacted."
    }

    private fun RuntimeRecoveryExecutionDecision.outcomeLabel(): String = when (mode) {
        RuntimeRecoveryExecutionMode.ExecuteNow -> executionLabel.ifBlank { "Runs reviewed action after tap" }
        RuntimeRecoveryExecutionMode.ReviewFirst -> "Opens review guidance before retry"
        RuntimeRecoveryExecutionMode.GuidanceOnly -> "Shows guidance only"
        RuntimeRecoveryExecutionMode.OpenRecoveryFirst -> "Opens Recovery Doctor first"
        RuntimeRecoveryExecutionMode.CopyOnly -> "Copies a redacted report"
    }

    private fun RuntimeRecoveryExecutionDecision.reviewLabel(): String = when (mode) {
        RuntimeRecoveryExecutionMode.ExecuteNow -> "Explicit tap required"
        RuntimeRecoveryExecutionMode.ReviewFirst -> "Review required"
        RuntimeRecoveryExecutionMode.GuidanceOnly -> "No background work"
        RuntimeRecoveryExecutionMode.OpenRecoveryFirst -> "Recovery Doctor required"
        RuntimeRecoveryExecutionMode.CopyOnly -> "Copy only"
    }

    private fun RuntimeRecoveryExecutionDecision.safeSafetyLabel(): String = safetyNote
        .replace(Regex("https?://[^\\s)]+"), "<redacted link>")
        .replace(Regex("(?i)\\b(?:authorization|cookie|set-cookie|proxy-authorization)\\s*:\\s*[^\\n]+"), "<redacted header>")
        .replace(Regex("(?i)bearer\\s+[^\\s]+"), "bearer <redacted>")
        .ifBlank { "No private request data is shown." }
}
