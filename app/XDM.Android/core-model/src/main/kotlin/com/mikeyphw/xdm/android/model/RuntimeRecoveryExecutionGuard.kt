package com.mikeyphw.xdm.android.model

/**
 * Phase58 guard for user-triggered recovery actions.
 *
 * This planner is intentionally pure. It does not start transfers, migrate methods, delete files,
 * persist browser context, or inspect private request data. UI surfaces use it to decide whether a
 * tapped recovery action can run immediately, should open Recovery Doctor first, should present
 * guidance, or should copy a redacted report only.
 */
enum class RuntimeRecoveryExecutionMode {
    ExecuteNow,
    ReviewFirst,
    GuidanceOnly,
    OpenRecoveryFirst,
    CopyOnly,
}

data class RuntimeRecoveryExecutionDecision(
    val actionKind: RuntimeFailureRecoveryActionKind,
    val mode: RuntimeRecoveryExecutionMode,
    val buttonLabel: String,
    val executionLabel: String,
    val safetyNote: String,
    val allowsImmediateCallback: Boolean,
)

object RuntimeRecoveryExecutionGuard {
    fun decide(download: Download, actionKind: RuntimeFailureRecoveryActionKind): RuntimeRecoveryExecutionDecision {
        val partialNeedsReview = download.state == DownloadState.RecoveryRequired ||
            (download.bytesReceived > 0L && download.state == DownloadState.Failed)
        val queueHold = download.errorMessage.orEmpty().startsWith("Queue policy:")
        return when (actionKind) {
            RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup -> when {
                queueHold -> execute(
                    actionKind,
                    buttonLabel = "Start now",
                    executionLabel = "Queue override",
                    note = "Starts only after you tap and keeps the existing queue override path.",
                )
                partialNeedsReview -> recoveryFirst(
                    actionKind,
                    buttonLabel = "Review before retry",
                    note = "Partial or quarantined data should be reviewed before another attempt owns the destination.",
                )
                else -> execute(
                    actionKind,
                    buttonLabel = "Retry current request",
                    executionLabel = "Reviewed retry",
                    note = "Retries the existing request only after this explicit tap.",
                )
            }
            RuntimeFailureRecoveryActionKind.RetryWithCapturedSession -> reviewFirst(
                actionKind,
                buttonLabel = "Review captured session",
                note = "Captured browser context must be fresh; refresh from the browser when sign-in or temporary links are involved.",
            )
            RuntimeFailureRecoveryActionKind.RefreshFromBrowser -> guidanceOnly(
                actionKind,
                buttonLabel = "Refresh from browser",
                note = "Open the source in your browser and share or capture it again so fresh context is available.",
            )
            RuntimeFailureRecoveryActionKind.TryYtDlp -> guidanceOnly(
                actionKind,
                buttonLabel = "Inspect with yt-dlp",
                note = "Use the media workflow for pages, playlists, and extractor-supported sources before creating another plain download.",
            )
            RuntimeFailureRecoveryActionKind.TryAria2 -> when {
                partialNeedsReview -> recoveryFirst(
                    actionKind,
                    buttonLabel = "Review before aria2",
                    note = "Switching methods with partial data should go through Recovery Doctor first.",
                )
                else -> execute(
                    actionKind,
                    buttonLabel = "Try aria2",
                    executionLabel = "Reviewed method switch",
                    note = "Switches method only after this explicit tap and before a new attempt starts.",
                )
            }
            RuntimeFailureRecoveryActionKind.TryNative -> when {
                partialNeedsReview -> recoveryFirst(
                    actionKind,
                    buttonLabel = "Review before Native",
                    note = "Switching methods with partial data should go through Recovery Doctor first.",
                )
                else -> execute(
                    actionKind,
                    buttonLabel = "Try XDM Native",
                    executionLabel = "Reviewed method switch",
                    note = "Switches method only after this explicit tap and before a new attempt starts.",
                )
            }
            RuntimeFailureRecoveryActionKind.RecheckStorageVisibility -> execute(
                actionKind,
                buttonLabel = "Re-check storage",
                executionLabel = "Storage visibility review",
                note = "Opens the safe activity/recovery path; it does not request all-files access.",
            )
            RuntimeFailureRecoveryActionKind.OpenRecoveryDoctor -> execute(
                actionKind,
                buttonLabel = "Open Recovery Doctor",
                executionLabel = "Recovery review",
                note = "Reviews partial, orphaned, and destination states without automatic deletion.",
            )
            RuntimeFailureRecoveryActionKind.CopyRedactedReport -> RuntimeRecoveryExecutionDecision(
                actionKind = actionKind,
                mode = RuntimeRecoveryExecutionMode.CopyOnly,
                buttonLabel = "Copy redacted report",
                executionLabel = "Report only",
                safetyNote = "Copies safe support details without full links, cookies, authorization values, bearer tokens, or credential query values.",
                allowsImmediateCallback = true,
            )
        }
    }

    fun summary(decisions: List<RuntimeRecoveryExecutionDecision>): String {
        val hasRecoveryFirst = decisions.any { it.mode == RuntimeRecoveryExecutionMode.OpenRecoveryFirst }
        val hasReview = decisions.any { it.mode == RuntimeRecoveryExecutionMode.ReviewFirst }
        val hasGuidance = decisions.any { it.mode == RuntimeRecoveryExecutionMode.GuidanceOnly }
        return when {
            hasRecoveryFirst -> "Recovery Doctor is required before retrying or switching methods."
            hasReview -> "Sensitive session actions require review before retrying."
            hasGuidance -> "Some actions provide guidance instead of starting background work."
            else -> "Actions run only after an explicit tap."
        }
    }

    private fun execute(
        actionKind: RuntimeFailureRecoveryActionKind,
        buttonLabel: String,
        executionLabel: String,
        note: String,
    ) = RuntimeRecoveryExecutionDecision(
        actionKind = actionKind,
        mode = RuntimeRecoveryExecutionMode.ExecuteNow,
        buttonLabel = buttonLabel,
        executionLabel = executionLabel,
        safetyNote = note,
        allowsImmediateCallback = true,
    )

    private fun reviewFirst(
        actionKind: RuntimeFailureRecoveryActionKind,
        buttonLabel: String,
        note: String,
    ) = RuntimeRecoveryExecutionDecision(
        actionKind = actionKind,
        mode = RuntimeRecoveryExecutionMode.ReviewFirst,
        buttonLabel = buttonLabel,
        executionLabel = "Review required",
        safetyNote = note,
        allowsImmediateCallback = false,
    )

    private fun guidanceOnly(
        actionKind: RuntimeFailureRecoveryActionKind,
        buttonLabel: String,
        note: String,
    ) = RuntimeRecoveryExecutionDecision(
        actionKind = actionKind,
        mode = RuntimeRecoveryExecutionMode.GuidanceOnly,
        buttonLabel = buttonLabel,
        executionLabel = "Guidance only",
        safetyNote = note,
        allowsImmediateCallback = false,
    )

    private fun recoveryFirst(
        actionKind: RuntimeFailureRecoveryActionKind,
        buttonLabel: String,
        note: String,
    ) = RuntimeRecoveryExecutionDecision(
        actionKind = actionKind,
        mode = RuntimeRecoveryExecutionMode.OpenRecoveryFirst,
        buttonLabel = buttonLabel,
        executionLabel = "Recovery review required",
        safetyNote = note,
        allowsImmediateCallback = false,
    )
}
