package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
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
    fun failedBackendAddReleasesOwnership() = runBlocking {
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
) : DownloadBackend {
    private val state = MutableStateFlow(BackendSnapshot("task", DownloadState.Queued, 0, null, 0))
    override suspend fun capabilities() = BackendCapabilities(setOf("https"), true, false, true, false)
    override suspend fun add(request: DownloadRequest): BackendTask {
        if (failAdd) error("boom")
        return BackendTask("task-${request.id}", type)
    }
    override suspend fun pause(taskId: String) = Unit
    override suspend fun resume(taskId: String) = Unit
    override suspend fun cancel(taskId: String) = Unit
    override suspend fun remove(taskId: String) = Unit
    override suspend fun query(taskId: String): BackendSnapshot = state.value
    override fun observe(taskId: String): Flow<BackendSnapshot> = state
    override suspend fun shutdown() = BackendShutdownResult(true, emptyList())
}
