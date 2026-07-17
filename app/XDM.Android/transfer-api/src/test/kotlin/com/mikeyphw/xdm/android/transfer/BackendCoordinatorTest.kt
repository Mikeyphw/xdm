package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendCoordinatorTest {
    @Test
    fun automaticPolicyPrefersNativeForDocumentDestinations() {
        val recommendation = BackendSelectionPolicy().recommend(
            DownloadRequest("1", "https://example.test/file", "content://downloads/file", "file"),
        )
        assertEquals(BackendType.Native, recommendation.backend)
    }

    @Test
    fun automaticPolicyPrefersAria2ForMultiMirrorDownloads() {
        val recommendation = BackendSelectionPolicy().recommend(
            DownloadRequest("1", "https://a.test/file", "file:///tmp/file", "file", mirrors = listOf("https://b.test/file", "https://c.test/file")),
        )
        assertEquals(BackendType.Aria2, recommendation.backend)
    }

    @Test
    fun ownershipIdentityIncludesFileNameForDirectoryDestinations() {
        val first = DestinationIdentity.key("xdm://mediastore/downloads", "first.bin")
        val second = DestinationIdentity.key("xdm://mediastore/downloads", "second.bin")
        assertNotEquals(first, second)
    }

    @Test
    fun coordinatorRejectsUnsupportedDocumentDestination() = runBlocking {
        val native = FakeBackend("native", BackendType.Native)
        val coordinator = BackendCoordinator(BackendRegistry(listOf(native)), InMemoryBackendOwnershipStore())
        val result = runCatching {
            coordinator.add(DownloadRequest("document", "https://example.test/a", "content://downloads/a", "a", preferredBackend = BackendType.Native))
        }
        assertTrue(result.exceptionOrNull() is BackendCapabilityException)
    }

    @Test
    fun coordinatorRejectsTwoDownloadsWritingTheSameDestination() = runBlocking {
        val native = FakeBackend("native", BackendType.Native)
        val coordinator = BackendCoordinator(BackendRegistry(listOf(native)), InMemoryBackendOwnershipStore { 10L })
        val destination = Files.createTempFile("xdm-owner", ".bin").toUri().toString()
        coordinator.add(DownloadRequest("first", "https://example.test/a", destination, "a", preferredBackend = BackendType.Native))
        val result = runCatching {
            coordinator.add(DownloadRequest("second", "https://example.test/b", destination, "b", preferredBackend = BackendType.Native))
        }
        assertTrue(result.exceptionOrNull() is DestinationOwnershipConflictException)
    }

    @Test
    fun coordinatorStoresBackendPreparedPhysicalArtifactIdentity() = runBlocking {
        val backend = FakeBackend("native", BackendType.Native)
        val store = InMemoryBackendOwnershipStore { 10L }
        val destination = Files.createTempFile("xdm-physical", ".bin").toUri().toString()
        val coordinated = BackendCoordinator(BackendRegistry(listOf(backend)), store).add(
            DownloadRequest("physical", "https://example.test/a", destination, "a", preferredBackend = BackendType.Native),
        )
        assertEquals("fake-native-v1", coordinated.ownership.artifacts.format)
        assertTrue(coordinated.ownership.artifacts.primary.endsWith(".fake.part"))
        assertEquals(backend.runtimeIdentity, coordinated.ownership.runtimeIdentity)
    }

    @Test
    fun staleRuntimeSessionCannotSilentlyReclaimOwnership() = runBlocking {
        val store = InMemoryBackendOwnershipStore { 10L }
        val destination = Files.createTempFile("xdm-stale", ".bin").toUri().toString()
        val request = DownloadRequest("stale", "https://example.test/a", destination, "a", preferredBackend = BackendType.Native)
        BackendCoordinator(BackendRegistry(listOf(FakeBackend("native", BackendType.Native, sessionId = "old"))), store).add(request)

        val result = runCatching {
            BackendCoordinator(BackendRegistry(listOf(FakeBackend("native", BackendType.Native, sessionId = "new"))), store).add(request)
        }

        assertTrue(result.exceptionOrNull() is DestinationOwnershipConflictException)
        assertEquals("old", store.findByDownload(request.id)?.runtimeIdentity?.sessionId)
    }

    @Test
    fun reconciledArtifactCanBeAdoptedWithANewGenerationAndSession() = runBlocking {
        val store = InMemoryBackendOwnershipStore { 10L }
        val destination = Files.createTempFile("xdm-adopt", ".bin").toUri().toString()
        val request = DownloadRequest("adopt", "https://example.test/a", destination, "a", preferredBackend = BackendType.Native)
        val oldBackend = FakeBackend("native", BackendType.Native, sessionId = "old")
        val old = BackendCoordinator(BackendRegistry(listOf(oldBackend)), store).add(request).ownership
        val newBackend = FakeBackend("native", BackendType.Native, sessionId = "new", reconcileAsResumable = true)
        val registry = BackendRegistry(listOf(newBackend))

        val result = BackendOwnershipReconciler(registry, store).reconcile(request.id)
        assertEquals(BackendReconciliationClassification.ResumableArtifact, result?.classification)

        val adopted = BackendCoordinator(registry, store).add(request).ownership
        assertTrue(adopted.generation > old.generation)
        assertEquals("new", adopted.runtimeIdentity.sessionId)
        assertEquals(BackendReconciliationClassification.ActiveTaskVerified, adopted.reconciliation)
    }


    @Test
    fun startedTaskIsDetachedAndOwnershipPreservedWhenAttachmentFails() = runBlocking {
        val backend = FakeBackend("native", BackendType.Native)
        val store = FailingAttachOwnershipStore(InMemoryBackendOwnershipStore { 10L })
        val coordinator = BackendCoordinator(BackendRegistry(listOf(backend)), store)
        val request = DownloadRequest(
            "attach-failure",
            "https://example.test/a",
            Files.createTempFile("xdm-attach", ".bin").toUri().toString(),
            "a",
            preferredBackend = BackendType.Native,
        )

        val result = runCatching { coordinator.add(request) }

        assertTrue(result.isFailure)
        assertEquals(listOf("task-attach-failure"), backend.detachedTaskIds)
        val ownership = requireNotNull(store.findByDownload(request.id))
        assertEquals(BackendOwnershipStatus.Reconciled, ownership.status)
        assertEquals(BackendReconciliationClassification.ResumableArtifact, ownership.reconciliation)
    }


    @Test
    fun suspendedBackendTaskActivatesOnlyAfterDurableOwnershipAttachment() = runBlocking {
        val backend = FakeBackend("aria2", BackendType.Aria2, requiresActivation = true)
        val store = InMemoryBackendOwnershipStore { 10L }
        val destination = Files.createTempFile("xdm-aria2-activation", ".bin").toUri().toString()

        BackendCoordinator(BackendRegistry(listOf(backend)), store).add(
            DownloadRequest(
                id = "aria2-activation",
                sourceUrl = "https://example.test/a",
                destinationUri = destination,
                fileName = "a",
                preferredBackend = BackendType.Aria2,
            ),
        )

        assertEquals(listOf("add", "ownership-attached", "activate"), backend.lifecycleEvents)
        assertEquals(BackendOwnershipStatus.Active, store.findByDownload("aria2-activation")?.status)
    }

    @Test
    fun failedBackendAddReleasesNewOwnership() = runBlocking {
        val backend = FakeBackend("native", BackendType.Native, failAdd = true)
        val store = InMemoryBackendOwnershipStore { 10L }
        val coordinator = BackendCoordinator(BackendRegistry(listOf(backend)), store)
        val request = DownloadRequest("failed", "https://example.test/a", Files.createTempFile("xdm-owner", ".bin").toUri().toString(), "a", preferredBackend = BackendType.Native)
        runCatching { coordinator.add(request) }
        assertEquals(null, store.findByDownload(request.id))
    }
}

private class FakeBackend(
    override val backendId: String,
    private val type: BackendType,
    private val failAdd: Boolean = false,
    sessionId: String = "session",
    private val reconcileAsResumable: Boolean = false,
    private val requiresActivation: Boolean = false,
) : DownloadBackend {
    val detachedTaskIds = mutableListOf<String>()
    val lifecycleEvents = mutableListOf<String>()
    override val runtimeIdentity = BackendRuntimeIdentity("instance-$backendId", sessionId)
    private val state = MutableStateFlow(BackendSnapshot("task", DownloadState.Queued, 0, null, 0))

    override suspend fun capabilities() = BackendCapabilities(setOf("https"), true, false, true, false)

    override suspend fun prepare(request: DownloadRequest): BackendPreparation {
        val destinationKey = DestinationIdentity.key(request.destinationUri, request.fileName)
        return BackendPreparation(
            preparationId = UUID.randomUUID().toString(),
            downloadId = request.id,
            backend = type,
            destinationKey = destinationKey,
            artifacts = BackendArtifactIdentity("fake-${backendId}-v1", "$destinationKey.fake.part", listOf("$destinationKey.fake.checkpoint")),
            runtimeIdentity = runtimeIdentity,
        )
    }

    override suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask {
        if (failAdd) error("boom")
        lifecycleEvents += "add"
        return BackendTask("task-${request.id}", type, requiresActivation)
    }

    override suspend fun onOwnershipAttached(taskId: String, ownership: BackendOwnership) {
        lifecycleEvents += "ownership-attached"
    }

    override suspend fun activate(taskId: String) {
        lifecycleEvents += "activate"
    }

    override suspend fun discardPreparation(preparation: BackendPreparation) = Unit
    override suspend fun pause(taskId: String) = Unit
    override suspend fun resume(taskId: String) = Unit
    override suspend fun cancel(taskId: String) = Unit
    override suspend fun remove(taskId: String) = Unit
    override suspend fun detach(taskId: String): Boolean { detachedTaskIds += taskId; return true }
    override suspend fun query(taskId: String): BackendSnapshot = state.value
    override fun observe(taskId: String): Flow<BackendSnapshot> = state
    override suspend fun reconcile(ownership: BackendOwnership) = if (reconcileAsResumable) {
        BackendReconciliationResult(
            BackendReconciliationClassification.ResumableArtifact,
            "Fake artifact can be adopted.",
            safeToResume = true,
        )
    } else {
        BackendReconciliationResult(
            BackendReconciliationClassification.BackendTaskOrphaned,
            "Fake backend task is not attached.",
        )
    }
    override suspend fun shutdown() = BackendShutdownResult(true, emptyList())
}


private class FailingAttachOwnershipStore(
    private val delegate: BackendOwnershipStore,
) : BackendOwnershipStore by delegate {
    override suspend fun attachTask(downloadId: String, generation: Long, backendTaskId: String): BackendOwnership =
        error("simulated durable attachment failure")
}
