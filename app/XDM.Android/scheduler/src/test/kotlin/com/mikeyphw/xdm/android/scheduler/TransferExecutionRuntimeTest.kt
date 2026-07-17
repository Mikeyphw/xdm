package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.InMemoryBackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.InMemoryBackendMigrationStore
import com.mikeyphw.xdm.android.transfer.InMemoryChecksumWorkflowStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferExecutionRuntimeTest {
    @Test fun restorePausesOnlyInterruptedStates() = runTest {
        val store = FakeStore(
            listOf(
                download("active", DownloadState.Downloading),
                download("finalizing", DownloadState.Finalizing),
                download("queued", DownloadState.Queued),
                download("complete", DownloadState.Completed),
            ),
        )
        val runtime = TransferExecutionRuntime(store, InMemoryBackendOwnershipStore(), emptyList(), InMemoryBackendMigrationStore(), InMemoryChecksumWorkflowStore(), this)

        assertEquals(2, runtime.restoreInterruptedTransfers())
        assertEquals(DownloadState.Paused, store.items.getValue("active").state)
        assertEquals(DownloadState.Paused, store.items.getValue("finalizing").state)
        assertEquals(DownloadState.Queued, store.items.getValue("queued").state)
        assertEquals(DownloadState.Completed, store.items.getValue("complete").state)
    }

    private class FakeStore(downloads: List<Download>) : TransferDownloadStore {
        val items = downloads.associateByTo(linkedMapOf(), Download::id)
        override suspend fun find(downloadId: String) = items[downloadId]
        override suspend fun findByStates(states: Set<DownloadState>) = items.values.filter { it.state in states }
        override suspend fun save(download: Download) { items[download.id] = download }
        override suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) = Unit
        override suspend fun deleteBackendTask(downloadId: String) = Unit
    }

    companion object {
        private fun download(id: String, state: DownloadState) = Download(
            id = id,
            fileName = "$id.bin",
            sourceUrl = "https://example.test/$id.bin",
            destinationUri = "file:///tmp/$id.bin",
            state = state,
            backend = BackendType.Native,
            bytesReceived = 16,
            totalBytes = 32,
            speedBytesPerSecond = 0,
            queueId = "default",
            priority = 0,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )
    }
}
