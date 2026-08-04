package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseReadinessModelsTest {
    @Test
    fun cleanInstallReadinessReportHasNoBlockingChecks() {
        val report = ReleaseInstallReadinessGate.evaluate(
            versionName = "0.21.0-rc01",
            versionCode = 22,
            packageId = "com.mikeyphw.xdm.android",
            schemaVersion = 17,
            buildType = "release",
            releaseSafetyComplete = true,
            recoverySurfaceReady = true,
            diagnosticsExportRedacted = true,
            aria2PayloadGateRetained = true,
            updateKeepsPackageIdentity = true,
            releaseSigningConfigured = true,
        )

        assertTrue(report.readyForInstall)
        assertEquals(0, report.blockingCount)
        assertTrue(report.summary.contains("clean"))
        assertTrue(report.redactedSummary().contains("com.mikeyphw.xdm.android"))
    }

    @Test
    fun staleVersionAndSchemaBlockUpdateReadiness() {
        val report = ReleaseInstallReadinessGate.evaluate(
            versionName = "0.20.0-alpha01",
            versionCode = 21,
            packageId = "com.mikeyphw.xdm.android.debug",
            schemaVersion = 16,
            buildType = "release",
            releaseSafetyComplete = false,
            recoverySurfaceReady = false,
            diagnosticsExportRedacted = false,
            aria2PayloadGateRetained = false,
            updateKeepsPackageIdentity = false,
            releaseSigningConfigured = false,
        )

        assertFalse(report.readyForInstall)
        assertTrue(report.checks.any { it.id == "version.phase16" && it.severity == ReleaseReadinessSeverity.Blocking })
        assertTrue(report.checks.any { it.id == "database.schema" && it.severity == ReleaseReadinessSeverity.Blocking })
        assertTrue(report.checks.any { it.id == "signing.release" && it.severity == ReleaseReadinessSeverity.Warning })
    }
}
