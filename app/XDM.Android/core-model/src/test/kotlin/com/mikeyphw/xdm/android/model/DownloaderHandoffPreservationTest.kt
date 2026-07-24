package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderHandoffPreservationTest {
    @Test
    fun externalDownloadUrlsNormalizeSupportedSchemes() {
        assertEquals("https://example.test/file.apk", BrowserHandoffPolicy.normalizedUrl(" HTTPS://Example.TEST:443/file.apk. "))
        assertEquals("http://example.test/file.zip", BrowserHandoffPolicy.normalizedUrl("http://Example.TEST:80/file.zip"))
        assertEquals("ftp://example.test/file.iso", BrowserHandoffPolicy.normalizedUrl("ftp://Example.TEST:21/file.iso"))
        assertNull(BrowserHandoffPolicy.normalizedUrl("javascript:alert(1)"))
        assertNull(BrowserHandoffPolicy.normalizedUrl("file:///sdcard/secret"))
    }

    @Test
    fun sharedTextExtractionStaysDistinctAndHttpOnly() {
        val urls = BrowserHandoffPolicy.urlsInText(
            "Get https://example.test/a, http://example.test/b, https://example.test/a and ignore ftp://example.test/c",
        )
        assertEquals(listOf("https://example.test/a", "http://example.test/b"), urls)
    }

    @Test
    fun sensitiveHeadersRemainRedactedWhileSafeHeadersSurvive() {
        val headers = BrowserHandoffPolicy.sanitizeHeaders(
            "Authorization: Bearer abcdefghijklmnop\nCookie: SID=secret\nReferer: https://example.test/watch?token=secret\nUser-Agent: XDM",
        )
        assertEquals(
            "authorization: <redacted>\ncookie: <redacted>\nReferer: https://example.test/watch?token=<redacted>\nUser-Agent: XDM",
            headers,
        )
    }

    @Test
    fun alreadyRedactedPlaceholdersRemainSafeAndStable() {
        val headers = BrowserHandoffPolicy.sanitizeHeaders(
            "Cookie: <redacted>\nAuthorization: Bearer <redacted>\nX-Trace: session=<redacted>",
        )
        assertEquals(
            "cookie: <redacted>\nauthorization: <redacted>\nX-Trace: session=<redacted>",
            headers,
        )
        assertTrue(headers.orEmpty().contains("<redacted>"))
    }

    @Test
    fun handoffIdempotencyRemainsSourceAgnosticWithoutExplicitKeys() {
        val share = AutomationCommandDraft(
            source = AutomationCommandSource.ShareSheet,
            action = AutomationCommandAction.PromptAddDownload,
            url = "https://EXAMPLE.test:443/file.bin",
            fileName = "File.bin",
        )
        val view = share.copy(
            source = AutomationCommandSource.ViewIntent,
            url = " https://example.test/file.bin. ",
            fileName = "file.BIN",
        )

        assertEquals(share.stableIdempotencyKey, view.stableIdempotencyKey)
    }
}
