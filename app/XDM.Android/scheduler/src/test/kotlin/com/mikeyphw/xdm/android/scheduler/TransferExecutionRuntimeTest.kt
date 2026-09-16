package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.BackendPreparation
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.BackendShutdownResult
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.BackendTask
import com.mikeyphw.xdm.android.transfer.DestinationIdentity
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.InMemoryBackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.InMemoryBackendMigrationStore
import com.mikeyphw.xdm.android.transfer.InMemoryChecksumWorkflowStore
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val runtime = TransferExecutionRuntime(store, InMemoryBackendOwnershipStore(), InMemoryBackendMigrationStore(), InMemoryChecksumWorkflowStore(), backends = emptyList(), scope = this)

        assertEquals(2, runtime.restoreInterruptedTransfers())
        assertEquals(DownloadState.Paused, store.items.getValue("active").state)
        assertEquals(DownloadState.Paused, store.items.getValue("finalizing").state)
        assertEquals(DownloadState.Queued, store.items.getValue("queued").state)
        assertEquals(DownloadState.Completed, store.items.getValue("complete").state)
    }

    @Test
    fun executionPersistsReservedGenerationBeforeBackendActivation() = runTest {
        val item = download("generation", DownloadState.Queued).copy(
            destinationUri = Files.createTempFile("xdm-runtime-generation", ".bin").toUri().toString(),
            attemptGeneration = 1L,
        )
        val store = FakeStore(listOf(item))
        val backend = ActivationFenceBackend(store)
        val runtime = TransferExecutionRuntime(
            store,
            InMemoryBackendOwnershipStore(),
            InMemoryBackendMigrationStore(),
            InMemoryChecksumWorkflowStore(),
            backends = listOf(backend),
            scope = this,
        )

        val state = runtime.execute(item.id, queueClaimToken = 0L)

        assertEquals(DownloadState.Paused, state)
        assertTrue(requireNotNull(backend.preparedGeneration) > item.attemptGeneration)
        assertEquals(backend.preparedGeneration, backend.addedGeneration)
        assertEquals(backend.addedGeneration, backend.generationObservedAtActivation)
        assertEquals(backend.addedGeneration, store.items.getValue(item.id).attemptGeneration)
    }

    private class FakeStore(downloads: List<Download>) : TransferDownloadStore {
        val items = downloads.associateByTo(linkedMapOf(), Download::id)
        override suspend fun find(downloadId: String) = items[downloadId]
        override suspend fun findByStates(states: Set<DownloadState>) = items.values.filter { it.state in states }
        override suspend fun save(download: Download): Boolean { items[download.id] = download; return true }
        override suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) = Unit
        override suspend fun deleteBackendTask(downloadId: String) = Unit
    }

    private class ActivationFenceBackend(
        private val store: FakeStore,
    ) : DownloadBackend {
        override val backendId: String = "activation-fence"
        override val runtimeIdentity = BackendRuntimeIdentity("activation-fence-instance", "activation-fence-session")
        var preparedGeneration: Long? = null
            private set
        var addedGeneration: Long? = null
            private set
        var generationObservedAtActivation: Long? = null
            private set
        private val snapshot = MutableStateFlow(
            BackendSnapshot(
                taskId = "activation-fence-task",
                state = DownloadState.Queued,
                bytesReceived = 0L,
                totalBytes = 32L,
                speedBytesPerSecond = 0L,
            ),
        )

        override suspend fun capabilities() = BackendCapabilities(
            protocols = setOf("https"),
            supportsSegmentation = true,
            supportsMirrors = false,
            supportsSelectiveRepair = true,
            supportsSafDestination = true,
        )

        override suspend fun prepare(request: DownloadRequest): BackendPreparation {
            preparedGeneration = request.attemptGeneration
            require(request.attemptGeneration > 0L)
            val destinationKey = DestinationIdentity.key(request.destinationUri, request.fileName)
            return BackendPreparation(
                preparationId = "activation-fence-prepare",
                downloadId = request.id,
                backend = BackendType.Native,
                destinationKey = destinationKey,
                artifacts = BackendArtifactIdentity(
                    format = "activation-fence-v1",
                    primary = "$destinationKey.g${request.attemptGeneration}.part",
                ),
                runtimeIdentity = runtimeIdentity,
            )
        }

        override suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask {
            addedGeneration = request.attemptGeneration
            require(request.attemptGeneration == preparedGeneration)
            return BackendTask("activation-fence-task", BackendType.Native, requiresActivation = true)
        }

        override suspend fun discardPreparation(preparation: BackendPreparation) = Unit
        override suspend fun onOwnershipAttached(taskId: String, ownership: BackendOwnership) = Unit
        override suspend fun activate(taskId: String) {
            val generation = requireNotNull(addedGeneration)
            generationObservedAtActivation = store.items.getValue("generation").attemptGeneration
            snapshot.value = BackendSnapshot(
                taskId = taskId,
                state = DownloadState.Paused,
                bytesReceived = 0L,
                totalBytes = 32L,
                speedBytesPerSecond = 0L,
                attemptGeneration = generation,
                backendInstanceId = runtimeIdentity.instanceId,
                backendSessionId = runtimeIdentity.sessionId,
            )
        }
        override suspend fun pause(taskId: String) = Unit
        override suspend fun resume(taskId: String) = Unit
        override suspend fun cancel(taskId: String) = Unit
        override suspend fun remove(taskId: String) = Unit
        override suspend fun detach(taskId: String) = true
        override suspend fun query(taskId: String): BackendSnapshot = snapshot.value
        override fun observe(taskId: String): Flow<BackendSnapshot> = snapshot
        override suspend fun reconcile(ownership: BackendOwnership) = BackendReconciliationResult(
            com.mikeyphw.xdm.android.model.BackendReconciliationClassification.ResumableArtifact,
            "Activation-fence test artifact is resumable.",
            safeToResume = true,
        )
        override suspend fun shutdown() = BackendShutdownResult(true, emptyList())
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
