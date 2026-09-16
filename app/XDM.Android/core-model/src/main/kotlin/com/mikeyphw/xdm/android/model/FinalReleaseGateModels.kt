package com.mikeyphw.xdm.android.model

import java.util.Locale

enum class FinalReleaseGateSeverity { Info, Warning, Blocking }

data class FinalReleaseGateCheck(
    val id: String,
    val severity: FinalReleaseGateSeverity,
    val title: String,
    val detail: String,
)

data class FinalReleaseGateOwner(
    val validator: String,
    val test: String,
)

data class FinalReleaseGateExplanation(
    val check: FinalReleaseGateCheck,
    val severityLabel: String,
    val impact: String,
    val safeToIgnore: String,
    val fixAction: String,
    val owner: FinalReleaseGateOwner,
) {
    val title: String get() = check.title
    val detail: String get() = check.detail
}

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

    val explanations: List<FinalReleaseGateExplanation>
        get() = checks.map(FinalReleaseGateExplainer::explain)

    val actionableExplanations: List<FinalReleaseGateExplanation>
        get() = explanations.filter { it.check.severity != FinalReleaseGateSeverity.Info }

    fun redactedSummary(): String = buildString {
        appendLine("XDM Android final release gate")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Package: $packageId")
        appendLine("Build: $buildType")
        appendLine("Schema: $schemaVersion")
        appendLine("Full validation: ${if (fullValidationPassed) "passed" else "required"}")
        append("Gate: $summary")
    }

    fun redactedExplanationSummary(): String = buildString {
        appendLine(redactedSummary())
        val actionable = actionableExplanations
        if (actionable.isEmpty()) {
            appendLine()
            append("Release warnings: none")
        } else {
            appendLine()
            appendLine("Release warnings explained:")
            actionable.forEach { explanation ->
                appendLine("- ${explanation.severityLabel}: ${explanation.title}")
                appendLine("  Impact: ${explanation.impact}")
                appendLine("  Safe to ignore: ${explanation.safeToIgnore}")
                appendLine("  Fix action: ${explanation.fixAction}")
                appendLine("  Owning check: ${explanation.owner.validator} • ${explanation.owner.test}")
            }
        }
    }.trimEnd()
}


object FinalReleaseGateExplainer {
    fun explain(check: FinalReleaseGateCheck): FinalReleaseGateExplanation = FinalReleaseGateExplanation(
        check = check,
        severityLabel = severityLabel(check.severity),
        impact = impactFor(check.id),
        safeToIgnore = safeToIgnoreFor(check.id, check.severity),
        fixAction = fixActionFor(check.id),
        owner = ownerFor(check.id),
    )

    private fun severityLabel(severity: FinalReleaseGateSeverity): String = when (severity) {
        FinalReleaseGateSeverity.Info -> "Info"
        FinalReleaseGateSeverity.Warning -> "Warning"
        FinalReleaseGateSeverity.Blocking -> "Blocked"
    }

    private fun impactFor(id: String): String = when (id) {
        "version.phase17", "version.code" -> "Release metadata is older than the public gate accepts, so update/install sequencing could be ambiguous."
        "version.alpha" -> "Alpha builds are intentionally not publishable through the public release gate."
        "package.identity" -> "Changing the package identity would break normal upgrades from existing installs."
        "database.schema" -> "The installed database schema does not match the release contract and needs a migration decision before shipping."
        "release.safety" -> "Privacy, diagnostics, or release-safety checks are incomplete."
        "install.update" -> "Install and update readiness checks are incomplete, so upgrades may not be safe enough for release."
        "diagnostics.export-attestation" -> "Runtime diagnostics redaction is enforced separately; the final exported ZIP still needs same-run privacy/integrity attestation before publication."
        "aria2.payload" -> "Publishable release artifacts must carry aria2 payload evidence bound to the signed APK. Native-only debug builds can continue without claiming release readiness."
        "ffmpeg.payload" -> "Publishable media releases must carry FFmpeg/FFprobe evidence bound to the signed APK."
        "lint.release" -> "Release lint must pass before publication because it checks manifest, storage, WebView and platform compatibility risks."
        "native.symbols" -> "Native symbol and 16 KiB alignment evidence is required for the attested ABI-specific release payload."
        "device.smoke" -> "The signed release must install, launch, upgrade and recover on a real device before publication is claimed."
        "apkset.install" -> "Generated APK sets must be installed/upgraded as APK sets; inspecting the .apks archive is not enough."
        "publication.evidence" -> "Publication metadata must be generated only after validation and must be hash-bound to the exact same-run artifacts."
        "release.journeys" -> "The signed release must execute canonical user journeys for intake, download, recovery, media, storage, settings and diagnostics."
        "validators.complete" -> "The static validator chain is incomplete, so the release gate cannot prove the expected contracts ran."
        "release.docs" -> "Release handoff instructions are incomplete for a publishable artifact."
        "route.topography" -> "A new top-level surface changed the release topology and needs explicit review."
        "full.validation" -> "Debug diagnostics can show this before the full selected-task validation is run; release artifacts must clear it."
        "signing.release" -> "A publishable release build cannot be installed as a verified release without signing inputs."
        "signing.debug-context" -> "Signing inputs are present, but this is not yet a release build artifact."
        "final-release.clean" -> "No action is needed; the final release gate is clean."
        else -> "Review the release check detail before publishing."
    }

    private fun safeToIgnoreFor(id: String, severity: FinalReleaseGateSeverity): String = when {
        severity == FinalReleaseGateSeverity.Blocking -> "No. This blocks publishable release artifacts until fixed."
        id == "aria2.payload" -> "Yes for Native-only debug testing; no for publishable release artifacts."
        id in setOf("ffmpeg.payload", "lint.release", "native.symbols", "device.smoke", "apkset.install", "publication.evidence", "release.journeys") -> "Yes only while inspecting debug diagnostics; no for public release packaging."
        id == "full.validation" -> "Yes in debug diagnostics before a full gate run; no for release artifacts."
        severity == FinalReleaseGateSeverity.Info -> "Yes. This is informational."
        else -> "Only for local debugging, not for public release packaging."
    }

    private fun fixActionFor(id: String): String = when (id) {
        "version.phase17", "version.code", "version.alpha" -> "Update versionName/versionCode to the current release-candidate contract."
        "package.identity" -> "Restore the stable package id before building release artifacts."
        "database.schema" -> "Keep reviewed schema 25 and its complete migration chain, or add a reviewed migration and update the release contract."
        "release.safety" -> "Run and fix the release safety validator chain."
        "install.update" -> "Run install/update readiness checks and resolve reported failures."
        "diagnostics.export-attestation" -> "Run the final diagnostics ZIP privacy/integrity validator in the same release run and bind its evidence to the publication bundle."
        "aria2.payload" -> "Run aria2 payload verification against the signed release APK."
        "ffmpeg.payload" -> "Run FFmpeg/FFprobe runtime verification against the signed release APK."
        "lint.release" -> "Run lintRelease as part of the signed-release gate and fix any reported issues."
        "native.symbols" -> "Run the native-symbol and 16 KiB verifier against the same-run release artifacts."
        "device.smoke" -> "Run the XAR16 install/upgrade/reboot launch matrix on a connected device and save the evidence JSON."
        "apkset.install" -> "Install the generated APK set with bundletool during the XAR16 device matrix."
        "publication.evidence" -> "Regenerate the XAR16 publication bundle after all artifact and device evidence has passed."
        "release.journeys" -> "Run the XAR16 signed-release journey runner on the installed signed release."
        "validators.complete" -> "Run the final release gate script and restore any missing validator links."
        "release.docs" -> "Complete release notes, validation scope, and artifact expectations."
        "route.topography" -> "Move the surface under an existing release-approved area or update the topology contract intentionally."
        "full.validation" -> "Run the full Devtool selected-task validation before signing release artifacts."
        "signing.release" -> "Provide release signing inputs and verify the signed artifact."
        "signing.debug-context" -> "Build the release variant when preparing the final signed artifact."
        "final-release.clean" -> "No fix required."
        else -> "Open the owning validator and follow its repair guidance."
    }

    private fun ownerFor(id: String): FinalReleaseGateOwner = when (id) {
        "aria2.payload" -> FinalReleaseGateOwner(
            validator = "tools/verify-aria2-runtime.py",
            test = "FinalReleaseGateModelsTest",
        )
        "ffmpeg.payload" -> FinalReleaseGateOwner(
            validator = "tools/verify-ffmpeg-runtime.py",
            test = "FinalReleaseGateModelsTest",
        )
        "lint.release", "native.symbols", "device.smoke", "apkset.install", "publication.evidence", "release.journeys" -> FinalReleaseGateOwner(
            validator = "tools/verify-xar16-release-evidence.py",
            test = "FinalReleaseGateModelsTest",
        )
        "full.validation" -> FinalReleaseGateOwner(
            validator = "tools/run-final-release-gate.sh",
            test = "FinalReleaseGateModelsTest",
        )
        "signing.release", "signing.debug-context" -> FinalReleaseGateOwner(
            validator = "tools/validate-phase-17.py",
            test = "FinalReleaseGateModelsTest",
        )
        "diagnostics.export-attestation" -> FinalReleaseGateOwner(
            validator = "tools/validate-phase50-operational-repair.py",
            test = "FinalReleaseGateModelsTest",
        )
        "install.update" -> FinalReleaseGateOwner(
            validator = "tools/validate-phase-16-packaging-recovery-readiness.py",
            test = "ReleaseReadinessModelsTest",
        )
        "release.safety" -> FinalReleaseGateOwner(
            validator = "tools/validate-phase-14.py",
            test = "ReleaseSecurityModelsTest",
        )
        "validators.complete", "release.docs", "route.topography" -> FinalReleaseGateOwner(
            validator = "tools/run-final-release-gate.sh",
            test = "ArchitectureContractTest",
        )
        else -> FinalReleaseGateOwner(
            validator = "tools/validate-phase-17.py",
            test = "FinalReleaseGateModelsTest",
        )
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
        diagnosticsExportAttested: Boolean,
        aria2PayloadVerified: Boolean,
        staticValidatorsComplete: Boolean,
        releaseDocsComplete: Boolean,
        noNewTopLevelRoutes: Boolean,
        fullValidationPassed: Boolean,
        releaseSigningConfigured: Boolean,
        ffmpegPayloadVerified: Boolean = true,
        lintValidationPassed: Boolean = true,
        nativeSymbolsValidated: Boolean = true,
        realDeviceSmokePassed: Boolean = true,
        apkSetInstalled: Boolean = true,
        publicationEvidenceVerified: Boolean = true,
        signedReleaseJourneysPassed: Boolean = true,
    ): FinalReleaseGateReport {
        val normalizedVersion = versionName.trim().ifBlank { "unknown" }
        val normalizedPackageId = packageId.trim().ifBlank { "unknown" }
        val normalizedBuildType = buildType.trim().ifBlank { "unknown" }.lowercase(Locale.US)
        val checks = buildList {
            val minor = normalizedVersion.removeSuffix("-debug")
                .split('.')
                .getOrNull(1)
                ?.toIntOrNull()
                ?: -1
            if (minor < 21) {
                add(
                    FinalReleaseGateCheck(
                        id = "version.phase17",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Version metadata is stale",
                        detail = "The final gate requires the Phase 10 0.21.x public release version metadata.",
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
            if (versionCode < 22) {
                add(
                    FinalReleaseGateCheck(
                        id = "version.code",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Version code is not final-gate monotonic",
                        detail = "Public release artifacts must advance versionCode to at least 22 for Phase 10.",
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
            if (schemaVersion != 25) {
                add(
                    FinalReleaseGateCheck(
                        id = "database.schema",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Unexpected Room schema for public gate",
                        detail = "The public release gate expects reviewed Room schema v25 after the reviewed migration chain through XAR03 generation integrity.",
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
            if (!diagnosticsExportAttested) {
                add(
                    FinalReleaseGateCheck(
                        id = "diagnostics.export-attestation",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Final diagnostics export redaction is not attested",
                        detail = "Runtime redaction is checked separately. Publishable support bundles must also carry final-ZIP privacy/integrity attestation from the exact release validation run.",
                    ),
                )
            }
            fun releaseEvidenceSeverity(): FinalReleaseGateSeverity =
                if (normalizedBuildType == "release") FinalReleaseGateSeverity.Blocking else FinalReleaseGateSeverity.Warning
            if (!aria2PayloadVerified) {
                add(
                    FinalReleaseGateCheck(
                        id = "aria2.payload",
                        severity = releaseEvidenceSeverity(),
                        title = "aria2 payload verification is pending",
                        detail = "Publishable releases must verify the aria2 payload against the same signed APK they publish.",
                    ),
                )
            }
            if (!ffmpegPayloadVerified) {
                add(
                    FinalReleaseGateCheck(
                        id = "ffmpeg.payload",
                        severity = releaseEvidenceSeverity(),
                        title = "FFmpeg payload verification is pending",
                        detail = "Publishable media releases must verify FFmpeg/FFprobe runtime payloads against the signed APK.",
                    ),
                )
            }
            if (!lintValidationPassed) {
                add(
                    FinalReleaseGateCheck(
                        id = "lint.release",
                        severity = releaseEvidenceSeverity(),
                        title = "Release lint evidence is missing",
                        detail = "XAR16 requires lintRelease evidence before publication metadata can be generated.",
                    ),
                )
            }
            if (!nativeSymbolsValidated) {
                add(
                    FinalReleaseGateCheck(
                        id = "native.symbols",
                        severity = releaseEvidenceSeverity(),
                        title = "Native symbol and 16 KiB evidence is missing",
                        detail = "Release artifacts must carry native-symbol and 16 KiB alignment evidence for the attested ABI payload.",
                    ),
                )
            }
            if (!realDeviceSmokePassed) {
                add(
                    FinalReleaseGateCheck(
                        id = "device.smoke",
                        severity = releaseEvidenceSeverity(),
                        title = "Real-device smoke evidence is missing",
                        detail = "The signed APK/APK set must be installed, launched, upgraded, rebooted and re-launched on a real device.",
                    ),
                )
            }
            if (!apkSetInstalled) {
                add(
                    FinalReleaseGateCheck(
                        id = "apkset.install",
                        severity = releaseEvidenceSeverity(),
                        title = "APK-set install evidence is missing",
                        detail = "The generated APK set must be installed/upgraded as an APK set instead of only being inspected.",
                    ),
                )
            }
            if (!publicationEvidenceVerified) {
                add(
                    FinalReleaseGateCheck(
                        id = "publication.evidence",
                        severity = releaseEvidenceSeverity(),
                        title = "Publication evidence is not verified",
                        detail = "Publication metadata must be be generated only after validation and hash-bound to same-run artifacts.",
                    ),
                )
            }
            if (!signedReleaseJourneysPassed) {
                add(
                    FinalReleaseGateCheck(
                        id = "release.journeys",
                        severity = releaseEvidenceSeverity(),
                        title = "Signed-release user journeys are missing",
                        detail = "XAR16 requires signed-release journeys for intake, download, resume/recovery, media, storage, settings and diagnostics.",
                    ),
                )
            }
            if (!staticValidatorsComplete) {
                add(
                    FinalReleaseGateCheck(
                        id = "validators.complete",
                        severity = FinalReleaseGateSeverity.Blocking,
                        title = "Static validator chain is incomplete",
                        detail = "CI and local final gate scripts must run the complete current validator suite.",
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
                            "Debug builds surface this as a warning so runtime diagnostics do not look broken before a publishable release gate is run."
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
