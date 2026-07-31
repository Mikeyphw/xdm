package com.mikeyphw.xdm.android.model

/**
 * Phase63 support-bundle/release-readiness seal.
 *
 * The planner is pure and only summarizes whether copied diagnostics contain the sections a release
 * candidate needs. It never uploads data, starts transfers, deletes files, requests broad storage,
 * or persists browser/session/header values.
 */
enum class SupportBundleSealStatus {
    Ready,
    NeedsAttention,
}

data class SupportBundleSealCheck(
    val title: String,
    val detail: String,
    val status: SupportBundleSealStatus,
    val owner: String,
)

data class SupportBundleReleaseReadinessSeal(
    val title: String,
    val readyForSupportHandoff: Boolean,
    val checks: List<SupportBundleSealCheck>,
) {
    val issueCount: Int get() = checks.count { it.status == SupportBundleSealStatus.NeedsAttention }

    val summary: String get() = when (issueCount) {
        0 -> "Support bundle seal is ready"
        1 -> "1 support bundle issue needs attention"
        else -> "$issueCount support bundle issues need attention"
    }

    fun redactedSummary(): String = buildString {
        appendLine("XDM Android support bundle seal")
        appendLine("Status: $summary")
        checks.forEach { check ->
            appendLine("- ${check.title}: ${check.status.humanLabel()}")
            appendLine("  Detail: ${check.detail}")
            appendLine("  Owner: ${check.owner}")
        }
        append("Private values: full links, raw headers, cookies, authorization values, bearer tokens, signatures, and credential query values are redacted.")
    }

    private fun SupportBundleSealStatus.humanLabel(): String = when (this) {
        SupportBundleSealStatus.Ready -> "ready"
        SupportBundleSealStatus.NeedsAttention -> "needs attention"
    }
}

object SupportBundleReleaseReadinessPlanner {
    fun evaluate(
        operationalDiagnosticsIncluded: Boolean,
        releaseSecurityIncluded: Boolean,
        installUpdateReadinessIncluded: Boolean,
        finalReleaseWarningsExplained: Boolean,
        realDeviceSmokeStatusIncluded: Boolean,
        redactedReportsOnly: Boolean,
        rawUrlsExcluded: Boolean,
        rawHeadersExcluded: Boolean,
        sessionValuesPersisted: Boolean,
        copyReportAvailable: Boolean,
    ): SupportBundleReleaseReadinessSeal {
        val checks = listOf(
            check(
                title = "Operational diagnostics summary",
                ready = operationalDiagnosticsIncluded,
                readyDetail = "Counts, unresolved work, method names, and diagnostic fingerprint are present in redacted form.",
                blockedDetail = "Copied support reports need the operational diagnostics summary before release handoff.",
                owner = "OperationalActivityPlanner",
            ),
            check(
                title = "Release-security status",
                ready = releaseSecurityIncluded,
                readyDetail = "Release-security status is included without exposing private request context.",
                blockedDetail = "Release-security status must be copied with the support report.",
                owner = "ReleaseSecurityGate",
            ),
            check(
                title = "Install/update readiness",
                ready = installUpdateReadinessIncluded,
                readyDetail = "Package identity, version, schema, recovery, diagnostics, and payload readiness are summarized.",
                blockedDetail = "Install/update readiness must be included for upgrade triage.",
                owner = "ReleaseInstallReadinessGate",
            ),
            check(
                title = "Final-release warning explanations",
                ready = finalReleaseWarningsExplained,
                readyDetail = "Warnings include impact, safe-to-ignore status, fix action, and owning check.",
                blockedDetail = "Final-release warnings need explanations instead of a bare count.",
                owner = "FinalReleaseGateExplainer",
            ),
            check(
                title = "Real-device smoke status",
                ready = realDeviceSmokeStatusIncluded,
                readyDetail = "Manual device evidence status is represented before RC handoff.",
                blockedDetail = "Real-device smoke status must be visible before RC handoff.",
                owner = "RealDeviceOperationalSmokeSealPlanner",
            ),
            check(
                title = "Privacy redaction boundary",
                ready = redactedReportsOnly && rawUrlsExcluded && rawHeadersExcluded && !sessionValuesPersisted,
                readyDetail = "Support reports stay local and omit full links, raw headers, cookies, authorization values, bearer tokens, signatures, credential query values, and persisted session values.",
                blockedDetail = "Support reports must remain redacted and must not persist session/header values.",
                owner = "PrivacyDiagnosticsRedactor",
            ),
            check(
                title = "Copy-only support handoff",
                ready = copyReportAvailable,
                readyDetail = "Support handoff is copy-only and requires a deliberate user action.",
                blockedDetail = "Support handoff must remain explicit and copy-only.",
                owner = "DebugWorkbenchShellPolicy",
            ),
        )
        return SupportBundleReleaseReadinessSeal(
            title = "Release readiness support bundle seal",
            readyForSupportHandoff = checks.none { it.status == SupportBundleSealStatus.NeedsAttention },
            checks = checks,
        )
    }

    private fun check(
        title: String,
        ready: Boolean,
        readyDetail: String,
        blockedDetail: String,
        owner: String,
    ): SupportBundleSealCheck = SupportBundleSealCheck(
        title = title,
        detail = if (ready) readyDetail else blockedDetail,
        status = if (ready) SupportBundleSealStatus.Ready else SupportBundleSealStatus.NeedsAttention,
        owner = owner,
    )
}
