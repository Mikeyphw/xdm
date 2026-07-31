package com.mikeyphw.xdm.android.model

/**
 * Phase64 final Android downloader RC seal.
 *
 * The planner is pure. It records readiness for a release-candidate handoff without starting work,
 * deleting files, uploading data, requesting broad storage, changing schema, reopening Debug
 * Workbench, or persisting browser/session/header values.
 */
enum class FinalAndroidDownloaderRcSealStatus {
    Ready,
    Hold,
}

data class FinalAndroidDownloaderRcSealCheck(
    val title: String,
    val detail: String,
    val status: FinalAndroidDownloaderRcSealStatus,
    val owner: String,
)

data class FinalAndroidDownloaderRcSeal(
    val title: String,
    val readyForRcHandoff: Boolean,
    val checks: List<FinalAndroidDownloaderRcSealCheck>,
) {
    val holdCount: Int get() = checks.count { it.status == FinalAndroidDownloaderRcSealStatus.Hold }

    val summary: String get() = when (holdCount) {
        0 -> "Final Android downloader RC seal is ready"
        1 -> "1 final RC hold remains"
        else -> "$holdCount final RC holds remain"
    }

    fun redactedSummary(): String = buildString {
        appendLine("XDM Android final downloader RC seal")
        appendLine("Status: $summary")
        checks.forEach { check ->
            appendLine("- ${check.title}: ${check.status.humanLabel()}")
            appendLine("  Detail: ${check.detail}")
            appendLine("  Owner: ${check.owner}")
        }
        append("Private values: full links, raw headers, cookies, authorization values, bearer tokens, signatures, credential query values, and browser session values are redacted.")
    }

    private fun FinalAndroidDownloaderRcSealStatus.humanLabel(): String = when (this) {
        FinalAndroidDownloaderRcSealStatus.Ready -> "ready"
        FinalAndroidDownloaderRcSealStatus.Hold -> "hold"
    }
}

object FinalAndroidDownloaderRcSealPlanner {
    fun evaluate(
        debugWorkbenchSealed: Boolean,
        operationalFieldFixesSealed: Boolean,
        runtimeRecoveryFlowSealed: Boolean,
        finalGateValidatorsHarmonized: Boolean,
        realDeviceSmokeRepresented: Boolean,
        supportBundleSealed: Boolean,
        browserRuntimeAbsent: Boolean,
        roomSchemaUnchanged: Boolean,
        noBroadStoragePermission: Boolean,
        noAutomaticWork: Boolean,
        noAutomaticDeletion: Boolean,
        noAutomaticUpload: Boolean,
        noPersistedSessionValues: Boolean,
        redactedDiagnosticsOnly: Boolean,
        signedArtifactsExpected: Boolean,
        checksumsExpected: Boolean,
        deferredFullValidationExpected: Boolean,
    ): FinalAndroidDownloaderRcSeal {
        val checks = listOf(
            check(
                title = "Debug Workbench D-series sealed",
                ready = debugWorkbenchSealed,
                readyDetail = "Debug Workbench D1-D7 remains sealed and is not reopened by RC work.",
                blockedDetail = "Debug Workbench must remain sealed before RC handoff.",
                owner = "DebugWorkbenchD7FinalDebugSealContractTest",
            ),
            check(
                title = "Operational hardening sealed",
                ready = operationalFieldFixesSealed,
                readyDetail = "Phases49-56 cover download actions, storage visibility, session health, detector quality, engine planning, release warnings, and copy cleanup.",
                blockedDetail = "Operational hardening phases must be present before RC handoff.",
                owner = "Phase49-Phase56 validators",
            ),
            check(
                title = "Runtime recovery flow sealed",
                ready = runtimeRecoveryFlowSealed,
                readyDetail = "Phases57-60 cover failure planning, execution guard, action preview, and recovery-flow seal.",
                blockedDetail = "Runtime recovery flow must be sealed before RC handoff.",
                owner = "RuntimeRecoveryFlowSealPlanner",
            ),
            check(
                title = "Final validators harmonized",
                ready = finalGateValidatorsHarmonized,
                readyDetail = "Final static gates use current planner-backed contracts and accept the final RC overlay as current.",
                blockedDetail = "Final gate validators must be aligned with current contracts.",
                owner = "Phase61FinalGateValidatorHarmonyContractTest",
            ),
            check(
                title = "Real-device smoke represented",
                ready = realDeviceSmokeRepresented,
                readyDetail = "Manual device smoke status is represented before the RC handoff.",
                blockedDetail = "Manual device smoke evidence must be represented before RC handoff.",
                owner = "RealDeviceOperationalSmokeSealPlanner",
            ),
            check(
                title = "Support bundle readiness sealed",
                ready = supportBundleSealed,
                readyDetail = "Copied support reports include operational, install/update, warning, smoke, and privacy sections.",
                blockedDetail = "Support bundle readiness must be sealed before RC handoff.",
                owner = "SupportBundleReleaseReadinessPlanner",
            ),
            check(
                title = "Browser-free downloader boundary",
                ready = browserRuntimeAbsent,
                readyDetail = "Built-in browser runtime remains absent while external browser handoff stays available.",
                blockedDetail = "The downloader-only product boundary must remain browser-free.",
                owner = "BrowserRemovalPhase7ContractTest",
            ),
            check(
                title = "Runtime side-effect boundary",
                ready = roomSchemaUnchanged && noBroadStoragePermission && noAutomaticWork && noAutomaticDeletion && noAutomaticUpload && noPersistedSessionValues,
                readyDetail = "RC seal adds no schema change, broad storage permission, automatic transfer start, automatic deletion, automatic upload, or persisted browser session values.",
                blockedDetail = "RC seal must not add schema drift, broad storage access, automatic work, deletion, upload, or persisted session values.",
                owner = "Phase64FinalAndroidDownloaderRcSealContractTest",
            ),
            check(
                title = "Privacy and artifact handoff",
                ready = redactedDiagnosticsOnly && signedArtifactsExpected && checksumsExpected && deferredFullValidationExpected,
                readyDetail = "Diagnostics stay redacted; signed artifacts, checksums, aria2 payload verification, and deferred full validation remain required for publishable artifacts.",
                blockedDetail = "RC handoff requires redacted diagnostics plus signed artifact, checksum, payload, and deferred full-validation expectations.",
                owner = "tools/run-final-release-gate.sh",
            ),
        )
        return FinalAndroidDownloaderRcSeal(
            title = "Final Android downloader RC seal",
            readyForRcHandoff = checks.none { it.status == FinalAndroidDownloaderRcSealStatus.Hold },
            checks = checks,
        )
    }

    private fun check(
        title: String,
        ready: Boolean,
        readyDetail: String,
        blockedDetail: String,
        owner: String,
    ): FinalAndroidDownloaderRcSealCheck = FinalAndroidDownloaderRcSealCheck(
        title = title,
        detail = if (ready) readyDetail else blockedDetail,
        status = if (ready) FinalAndroidDownloaderRcSealStatus.Ready else FinalAndroidDownloaderRcSealStatus.Hold,
        owner = owner,
    )
}
