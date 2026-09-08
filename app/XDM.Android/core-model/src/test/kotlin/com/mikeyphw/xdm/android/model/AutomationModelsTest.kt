package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationModelsTest {
    @Test
    fun stableKeysNormalizeAuthorityAndFileNameButPreserveCaseSensitiveUrlPath() {
        val first = AutomationCommandDraft(
            source = AutomationCommandSource.Tasker,
            action = AutomationCommandAction.EnqueueDownload,
            url = " HTTPS://EXAMPLE.COM/File.MP4 ",
            fileName = " clip.mp4 ",
        )
        val sameUrlDifferentFileCase = first.copy(fileName = "CLIP.MP4")
        val differentPathCase = first.copy(url = "https://example.com/file.mp4", fileName = "CLIP.MP4")

        assertEquals(first.stableIdempotencyKey, sameUrlDifferentFileCase.stableIdempotencyKey)
        assertNotEquals(first.stableIdempotencyKey, differentPathCase.stableIdempotencyKey)
        assertTrue(AutomationCommandIds.commandId(first.stableIdempotencyKey).startsWith("cmd-"))
    }

    @Test
    fun captureMediaIdempotencyIncludesRevisionButNotCredentialValue() {
        val first = AutomationCommandDraft(
            source = AutomationCommandSource.BrowserExtension,
            action = AutomationCommandAction.CaptureMedia,
            url = "https://cdn.example/Video.m3u8?token=one",
            stableMediaId = "browser-media-logical",
            sessionRevision = 10L,
        )
        val replay = first.copy(url = "https://cdn.example/Video.m3u8?token=two")
        val refreshed = replay.copy(sessionRevision = 11L)
        assertEquals(first.stableIdempotencyKey, replay.stableIdempotencyKey)
        assertNotEquals(first.stableIdempotencyKey, refreshed.stableIdempotencyKey)
    }

    @Test
    fun explicitKeysAreSourceScoped() {
        val tasker = AutomationCommandDraft(
            source = AutomationCommandSource.Tasker,
            action = AutomationCommandAction.EnqueueDownload,
            explicitIdempotencyKey = "same-event",
        )
        val share = tasker.copy(source = AutomationCommandSource.ShareSheet)

        assertTrue(tasker.stableIdempotencyKey != share.stableIdempotencyKey)
        assertEquals("external:Tasker:same-event", tasker.stableIdempotencyKey)
    }

    @Test
    fun browserShareAndTaskerUrlsDeduplicateAcrossSources() {
        val browser = AutomationCommandDraft(
            source = AutomationCommandSource.BrowserExtension,
            action = AutomationCommandAction.EnqueueDownload,
            url = "https://EXAMPLE.test:443/video.mp4.",
        )
        val tasker = AutomationCommandDraft(
            source = AutomationCommandSource.Tasker,
            action = AutomationCommandAction.EnqueueDownload,
            url = " https://example.test/video.mp4 ",
        )

        assertEquals("https://example.test/video.mp4", browser.normalizedUrl)
        assertEquals(browser.stableIdempotencyKey, tasker.stableIdempotencyKey)
    }

    @Test
    fun externalMetadataRemainsReviewOnlyAndDoesNotChangeDeduplication() {
        val base = AutomationCommandDraft(
            source = AutomationCommandSource.ShareSheet,
            action = AutomationCommandAction.PromptAddDownload,
            url = "https://example.test/asset",
            mimeType = "video/mp4",
            contentLength = 2048L,
        )
        val withoutMetadata = base.copy(mimeType = null, contentLength = null)

        assertEquals("video/mp4", base.mimeType)
        assertEquals(2048L, base.contentLength)
        assertEquals(base.stableIdempotencyKey, withoutMetadata.stableIdempotencyKey)
    }

    @Test
    fun sensitiveBrowserHeadersAreRedacted() {
        val draft = AutomationCommandDraft(
            source = AutomationCommandSource.BrowserExtension,
            action = AutomationCommandAction.EnqueueDownload,
            url = "https://example.test/file.bin",
            rawHeaders = "Authorization: Bearer secret\nCookie: session=secret\nUser-Agent: XDM",
        )

        assertEquals("authorization: <redacted>\ncookie: <redacted>\nUser-Agent: XDM", draft.sanitizedHeaders)
    }
}
