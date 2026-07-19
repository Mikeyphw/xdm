package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseReadinessModelsTest {
    @Test
    fun cleanBetaReadinessReportHasNoBlockingChecks() {
        val report = ReleaseInstallReadinessGate.evaluate(
            versionName = "0.16.0-alpha01",
            versionCode = 17,
            packageId = "com.mikeyphw.xdm.android",
            schemaVersion = 13,
            buildType = "beta",
            releaseSafetyComplete = true,
            recoverySurfaceReady = true,
            diagnosticsExportRedacted = true,
            aria2PayloadGateRetained = true,
            updateKeepsPackageIdentity = true,
            releaseSigningConfigured = false,
        )

        assertTrue(report.readyForBetaInstall)
        assertEquals(0, report.blockingCount)
        assertTrue(report.summary.contains("clean"))
        assertTrue(report.redactedSummary().contains("com.mikeyphw.xdm.android"))
    }

    @Test
    fun staleVersionAndSchemaBlockUpdateReadiness() {
        val report = ReleaseInstallReadinessGate.evaluate(
            versionName = "0.15.0-alpha01",
            versionCode = 16,
            packageId = "com.mikeyphw.xdm.android.debug",
            schemaVersion = 14,
            buildType = "release",
            releaseSafetyComplete = false,
            recoverySurfaceReady = false,
            diagnosticsExportRedacted = false,
            aria2PayloadGateRetained = false,
            updateKeepsPackageIdentity = false,
            releaseSigningConfigured = false,
        )

        assertFalse(report.readyForBetaInstall)
        assertTrue(report.checks.any { it.id == "version.phase16" && it.severity == ReleaseReadinessSeverity.Blocking })
        assertTrue(report.checks.any { it.id == "database.schema" && it.severity == ReleaseReadinessSeverity.Blocking })
        assertTrue(report.checks.any { it.id == "signing.release" && it.severity == ReleaseReadinessSeverity.Warning })
    }
}
