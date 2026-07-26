package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR3DownloadsWorkspaceTest {
    @Test
    fun filtersKeepEveryTransferInTheExpectedWorkspace() {
        val downloads = listOf(
            download("running", DownloadState.Downloading),
            download("queued", DownloadState.Queued),
            download("paused", DownloadState.Paused),
            download("done", DownloadState.Completed),
            download("archived", DownloadState.Completed, archived = true),
        )

        assertEquals(listOf("running"), visible(downloads, DownloadWorkspaceFilter.Active).map { it.id })
        assertEquals(listOf("paused", "queued"), visible(downloads, DownloadWorkspaceFilter.Queued).map { it.id }.sorted())
        assertEquals(listOf("done"), visible(downloads, DownloadWorkspaceFilter.Finished).map { it.id })
        assertEquals(4, visible(downloads, DownloadWorkspaceFilter.All).size)
        assertEquals(5, DownloadsWorkspacePlanner.visibleDownloads(downloads, DownloadWorkspaceFilter.All, "", true, DownloadDashboardOrdering.Smart).size)
    }

    @Test
    fun metricsAggregateOnlyMovingTransferSpeedAndEstimateRemainingTime() {
        val metrics = DownloadsWorkspacePlanner.metrics(
            listOf(
                download("a", DownloadState.Downloading, bytes = 50, total = 150, speed = 10),
                download("b", DownloadState.Downloading, bytes = 0, total = 100, speed = 10),
                download("q", DownloadState.Queued, speed = 999),
            ),
        )
        assertEquals(2, metrics.activeCount)
        assertEquals(1, metrics.queuedCount)
        assertEquals(20L, metrics.aggregateSpeedBytesPerSecond)
        assertEquals(10L, metrics.remainingSeconds)
    }

    @Test
    fun policyHoldDetectionIgnoresCompletedAndMovingItems() {
        val held = download("held", DownloadState.Queued, error = "Queue policy: Wi-Fi required")
        assertEquals("held", DownloadsWorkspacePlanner.firstPolicyHeldDownload(listOf(held))?.id)
        assertNull(DownloadsWorkspacePlanner.firstPolicyHeldDownload(listOf(held.copy(state = DownloadState.Downloading))))
        assertTrue(DownloadsWorkspacePlanner.copyFor(DownloadWorkspaceFilter.All).emptyDescription.contains("review", ignoreCase = true))
    }

    private fun visible(downloads: List<Download>, filter: DownloadWorkspaceFilter) =
        DownloadsWorkspacePlanner.visibleDownloads(downloads, filter, "", false, DownloadDashboardOrdering.Smart)

    private fun download(
        id: String,
        state: DownloadState,
        bytes: Long = 0,
        total: Long? = 100,
        speed: Long = 0,
        archived: Boolean = false,
        error: String? = null,
    ) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://example.test/$id.bin",
        destinationUri = "xdm://downloads",
        state = state,
        backend = BackendType.Native,
        bytesReceived = bytes,
        totalBytes = total,
        speedBytesPerSecond = speed,
        queueId = null,
        priority = 0,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        archived = archived,
        errorMessage = error,
    )
}
