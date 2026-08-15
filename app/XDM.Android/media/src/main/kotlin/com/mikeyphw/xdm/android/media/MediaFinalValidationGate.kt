package com.mikeyphw.xdm.android.media

/**
 * Final media/remediation validation gate.
 *
 * This planner is deliberately pure Kotlin. It never treats "validation enabled" as evidence that
 * validation passed. The current remediation overlay, Room schema, static validator result, and
 * full build/test/lint result are explicit inputs so release-readiness cannot self-certify.
 */
enum class MediaFinalValidationSeverity(val label: String) {
    Pass("pass"),
    Review("review"),
    Blocker("blocker"),
}

enum class MediaFinalValidationSurface(val label: String) {
    PhaseLedger("phase ledger"),
    StaticValidators("static validators"),
    GradleGate("Gradle build/test/lint"),
    PrivacyLeakScan("privacy leak scan"),
    KotlinTrapScan("Kotlin trap scan"),
    TermuxChrootSafety("Termux/chroot safety"),
    RouteContract("route contract"),
    ReleaseDocs("release docs"),
}

data class MediaFinalValidationCheck(
    val id: String,
    val title: String,
    val surface: MediaFinalValidationSurface,
    val severity: MediaFinalValidationSeverity,
    val passing: Boolean,
    val summary: String,
    val evidence: String,
) {
    val blocking: Boolean get() = severity == MediaFinalValidationSeverity.Blocker && !passing
    val needsReview: Boolean get() = severity == MediaFinalValidationSeverity.Review && !passing
    val compactLine: String get() = listOf(surface.label, title, if (passing) "ok" else severity.label, summary).joinToString(" • ")
}

data class MediaFinalValidationCommand(
    val label: String,
    val command: String,
    val required: Boolean,
) {
    val safePreview: String get() = redactKnownSecrets(command)
}

data class MediaFinalValidationDashboard(
    val checks: List<MediaFinalValidationCheck>,
    val commands: List<MediaFinalValidationCommand>,
    val implementedPhaseCount: Int,
    val expectedFinalPhase: Int,
    val releaseReady: Boolean,
    val warningGate: Boolean,
    val noNewTopLevelRoutes: Boolean,
    val secretSafe: Boolean,
    val finalValidationScriptReady: Boolean,
) {
    val passCount: Int get() = checks.count { it.passing }
    val reviewCount: Int get() = checks.count { it.needsReview }
    val blockerCount: Int get() = checks.count { it.blocking }
    val commandCount: Int get() = commands.size
    val summary: String get() = listOf(
        "phase=$expectedFinalPhase",
        "checks=$passCount/${checks.size}",
        "review=$reviewCount",
        "blockers=$blockerCount",
        "commands=$commandCount",
        if (warningGate) "warning-zero gate" else "warning gate review",
        if (noNewTopLevelRoutes) "no new routes" else "route review",
        if (secretSafe) "secret-safe" else "redaction review",
        if (finalValidationScriptReady) "script-ready" else "script review",
    ).joinToString(" • ")
}

class MediaFinalValidationGatePlanner {
    fun dashboard(
        currentOverlay: String,
        currentRoomSchemaVersion: Int,
        mediaMobilePolish: MediaMobilePolishDashboard,
        privacyAudit: MediaSessionPrivacyAuditDashboard,
        captureQuality: MediaCaptureQualityDashboard,
        playerReports: List<MediaPlayerDiagnosticReport>,
        library: OfflineLibraryV2Dashboard,
        termuxRuntime: TermuxRuntimeDashboard,
        nativeDirect: NativeDirectDashboard,
        validatorCommands: List<String> = defaultValidatorCommands(),
        gradleCommand: String = DefaultGradleCommand,
        staticValidationPassed: Boolean = false,
        fullValidationPassed: Boolean = false,
        noNewTopLevelRoutes: Boolean = false,
        keepDebugSymbolsProtected: Boolean = false,
        warningsAsErrors: Boolean = false,
    ): MediaFinalValidationDashboard {
        val overlayCurrent = currentOverlay == FinalOverlayArtifact
        val schemaCurrent = currentRoomSchemaVersion == CurrentRoomSchema
        val commands = validatorCommands.map { command -> MediaFinalValidationCommand(labelForCommand(command), command, required = true) } +
            MediaFinalValidationCommand("Gradle build/test/lint", gradleCommand, required = true)
        val commandText = commands.joinToString("\n") { it.safePreview }
        val secretSafe = mediaMobilePolish.secretSafe &&
            privacyAudit.durableSecretSafe &&
            privacyAudit.transientCleanupHealthy &&
            privacyAudit.filesystemCoverageComplete &&
            privacyAudit.filesystemCoverageIssueCount == 0 &&
            privacyAudit.scannedFilesystemRootCount >= RequiredFilesystemRoots &&
            captureQuality.secretSafe &&
            library.secretSafe &&
            termuxRuntime.secretSafe &&
            nativeDirect.secretSafe &&
            playerReports.all { it.sourceSafe && it.protectedDiagnosticOnly == (it.bucket == MediaPlayerDiagnosticBucket.ProtectedMedia || it.protectedDiagnosticOnly) } &&
            !containsKnownSecret(commandText)
        val checks = listOf(
            MediaFinalValidationCheck(
                id = "phase-ledger",
                title = "Current remediation overlay and schema",
                surface = MediaFinalValidationSurface.PhaseLedger,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = overlayCurrent && schemaCurrent,
                summary = if (overlayCurrent && schemaCurrent) "final overlay/schema current" else "overlay or schema is stale",
                evidence = "overlay=$currentOverlay schema=$currentRoomSchemaVersion",
            ),
            MediaFinalValidationCheck(
                id = "static-validators",
                title = "All media validators wired",
                surface = MediaFinalValidationSurface.StaticValidators,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = staticValidationPassed && validatorCommands.any { it.contains("validate-media-final-validation-gate.py") } && validatorCommands.any { it.contains("run-final-release-gate.sh") },
                summary = if (staticValidationPassed) "static validator evidence passed" else "static validation evidence pending",
                evidence = validatorCommands.joinToString(" | "),
            ),
            MediaFinalValidationCheck(
                id = "gradle-gate",
                title = "Gradle/lint/test gate restored",
                surface = MediaFinalValidationSurface.GradleGate,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = fullValidationPassed && warningsAsErrors && REQUIRED_FULL_VALIDATION_TASKS.all(gradleCommand::contains),
                summary = if (fullValidationPassed) "full build/test/lint evidence passed" else "full validation evidence pending",
                evidence = gradleCommand,
            ),
            MediaFinalValidationCheck(
                id = "privacy-leak-scan",
                title = "No durable cookies, headers, or tokens",
                surface = MediaFinalValidationSurface.PrivacyLeakScan,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = secretSafe && privacyAudit.blockerCount == 0 && privacyAudit.filesystemCoverageComplete,
                summary = privacyAudit.summary,
                evidence = listOf(captureQuality.summary, library.summary, termuxRuntime.summary, nativeDirect.summary).joinToString(" • "),
            ),
            MediaFinalValidationCheck(
                id = "kotlin-trap-scan",
                title = "Known Kotlin and warning traps blocked",
                surface = MediaFinalValidationSurface.KotlinTrapScan,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = warningsAsErrors && mediaMobilePolish.accessibilityReady,
                summary = "smart casts, raw buildList helpers, nullable in checks, and redundant assertions are preflighted by validator",
                evidence = "warningsAsErrors=$warningsAsErrors accessibility=${mediaMobilePolish.accessibilityReady}",
            ),
            MediaFinalValidationCheck(
                id = "termux-chroot-strip",
                title = "Termux/chroot native strip protection retained",
                surface = MediaFinalValidationSurface.TermuxChrootSafety,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = keepDebugSymbolsProtected,
                summary = "jniLibs.keepDebugSymbols covers packaged JNI libraries",
                evidence = "keepDebugSymbols=$keepDebugSymbolsProtected",
            ),
            MediaFinalValidationCheck(
                id = "route-contract",
                title = "Media remains inside existing route",
                surface = MediaFinalValidationSurface.RouteContract,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = noNewTopLevelRoutes,
                summary = "browser, inbox, library, player, diagnostics, and validation stay inside Media",
                evidence = "topLevelRoutesAdded=${!noNewTopLevelRoutes}",
            ),
            MediaFinalValidationCheck(
                id = "release-docs",
                title = "Final docs and command ledger present",
                surface = MediaFinalValidationSurface.ReleaseDocs,
                severity = MediaFinalValidationSeverity.Review,
                passing = commands.isNotEmpty() && commands.all { !containsKnownSecret(it.safePreview) },
                summary = "final validation runbook is generated without secret-bearing command previews",
                evidence = commands.joinToString("\n") { it.safePreview },
            ),
        )
        val ready = checks.none { it.blocking } && staticValidationPassed && fullValidationPassed
        return MediaFinalValidationDashboard(
            checks = checks,
            commands = commands,
            implementedPhaseCount = if (overlayCurrent) 1 else 0,
            expectedFinalPhase = 13,
            releaseReady = ready,
            warningGate = warningsAsErrors,
            noNewTopLevelRoutes = noNewTopLevelRoutes,
            secretSafe = secretSafe,
            finalValidationScriptReady = commands.any { it.command.contains("run-final-release-gate.sh") || it.command.contains("validate-media-final-validation-gate.py") },
        )
    }

    private fun labelForCommand(command: String): String = when {
        command.contains("validate-media-final-validation-gate.py") -> "Final media gate"
        command.contains("validate-media-session-privacy-audit.py") -> "Privacy audit"
        command.contains("validate-media-mobile-polish.py") -> "Mobile polish"
        command.contains("validate-media-capture-quality.py") -> "Capture quality"
        command.contains("validate-media-player-diagnostics.py") -> "Player diagnostics"
        command.contains("validate-media-offline-library-v2.py") -> "Offline library"
        command.contains("validate-media-native-direct-download-engine.py") -> "Native direct engine"
        command.contains("validate-media-termux-runtime-adapter.py") -> "Termux runtime"
        command.contains("validate-media-worker-bridge.py") -> "Worker bridge"
        command.contains("validate-media-queue-actions.py") -> "Queue actions"
        command.contains("validate-media-queue-telemetry.py") -> "Queue telemetry"
        command.contains("validate-media-dispatch-control-tower.py") -> "Dispatch control tower"
        command.contains("validate-media-download-engine-hardening.py") -> "Engine hardening"
        command.contains("validate-media-execution-library.py") -> "Execution library"
        command.contains("validate-media-resolver-player.py") -> "Resolver player"
        command.contains("validate-browser-removal-phase-5.py") -> "Browser persistence cleanup"
        command.contains("validate-browser-removal-phase-4.py") -> "Browser runtime removal"
        command.contains("validate-browser-removal-phase-3.py") -> "External handoff replacement"
        command.contains("validate-browser-removal-phase-2.py") -> "Neutral intake extraction"
        command.contains("validate-browser-removal-phase-0-1.py") -> "Downloader preservation"
        else -> command.substringAfterLast('/').ifBlank { "validator" }
    }

    companion object {
        const val FinalOverlayArtifact: String = "xdm_android_privacy_quality_final_gate_overlay_v2.zip"
        const val CurrentRoomSchema: Int = 20
        const val RequiredFilesystemRoots: Int = 4
        const val DefaultGradleCommand: String = "./gradlew -Pxdm.requireAria2Runtime=true --stacktrace :app:compileDebugKotlin :core-model:test :core-utils:test :transfer-api:test :browser-integration:testDebugUnitTest :storage:testDebugUnitTest :transfer-native:testDebugUnitTest :transfer-aria2:test :scheduler:testDebugUnitTest :media:test :persistence:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :browser-extension:test :browser-extension:jsTest :browser-extension:validateFirefoxExtension :app:checkBrowserIntegration assembleDebug :app:assembleDebugAndroidTest"
        val REQUIRED_FULL_VALIDATION_TASKS: List<String> = listOf(
            ":core-model:test",
            ":media:test",
            ":persistence:testDebugUnitTest",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
            "assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":browser-extension:validateFirefoxExtension",
        )

        fun defaultValidatorCommands(): List<String> = listOf(
            "bash tools/run-final-release-gate.sh --ci",
            "python3 tools/validate-browser-removal-phase-0-1.py",
            "python3 tools/validate-browser-removal-phase-2.py",
            "python3 tools/validate-browser-removal-phase-3.py",
            "python3 tools/validate-browser-removal-phase-4.py",
            "python3 tools/validate-browser-removal-phase-5.py",
            "python3 tools/validate-media-resolver-player.py",
            "python3 tools/validate-media-execution-library.py",
            "python3 tools/validate-media-download-engine-hardening.py",
            "python3 tools/validate-media-dispatch-control-tower.py",
            "python3 tools/validate-media-queue-telemetry.py",
            "python3 tools/validate-media-queue-actions.py",
            "python3 tools/validate-media-worker-bridge.py",
            "python3 tools/validate-media-termux-runtime-adapter.py",
            "python3 tools/validate-media-native-direct-download-engine.py",
            "python3 tools/validate-media-offline-library-v2.py",
            "python3 tools/validate-media-player-diagnostics.py",
            "python3 tools/validate-media-capture-quality.py",
            "python3 tools/validate-media-session-privacy-audit.py",
            "python3 tools/validate-media-mobile-polish.py",
            "python3 tools/validate-media-final-validation-gate.py",
        )
    }
}

private fun redactKnownSecrets(value: String): String = value
    .replace(Regex("(?i)(authorization|cookie|x-api-key|token|signature|password|secret)=([^\\s&]+)"), "$1=<redacted>")
    .replace(Regex("(?i)(Bearer|Basic)\\s+(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})"), "$1 <redacted>")

private fun containsKnownSecret(value: String): Boolean {
    val lowered = value.lowercase()
    return listOf("authorization=", "cookie=", "bearer ", "x-api-key=", "password=", "secret=", "signature=", "token=").any { lowered.contains(it) }
}
