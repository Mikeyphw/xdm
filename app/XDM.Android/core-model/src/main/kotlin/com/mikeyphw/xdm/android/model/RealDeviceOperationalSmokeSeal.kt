package com.mikeyphw.xdm.android.model

/**
 * Phase62 real-device operational smoke seal.
 *
 * This pure checklist binds the post-D7/Phase49-61 downloader-only flow into a manual device
 * smoke plan. It does not start transfers, delete files, request all-files storage, persist browser
 * session values, or reopen Debug Workbench. Device operators run these checks on a real Android
 * device and copy the redacted summary into release notes or support bundles.
 */
enum class RealDeviceSmokeFlow {
    ExternalBrowserHandoff,
    ExtensionMediaCapture,
    AuthenticatedFailureRecovery,
    CompletedStorageVisibility,
    RecoveryDoctorReview,
}

enum class RealDeviceSmokeStatus {
    Ready,
    ManualDeviceRunRequired,
    NeedsReview,
}

data class RealDeviceSmokeCheck(
    val flow: RealDeviceSmokeFlow,
    val title: String,
    val expectedResult: String,
    val evidenceLabel: String,
    val recoveryPath: String,
    val status: RealDeviceSmokeStatus,
)

data class RealDeviceOperationalSmokeSeal(
    val title: String,
    val readyForRcCandidate: Boolean,
    val manualDeviceRunRequired: Boolean,
    val checks: List<RealDeviceSmokeCheck>,
    val redactedSummary: String,
)

object RealDeviceOperationalSmokeSealPlanner {
    fun build(manualResultsCaptured: Boolean = false): RealDeviceOperationalSmokeSeal {
        val status = if (manualResultsCaptured) RealDeviceSmokeStatus.Ready else RealDeviceSmokeStatus.ManualDeviceRunRequired
        val checks = listOf(
            RealDeviceSmokeCheck(
                flow = RealDeviceSmokeFlow.ExternalBrowserHandoff,
                title = "External browser handoff",
                expectedResult = "Share from an external browser opens Add Download with Browser session health and Suggested method review.",
                evidenceLabel = "Add Download review shown; no automatic transfer starts.",
                recoveryPath = "Refresh from browser when page context is stale.",
                status = status,
            ),
            RealDeviceSmokeCheck(
                flow = RealDeviceSmokeFlow.ExtensionMediaCapture,
                title = "Extension media capture",
                expectedResult = "Firefox extension shows high-confidence media by default and keeps possible media behind the advanced toggle.",
                evidenceLabel = "Reviewed media add path is visible without fake thumbnail/API candidates.",
                recoveryPath = "Inspect media first or rescan with possible media enabled.",
                status = status,
            ),
            RealDeviceSmokeCheck(
                flow = RealDeviceSmokeFlow.AuthenticatedFailureRecovery,
                title = "Authenticated failure recovery",
                expectedResult = "HTTP 401/403 failures show Recovery options, Action safety, Action preview, and redacted copy guidance.",
                evidenceLabel = "Retry choices stay manual and session values remain hidden.",
                recoveryPath = "Retry with captured session, refresh from browser, or try yt-dlp after review.",
                status = status,
            ),
            RealDeviceSmokeCheck(
                flow = RealDeviceSmokeFlow.CompletedStorageVisibility,
                title = "Completed storage visibility",
                expectedResult = "A completed download appears in shared storage while normal UI shows a human destination label instead of a raw handle.",
                evidenceLabel = "Storage visibility check passes for Downloads or selected MediaStore destination.",
                recoveryPath = "Re-check storage visibility or open Recovery Doctor.",
                status = status,
            ),
            RealDeviceSmokeCheck(
                flow = RealDeviceSmokeFlow.RecoveryDoctorReview,
                title = "Recovery Doctor review",
                expectedResult = "Old partial, orphan, interrupted, and missing-artifact records are classified with safe validate/report actions.",
                evidenceLabel = "Recovery Doctor shows redacted buckets and no automatic deletion.",
                recoveryPath = "Validate all, forget stale record, or retry from source after review.",
                status = status,
            ),
        )
        val ready = manualResultsCaptured && checks.none { it.status == RealDeviceSmokeStatus.NeedsReview }
        return RealDeviceOperationalSmokeSeal(
            title = "Real-device operational smoke seal",
            readyForRcCandidate = ready,
            manualDeviceRunRequired = !manualResultsCaptured,
            checks = checks,
            redactedSummary = redactedSummary(checks, ready, manualResultsCaptured),
        )
    }

    fun redactedSummary(
        checks: List<RealDeviceSmokeCheck>,
        readyForRcCandidate: Boolean,
        manualResultsCaptured: Boolean,
    ): String = buildString {
        appendLine("XDM Android real-device operational smoke seal")
        appendLine("Manual device run: ${if (manualResultsCaptured) "captured" else "required"}")
        appendLine("RC candidate: ${if (readyForRcCandidate) "ready" else "hold until smoke run is captured"}")
        checks.forEach { check ->
            appendLine("- ${check.title}: ${check.status.humanLabel()}")
            appendLine("  Evidence: ${check.evidenceLabel}")
            appendLine("  Recovery path: ${check.recoveryPath}")
        }
        append("Private values: full links, cookies, authorization values, bearer tokens, signatures, and credential query values are redacted.")
    }

    private fun RealDeviceSmokeStatus.humanLabel(): String = when (this) {
        RealDeviceSmokeStatus.Ready -> "ready"
        RealDeviceSmokeStatus.ManualDeviceRunRequired -> "manual device run required"
        RealDeviceSmokeStatus.NeedsReview -> "needs review"
    }
}
