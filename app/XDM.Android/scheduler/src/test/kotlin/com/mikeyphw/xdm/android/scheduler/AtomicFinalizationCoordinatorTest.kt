package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
import com.mikeyphw.xdm.android.transfer.InMemoryFinalizationJournalStore
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals

class AtomicFinalizationCoordinatorTest {
    @Test
    fun finalizationStagesAdvanceDeterministically() = kotlinx.coroutines.test.runTest {
        val file = File.createTempFile("xdm-finalize", ".bin")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val store = InMemoryFinalizationJournalStore()
        val coordinator = AtomicFinalizationCoordinator(store) { 10L }
        val download = Download("d", "file.bin", "https://example.test/file.bin", file.toURI().toString(), DownloadState.Finalizing, BackendType.Native, 3, 3, 0, null, 0, 1, 1)

        val prepared = coordinator.prepare(download, file)
        val committed = coordinator.recordDestinationCommitted(coordinator.recordPromotionStarted(prepared), 3)

        assertEquals(FinalizationJournalStage.DestinationCommitted, committed.stage)
        assertEquals(3L, store.find(download.id)?.bytesPromoted)
    }
    @Test
    fun committedJournalRemainsRecoverableUntilCompletion() = kotlinx.coroutines.test.runTest {
        val store = InMemoryFinalizationJournalStore()
        var now = 20L
        val coordinator = AtomicFinalizationCoordinator(store) { now++ }
        val download = Download(
            "committed",
            "file.bin",
            "https://example.test/file.bin",
            "file:///tmp/file.bin",
            DownloadState.Verifying,
            BackendType.Native,
            3,
            3,
            0,
            null,
            0,
            1,
            1,
            attemptGeneration = 7L,
        )

        val prepared = coordinator.prepareCommitted(download, "file:///tmp/file.bin", 3L, 7L)
        assertEquals(prepared, coordinator.findIncomplete(download.id))

        val recovered = coordinator.recover(prepared, "verification interrupted")
        assertEquals(FinalizationJournalStage.RecoveryRequired, recovered.stage)
        assertEquals("verification interrupted", store.find(download.id)?.message)
    }

}
