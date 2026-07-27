package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSecurityModelsTest {
    @Test
    fun redactsBearerTokensSensitiveHeadersAndQuerySecrets() {
        val headers = PrivacyDiagnosticsRedactor.redactHeaders(
            "Authorization: Bearer abcdefghijklmnop\nX-CSRF-Token: topsecret\nUser-Agent: XDM?token=secret",
        )

        assertEquals("authorization: <redacted>\nx-csrf-token: <redacted>\nUser-Agent: XDM?token=<redacted>", headers)
        assertEquals("https://example.test/video.mp4?token=<redacted>&quality=1080", PrivacyDiagnosticsRedactor.redactUrl("https://example.test/video.mp4?token=secret&quality=1080"))
    }

    @Test
    fun releaseGateBlocksUnexpectedSchemaButAllowsDebugBuilds() {
        val report = ReleaseSecurityGate.evaluate(
            versionName = "0.14.0-alpha01",
            schemaVersion = 13,
            buildType = "debug",
            debuggable = true,
            privacySafeDiagnostics = true,
            releaseSigningConfigured = false,
        )

        assertFalse(report.releaseReady)
        assertEquals(1, report.blockingCount)
        assertTrue(report.findings.any { it.id == "database.schema" })
    }

    @Test
    fun cleanReleaseGateProducesInfoFinding() {
        val report = ReleaseSecurityGate.evaluate(
            versionName = "0.14.0-alpha01",
            schemaVersion = 14,
            buildType = "release",
            debuggable = false,
            privacySafeDiagnostics = true,
            releaseSigningConfigured = true,
        )

        assertTrue(report.releaseReady)
        assertEquals("Release gate checks are clean", report.summary)
        assertTrue(report.findings.any { it.severity == ReleaseSecuritySeverity.Info })
    }
}
