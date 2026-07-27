package com.mikeyphw.xdm.android.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XdmBrowserDeepLinkDiagnosticsTest {
    @Test
    fun nonXdmLinksRemainNotApplicable() {
        val result = XdmBrowserDeepLinkParser.parseDetailed(
            "https://example.test/video.mp4?token=opaque-value",
            XdmBrowserDeepLinkContract.ReleaseScheme,
        )
        assertEquals(XdmBrowserDeepLinkParseResult.NotApplicable, result)
    }

    @Test
    fun variantMismatchHasBoundedReasonWithoutPayload() {
        val result = XdmBrowserDeepLinkParser.parseDetailed(
            deepLink(XdmBrowserDeepLinkContract.DebugScheme, "https://example.test/video.mp4?token=opaque-value"),
            XdmBrowserDeepLinkContract.ReleaseScheme,
        )
        assertEquals(
            XdmBrowserDeepLinkRejection.VariantMismatch,
            (result as XdmBrowserDeepLinkParseResult.Rejected).reason,
        )
        assertTrue(result.reason.userMessage.none { it == '?' || it == '&' })
    }

    @Test
    fun unsupportedContractIsActionable() {
        val result = XdmBrowserDeepLinkParser.parseDetailed(
            "xdmdownload://capture?v=99&url=${encode("https://example.test/video.mp4")}",
            XdmBrowserDeepLinkContract.ReleaseScheme,
        )
        assertEquals(
            XdmBrowserDeepLinkRejection.UnsupportedContract,
            (result as XdmBrowserDeepLinkParseResult.Rejected).reason,
        )
    }

    @Test
    fun acceptedResultRetainsTheSanitizedPayload() {
        val result = XdmBrowserDeepLinkParser.parseDetailed(
            deepLink(XdmBrowserDeepLinkContract.ReleaseScheme, "https://cdn.example.test/master.m3u8?signature=abc"),
            XdmBrowserDeepLinkContract.ReleaseScheme,
        )
        val accepted = result as XdmBrowserDeepLinkParseResult.Accepted
        assertEquals("https://cdn.example.test/master.m3u8?signature=abc", accepted.payload.url)
    }

    private fun deepLink(scheme: String, url: String): String =
        "$scheme://capture?v=1&url=${encode(url)}&kind=hls"

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
