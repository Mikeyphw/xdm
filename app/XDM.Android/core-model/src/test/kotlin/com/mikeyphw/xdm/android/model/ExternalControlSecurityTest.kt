package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalControlSecurityTest {
    @Test
    fun persistableUrlRedactsCredentialQueriesWithoutChangingPublicFields() {
        val safe = ExternalUrlPolicy.persistableUrl(
            "https://cdn.example/video.mp4?quality=1080&token=secret-value&sig=abc123",
        )
        assertEquals(
            "https://cdn.example/video.mp4?quality=1080&token=REDACTED&sig=REDACTED",
            safe,
        )
        assertFalse(safe.orEmpty().contains("secret-value"))
        assertTrue(ExternalUrlPolicy.hasCredentialBearingQuery("https://cdn.example/file?access_token=x"))
    }

    @Test
    fun publicQueryNamesContainingSecretSubstringsRemainVisible() {
        val safe = ExternalUrlPolicy.persistableUrl(
            "https://cdn.example/video.mp4?quality=1080&author=alice&monkey=capuchin&api_key=secret",
        )
        assertEquals(
            "https://cdn.example/video.mp4?quality=1080&author=alice&monkey=capuchin&api_key=REDACTED",
            safe,
        )
    }

    @Test
    fun encodedCredentialQueryNamesAreRedacted() {
        assertEquals(
            "https://cdn.example/file?%74oken=REDACTED&quality=high",
            ExternalUrlPolicy.persistableUrl("https://cdn.example/file?%74oken=secret&quality=high"),
        )
    }

    @Test
    fun privilegedNetworkTargetsRequireReview() {
        assertEquals(ExternalNetworkTarget.Loopback, ExternalUrlPolicy.classifyNetworkTarget("https://127.0.0.1/file"))
        assertEquals(ExternalNetworkTarget.PrivateAddress, ExternalUrlPolicy.classifyNetworkTarget("https://192.168.1.1/file"))
        assertEquals(ExternalNetworkTarget.LinkLocal, ExternalUrlPolicy.classifyNetworkTarget("https://169.254.2.3/file"))
        assertEquals(ExternalNetworkTarget.LocalHostname, ExternalUrlPolicy.classifyNetworkTarget("https://router.local/file"))
        assertEquals(ExternalNetworkTarget.Public, ExternalUrlPolicy.classifyNetworkTarget("https://downloads.example/file"))
    }

    @Test
    fun urlUserInfoAndFragmentsAreRejected() {
        assertNull(ExternalUrlPolicy.normalizedUrl("https://user:pass@example.com/file"))
        assertNull(ExternalUrlPolicy.normalizedUrl("https://example.com/file#secret"))
    }

    @Test
    fun exportRedactionRunsIndependentlyOfCaptureRedaction() {
        val line = """{"Authorization":"Bearer abcdefghijklmnop","url":"https://x.test/f?token=secret"}"""
        val redacted = DebugRedactor.redactExportLine(line)
        assertFalse(redacted.contains("abcdefghijklmnop"))
        assertFalse(redacted.contains("token=secret"))
        assertTrue(redacted.contains("<redacted>"))
    }
}
