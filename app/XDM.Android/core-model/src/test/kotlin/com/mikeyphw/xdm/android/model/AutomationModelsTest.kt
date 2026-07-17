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
}
