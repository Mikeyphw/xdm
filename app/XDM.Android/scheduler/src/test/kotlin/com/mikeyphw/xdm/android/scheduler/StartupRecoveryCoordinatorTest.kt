package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.transfer.InMemoryBackendMigrationStore
import com.mikeyphw.xdm.android.transfer.InMemoryBackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.InMemoryFinalizationJournalStore
import com.mikeyphw.xdm.android.transfer.InMemoryRecoveryWorkflowStore
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class StartupRecoveryCoordinatorTest {
    @Test
    fun activeDownloadBecomesRecoveryRequiredAndPaused() = kotlinx.coroutines.test.runTest {
        val download = sampleDownload(DownloadState.Downloading)
        val store = InMemoryTransferDownloadStore(listOf(download))
        val recovery = InMemoryRecoveryWorkflowStore()
        val coordinator = StartupRecoveryCoordinator(store, InMemoryBackendOwnershipStore(), InMemoryBackendMigrationStore(), InMemoryFinalizationJournalStore(), recovery) { 42L }

        val report = coordinator.scan()

        assertEquals(1, report.recordsCreated)
        assertEquals(DownloadState.RecoveryRequired, store.find(download.id)?.state)
        assertEquals(RecoveryClassification.NeedsRemoteValidation, recovery.listRecovery().single().classification)
    }

    @Test
    fun incompleteMigrationCreatesBackendRecoveryRecord() = kotlinx.coroutines.test.runTest {
        val migrationStore = InMemoryBackendMigrationStore()
        migrationStore.save(BackendMigrationRecord("m", "d", BackendType.Native, BackendType.Aria2, 1, null, "n", null, BackendMigrationStage.TargetPrepared, "native-part", null, true, "waiting for target", 1, 1))
        val recovery = InMemoryRecoveryWorkflowStore()
        val coordinator = StartupRecoveryCoordinator(InMemoryTransferDownloadStore(), InMemoryBackendOwnershipStore(), migrationStore, InMemoryFinalizationJournalStore(), recovery) { 2L }

        coordinator.scan()

        assertTrue(recovery.listRecovery().any { it.classification == RecoveryClassification.BackendTaskOrphaned })
    }

    private fun sampleDownload(state: DownloadState) = Download("d", "file.bin", "https://example.test/file.bin", "file:///tmp/file.bin", state, BackendType.Native, 3, 10, 0, null, 0, 1, 1)
}

private class InMemoryTransferDownloadStore(initial: List<Download> = emptyList()) : TransferDownloadStore {
    private val rows = initial.associateBy { it.id }.toMutableMap()
    override suspend fun find(downloadId: String): Download? = rows[downloadId]
    override suspend fun save(download: Download) { rows[download.id] = download }
    override suspend fun findByStates(states: Set<DownloadState>): List<Download> = rows.values.filter { it.state in states }
    override suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) = Unit
    override suspend fun deleteBackendTask(downloadId: String) = Unit
}
