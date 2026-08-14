package com.mikeyphw.xdm.android.transfer.nativeengine

import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.storage.DestinationConflict
import com.mikeyphw.xdm.android.storage.DestinationHealth
import com.mikeyphw.xdm.android.storage.DestinationPromotionResult
import com.mikeyphw.xdm.android.storage.DestinationPublicationException
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.storage.DestinationWriter
import com.mikeyphw.xdm.android.storage.FileDestinationWriter
import com.mikeyphw.xdm.android.storage.PreparedDestination
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeHttpDownloadBackendTest {
    private lateinit var server: HttpServer
    private lateinit var payload: ByteArray
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var scope: CoroutineScope
    private lateinit var executor: ExecutorService
    private val retryAttempts = AtomicInteger()

    @Before
    fun setUp() {
        payload = ByteArray(512 * 1024) { index -> (index * 31).toByte() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/file") { exchange -> serveRangeFile(exchange, "\"stable\"") }
        server.createContext("/changed") { exchange -> serveRangeFile(exchange, "\"new\"") }
        server.createContext("/invalid-range") { exchange -> serveInvalidRange(exchange) }
        server.createContext("/retry") { exchange -> serveRetryingRange(exchange) }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun segmentedDownloadProducesTheOriginalFile() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native")
        val destination = directory.resolve("payload.bin")
        val backend = NativeHttpDownloadBackend(
            OkHttpClient(), scope,
            NativeTransferConfig(defaultConnections = 4, segmentThresholdBytes = 64 * 1024, checkpointIntervalBytes = 32 * 1024, maximumRetries = 1, baseRetryDelayMillis = 1),
        )
        val task = startOwned(backend, request("segmented", "/file", destination, maxConnections = 4))
        val completed = withTimeout(15_000) { backend.observe(task.taskId).first { it.state in terminalStates } }
        assertEquals(completed.errorMessage, DownloadState.Completed, completed.state)
        assertArrayEquals(payload, Files.readAllBytes(destination))
        assertTrue("Expected multiple range requests", ranges.filterNotNull().distinct().size >= 2)
    }

    @Test
    fun validCheckpointResumesFromPersistedOffset() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-resume")
        val destination = directory.resolve("payload.bin")
        val paths = NativeArtifactPaths.fromDestinationUri(destination.toUri().toString())
        val prefix = 100_000
        Files.write(paths.partial, payload.copyOfRange(0, prefix))
        NativeCheckpointStore().save(
            paths.checkpoint,
            NativeCheckpoint(
                downloadId = "resume",
                sourceUrl = url("/file"),
                effectiveUrl = url("/file"),
                destinationPath = destination.toString(),
                partialPath = paths.partial.toString(),
                expectedLength = payload.size.toLong(),
                etag = "\"stable\"",
                lastModified = null,
                rangeSupported = true,
                segments = listOf(NativeSegmentCheckpoint(0, 0, payload.size.toLong() - 1, prefix.toLong(), false)),
                persistedAtEpochMs = 1,
                attemptGeneration = 7L,
                backendInstanceId = "native-default",
            ),
        )
        val backend = NativeHttpDownloadBackend(OkHttpClient(), scope, NativeTransferConfig(defaultConnections = 1, segmentThresholdBytes = Long.MAX_VALUE, maximumRetries = 1, baseRetryDelayMillis = 1))
        val task = startOwned(backend, request("resume", "/file", destination, maxConnections = 1))
        val completed = withTimeout(15_000) { backend.observe(task.taskId).first { it.state in terminalStates } }
        assertEquals(completed.errorMessage, DownloadState.Completed, completed.state)
        assertArrayEquals(payload, Files.readAllBytes(destination))
        assertTrue(ranges.any { it == "bytes=$prefix-${payload.size - 1}" })
    }

    @Test
    fun changedEtagMovesTheTaskToRecoveryRequired() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-changed")
        val destination = directory.resolve("payload.bin")
        val paths = NativeArtifactPaths.fromDestinationUri(destination.toUri().toString())
        Files.write(paths.partial, payload.copyOfRange(0, 32))
        NativeCheckpointStore().save(
            paths.checkpoint,
            NativeCheckpoint(
                downloadId = "changed",
                sourceUrl = url("/changed"),
                effectiveUrl = url("/changed"),
                destinationPath = destination.toString(),
                partialPath = paths.partial.toString(),
                expectedLength = payload.size.toLong(),
                etag = "\"old\"",
                lastModified = null,
                rangeSupported = true,
                segments = listOf(NativeSegmentCheckpoint(0, 0, payload.size.toLong() - 1, 32, false)),
                persistedAtEpochMs = 1,
                attemptGeneration = 7L,
                backendInstanceId = "native-default",
            ),
        )
        val backend = NativeHttpDownloadBackend(OkHttpClient(), scope, NativeTransferConfig(maximumRetries = 0))
        val task = startOwned(backend, request("changed", "/changed", destination, maxConnections = 1))
        val terminal = withTimeout(15_000) { backend.observe(task.taskId).first { it.state in terminalStates } }
        assertEquals(DownloadState.RecoveryRequired, terminal.state)
        assertTrue(terminal.errorMessage.orEmpty().contains("ETag"))
    }


    @Test
    fun malformedContentRangeFailsWithoutFinalizing() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-invalid-range")
        val destination = directory.resolve("payload.bin")
        val backend = NativeHttpDownloadBackend(OkHttpClient(), scope, NativeTransferConfig(defaultConnections = 1, maximumRetries = 0))
        val task = startOwned(backend, request("invalid-range", "/invalid-range", destination, maxConnections = 1))
        val terminal = withTimeout(15_000) { backend.observe(task.taskId).first { it.state in terminalStates } }
        assertEquals(DownloadState.Failed, terminal.state)
        assertTrue(terminal.errorMessage.orEmpty().contains("Content-Range"))
        assertTrue(!Files.exists(destination))
    }

    @Test
    fun retryableServerFailureUsesBackoffAndCompletes() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-retry")
        val destination = directory.resolve("payload.bin")
        val backend = NativeHttpDownloadBackend(
            OkHttpClient(),
            scope,
            NativeTransferConfig(defaultConnections = 1, segmentThresholdBytes = Long.MAX_VALUE, maximumRetries = 2, baseRetryDelayMillis = 1),
        )
        val task = startOwned(backend, request("retry", "/retry", destination, maxConnections = 1))
        val terminal = withTimeout(15_000) { backend.observe(task.taskId).first { it.state in terminalStates } }
        assertEquals(terminal.errorMessage, DownloadState.Completed, terminal.state)
        assertArrayEquals(payload, Files.readAllBytes(destination))
        assertTrue(retryAttempts.get() >= 2)
    }


    @Test
    fun finalSaveRecoveryRetriesPublicationWithoutNetworkRedownload() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-final-save")
        val destination = directory.resolve("payload.bin")
        val writer = FailOnceDestinationWriter(FileDestinationWriter(directory.toFile()))
        val backend = NativeHttpDownloadBackend(
            OkHttpClient(),
            scope,
            NativeTransferConfig(defaultConnections = 1, segmentThresholdBytes = Long.MAX_VALUE, maximumRetries = 0),
            destinationWriter = writer,
        )
        val task = startOwned(backend, request("final-save", "/file", destination, maxConnections = 1))
        val recovery = withTimeout(15_000) { backend.observe(task.taskId).first { it.state == DownloadState.RecoveryRequired } }
        assertTrue(recovery.errorMessage.orEmpty().startsWith("Final save failed"))
        assertTrue(writer.lastPrepared?.artifacts?.stagingFile?.isFile == true)
        assertEquals(1, writer.promotionAttempts)

        server.stop(0)
        backend.resume(task.taskId)
        val completed = withTimeout(15_000) { backend.observe(task.taskId).first { it.state == DownloadState.Completed } }

        assertEquals(2, writer.promotionAttempts)
        assertEquals(payload.size.toLong(), completed.bytesReceived)
        assertArrayEquals(payload, Files.readAllBytes(destination))
    }

    @Test
    fun reconciliationAcceptsMatchingPhysicalPartialAndCheckpoint() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-reconcile")
        val destination = directory.resolve("payload.bin")
        val identity = BackendRuntimeIdentity("native-install", "new-session")
        val backend = NativeHttpDownloadBackend(OkHttpClient(), scope, runtimeIdentity = identity)
        val request = request("reconcile", "/file", destination, maxConnections = 1)
        val preparation = backend.prepare(request)
        val partial = Paths.get(java.net.URI(preparation.artifacts.primary))
        val checkpoint = Paths.get(java.net.URI(preparation.artifacts.companions.first { it.endsWith(".checkpoint.json") }))
        Files.write(partial, payload.copyOfRange(0, 64))
        NativeCheckpointStore().save(
            checkpoint,
            NativeCheckpoint(
                downloadId = request.id,
                sourceUrl = request.sourceUrl,
                effectiveUrl = request.sourceUrl,
                destinationPath = destination.toString(),
                partialPath = partial.toString(),
                expectedLength = payload.size.toLong(),
                etag = "\"stable\"",
                lastModified = null,
                rangeSupported = true,
                segments = listOf(NativeSegmentCheckpoint(0, 0, payload.size.toLong() - 1, 64, false)),
                persistedAtEpochMs = 1,
                attemptGeneration = 7L,
                backendInstanceId = "native-install",
                backendSessionId = "old-session",
            ),
        )

        val result = backend.reconcile(ownership(request.id, preparation, identity.copy(sessionId = "old-session")))

        assertEquals(BackendReconciliationClassification.ResumableArtifact, result.classification)
        assertTrue(result.safeToResume)
    }

    @Test
    fun reconciliationRejectsArtifactsFromAnotherBackendInstallation() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-instance")
        val destination = directory.resolve("payload.bin")
        val identity = BackendRuntimeIdentity("current-install", "new-session")
        val backend = NativeHttpDownloadBackend(OkHttpClient(), scope, runtimeIdentity = identity)
        val request = request("instance-mismatch", "/file", destination, maxConnections = 1)
        val preparation = backend.prepare(request)

        val result = backend.reconcile(
            ownership(
                request.id,
                preparation,
                BackendRuntimeIdentity("different-install", "old-session"),
            ),
        )

        assertEquals(BackendReconciliationClassification.ConflictingArtifact, result.classification)
        assertTrue(!result.safeToResume)
    }

    @Test
    fun reconciliationQuarantinesMissingPhysicalPartial() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-missing")
        val destination = directory.resolve("payload.bin")
        val identity = BackendRuntimeIdentity("native-install", "new-session")
        val backend = NativeHttpDownloadBackend(OkHttpClient(), scope, runtimeIdentity = identity)
        val request = request("missing", "/file", destination, maxConnections = 1)
        val preparation = backend.prepare(request)

        val result = backend.reconcile(ownership(request.id, preparation, identity.copy(sessionId = "old-session")))

        assertEquals(BackendReconciliationClassification.MissingArtifact, result.classification)
        assertTrue(!result.safeToResume)
    }

    @Test
    fun reconciliationRejectsCheckpointForAnotherDownload() = runBlocking {
        val directory = Files.createTempDirectory("xdm-native-conflict")
        val destination = directory.resolve("payload.bin")
        val identity = BackendRuntimeIdentity("native-install", "new-session")
        val backend = NativeHttpDownloadBackend(OkHttpClient(), scope, runtimeIdentity = identity)
        val request = request("expected", "/file", destination, maxConnections = 1)
        val preparation = backend.prepare(request)
        val partial = Paths.get(java.net.URI(preparation.artifacts.primary))
        val checkpoint = Paths.get(java.net.URI(preparation.artifacts.companions.first { it.endsWith(".checkpoint.json") }))
        Files.write(partial, payload.copyOfRange(0, 64))
        NativeCheckpointStore().save(
            checkpoint,
            NativeCheckpoint(
                downloadId = "different-download",
                sourceUrl = request.sourceUrl,
                effectiveUrl = request.sourceUrl,
                destinationPath = destination.toString(),
                partialPath = partial.toString(),
                expectedLength = payload.size.toLong(),
                etag = "\"stable\"",
                lastModified = null,
                rangeSupported = true,
                segments = listOf(NativeSegmentCheckpoint(0, 0, payload.size.toLong() - 1, 64, false)),
                persistedAtEpochMs = 1,
            ),
        )

        val result = backend.reconcile(ownership(request.id, preparation, identity.copy(sessionId = "old-session")))

        assertEquals(BackendReconciliationClassification.ConflictingArtifact, result.classification)
        assertTrue(!result.safeToResume)
    }

    private fun ownership(
        downloadId: String,
        preparation: com.mikeyphw.xdm.android.transfer.BackendPreparation,
        runtimeIdentity: BackendRuntimeIdentity,
    ) = BackendOwnership(
        downloadId = downloadId,
        destinationKey = preparation.destinationKey,
        artifacts = preparation.artifacts,
        backend = BackendType.Native,
        generation = 7,
        status = BackendOwnershipStatus.Reconciling,
        runtimeIdentity = runtimeIdentity,
        claimedAtEpochMs = 1,
        synchronizedAtEpochMs = 1,
    )

    private suspend fun startOwned(backend: NativeHttpDownloadBackend, request: DownloadRequest): com.mikeyphw.xdm.android.transfer.BackendTask {
        val generation = 7L
        val ownedRequest = request.copy(attemptGeneration = generation)
        val preparation = backend.prepare(ownedRequest)
        val task = backend.add(ownedRequest, preparation)
        val ownership = BackendOwnership(
            downloadId = ownedRequest.id,
            destinationKey = preparation.destinationKey,
            artifacts = preparation.artifacts,
            backend = BackendType.Native,
            generation = generation,
            status = BackendOwnershipStatus.Active,
            runtimeIdentity = backend.runtimeIdentity,
            backendTaskId = task.taskId,
            claimedAtEpochMs = 1L,
            synchronizedAtEpochMs = 1L,
        )
        backend.onOwnershipAttached(task.taskId, ownership)
        backend.activate(task.taskId)
        return task
    }

    private fun request(id: String, path: String, destination: java.nio.file.Path, maxConnections: Int) = DownloadRequest(
        id = id,
        sourceUrl = url(path),
        destinationUri = destination.toUri().toString(),
        fileName = destination.fileName.toString(),
        preferredBackend = BackendType.Native,
        maxConnections = maxConnections,
        privateNetworkApproved = true,
    )

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"


    private fun serveInvalidRange(exchange: HttpExchange) {
        exchange.responseHeaders.add("ETag", "\"stable\"")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("Content-Length", payload.size.toString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }
        val range = exchange.requestHeaders.getFirst("Range") ?: "bytes=0-0"
        val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range) ?: error("bad range")
        val requestedStart = match.groupValues[1].toInt()
        val requestedEnd = match.groupValues[2].takeIf(String::isNotBlank)?.toInt() ?: payload.lastIndex
        val wrongStart = (requestedStart + 1).coerceAtMost(requestedEnd)
        val bytes = payload.copyOfRange(requestedStart, requestedEnd + 1)
        exchange.responseHeaders.add("Content-Range", "bytes $wrongStart-$requestedEnd/${payload.size}")
        exchange.sendResponseHeaders(206, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serveRetryingRange(exchange: HttpExchange) {
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("ETag", "\"stable\"")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Length", payload.size.toString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != "bytes=0-0" && retryAttempts.incrementAndGet() == 1) {
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
            return
        }
        serveRangeFile(exchange, "\"stable\"")
    }

    private fun serveRangeFile(exchange: HttpExchange, etag: String) {
        exchange.responseHeaders.add("ETag", etag)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("Content-Length", payload.size.toString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")
        ranges += range
        if (range == null) {
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            return
        }
        val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range) ?: error("bad range")
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].takeIf(String::isNotBlank)?.toInt() ?: payload.lastIndex
        val bytes = payload.copyOfRange(start, end + 1)
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
        exchange.sendResponseHeaders(206, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class FailOnceDestinationWriter(
        private val delegate: DestinationWriter,
    ) : DestinationWriter {
        var promotionAttempts: Int = 0
            private set
        var lastPrepared: PreparedDestination? = null
            private set

        override val supportsContentDestinations: Boolean
            get() = delegate.supportsContentDestinations

        override fun artifactPaths(request: DestinationRequest) = delegate.artifactPaths(request)

        override suspend fun prepare(request: DestinationRequest): PreparedDestination {
            val prepared = delegate.prepare(request)
            return object : PreparedDestination {
                override val destinationKey: String = prepared.destinationKey
                override val displayName: String = prepared.displayName
                override val artifacts = prepared.artifacts
                override suspend fun availableSpace(): Long? = prepared.availableSpace()
                override suspend fun promote(): DestinationPromotionResult {
                    promotionAttempts++
                    if (promotionAttempts == 1) {
                        throw DestinationPublicationException(
                            message = "simulated provider publication failure",
                            destinationUri = request.destinationUri,
                            stagingPath = artifacts.stagingFile.absolutePath,
                            stagingPreserved = artifacts.stagingFile.isFile,
                        )
                    }
                    return prepared.promote()
                }
                override suspend fun deleteArtifacts() = prepared.deleteArtifacts()
            }.also { lastPrepared = it }
        }

        override suspend fun previewConflict(request: DestinationRequest): DestinationConflict? = delegate.previewConflict(request)
        override suspend fun health(destinationUri: String): DestinationHealth = delegate.health(destinationUri)
    }

    private companion object {
        val terminalStates = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.RecoveryRequired, DownloadState.Cancelled)
    }
}
