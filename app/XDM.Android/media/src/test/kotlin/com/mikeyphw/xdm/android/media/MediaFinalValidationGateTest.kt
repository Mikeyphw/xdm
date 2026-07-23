package com.mikeyphw.xdm.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFinalValidationGateTest {
    @Test
    fun finalGateDashboardBlocksLeaksAndRequiresGradleValidation() {
        val dashboard = MediaFinalValidationGatePlanner().dashboard(
            implementedPhases = (18..33).toList(),
            mediaMobilePolish = mobilePolish(secretSafe = true),
            privacyAudit = privacy(secretSafe = true),
            captureQuality = captureQuality(secretSafe = true),
            playerReports = emptyList(),
            library = library(secretSafe = true),
            termuxRuntime = termux(secretSafe = true),
            nativeDirect = native(secretSafe = true),
            validatorCommands = MediaFinalValidationGatePlanner.defaultValidatorCommands(),
            fullValidationEnabled = true,
            noNewTopLevelRoutes = true,
            keepDebugSymbolsProtected = true,
            warningsAsErrors = true,
        )

        assertTrue(dashboard.readyForFullValidation)
        assertTrue(dashboard.secretSafe)
        assertTrue(dashboard.warningGate)
        assertEquals(0, dashboard.blockerCount)
        assertTrue(dashboard.commands.any { it.command.contains("validate-media-final-validation-gate.py") })
        assertTrue(dashboard.commands.any { it.command.contains("lintDebug") })
    }

    @Test
    fun finalGateBlocksSecretLeakAndMissingPhase() {
        val dashboard = MediaFinalValidationGatePlanner().dashboard(
            implementedPhases = (18..32).toList(),
            mediaMobilePolish = mobilePolish(secretSafe = false),
            privacyAudit = privacy(secretSafe = false),
            captureQuality = captureQuality(secretSafe = false),
            playerReports = emptyList(),
            library = library(secretSafe = false),
            termuxRuntime = termux(secretSafe = false),
            nativeDirect = native(secretSafe = false),
            validatorCommands = listOf("python3 tools/validate-media-final-validation-gate.py"),
            gradleCommand = "./gradlew assembleDebug authorization=BearerSecret",
            fullValidationEnabled = false,
            noNewTopLevelRoutes = false,
            keepDebugSymbolsProtected = false,
            warningsAsErrors = false,
        )

        assertFalse(dashboard.readyForFullValidation)
        assertFalse(dashboard.secretSafe)
        assertTrue(dashboard.blockerCount >= 4)
        assertTrue(dashboard.checks.any { it.id == "phase-ledger" && it.blocking })
        assertTrue(dashboard.commands.first().safePreview.contains("validate-media-final-validation-gate.py"))
    }

    private fun mobilePolish(secretSafe: Boolean): MediaMobilePolishDashboard = MediaMobilePolishDashboard(
        mode = MediaMobileSurfaceMode.CompactPhone,
        currentJob = MediaMobileCurrentJobSummary(
            captureId = null,
            title = "No active media job",
            statusLabel = "Idle",
            progressLabel = "0 active",
            primaryActionLabel = "Browse or share media",
            attentionRequired = false,
            safeDiagnostic = "secret-safe",
        ),
        sections = listOf(
            MediaMobileSection(
                key = "sticky",
                title = "Sticky current job summary",
                priority = MediaMobileSectionPriority.Sticky,
                summary = "ready",
                recommendedMaxRows = 1,
                collapsedByDefault = false,
                accessibilityLabel = "Sticky current job summary",
            ),
        ),
        recommendations = listOf(
            MediaMobileRecommendation("Accessibility", "labels present", MediaMobilePolishSignal.AccessibilityLabels, blocking = false),
            MediaMobileRecommendation("Foldable", "two-pane ready", MediaMobilePolishSignal.FoldableReady, blocking = false),
        ),
        visiblePrimarySectionCount = 1,
        collapsedDiagnosticsCount = 0,
        attentionCount = 0,
        emptyStateLabel = "ready",
        noTinyScrollIslands = true,
        accessibilityReady = true,
        foldableReady = true,
        secretSafe = secretSafe,
    )

    private fun privacy(secretSafe: Boolean): MediaSessionPrivacyAuditDashboard = MediaSessionPrivacyAuditDashboard(
        findings = emptyList(),
        blockerCount = if (secretSafe) 0 else 1,
        reviewCount = 0,
        cleanupDueCount = 0,
        cleanupVerifiedCount = 1,
        scannedSurfaceCount = 8,
        durableSecretSafe = secretSafe,
        transientCleanupHealthy = secretSafe,
    )

    private fun captureQuality(secretSafe: Boolean): BrowserCaptureQualityDashboard = BrowserCaptureQualityDashboard(
        rows = emptyList(),
        treasureCount = 0,
        noiseCount = 0,
        duplicateCount = 0,
        refreshCount = 0,
        protectedCount = 0,
        liveCount = 0,
        groupedHosts = emptyList(),
        secretSafe = secretSafe,
    )

    private fun library(secretSafe: Boolean): OfflineLibraryV2Dashboard = OfflineLibraryV2Dashboard(
        rows = emptyList(),
        filterState = OfflineLibraryV2FilterState(),
        visibleCount = 0,
        playableCount = 0,
        audioCount = 0,
        videoCount = 0,
        failedCount = 0,
        missingCount = 0,
        cleanupCount = 0,
        sourceHosts = emptyList(),
        secretSafe = secretSafe,
    )

    private fun termux(secretSafe: Boolean): TermuxRuntimeDashboard = TermuxRuntimeDashboard(
        plans = emptyList(),
        launchableCount = 0,
        missingToolCount = 0,
        cleanupArmedCount = 0,
        secretSafe = secretSafe,
    )

    private fun native(secretSafe: Boolean): NativeDirectDashboard = NativeDirectDashboard(
        plans = emptyList(),
        readyCount = 0,
        resumeCount = 0,
        permissionCount = 0,
        unsupportedCount = 0,
        secretSafe = secretSafe,
    )
}
