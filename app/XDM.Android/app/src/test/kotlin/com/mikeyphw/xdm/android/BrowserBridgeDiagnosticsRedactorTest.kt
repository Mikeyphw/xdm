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
        val value = BrowserBridgeDiagnosticsRedactor.sanitize(
            "Authorization: Bearer abc.def Cookie=session=private-value token=xyz https://x.test/v.mp4?sig=123",
        )
        assertFalse(value.contains("abc.def"))
        assertFalse(value.contains("session=private-value"))
        assertFalse(value.contains("xyz"))
        assertFalse(value.contains("sig=123"))
        assertTrue(value.contains("<redacted>"))
    }
}
