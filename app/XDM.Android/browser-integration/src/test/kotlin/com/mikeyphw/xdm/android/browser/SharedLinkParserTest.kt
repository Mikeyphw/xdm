package com.mikeyphw.xdm.android.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedLinkParserTest {
    @Test
    fun extractsDistinctHttpLinks() {
        val links = SharedLinkParser.parse("Get https://example.test/a and http://example.test/b then https://example.test/a")
        assertEquals(listOf("https://example.test/a", "http://example.test/b"), links)
    }

    @Test
    fun stripsTrailingPunctuationAndNormalizesDefaultHttpsPort() {
        val links = SharedLinkParser.parse("Watch (HTTPS://Example.TEST:443/video.m3u8). Then ignore ftp://example.test/file")
        assertEquals(listOf("https://example.test/video.m3u8"), links)
    }

    @Test
    fun ignoresShareTextAroundDownloadUrl() {
        val links = SharedLinkParser.parse("Download this build: https://example.test/releases/app.apk?token=abc. Thanks!")
        assertEquals(listOf("https://example.test/releases/app.apk?token=abc"), links)
    }


    @Test
    fun ignoresUnsafeAndNonShareSchemes() {
        val links = SharedLinkParser.parse(
            "javascript:alert(1) file:///sdcard/private ftp://example.test/file.zip https://example.test/safe.zip",
        )
        assertEquals(listOf("https://example.test/safe.zip"), links)
    }

    @Test
    fun acceptsNewlinesAndAngleBracketWrappedLinks() {
        val links = SharedLinkParser.parse(
            "First:\n<https://example.test/a.mp4>\nSecond: https://example.test/b.m3u8;",
        )
        assertEquals(listOf("https://example.test/a.mp4", "https://example.test/b.m3u8"), links)
    }
}
