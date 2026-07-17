package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendMigrationInspection
import com.mikeyphw.xdm.android.model.BackendMigrationReuse
import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.BackendMigrationStore
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendPreparation
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.BackendRegistry
import com.mikeyphw.xdm.android.transfer.BackendShutdownResult
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.BackendTask
import com.mikeyphw.xdm.android.transfer.DestinationIdentity
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.InMemoryBackendMigrationStore
import com.mikeyphw.xdm.android.transfer.InMemoryBackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.OwnershipClaimResult
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendMigrationCoordinatorTest {
    @Test
    fun partialBytesRequireExplicitRestartAndDoNotTransferOwnership() = runTest {
        val fixture = fixture(bytesPresent = 64)

        val outcome = fixture.coordinator.migrate("download", BackendType.Aria2, restartFromZero = false)

        assertTrue(outcome is BackendMigrationOutcome.Rejected)
        assertEquals(BackendMigrationStage.RecoveryRequired, (outcome as BackendMigrationOutcome.Rejected).record.stage)
        assertEquals(BackendType.Native, fixture.ownership.findByDownload("download")?.backend)
        assertTrue(fixture.target.events.isEmpty())
    }

    @Test
    fun emptySourceTransfersGenerationBeforeTargetActivation() = runTest {
        val fixture = fixture(bytesPresent = 0)

        val outcome = fixture.coordinator.migrate("download", BackendType.Aria2, restartFromZero = false)

        assertTrue(outcome is BackendMigrationOutcome.Started)
        assertEquals(listOf("prepare", "add", "attached", "activate"), fixture.target.events)
        assertTrue("retire" in fixture.source.events)
        assertTrue(fixture.store.deletedBackendTasks.contains("download"))
        val ownership = requireNotNull(fixture.ownership.findByDownload("download"))
        assertEquals(BackendType.Aria2, ownership.backend)
        assertTrue(ownership.generation > 1)
        assertEquals(BackendType.Aria2, fixture.store.items.getValue("download").backend)
    }

    @Test
    fun ownershipTransferFailureAfterSourceRetirementRequiresRecovery() = runTest {
        val fixture = fixture(bytesPresent = 0, failOwnershipTransfer = true)

        val outcome = fixture.coordinator.migrate("download", BackendType.Aria2, restartFromZero = false)

        assertTrue(outcome is BackendMigrationOutcome.Rejected)
        assertEquals(BackendMigrationStage.RecoveryRequired, (outcome as BackendMigrationOutcome.Rejected).record.stage)
        assertEquals(DownloadState.RecoveryRequired, fixture.store.items.getValue("download").state)
        assertEquals(BackendType.Native, fixture.ownership.findByDownload("download")?.backend)
        assertTrue("retire" in fixture.source.events)
    }

    @Test
    fun targetFailureAfterOwnershipTransferRequiresRecoveryWithoutRestoringSourceWriter() = runTest {
        val fixture = fixture(bytesPresent = 0, failTargetAdd = true)

        val outcome = fixture.coordinator.migrate("download", BackendType.Aria2, restartFromZero = false)

        assertTrue(outcome is BackendMigrationOutcome.Rejected)
        assertEquals(BackendMigrationStage.RecoveryRequired, (outcome as BackendMigrationOutcome.Rejected).record.stage)
        assertEquals(DownloadState.RecoveryRequired, fixture.store.items.getValue("download").state)
        assertEquals(BackendType.Aria2, fixture.ownership.findByDownload("download")?.backend)
        assertTrue("retire" in fixture.source.events)
    }

    private suspend fun fixture(
        bytesPresent: Long,
        failTargetAdd: Boolean = false,
        failOwnershipTransfer: Boolean = false,
    ): Fixture {
        val destination = Files.createTempFile("xdm-migrate", ".bin").toUri().toString()
        val download = Download(
            id = "download",
            fileName = "file.bin",
            sourceUrl = "https://example.test/file.bin",
            destinationUri = destination,
            state = DownloadState.Paused,
            backend = BackendType.Native,
            bytesReceived = bytesPresent,
            totalBytes = 1024,
            speedBytesPerSecond = 0,
            queueId = "default",
            priority = 0,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )
        val store = FakeStore(download)
        val baseOwnership = InMemoryBackendOwnershipStore { 100L }
        val source = MigrationBackend(BackendType.Native, bytesPresent)
        val target = MigrationBackend(BackendType.Aria2, 0, failTargetAdd)
        val artifacts = BackendArtifactIdentity("native-v1", "$destination.xdm.part", listOf("$destination.xdm.checkpoint.json"))
        val claim = baseOwnership.claim("download", DestinationIdentity.key(destination, "file.bin"), artifacts, BackendType.Native, source.runtimeIdentity)
        val claimed = (claim as OwnershipClaimResult.Claimed).ownership
        baseOwnership.attachTask("download", claimed.generation, "native-task")
        val ownership: BackendOwnershipStore = if (failOwnershipTransfer) {
            FailingTransferOwnershipStore(baseOwnership)
        } else {
            baseOwnership
        }
        store.saveBackendTask("download", BackendType.Native, "native-task", claimed)
        val migrations: BackendMigrationStore = InMemoryBackendMigrationStore()
        val coordinator = BackendMigrationCoordinator(store, ownership, migrations, BackendRegistry(listOf(source, target)))
        return Fixture(store, ownership, source, target, coordinator)
    }

    private data class Fixture(
        val store: FakeStore,
        val ownership: BackendOwnershipStore,
        val source: MigrationBackend,
        val target: MigrationBackend,
        val coordinator: BackendMigrationCoordinator,
    )
}

private class FakeStore(download: Download) : TransferDownloadStore {
    val items = linkedMapOf(download.id to download)
    val deletedBackendTasks = mutableListOf<String>()
    override suspend fun find(downloadId: String) = items[downloadId]
    override suspend fun findByStates(states: Set<DownloadState>) = items.values.filter { it.state in states }
    override suspend fun save(download: Download) { items[download.id] = download }
    override suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) = Unit
    override suspend fun deleteBackendTask(downloadId: String) { deletedBackendTasks += downloadId }
}

private class MigrationBackend(
    private val type: BackendType,
    private val bytesPresent: Long,
    private val failAdd: Boolean = false,
) : DownloadBackend {
    override val backendId = type.name.lowercase()
    override val runtimeIdentity = BackendRuntimeIdentity("instance-$backendId", "session-$backendId")
    val events = mutableListOf<String>()
    private val snapshot = MutableStateFlow(BackendSnapshot("task-$backendId", DownloadState.Paused, 0, 1024, 0))

    override suspend fun capabilities() = BackendCapabilities(
        protocols = setOf("https"),
        supportsSegmentation = true,
        supportsMirrors = type == BackendType.Aria2,
        supportsSelectiveRepair = type == BackendType.Native,
        supportsSafDestination = type == BackendType.Native,
    )

    override suspend fun prepare(request: DownloadRequest): BackendPreparation {
        events += "prepare"
        val destinationKey = DestinationIdentity.key(request.destinationUri, request.fileName)
        return BackendPreparation(
            preparationId = "prepare-$backendId",
            downloadId = request.id,
            backend = type,
            destinationKey = destinationKey,
            artifacts = BackendArtifactIdentity("$backendId-v1", "$destinationKey.$backendId.part"),
            runtimeIdentity = runtimeIdentity,
        )
    }

    override suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask {
        events += "add"
        if (failAdd) error("target add failed")
        return BackendTask("task-$backendId", type, requiresActivation = true)
    }

    override suspend fun discardPreparation(preparation: BackendPreparation) = Unit
    override suspend fun onOwnershipAttached(taskId: String, ownership: BackendOwnership) { events += "attached" }
    override suspend fun activate(taskId: String) { events += "activate" }
    override suspend fun pause(taskId: String) { events += "pause" }
    override suspend fun resume(taskId: String) = Unit
    override suspend fun cancel(taskId: String) = Unit
    override suspend fun remove(taskId: String) = Unit
    override suspend fun detach(taskId: String) = true
    override suspend fun retireForMigration(taskId: String): Boolean { events += "retire"; return true }
    override suspend fun query(taskId: String) = snapshot.value
    override fun observe(taskId: String): Flow<BackendSnapshot> = snapshot
    override suspend fun reconcile(ownership: BackendOwnership) = BackendReconciliationResult(
        com.mikeyphw.xdm.android.model.BackendReconciliationClassification.ResumableArtifact,
        "resumable",
        safeToResume = true,
    )
    override suspend fun inspectForMigration(ownership: BackendOwnership) = BackendMigrationInspection(
        backend = type,
        bytesPresent = bytesPresent,
        expectedLength = 1024,
        reuse = if (bytesPresent == 0L) BackendMigrationReuse.Empty else BackendMigrationReuse.RestartRequired,
        remoteValidationRequired = bytesPresent > 0,
        message = if (bytesPresent == 0L) "No bytes written." else "Partial bytes require restart.",
    )
    override suspend fun shutdown() = BackendShutdownResult(true, emptyList())
}

private class FailingTransferOwnershipStore(
    private val delegate: BackendOwnershipStore,
) : BackendOwnershipStore by delegate {
    override suspend fun transfer(
        downloadId: String,
        expectedGeneration: Long,
        sourceBackend: BackendType,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        targetBackend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult = error("simulated ownership transfer failure")
}
