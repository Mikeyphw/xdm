package com.mikeyphw.xdm.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRequestKindTest {
    @Test
    fun callerMetadataCannotChangeProtocolKind() {
        assertEquals(
            DownloadRequestKind.Direct,
            inferDownloadRequestKind(
                url = "https://example.test/download",
                fileName = "attacker.torrent",
                mimeType = "application/x-bittorrent",
            ),
        )
        assertEquals(DownloadRequestKind.Torrent, inferDownloadRequestKind("https://example.test/file.torrent?token=redacted"))
        assertEquals(DownloadRequestKind.Metalink, inferDownloadRequestKind("https://example.test/file.meta4#ignored"))
        assertEquals(DownloadRequestKind.Magnet, inferDownloadRequestKind("magnet:?xt=urn:btih:0123456789abcdef"))
    }
}
