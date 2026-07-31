package com.mikeyphw.xdm.android.model

/**
 * Phase60 seal for the runtime recovery flow.
 *
 * This is intentionally pure: it checks that the recovery planner, execution guard, action preview,
 * and redacted support copy fit together before the UI offers action buttons. It does not start
 * transfers, delete files, change engines, persist browser session values, or request storage
 * permissions.
 */
data class RuntimeRecoveryFlowSealCheck(
    val label: String,
    val status: String,
    val guidance: String,
)

data class RuntimeRecoveryFlowSeal(
    val title: String,
    val sealed: Boolean,
    val recommendedActionLabel: String,
    val checks: List<RuntimeRecoveryFlowSealCheck>,
    val redactedSummary: String,
)

object RuntimeRecoveryFlowSealPlanner {
    fun evaluate(download: Download): RuntimeRecoveryFlowSeal {
        val plan = RuntimeFailureRecoveryPlanner.evaluate(download)
        if (plan == null) {
            return RuntimeRecoveryFlowSeal(
                title = "Recovery flow seal",
                sealed = true,
                recommendedActionLabel = "No recovery needed",
                checks = listOf(
                    RuntimeRecoveryFlowSealCheck(
                        label = "Recovery state",
                        status = "Healthy",
                        guidance = "This item does not need runtime recovery actions.",
                    ),
                    RuntimeRecoveryFlowSealCheck(
                        label = "Safety boundary",
                        status = "Manual only",
                        guidance = "No recovery work runs unless you explicitly choose an action.",
                    ),
                ),
                redactedSummary = "Runtime recovery flow seal: healthy. No recovery action is required.",
            )
        }
        val guardedActions = plan.actions.map { action -> RuntimeRecoveryExecutionGuard.decide(download, action.kind) }
        val previews = RuntimeRecoveryActionPreviewPlanner.build(download, plan, guardedActions)
        val guardedReady = guardedActions.isNotEmpty()
        val previewsReady = previews.isNotEmpty()
        val reportReady = plan.redactedReport.isSafeRecoveryText() &&
            RuntimeRecoveryActionPreviewPlanner.redactedReportSection(previews).isSafeRecoveryText()
        val sealed = guardedReady && previewsReady && reportReady
        val guidanceActions = guardedActions.count { it.mode == RuntimeRecoveryExecutionMode.GuidanceOnly }
        val reviewActions = guardedActions.count { it.mode == RuntimeRecoveryExecutionMode.ReviewFirst || it.mode == RuntimeRecoveryExecutionMode.OpenRecoveryFirst }
        val immediateActions = guardedActions.count { it.allowsImmediateCallback }
        return RuntimeRecoveryFlowSeal(
            title = "Recovery flow seal",
            sealed = sealed,
            recommendedActionLabel = plan.recommendedActionLabel,
            checks = listOf(
                RuntimeRecoveryFlowSealCheck(
                    label = "Recovery plan",
                    status = if (plan.actions.isNotEmpty()) "Ready" else "Needs review",
                    guidance = "The failure is classified as ${plan.causeLabel} before actions are shown.",
                ),
                RuntimeRecoveryFlowSealCheck(
                    label = "Action guard",
                    status = "${guardedActions.size} guarded",
                    guidance = "$reviewActions review-first, $guidanceActions guidance-only, $immediateActions explicit-tap action(s).",
                ),
                RuntimeRecoveryFlowSealCheck(
                    label = "Action preview",
                    status = if (previewsReady) "Shown before tap" else "Missing",
                    guidance = "Each recovery action explains what happens before the callback is allowed.",
                ),
                RuntimeRecoveryFlowSealCheck(
                    label = "Support copy",
                    status = if (reportReady) "Redacted" else "Needs review",
                    guidance = "Recovery reports avoid full links, cookies, authorization values, bearer tokens, and credential query values.",
                ),
                RuntimeRecoveryFlowSealCheck(
                    label = "Safety boundary",
                    status = "Manual only",
                    guidance = "The seal does not start transfers, delete files, or persist browser session values.",
                ),
            ),
            redactedSummary = buildString {
                appendLine("Runtime recovery flow seal")
                appendLine("Status: ${if (sealed) "ready" else "needs review"}")
                appendLine("Recommended action: ${plan.recommendedActionLabel}")
                appendLine("Actions guarded: ${guardedActions.size}")
                appendLine("Action previews: ${previews.size}")
                appendLine("Private values: full links, cookies, authorization values, bearer tokens, signatures, and credential query values are redacted.")
            }.trimEnd(),
        )
    }

    private fun String.isSafeRecoveryText(): Boolean {
        val lower = lowercase()
        return listOf(
            "http://",
            "https://",
            "cookie=",
            "authorization:",
            "bearer secret",
            "bearer.",
            "token=secret",
            "signature=secret",
        ).none { it in lower }
    }
}
