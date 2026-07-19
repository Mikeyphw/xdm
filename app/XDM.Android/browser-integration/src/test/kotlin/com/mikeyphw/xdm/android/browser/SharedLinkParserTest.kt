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
}
