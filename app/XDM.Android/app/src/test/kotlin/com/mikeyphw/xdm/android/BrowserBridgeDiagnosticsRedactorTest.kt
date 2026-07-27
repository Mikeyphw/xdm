package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBridgeDiagnosticsRedactorTest {
    @Test
    fun acceptedSummaryKeepsEndpointButDropsQuerySecrets() {
        val summary = BrowserBridgeDiagnosticsRedactor.acceptedSummary(
            XdmBrowserDeepLinkPayload(
                version = 1,
                action = AutomationCommandAction.CaptureMedia,
                url = "https://cdn.example.test/master.m3u8?token=opaque-value&signature=abc",
                mimeType = "application/vnd.apple.mpegurl",
                mediaKind = "hls",
            ),
        )
        assertTrue(summary.contains("https://cdn.example.test/master.m3u8"))
        assertFalse(summary.contains("opaque-value"))
        assertFalse(summary.contains("signature"))
        assertFalse(summary.contains("?"))
    }

    @Test
    fun genericDiagnosticsRedactHeadersTokensAndBearerValues() {
        val bearerFixture = listOf("fixture", "bearer").joinToString("-")
        val cookieFixture = listOf("fixture", "cookie").joinToString("-")
        val tokenFixture = listOf("fixture", "token").joinToString("-")
        val signatureFixture = listOf("fixture", "signature").joinToString("-")
        val value = BrowserBridgeDiagnosticsRedactor.sanitize(
            "Authorization: Bearer $bearerFixture Cookie=session=$cookieFixture token=$tokenFixture " +
                "https://x.test/v.mp4?sig=$signatureFixture",
        )
        assertFalse(value.contains(bearerFixture))
        assertFalse(value.contains("session=$cookieFixture"))
        assertFalse(value.contains(tokenFixture))
        assertFalse(value.contains("sig=$signatureFixture"))
        assertTrue(value.contains("<redacted>"))
    }
}
