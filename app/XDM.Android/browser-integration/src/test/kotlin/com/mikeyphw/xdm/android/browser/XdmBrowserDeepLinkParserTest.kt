package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XdmBrowserDeepLinkParserTest {
    @Test
    fun captureLinkProducesBrowserExtensionMediaDraft() {
        val link = deepLink(
            scheme = XdmBrowserDeepLinkContract.ReleaseScheme,
            host = XdmBrowserDeepLinkContract.CaptureHost,
            url = "https://cdn.example.test/master.m3u8?token=signed-value",
            page = "https://example.test/watch/42",
            title = "Example stream",
            fileName = "episode-42.m3u8",
            mime = "application/vnd.apple.mpegurl; charset=utf-8",
            kind = "hls",
        )

        val payload = XdmBrowserDeepLinkParser.parse(link, XdmBrowserDeepLinkContract.ReleaseScheme)
        assertNotNull(payload)
        payload!!
        assertEquals(AutomationCommandAction.CaptureMedia, payload.action)
        assertEquals("https://cdn.example.test/master.m3u8?token=signed-value", payload.url)
        assertEquals("https://example.test/watch/42", payload.pageUrl)
        assertEquals("Example stream", payload.pageTitle)
        assertEquals("episode-42.m3u8", payload.fileName)
        assertEquals("application/vnd.apple.mpegurl", payload.mimeType)
        assertEquals("hls", payload.mediaKind)

        val draft = payload.toAutomationCommandDraft(originPackage = "org.ironfoxoss.ironfox")
        assertEquals(AutomationCommandSource.BrowserExtension, draft.source)
        assertEquals(AutomationCommandAction.CaptureMedia, draft.action)
        assertEquals("org.ironfoxoss.ironfox", draft.originPackage)
        assertNull(draft.rawHeaders)
    }

    @Test
    fun addLinkProducesReviewFirstAddDraft() {
        val link = deepLink(
            scheme = XdmBrowserDeepLinkContract.ReleaseScheme,
            host = XdmBrowserDeepLinkContract.AddHost,
            url = "https://example.test/releases/app.apk",
        )

        val payload = XdmBrowserDeepLinkParser.parse(link, XdmBrowserDeepLinkContract.ReleaseScheme)
        assertEquals(AutomationCommandAction.PromptAddDownload, payload?.action)
        assertEquals(AutomationCommandSource.BrowserExtension, payload?.toAutomationCommandDraft()?.source)
    }

    @Test
    fun everyBuildVariantUsesOnlyItsOwnScheme() {
        XdmBrowserDeepLinkContract.BuildVariantSchemes.forEach { scheme ->
            val link = deepLink(scheme = scheme, host = "capture", url = "https://example.test/video.mp4")
            assertNotNull("Expected $scheme to parse", XdmBrowserDeepLinkParser.parse(link, scheme))
            val other = XdmBrowserDeepLinkContract.BuildVariantSchemes.first { it != scheme }
            assertNull("$scheme must not parse as $other", XdmBrowserDeepLinkParser.parse(link, other))
        }
    }

    @Test
    fun rejectsUnsafeNestedAndCredentialBearingUrls() {
        listOf(
            "javascript:alert(1)",
            "data:text/plain,hello",
            "file:///sdcard/private",
            "content://downloads/42",
            "blob:https://example.test/id",
            "intent:https://example.test/#Intent;end",
            "xdmdownload://capture?v=1&url=https%3A%2F%2Fexample.test%2Fvideo.mp4",
            "https://user:password@example.test/private.mp4",
        ).forEach { unsafe ->
            val link = deepLink(scheme = "xdmdownload", host = "capture", url = unsafe)
            assertNull("Unsafe URL accepted: $unsafe", XdmBrowserDeepLinkParser.parse(link, "xdmdownload"))
        }
    }

    @Test
    fun rejectsMalformedVersionHostDuplicatesAndOversizedUrls() {
        val base = "xdmdownload://capture?v=1&url=${encode("https://example.test/video.mp4")}"
        assertNull(XdmBrowserDeepLinkParser.parse(base.replace("v=1", "v=2"), "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse(base.replace("capture", "unknown"), "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse("$base&url=${encode("https://example.test/other.mp4")}", "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse("xdmdownload://capture?v=1", "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse("xdmdownload://capture/path?v=1&url=${encode("https://example.test/v.mp4")}", "xdmdownload"))

        val oversizedUrl = "https://example.test/" + "x".repeat(XdmBrowserDeepLinkContract.MaxMediaUrlBytes)
        assertNull(XdmBrowserDeepLinkParser.parse(deepLink("xdmdownload", "capture", oversizedUrl), "xdmdownload"))
        val oversizedEnvelope = "xdmdownload://capture?v=1&url=" + "x".repeat(XdmBrowserDeepLinkContract.MaxDeepLinkBytes)
        assertNull(XdmBrowserDeepLinkParser.parse(oversizedEnvelope, "xdmdownload"))
    }

    @Test
    fun rejectsUnsafePageMetadataInsteadOfSmugglingIt() {
        val link = deepLink(
            scheme = "xdmdownload",
            host = "capture",
            url = "https://example.test/video.mp4",
            page = "javascript:alert(1)",
        )
        assertNull(XdmBrowserDeepLinkParser.parse(link, "xdmdownload"))
    }

    @Test
    fun truncatesDisplayMetadataAndSanitizesFilename() {
        val title = "Title\u0000" + "x".repeat(400)
        val fileName = "folder\\nested/" + "v".repeat(200) + ".mp4"
        val link = deepLink(
            scheme = "xdmdownload",
            host = "capture",
            url = "https://example.test/video.mp4",
            title = title,
            fileName = fileName,
            mime = "VIDEO/MP4; codecs=avc1",
            kind = "HLS-LIVE",
        )

        val payload = XdmBrowserDeepLinkParser.parse(link, "xdmdownload")!!
        assertEquals(XdmBrowserDeepLinkContract.MaxPageTitleCharacters, payload.pageTitle?.length)
        assertEquals(XdmBrowserDeepLinkContract.MaxFileNameCharacters, payload.fileName?.length)
        assertTrue(payload.fileName.orEmpty().none { it == '/' || it == '\\' })
        assertEquals("video/mp4", payload.mimeType)
        assertEquals("hls-live", payload.mediaKind)
    }

    @Test
    fun repeatedDeliveryUsesExistingStableIdempotencyContract() {
        val link = deepLink(
            scheme = "xdmdownload",
            host = "capture",
            url = "https://example.test/video.mp4?signature=abc",
            page = "https://example.test/watch",
            fileName = "video.mp4",
        )
        val first = XdmBrowserDeepLinkParser.parse(link, "xdmdownload")!!.toAutomationCommandDraft()
        val second = XdmBrowserDeepLinkParser.parse(link, "xdmdownload")!!.toAutomationCommandDraft()
        assertEquals(first.stableIdempotencyKey, second.stableIdempotencyKey)
    }

    private fun deepLink(
        scheme: String,
        host: String,
        url: String,
        page: String? = null,
        title: String? = null,
        fileName: String? = null,
        mime: String? = null,
        kind: String? = null,
    ): String {
        val params = linkedMapOf("v" to "1", "url" to url)
        page?.let { params["page"] = it }
        title?.let { params["title"] = it }
        fileName?.let { params["filename"] = it }
        mime?.let { params["mime"] = it }
        kind?.let { params["kind"] = it }
        return "$scheme://$host?" + params.entries.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    @Test
    fun phaseFiveParsesStableSessionAndFrameMetadata() {
        val raw = "xdmdownload://capture?v=1&url=https%3A%2F%2Fcdn.example%2Fvideo.mp4&stableMediaId=media-session-abcdef123456&sessionRevision=12345&frame=https%3A%2F%2Fplayer.example%2Fembed"
        val result = XdmBrowserDeepLinkParser.parseDetailed(raw, "xdmdownload") as XdmBrowserDeepLinkParseResult.Accepted
        assertEquals("media-session-abcdef123456", result.payload.stableMediaId)
        assertEquals(12345L, result.payload.sessionRevision)
        assertEquals("https://player.example/embed", result.payload.frameUrl)
    }

}
