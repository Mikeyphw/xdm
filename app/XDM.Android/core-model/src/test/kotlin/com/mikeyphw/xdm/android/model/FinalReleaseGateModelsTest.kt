package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalReleaseGateModelsTest {
    @Test
    fun cleanSignedReleaseReportPassesFinalGate() {
        val report = FinalPublicReleaseGate.evaluate(
            versionName = "0.17.0-rc01",
            versionCode = 18,
            packageId = "com.mikeyphw.xdm.android",
            schemaVersion = 14,
            buildType = "release",
            releaseSafetyReady = true,
            installUpdateReady = true,
            diagnosticsRedacted = true,
            aria2PayloadVerified = true,
            staticValidatorsComplete = true,
            releaseDocsComplete = true,
            noNewTopLevelRoutes = true,
            fullValidationPassed = true,
            releaseSigningConfigured = true,
        )

        assertTrue(report.readyForPublicRelease)
        assertEquals(0, report.blockingCount)
        assertTrue(report.summary.contains("clean"))
        assertTrue(report.redactedSummary().contains("0.17.0-rc01"))
    }

    @Test
    fun alphaOrPartialValidationBlocksFinalRelease() {
        val report = FinalPublicReleaseGate.evaluate(
            versionName = "0.16.0-alpha01",
            versionCode = 17,
            packageId = "com.mikeyphw.xdm.android.debug",
            schemaVersion = 13,
            buildType = "release",
            releaseSafetyReady = false,
            installUpdateReady = false,
            diagnosticsRedacted = false,
            aria2PayloadVerified = false,
            staticValidatorsComplete = false,
            releaseDocsComplete = false,
            noNewTopLevelRoutes = false,
            fullValidationPassed = false,
            releaseSigningConfigured = false,
        )

        assertFalse(report.readyForPublicRelease)
        assertTrue(report.checks.any { it.id == "version.alpha" && it.severity == FinalReleaseGateSeverity.Blocking })
        assertTrue(report.checks.any { it.id == "full.validation" && it.severity == FinalReleaseGateSeverity.Blocking })
        assertTrue(report.checks.any { it.id == "signing.release" && it.severity == FinalReleaseGateSeverity.Blocking })
    }
    @Test
    fun debugBuildKeepsUnrunFullValidationAsWarning() {
        val report = FinalPublicReleaseGate.evaluate(
            versionName = "0.18.0-rc01",
            versionCode = 19,
            packageId = "com.mikeyphw.xdm.android",
            schemaVersion = 14,
            buildType = "debug",
            releaseSafetyReady = true,
            installUpdateReady = true,
            diagnosticsRedacted = true,
            aria2PayloadVerified = false,
            staticValidatorsComplete = true,
            releaseDocsComplete = true,
            noNewTopLevelRoutes = true,
            fullValidationPassed = false,
            releaseSigningConfigured = false,
        )

        assertEquals(0, report.blockingCount)
        assertTrue(report.checks.any { it.id == "full.validation" && it.severity == FinalReleaseGateSeverity.Warning })
    }

}
