package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationModelsTest {
    @Test
    fun stableKeysNormalizeWhitespaceAndCase() {
        val first = AutomationCommandDraft(
            source = AutomationCommandSource.Tasker,
            action = AutomationCommandAction.EnqueueDownload,
            url = " HTTPS://EXAMPLE.COM/File.MP4 ",
            fileName = " clip.mp4 ",
        )
        val second = first.copy(url = "https://example.com/file.mp4", fileName = "CLIP.MP4")

        assertEquals(first.stableIdempotencyKey, second.stableIdempotencyKey)
        assertTrue(AutomationCommandIds.commandId(first.stableIdempotencyKey).startsWith("cmd-"))
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
