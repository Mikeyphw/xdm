package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.transfer.InMemoryBackendMigrationStore
import com.mikeyphw.xdm.android.transfer.InMemoryBackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.InMemoryFinalizationJournalStore
import com.mikeyphw.xdm.android.transfer.InMemoryRecoveryWorkflowStore
import java.io.File
import java.nio.file.Files
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
    fun obsoleteScannerRecordsAreReconciledWithoutDeletingManualRecords() = kotlinx.coroutines.test.runTest {
        val recovery = InMemoryRecoveryWorkflowStore()
        recovery.saveRecovery(
            RecoveryRecord(
                id = "recovery-d-NeedsRemoteValidation",
                downloadId = "d",
                artifactPath = "file:///tmp/file.bin",
                classification = RecoveryClassification.NeedsRemoteValidation,
                reason = "old scanner result",
                createdAtEpochMs = 1L,
                recommendedAction = RecoveryAction.Validate,
            ),
        )
        recovery.saveRecovery(
            RecoveryRecord(
                id = "manual-record",
                downloadId = null,
                artifactPath = "manual",
                classification = RecoveryClassification.OrphanedArtifact,
                reason = "manual review",
                createdAtEpochMs = 1L,
                recommendedAction = RecoveryAction.AdoptOrphan,
            ),
        )
        val coordinator = StartupRecoveryCoordinator(
            InMemoryTransferDownloadStore(),
            InMemoryBackendOwnershipStore(),
            InMemoryBackendMigrationStore(),
            InMemoryFinalizationJournalStore(),
            recovery,
        ) { 3L }

        coordinator.scan()

        val remaining = recovery.listRecovery()
        assertTrue(remaining.none { it.id == "recovery-d-NeedsRemoteValidation" })
        assertTrue(remaining.any { it.id == "manual-record" })
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

    @Test
    fun committedStorageJournalIsImportedBeforeMissingStagingCanMisclassifyRecovery() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("xdm-publication-recovery-").toFile()
        val directory = File(root, "transfer-staging/d").apply { mkdirs() }
        val journalFile = File(directory, "file.bin.finalization.json")
        journalFile.writeText(
            """
            phase=bug-hunt-phase-3
            journalIdentity=finalize-d-attempt-1-artifact-1
            downloadId=d
            attemptGeneration=1
            artifactGeneration=1
            sourcePath=/tmp/file.bin.part
            stagingPath=
            destinationSpec=file:///tmp/file.bin
            committedUri=file:///tmp/file.bin
            bytesExpected=3
            bytesCommitted=3
            boundary=DestinationCommitted
            health=Present
            message=committed
            """.trimIndent(),
        )
        val download = sampleDownload(DownloadState.Finalizing)
        val store = InMemoryTransferDownloadStore(listOf(download))
        val recovery = InMemoryRecoveryWorkflowStore()
        val finalization = InMemoryFinalizationJournalStore()
        val coordinator = StartupRecoveryCoordinator(
            store,
            InMemoryBackendOwnershipStore(),
            InMemoryBackendMigrationStore(),
            finalization,
            recovery,
            artifactRoots = listOf(root),
            clock = { 42L },
        )

        coordinator.scan()

        assertEquals(FinalizationJournalStage.DestinationCommitted, finalization.find(download.id)?.stage)
        assertEquals(DownloadState.RecoveryRequired, store.find(download.id)?.state)
        assertTrue(recovery.listRecovery().any { it.classification == RecoveryClassification.CompletionRecovered })
        assertTrue(recovery.listRecovery().none { it.classification == RecoveryClassification.NeedsRemoteValidation })
        root.deleteRecursively()
    }

    @Test
    fun atomicMoveCrashRecoversCommittedFileInsteadOfMissingStagingPath() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("xdm-publication-atomic-move-").toFile()
        val directory = File(root, "transfer-staging/d").apply { mkdirs() }
        val committed = File(root, "file.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val journalFile = File(directory, "file.bin.finalization.json")
        journalFile.writeText(
            """
            phase=bug-hunt-phase-3
            journalIdentity=finalize-d-attempt-1-artifact-1
            downloadId=d
            attemptGeneration=1
            artifactGeneration=1
            sourcePath=${File(root, "file.bin.part").absolutePath}
            stagingPath=${File(directory, "missing.part").absolutePath}
            destinationSpec=${committed.toURI()}
            committedUri=${committed.toURI()}
            bytesExpected=3
            bytesCommitted=0
            boundary=DestinationCommitInProgress
            health=Unknown
            message=commit started
            """.trimIndent(),
        )
        val download = sampleDownload(DownloadState.Finalizing)
        val store = InMemoryTransferDownloadStore(listOf(download))
        val recovery = InMemoryRecoveryWorkflowStore()
        val finalization = InMemoryFinalizationJournalStore()
        val coordinator = StartupRecoveryCoordinator(
            store,
            InMemoryBackendOwnershipStore(),
            InMemoryBackendMigrationStore(),
            finalization,
            recovery,
            artifactRoots = listOf(root),
            clock = { 42L },
        )

        coordinator.scan()

        val recovered = finalization.find(download.id)
        assertEquals(FinalizationJournalStage.DestinationCommitted, recovered?.stage)
        assertEquals(null, recovered?.stagingPath)
        assertEquals(3L, recovered?.bytesPromoted)
        assertEquals(committed.toURI().toString(), recovered?.sourcePath)
        assertTrue(recovery.listRecovery().any { it.artifactPath == committed.toURI().toString() })
        root.deleteRecursively()
    }

    @Test
    fun durableCompletedMetadataClosesIncompleteFinalizationJournalWithoutDowngrade() = kotlinx.coroutines.test.runTest {
        val completed = sampleDownload(DownloadState.Completed).copy(
            completedArtifactUri = "file:///tmp/file.bin",
            completedArtifactGeneration = 1L,
            completedArtifactBytes = 3L,
        )
        val store = InMemoryTransferDownloadStore(listOf(completed))
        val finalization = InMemoryFinalizationJournalStore()
        finalization.save(
            FinalizationJournal(
                id = "finalize-d",
                downloadId = completed.id,
                stage = FinalizationJournalStage.DestinationCommitted,
                sourcePath = "file:///tmp/file.bin",
                stagingPath = null,
                destinationUri = "file:///tmp/file.bin",
                bytesExpected = 3L,
                bytesPromoted = 3L,
                checksumAlgorithm = null,
                checksumHex = null,
                message = "destination committed",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
                attemptGeneration = 1L,
            ),
        )
        val recovery = InMemoryRecoveryWorkflowStore()
        val coordinator = StartupRecoveryCoordinator(
            store,
            InMemoryBackendOwnershipStore(),
            InMemoryBackendMigrationStore(),
            finalization,
            recovery,
            clock = { 42L },
        )

        coordinator.scan()

        assertEquals(DownloadState.Completed, store.find(completed.id)?.state)
        assertEquals(FinalizationJournalStage.Completed, finalization.find(completed.id)?.stage)
        assertTrue(recovery.listRecovery().none { it.downloadId == completed.id })
    }

    private fun sampleDownload(state: DownloadState) = Download("d", "file.bin", "https://example.test/file.bin", "file:///tmp/file.bin", state, BackendType.Native, 3, 10, 0, null, 0, 1, 1)
}

private class InMemoryTransferDownloadStore(initial: List<Download> = emptyList()) : TransferDownloadStore {
    private val rows = initial.associateBy { it.id }.toMutableMap()
    override suspend fun find(downloadId: String): Download? = rows[downloadId]
    override suspend fun save(download: Download): Boolean { rows[download.id] = download; return true }
    override suspend fun findByStates(states: Set<DownloadState>): List<Download> = rows.values.filter { it.state in states }
    override suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) = Unit
    override suspend fun deleteBackendTask(downloadId: String) = Unit
}
