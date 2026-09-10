package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadUiTruth
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
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
            download("network", DownloadState.WaitingForNetwork),
            download("done", DownloadState.Completed),
            download("archived", DownloadState.Completed, archived = true),
        )

        assertEquals(listOf("running"), visible(downloads, DownloadWorkspaceFilter.Downloading).map { it.id })
        assertEquals(setOf("queued", "paused", "network"), visible(downloads, DownloadWorkspaceFilter.Waiting).map { it.id }.toSet())
        assertEquals(listOf("done"), visible(downloads, DownloadWorkspaceFilter.Finished).map { it.id })
        assertEquals(5, visible(downloads, DownloadWorkspaceFilter.All).size)
        assertEquals(6, DownloadsWorkspacePlanner.visibleDownloads(downloads, DownloadWorkspaceFilter.All, "", true, DownloadDashboardOrdering.Smart).size)
    }

    @Test
    fun metricsExposeDownloadingWaitingQueuedAndMovingSpeedSeparately() {
        val metrics = DownloadsWorkspacePlanner.metrics(
            listOf(
                download("a", DownloadState.Downloading, bytes = 50, total = 150, speed = 10),
                download("b", DownloadState.Downloading, bytes = 0, total = 100, speed = 10),
                download("q", DownloadState.Queued, speed = 999),
                download("p", DownloadState.Paused),
            ),
        )
        assertEquals(2, metrics.downloadingCount)
        assertEquals(2, metrics.waitingCount)
        assertEquals(1, metrics.queuedCount)
        assertEquals(20L, metrics.aggregateSpeedBytesPerSecond)
        assertEquals(10L, metrics.remainingSeconds)
    }

    @Test
    fun queueProblemsAreGroupedIntoOneUserFacingIssue() {
        val storage = DownloadsWorkspacePlanner.queueIssue(QueueIntelligenceSummary(heldForStorage = 3))
        assertEquals(DownloadQueueIssueKind.Storage, storage?.kind)
        assertEquals(3, storage?.affectedCount)
        assertTrue(storage?.title.orEmpty().contains("3 downloads"))
        assertTrue(storage?.detail.orEmpty().contains("free space"))

        val network = DownloadsWorkspacePlanner.queueIssue(QueueIntelligenceSummary(heldForNetwork = 1))
        assertEquals(DownloadQueueIssueKind.Network, network?.kind)
        assertTrue(network?.title.orEmpty().contains("1 download"))
        assertNull(DownloadsWorkspacePlanner.queueIssue(QueueIntelligenceSummary()))
    }

    @Test
    fun rowStatusKeepsTechnicalQueueErrorsOutOfThePrimaryCard() {
        val held = download("held", DownloadState.Queued, error = "Queue policy: Destination storage unavailable. Internal provider probe returned null.")
        val truth = DownloadUiTruth("Queued", "Waiting in queue", "Destination storage unavailable", "0 bytes", "Waiting", "Queued", "", "", "")
        assertEquals("Waiting — Storage check failed", DownloadsWorkspacePlanner.rowStatus(held, truth))
    }

    @Test
    fun policyHoldDetectionIgnoresCompletedAndMovingItems() {
        val held = download("held", DownloadState.Queued, error = "Queue policy: Wi-Fi required")
        assertEquals("held", DownloadsWorkspacePlanner.firstPolicyHeldDownload(listOf(held))?.id)
        assertNull(DownloadsWorkspacePlanner.firstPolicyHeldDownload(listOf(held.copy(state = DownloadState.Downloading))))
        assertTrue(DownloadsWorkspacePlanner.copyFor(DownloadWorkspaceFilter.All).emptyDescription.contains("New download", ignoreCase = true))
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
        destinationUri = "xdm://filesystem/downloads",
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
