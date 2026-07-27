package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntakePlannerTest {
    private val planner = DownloadIntakePlanner { prefix -> "$prefix-test-id" }

    @Test
    fun builtInBrowserPageCreatesReviewOnlyNeutralDraft() {
        val draft = planner.fromBuiltInBrowserPage(
            url = " HTTPS://Example.TEST:443/path/file.zip. ",
            pageTitle = "  Example downloads  ",
        ) ?: error("Expected a draft")

        assertEquals("page-review-test-id", draft.id)
        assertEquals("https://example.test/path/file.zip", draft.url)
        assertEquals(DownloadIntakeOrigin.BuiltInBrowserPage, draft.origin)
        assertEquals("Page: Example downloads", draft.sourceLabel)
        assertEquals("Example downloads", draft.pageTitle)
        assertEquals("https://example.test/path/file.zip", draft.pageUrl)
        assertEquals("", draft.fileName)
        assertEquals(DownloadIntakeKind.DirectFile, draft.kind)
    }

    @Test
    fun detectedDownloadPreservesSafeMetadataForAddDownloadReview() {
        val draft = planner.fromBuiltInBrowserDownload(
            url = "https://cdn.example.test/releases/app.apk",
            fileName = "folder\\unsafe/name.apk",
            pageTitle = "Release page",
            pageUrl = "https://example.test/releases",
            mimeType = "Application/Vnd.Android.Package-Archive; charset=binary",
            contentLength = 12_345L,
        ) ?: error("Expected a draft")

        assertEquals("name.apk", draft.fileName)
        assertEquals("application/vnd.android.package-archive", draft.mimeType)
        assertEquals(12_345L, draft.contentLength ?: -1L)
        assertEquals("https://example.test/releases", draft.pageUrl)
        assertEquals(DownloadIntakeOrigin.BuiltInBrowserDownload, draft.origin)
        assertEquals(DownloadIntakeKind.DirectFile, draft.kind)
        assertTrue(draft.sourceLabel.startsWith("Detected download:"))
    }

    @Test
    fun externalIntakeAllowsFtpButBrowserPageDoesNot() {
        val external = planner.fromExternal(
            id = "command-1",
            url = "ftp://Example.TEST:21/releases/image.iso",
            fileName = "image.iso",
            sourceLabel = "External app",
            origin = DownloadIntakeOrigin.ExternalView,
        ) ?: error("Expected an external draft")

        assertEquals("command-1", external.id)
        assertEquals("ftp://example.test/releases/image.iso", external.url)
        assertNull(planner.fromBuiltInBrowserPage("ftp://example.test/releases/image.iso"))
    }

    @Test
    fun classifiesAdaptiveDirectMediaTorrentAndPages() {
        assertEquals(
            DownloadIntakeKind.AdaptiveMedia,
            planner.fromExternal(url = "https://cdn.example/live/master.m3u8", origin = DownloadIntakeOrigin.ExternalShare)?.kind,
        )
        assertEquals(
            DownloadIntakeKind.DirectMedia,
            planner.fromExternal(url = "https://cdn.example/opaque", mimeType = "video/mp4", origin = DownloadIntakeOrigin.ExternalShare)?.kind,
        )
        assertEquals(
            DownloadIntakeKind.Torrent,
            planner.fromExternal(url = "https://cdn.example/release.torrent", origin = DownloadIntakeOrigin.ExternalShare)?.kind,
        )
        val page = planner.fromExternal(
            url = "https://example.test/watch?id=3",
            pageTitle = "Watch",
            origin = DownloadIntakeOrigin.ExternalShare,
        ) ?: error("Expected page draft")
        assertEquals(DownloadIntakeKind.PageOrUnknown, page.kind)
        assertTrue(page.canInspectAsMedia)
    }


    @Test
    fun browserExtensionSourceKeepsDedicatedOriginForAddDownloadPolicy() {
        val draft = planner.fromExternal(
            id = "extension-command",
            url = "https://cdn.example/video.mp4",
            fileName = "video.mp4",
            sourceLabel = "Browser extension",
            origin = DownloadIntakeOrigin.BrowserExtension,
        ) ?: error("Expected browser extension draft")

        assertEquals("extension-command", draft.id)
        assertEquals(DownloadIntakeOrigin.BrowserExtension, draft.origin)
        assertEquals(DownloadIntakeKind.DirectMedia, draft.kind)
        assertTrue(draft.canInspectAsMedia)
    }

    @Test
    fun unsafeAndLocalSchemesAreRejected() {
        assertNull(planner.fromBuiltInBrowserPage("javascript:alert(1)"))
        assertNull(
            planner.fromExternal(
                url = "file:///sdcard/private.txt",
                origin = DownloadIntakeOrigin.ExternalView,
            ),
        )
    }
    @Test
    fun manualEntryCreatesNeutralReviewDraft() {
        val draft = planner.fromManual("https://example.com/file.zip", "file.zip")!!
        assertEquals(DownloadIntakeOrigin.ManualEntry, draft.origin)
        assertEquals("Add Download", draft.sourceLabel)
        assertEquals(DownloadIntakeKind.DirectFile, draft.kind)
    }

}
