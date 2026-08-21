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
    fun encryptedCaptureLinkProducesBrowserExtensionMediaDraftWithoutPlaintextMedia() {
        val link = encryptedCaptureLink(XdmBrowserDeepLinkContract.ReleaseScheme)

        val payload = XdmBrowserDeepLinkParser.parse(link, XdmBrowserDeepLinkContract.ReleaseScheme)
        assertNotNull(payload)
        payload!!
        assertEquals(AutomationCommandAction.CaptureMedia, payload.action)
        assertTrue(payload.hasEncryptedCaptureEnvelope)
        assertNull(payload.url)
        assertNull(payload.pageUrl)
        assertNull(payload.fileName)
        assertNull(payload.mimeType)

        val draft = payload.toAutomationCommandDraft(originPackage = "org.ironfoxoss.ironfox")
        assertEquals(AutomationCommandSource.BrowserExtension, draft.source)
        assertEquals(AutomationCommandAction.CaptureMedia, draft.action)
        assertEquals("org.ironfoxoss.ironfox", draft.originPackage)
        assertNull(draft.url)
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
            val link = encryptedCaptureLink(scheme)
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
            val link = deepLink(scheme = "xdmdownload", host = "add", url = unsafe)
            assertNull("Unsafe URL accepted: $unsafe", XdmBrowserDeepLinkParser.parse(link, "xdmdownload"))
        }
    }

    @Test
    fun rejectsMalformedVersionHostDuplicatesAndOversizedUrls() {
        val base = "xdmdownload://add?v=1&url=${encode("https://example.test/video.mp4")}"
        assertNull(XdmBrowserDeepLinkParser.parse(base.replace("v=1", "v=99"), "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse(base.replace("add", "unknown"), "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse("$base&url=${encode("https://example.test/other.mp4")}", "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse("xdmdownload://add?v=1", "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse("xdmdownload://add/path?v=1&url=${encode("https://example.test/v.mp4")}", "xdmdownload"))

        val oversizedUrl = "https://example.test/" + "x".repeat(XdmBrowserDeepLinkContract.MaxMediaUrlBytes)
        assertNull(XdmBrowserDeepLinkParser.parse(deepLink("xdmdownload", "add", oversizedUrl), "xdmdownload"))
        val oversizedEnvelope = "xdmdownload://add?v=1&url=" + "x".repeat(XdmBrowserDeepLinkContract.MaxDeepLinkBytes)
        assertNull(XdmBrowserDeepLinkParser.parse(oversizedEnvelope, "xdmdownload"))
    }


    @Test
    fun encryptedV2CaptureEnvelopeParsesWithoutExposingMediaUrl() {
        val raw = "xdmdownload://capture?v=2&sid=browser-7-secure-session&kid=phase59-61-test-key&ek=QUJDREVGR0g&iv=QUJDREVGR0hJSktM&ct=QUJDREVGR0hJSktMTU5PUA"
        val payload = XdmBrowserDeepLinkParser.parse(raw, "xdmdownload")!!

        assertEquals(2, payload.version)
        assertEquals(AutomationCommandAction.CaptureMedia, payload.action)
        assertNull(payload.url)
        assertTrue(payload.hasEncryptedCaptureEnvelope)
        assertEquals("browser-7-secure-session", payload.captureSessionId)
        assertEquals("phase59-61-test-key", payload.captureKeyId)
    }

    @Test
    fun encryptedV2EnvelopeRejectsDuplicatesMissingTokensAndFutureVersion() {
        val valid = "xdmdownload://capture?v=2&sid=browser-7-secure-session&kid=phase59-61-test-key&ek=QUJDREVGR0g&iv=QUJDREVGR0hJSktM&ct=QUJDREVGR0hJSktMTU5PUA"
        assertNull(XdmBrowserDeepLinkParser.parse(valid + "&ct=duplicate", "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse(valid.replace("&ct=QUJDREVGR0hJSktMTU5PUA", ""), "xdmdownload"))
        assertNull(XdmBrowserDeepLinkParser.parse(valid.replace("v=2", "v=4"), "xdmdownload"))
    }

    @Test
    fun rejectsUnsafePageMetadataInsteadOfSmugglingIt() {
        val link = deepLink(
            scheme = "xdmdownload",
            host = "add",
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
            host = "add",
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
    fun repeatedAddDeliveryUsesExistingStableIdempotencyContract() {
        val link = deepLink(
            scheme = "xdmdownload",
            host = "add",
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

    private fun encryptedCaptureLink(scheme: String): String =
        "$scheme://capture?v=2&sid=browser-test-session&kid=browser-test-key&ek=QUJDREVGR0g&iv=QUJDREVGR0hJSktM&ct=QUJDREVGR0hJSktMTU5PUA"

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    @Test
    fun legacyPlaintextCaptureMetadataIsRejectedBySecureV2Contract() {
        val raw = "xdmdownload://capture?v=1&url=https%3A%2F%2Fcdn.example%2Fvideo.mp4&stableMediaId=media-session-abcdef123456&sessionRevision=12345&frame=https%3A%2F%2Fplayer.example%2Fembed"
        val result = XdmBrowserDeepLinkParser.parseDetailed(raw, "xdmdownload")
        assertEquals(
            XdmBrowserDeepLinkRejection.UnsafeEnvelope,
            (result as XdmBrowserDeepLinkParseResult.Rejected).reason,
        )
    }

    @Test
    fun addLinkParsesOptionalSizeDurationAndThumbnailMetadata() {
        val raw = "xdmdownload://add?v=1&url=https%3A%2F%2Fcdn.example%2Fmaster.m3u8&kind=hls&length=734003200&durationMs=630000&thumbnail=https%3A%2F%2Fcdn.example%2Fposter.jpg"
        val payload = XdmBrowserDeepLinkParser.parse(raw, "xdmdownload")!!

        assertEquals(734003200L, payload.contentLength)
        assertEquals(630000L, payload.durationMs)
        assertEquals("https://cdn.example/poster.jpg", payload.thumbnailUrl)

        val draft = payload.toAutomationCommandDraft()
        assertEquals(734003200L, draft.contentLength)
        assertEquals(630000L, draft.durationMs)
        assertEquals("https://cdn.example/poster.jpg", draft.thumbnailUrl)
    }

    @Test
    fun extensionCaptureParsesOptionalSizeDurationAndThumbnailMetadata() {
        val raw = "xdmdownload://capture?v=3&url=https%3A%2F%2Fcdn.example%2Fvideo.mp4&kind=video&length=734003200&durationMs=630000&thumbnail=https%3A%2F%2Fcdn.example%2Fposter.jpg"
        val payload = XdmBrowserDeepLinkParser.parse(raw, "xdmdownload")!!

        assertEquals(3, payload.version)
        assertEquals(734003200L, payload.contentLength)
        assertEquals(630000L, payload.durationMs)
        assertEquals("https://cdn.example/poster.jpg", payload.thumbnailUrl)
        val draft = payload.toAutomationCommandDraft()
        assertEquals(734003200L, draft.contentLength)
        assertEquals(630000L, draft.durationMs)
        assertEquals("https://cdn.example/poster.jpg", draft.thumbnailUrl)
    }

    @Test
    fun directV3CaptureCarriesExactMediaAndSanitizedSessionHeadersWithoutCryptoBinding() {
        val rawHeaders = "Referer: https://watch.example/episode\nCookie: session=abc\nX-Injected: no"
        val proposed = "User-Agent: TestBrowser/1\nAuthorization: Bearer token"
        val finalHeaders = "Origin: https://watch.example\nRange: bytes=0-"
        val raw = "xdmdownload://capture?" + listOf(
            "v=3",
            "url=${encode("https://cdn.example/media/master.m3u8?token=signed")}",
            "page=${encode("https://watch.example/episode")}",
            "mime=${encode("application/vnd.apple.mpegurl")}",
            "kind=hls",
            "headers=${encode(rawHeaders)}",
            "proposedHeaders=${encode(proposed)}",
            "finalHeaders=${encode(finalHeaders)}",
        ).joinToString("&")

        val payload = XdmBrowserDeepLinkParser.parse(raw, "xdmdownload")!!
        assertEquals(3, payload.version)
        assertEquals(AutomationCommandAction.CaptureMedia, payload.action)
        assertEquals("https://cdn.example/media/master.m3u8?token=signed", payload.url)
        assertEquals("https://watch.example/episode", payload.pageUrl)
        assertEquals("application/vnd.apple.mpegurl", payload.mimeType)
        assertTrue(!payload.hasEncryptedCaptureEnvelope)
        assertTrue(payload.rawHeaders.orEmpty().contains("cookie: session=abc"))
        assertTrue(!payload.rawHeaders.orEmpty().contains("X-Injected"))

        val draft = payload.toAutomationCommandDraft(originPackage = "org.ironfoxoss.ironfox")
        assertEquals(AutomationCommandSource.BrowserExtension, draft.source)
        assertEquals("https://cdn.example/media/master.m3u8?token=signed", draft.url)
        assertTrue(draft.proposedHeaders.orEmpty().contains("authorization: Bearer token"))
        assertTrue(draft.finalHeaders.orEmpty().contains("range: bytes=0-"))
    }

}
