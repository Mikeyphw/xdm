package com.mikeyphw.xdm.android.media

/**
 * Phase 33 Media Final Validation Gate.
 *
 * This planner is deliberately pure Kotlin. It does not execute Gradle, shell commands, workers,
 * WebView hooks, or native downloads. Its job is to model the final gate surface that stitches the
 * Phase 18-32 media stack back into full validation: all validators present, Gradle/lint expected,
 * no secret persistence, no new top-level routes, and Termux/chroot strip hardening retained.
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
    val readyForFullValidation: Boolean,
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
        implementedPhases: List<Int>,
        mediaMobilePolish: MediaMobilePolishDashboard,
        privacyAudit: MediaSessionPrivacyAuditDashboard,
        captureQuality: BrowserCaptureQualityDashboard,
        playerReports: List<MediaPlayerDiagnosticReport>,
        library: OfflineLibraryV2Dashboard,
        termuxRuntime: TermuxRuntimeDashboard,
        nativeDirect: NativeDirectDashboard,
        validatorCommands: List<String> = defaultValidatorCommands(),
        gradleCommand: String = DefaultGradleCommand,
        fullValidationEnabled: Boolean = true,
        noNewTopLevelRoutes: Boolean = true,
        keepDebugSymbolsProtected: Boolean = true,
        warningsAsErrors: Boolean = true,
    ): MediaFinalValidationDashboard {
        val expectedPhases = (18..33).toList()
        val phaseSet = implementedPhases.toSet()
        val missingPhases = expectedPhases.filterNot { it in phaseSet }
        val commands = validatorCommands.map { command -> MediaFinalValidationCommand(labelForCommand(command), command, required = true) } +
            MediaFinalValidationCommand("Gradle build/test/lint", gradleCommand, required = true)
        val commandText = commands.joinToString("\n") { it.safePreview }
        val secretSafe = mediaMobilePolish.secretSafe &&
            privacyAudit.durableSecretSafe &&
            privacyAudit.transientCleanupHealthy &&
            captureQuality.secretSafe &&
            library.secretSafe &&
            termuxRuntime.secretSafe &&
            nativeDirect.secretSafe &&
            playerReports.all { it.sourceSafe && it.protectedDiagnosticOnly == (it.bucket == MediaPlayerDiagnosticBucket.ProtectedMedia || it.protectedDiagnosticOnly) } &&
            !containsKnownSecret(commandText)
        val checks = listOf(
            MediaFinalValidationCheck(
                id = "phase-ledger",
                title = "Phase 18-33 ledger complete",
                surface = MediaFinalValidationSurface.PhaseLedger,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = missingPhases.isEmpty() && implementedPhases.lastOrNull() == 33,
                summary = if (missingPhases.isEmpty()) "all media phases recorded" else "missing ${missingPhases.joinToString()}",
                evidence = "implemented=${implementedPhases.joinToString()}",
            ),
            MediaFinalValidationCheck(
                id = "static-validators",
                title = "All media validators wired",
                surface = MediaFinalValidationSurface.StaticValidators,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = validatorCommands.any { it.contains("validate-media-final-validation-gate.py") } && validatorCommands.count { it.contains("validate-media-") } >= 15,
                summary = "${validatorCommands.size} validator commands modeled",
                evidence = validatorCommands.joinToString(" | "),
            ),
            MediaFinalValidationCheck(
                id = "gradle-gate",
                title = "Gradle/lint/test gate restored",
                surface = MediaFinalValidationSurface.GradleGate,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = fullValidationEnabled && warningsAsErrors && gradleCommand.contains("lintDebug") && gradleCommand.contains(":media:test") && gradleCommand.contains("testDebugUnitTest"),
                summary = if (fullValidationEnabled) "full validation expected" else "validation still deferred",
                evidence = gradleCommand,
            ),
            MediaFinalValidationCheck(
                id = "privacy-leak-scan",
                title = "No durable cookies, headers, or tokens",
                surface = MediaFinalValidationSurface.PrivacyLeakScan,
                severity = MediaFinalValidationSeverity.Blocker,
                passing = secretSafe && privacyAudit.blockerCount == 0,
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
        val ready = checks.none { it.blocking } && fullValidationEnabled
        return MediaFinalValidationDashboard(
            checks = checks,
            commands = commands,
            implementedPhaseCount = implementedPhases.distinct().size,
            expectedFinalPhase = 33,
            readyForFullValidation = ready,
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
        command.contains("validate-media-browser-capture-quality.py") -> "Capture quality"
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
        command.contains("validate-browser-media-continuity.py") -> "Browser continuity"
        command.contains("validate-browser-media-downloader.py") -> "Browser downloader"
        else -> command.substringAfterLast('/').ifBlank { "validator" }
    }

    companion object {
        const val DefaultGradleCommand: String = "./gradlew -Pxdm.requireAria2Runtime=true --stacktrace lintDebug lintBeta :media:test :transfer-api:test :storage:test :transfer-native:test :transfer-aria2:test :scheduler:test :persistence:testDebugUnitTest testDebugUnitTest assembleDebug assembleBeta"

        fun defaultValidatorCommands(): List<String> = listOf(
            "python3 tools/validate-browser-media-downloader.py",
            "python3 tools/validate-browser-media-continuity.py",
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
            "python3 tools/validate-media-browser-capture-quality.py",
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
