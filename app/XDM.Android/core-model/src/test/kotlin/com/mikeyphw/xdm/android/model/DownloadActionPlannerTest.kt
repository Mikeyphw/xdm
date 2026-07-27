package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadActionPlannerTest {
    @Test
    fun activeDownloadsExposePausePrimaryAndSafeMoreActions() {
        val actions = DownloadActionPlanner.actionsFor(download(DownloadState.Downloading))

        assertEquals(DownloadActionKind.Pause, actions.primaryKind())
        assertEquals(
            listOf(
                DownloadActionKind.Pause,
                DownloadActionKind.OpenDetails,
                DownloadActionKind.CopyLink,
                DownloadActionKind.ShareLink,
                DownloadActionKind.Cancel,
            ),
            actions.map { it.kind },
        )
        assertTrue(actions.last().destructive)
        assertTrue(actions.last().requiresConfirmation)
    }

    @Test
    fun queuedDownloadsExposeStartAndReorderActions() {
        val actions = DownloadActionPlanner.actionsFor(download(DownloadState.Queued, queueId = "main"))

        assertEquals(DownloadActionKind.StartNow, actions.primaryKind())
        assertTrue(actions.map { it.kind }.containsAll(listOf(
            DownloadActionKind.MoveToTop,
            DownloadActionKind.MoveUp,
            DownloadActionKind.MoveDown,
            DownloadActionKind.MoveToBottom,
        )))
        assertTrue(actions.first { it.kind == DownloadActionKind.MoveUp }.enabled)
    }

    @Test
    fun pausedAndFailedDownloadsExposeResumeRetryAndRefresh() {
        val paused = DownloadActionPlanner.actionsFor(download(DownloadState.Paused))
        val failed = DownloadActionPlanner.actionsFor(download(DownloadState.Failed, error = "404"))

        assertEquals(DownloadActionKind.Resume, paused.primaryKind())
        assertTrue(paused.map { it.kind }.contains(DownloadActionKind.RefreshLink))
        assertTrue(paused.map { it.kind }.contains(DownloadActionKind.Redownload))
        assertEquals(DownloadActionKind.Retry, failed.primaryKind())
        assertTrue(failed.map { it.kind }.contains(DownloadActionKind.CopyLink))
    }

    @Test
    fun completedDownloadsPreferOpenFileAndKeepDestructiveDeleteSeparated() {
        val actions = DownloadActionPlanner.actionsFor(download(DownloadState.Completed, destination = "content://downloads/video.mp4"))

        assertEquals(DownloadActionKind.OpenFile, actions.primaryKind())
        assertEquals("Open file", actions.first().label)
        assertTrue(actions.map { it.kind }.containsAll(listOf(
            DownloadActionKind.OpenDetails,
            DownloadActionKind.OpenFolder,
            DownloadActionKind.ShareFile,
            DownloadActionKind.CopyLink,
            DownloadActionKind.Rename,
            DownloadActionKind.Redownload,
            DownloadActionKind.DeleteRecord,
            DownloadActionKind.DeleteFileAndRecord,
        )))
        val deleteFile = actions.first { it.kind == DownloadActionKind.DeleteFileAndRecord }
        assertTrue(deleteFile.destructive)
        assertTrue(deleteFile.requiresConfirmation)
    }

    @Test
    fun recoveryDownloadsRoutePrimaryActionToReviewRecovery() {
        val actions = DownloadActionPlanner.actionsFor(download(DownloadState.RecoveryRequired))

        assertEquals(DownloadActionKind.ReviewRecovery, actions.primaryKind())
        assertEquals("Review recovery", actions.first().label)
        assertTrue(actions.map { it.kind }.contains(DownloadActionKind.OpenFolder))
        assertTrue(actions.map { it.kind }.contains(DownloadActionKind.Redownload))
    }

    @Test
    fun batchActionsAreDerivedFromSelectedStateMix() {
        val actions = DownloadActionPlanner.batchActionsFor(listOf(
            download(DownloadState.Downloading, id = "a"),
            download(DownloadState.Paused, id = "b"),
            download(DownloadState.Completed, id = "c"),
        ))

        assertTrue(actions.map { it.kind }.contains(DownloadActionKind.Pause))
        assertTrue(actions.map { it.kind }.contains(DownloadActionKind.Resume))
        assertTrue(actions.map { it.kind }.contains(DownloadActionKind.CopyLink))
        assertFalse(actions.map { it.kind }.contains(DownloadActionKind.DeleteRecord))
    }

    private fun List<DownloadAction>.primaryKind(): DownloadActionKind = first { it.primary }.kind

    private fun download(
        state: DownloadState,
        id: String = "download-id",
        queueId: String? = null,
        destination: String = "xdm://downloads/file.bin",
        error: String? = null,
    ) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://example.test/$id.bin",
        destinationUri = destination,
        state = state,
        backend = BackendType.Native,
        bytesReceived = if (state == DownloadState.Completed) 1024 else 128,
        totalBytes = 1024,
        speedBytesPerSecond = if (state == DownloadState.Downloading) 2048 else 0,
        queueId = queueId,
        priority = 0,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
        errorMessage = error,
    )
}
