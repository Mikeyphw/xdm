package com.mikeyphw.xdm.android.model

import java.util.Locale

enum class FinalReleaseGateSeverity { Info, Warning, Blocking }

data class FinalReleaseGateCheck(
    val id: String,
    val severity: FinalReleaseGateSeverity,
    val title: String,
    val detail: String,
)

data class FinalReleaseGateReport(
    val versionName: String,
    val versionCode: Int,
    val packageId: String,
    val schemaVersion: Int,
    val buildType: String,
    val fullValidationRequired: Boolean,
    val fullValidationPassed: Boolean,
    val checks: List<FinalReleaseGateCheck>,
) {
    val blockingCount: Int get() = checks.count { it.severity == FinalReleaseGateSeverity.Blocking }
    val warningCount: Int get() = checks.count { it.severity == FinalReleaseGateSeverity.Warning }
    val readyForPublicRelease: Boolean get() = blockingCount == 0 && (!fullValidationRequired || fullValidationPassed)
    val summary: String get() = when {
        blockingCount > 0 -> "$blockingCount blocking final-release issue${if (blockingCount == 1) "" else "s"}"
        warningCount > 0 -> "$warningCount final-release warning${if (warningCount == 1) "" else "s"}"
        else -> "Final release gate is clean"
    }

    fun redactedSummary(): String = buildString {
        appendLine("XDM Android final release gate")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Package: $packageId")
        appendLine("Build: $buildType")
        appendLine("Schema: $schemaVersion")
        appendLine("Full validation: ${if (fullValidationPassed) "passed" else "required"}")
        append("Gate: $summary")
    }
}

object FinalPublicReleaseGate {
    fun evaluate(
        versionName: String,
        versionCode: Int,
        packageId: String,
        schemaVersion: Int,
        buildType: String,
        releaseSafetyReady: Boolean,
        installUpdateReady: Boolean,
        diagnosticsRedacted: Boolean,
        aria2PayloadVerified: Boolean,
        staticValidatorsComplete: Boolean,
        releaseDocsComplete: Boolean,
        noNewTopLevelRoutes: Boolean,
        fullValidationPassed: Boolean,
        releaseSigningConfigured: Boolean,
    ): FinalReleaseGateReport {
        val normalizedVersion = versionName.trim().ifBlank { "unknown" }
        val normalizedPackageId = packageId.trim().ifBlank { "unknown" }
        val normalizedBuildType = buildType.trim().ifBlank { "unknown" }.lowercase(Locale.US)
        val checks = buildList {
            val minor = normalizedVersion.removeSuffix("-debug").removeSuffix("-beta")
                .split('.')
                .getOrNull(1)
                ?.toIntOrNull()
                ?: -1
            if (minor < 17) {
                add(
                    FinalReleaseGateCheck(
                        id = "version.phase17",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Version metadata is stale",
                        detail = "The final gate requires a 0.17.x release-candidate or public release version.",
                    ),
                )
            }
            if ("alpha" in normalizedVersion.lowercase(Locale.US)) {
                add(
                    FinalReleaseGateCheck(
                        id = "version.alpha",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Alpha version cannot pass the public gate",
                        detail = "Use an rc or final version name before producing public release artifacts.",
                    ),
                )
            }
            if (versionCode < 18) {
                add(
                    FinalReleaseGateCheck(
                        id = "version.code",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Version code is not final-gate monotonic",
                        detail = "Phase 17 artifacts must advance versionCode to at least 18.",
                    ),
                )
            }
            if (normalizedPackageId != "com.mikeyphw.xdm.android") {
                add(
                    FinalReleaseGateCheck(
                        id = "package.identity",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Package identity changed",
                        detail = "Public release upgrades depend on com.mikeyphw.xdm.android remaining stable.",
                    ),
                )
            }
            if (schemaVersion != 14) {
                add(
                    FinalReleaseGateCheck(
                        id = "database.schema",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Unexpected Room schema for public gate",
                        detail = "Phase 17 intentionally ships without another Room migration; schema must stay at v14.",
                    ),
                )
            }
            if (!releaseSafetyReady) {
                add(
                    FinalReleaseGateCheck(
                        id = "release.safety",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Release safety is incomplete",
                        detail = "Privacy-safe diagnostics and release security checks must stay active.",
                    ),
                )
            }
            if (!installUpdateReady) {
                add(
                    FinalReleaseGateCheck(
                        id = "install.update",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Install/update readiness is incomplete",
                        detail = "Update identity, recovery, payload and schema checks must pass before public release.",
                    ),
                )
            }
            if (!diagnosticsRedacted) {
                add(
                    FinalReleaseGateCheck(
                        id = "diagnostics.redaction",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Diagnostics are not redacted",
                        detail = "Support bundles must not expose cookies, bearer tokens, signed URLs, or auth headers.",
                    ),
                )
            }
            if (!aria2PayloadVerified) {
                add(
                    FinalReleaseGateCheck(
                        id = "aria2.payload",
                        severity = FinalReleaseGateSeverity.Warning,
                        title = "aria2 payload verification is pending",
                        detail = "Native-only builds are allowed, but publishable aria2-enabled artifacts must pass payload verification.",
                    ),
                )
            }
            if (!staticValidatorsComplete) {
                add(
                    FinalReleaseGateCheck(
                        id = "validators.complete",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Static validator chain is incomplete",
                        detail = "CI and local final gate scripts must run validators through Phase 17.",
                    ),
                )
            }
            if (!releaseDocsComplete) {
                add(
                    FinalReleaseGateCheck(
                        id = "release.docs",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Release documentation is incomplete",
                        detail = "Public release instructions, validation scope, and artifact expectations must be documented.",
                    ),
                )
            }
            if (!noNewTopLevelRoutes) {
                add(
                    FinalReleaseGateCheck(
                        id = "route.topography",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Route topography changed",
                        detail = "Final release readiness must stay inside Diagnostics and Settings.",
                    ),
                )
            }
            if (!fullValidationPassed) {
                val requiresReleaseBlocking = normalizedBuildType == "release"
                add(
                    FinalReleaseGateCheck(
                        id = "full.validation",
                        severity = if (requiresReleaseBlocking) FinalReleaseGateSeverity.Blocking else FinalReleaseGateSeverity.Warning,
                        title = if (requiresReleaseBlocking) "Full validation has not passed" else "Full validation pending for release builds",
                        detail = if (requiresReleaseBlocking) {
                            "The final public gate requires the full devtool validation pass, not a medium selected-task gate."
                        } else {
                            "Debug and beta builds surface this as a warning so runtime diagnostics do not look broken before a publishable release gate is run."
                        },
                    ),
                )
            }
            if (normalizedBuildType == "release" && !releaseSigningConfigured) {
                add(
                    FinalReleaseGateCheck(
                        id = "signing.release",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Release signing is missing",
                        detail = "Publishable release APKs must provide signing inputs and pass signature verification.",
                    ),
                )
            }
            if (normalizedBuildType != "release" && releaseSigningConfigured) {
                add(
                    FinalReleaseGateCheck(
                        id = "signing.debug-context",
                        severity = FinalReleaseGateSeverity.Info,
                        title = "Signing configured outside release build",
                        detail = "Signing inputs are present; the final signed artifact still needs the release build type.",
                    ),
                )
            }
            if (isEmpty()) {
                add(
                    FinalReleaseGateCheck(
                        id = "final-release.clean",
                        severity = FinalReleaseGateSeverity.Info,
                        title = "Final release gate",
                        detail = "Version, package identity, schema, diagnostics, payload checks, release docs and validation requirements are clean.",
                    ),
                )
            }
        }
        return FinalReleaseGateReport(
            versionName = normalizedVersion,
            versionCode = versionCode,
            packageId = normalizedPackageId,
            schemaVersion = schemaVersion,
            buildType = normalizedBuildType,
            fullValidationRequired = true,
            fullValidationPassed = fullValidationPassed,
            checks = checks,
        )
    }
}
