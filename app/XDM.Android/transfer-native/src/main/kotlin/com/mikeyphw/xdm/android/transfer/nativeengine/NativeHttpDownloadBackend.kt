package com.mikeyphw.xdm.android.transfer.nativeengine

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendBatteryImpact
import com.mikeyphw.xdm.android.model.BackendDiagnosticDetail
import com.mikeyphw.xdm.android.model.BackendMigrationInspection
import com.mikeyphw.xdm.android.model.BackendMigrationReuse
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.transfer.BackendShutdownResult
import com.mikeyphw.xdm.android.transfer.BackendPreparation
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.BackendTask
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.storage.DestinationWriter
import com.mikeyphw.xdm.android.storage.FileDestinationWriter
import com.mikeyphw.xdm.android.storage.PreparedDestination
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.InetAddress
import java.net.Inet6Address
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class NativeHttpDownloadBackend(
    client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val config: NativeTransferConfig = NativeTransferConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val destinationWriter: DestinationWriter = FileDestinationWriter(),
    override val runtimeIdentity: BackendRuntimeIdentity = BackendRuntimeIdentity("native-default", UUID.randomUUID().toString()),
) : DownloadBackend {
    private val client: OkHttpClient = client.newBuilder()
        .addNetworkInterceptor(NativeRequestSecurityInterceptor())
        .build()
    override val backendId: String = "native"
    private val tasks = ConcurrentHashMap<String, TaskControl>()
    private val preparations = ConcurrentHashMap<String, NativePreparation>()
    private val checkpointStore = NativeCheckpointStore()
    private val globalConnections = Semaphore(config.maximumGlobalConnections.coerceAtLeast(1))
    private val hostConnections = ConcurrentHashMap<String, Semaphore>()
    private val hostRetryBackoff = HostRetryBackoff(clock, random)

    override suspend fun capabilities() = BackendCapabilities(
        protocols = setOf("http", "https"),
        supportsSegmentation = true,
        supportsMirrors = false,
        supportsSelectiveRepair = true,
        supportsSafDestination = destinationWriter.supportsContentDestinations,
        supportsAuthentication = true,
        supportsProxy = true,
        maxConnectionsPerDownload = config.defaultConnections,
        supportsExpiringUrls = true,
        supportsMediaPlaylists = true,
        supportsMigrationImport = false,
        batteryImpact = BackendBatteryImpact.Low,
        diagnosticDetail = BackendDiagnosticDetail.Forensic,
    )

    override suspend fun prepare(request: DownloadRequest): BackendPreparation {
        require(request.sourceUrl.startsWith("http://") || request.sourceUrl.startsWith("https://")) { "Native backend supports HTTP and HTTPS" }
        val preparedDestination = destinationWriter.prepare(request.toDestinationRequest())
        val preparationId = UUID.randomUUID().toString()
        val artifacts = preparedDestination.artifacts.toBackendArtifactIdentity()
        preparations[preparationId] = NativePreparation(request.id, preparedDestination, artifacts)
        return BackendPreparation(
            preparationId = preparationId,
            downloadId = request.id,
            backend = BackendType.Native,
            destinationKey = preparedDestination.destinationKey,
            artifacts = artifacts,
            runtimeIdentity = runtimeIdentity,
        )
    }

    override suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask {
        require(preparation.backend == BackendType.Native) { "Native backend received a foreign preparation" }
        require(preparation.downloadId == request.id) { "Native preparation belongs to a different download" }
        require(preparation.runtimeIdentity == runtimeIdentity) { "Native preparation belongs to a stale runtime session" }
        val nativePreparation = requireNotNull(preparations.remove(preparation.preparationId)) { "Unknown or already consumed native preparation" }
        require(nativePreparation.downloadId == request.id) { "Native preparation download mismatch" }
        require(nativePreparation.artifacts == preparation.artifacts) { "Native preparation artifact identity changed" }
        val taskId = UUID.randomUUID().toString()
        val control = TaskControl(
            request = request,
            preparedDestination = nativePreparation.destination,
            artifacts = nativePreparation.artifacts,
            state = MutableStateFlow(BackendSnapshot(taskId, DownloadState.Queued, 0, request.expectedLength, 0)),
        )
        tasks[taskId] = control
        launch(control)
        return BackendTask(taskId, BackendType.Native)
    }

    override suspend fun discardPreparation(preparation: BackendPreparation) {
        preparations.remove(preparation.preparationId)
    }

    override suspend fun pause(taskId: String) {
        val control = requireTask(taskId)
        if (control.state.value.state in TERMINAL_STATES) return
        control.pauseRequested = true
        control.activeCalls.forEach(Call::cancel)
        control.job?.cancelAndJoin()
        runCatching { control.checkpointFlusher?.invoke() }
        control.state.value = control.state.value.copy(state = DownloadState.Paused, speedBytesPerSecond = 0, errorMessage = null)
    }

    override suspend fun resume(taskId: String) {
        val control = requireTask(taskId)
        require(control.state.value.state != DownloadState.Cancelled) { "Cancelled tasks cannot be resumed" }
        if (control.job?.isActive == true) return
        control.pauseRequested = false
        control.cancelRequested = false
        launch(control)
    }

    override suspend fun cancel(taskId: String) {
        val control = requireTask(taskId)
        control.cancelRequested = true
        control.activeCalls.forEach(Call::cancel)
        control.job?.cancelAndJoin()
        control.state.value = control.state.value.copy(state = DownloadState.Cancelled, speedBytesPerSecond = 0)
    }

    override suspend fun remove(taskId: String) {
        val control = tasks.remove(taskId) ?: return
        control.cancelRequested = true
        control.activeCalls.forEach(Call::cancel)
        control.job?.cancelAndJoin()
        val artifacts = control.preparedDestination.artifacts
        Files.deleteIfExists(artifacts.stagingFile.toPath())
        checkpointStore.delete(artifacts.checkpointFile.toPath())
        Files.deleteIfExists(artifacts.journalFile.toPath())
    }

    override suspend fun detach(taskId: String): Boolean {
        val control = tasks.remove(taskId) ?: return true
        control.pauseRequested = true
        control.activeCalls.forEach(Call::cancel)
        control.job?.cancelAndJoin()
        control.state.value = control.state.value.copy(
            state = DownloadState.Paused,
            speedBytesPerSecond = 0,
            errorMessage = null,
        )
        return control.job?.isActive != true
    }

    override suspend fun query(taskId: String): BackendSnapshot? = tasks[taskId]?.state?.value
    override fun observe(taskId: String): Flow<BackendSnapshot> = tasks[taskId]?.state ?: emptyFlow()

    override suspend fun reconcile(ownership: BackendOwnership): BackendReconciliationResult {
        if (ownership.backend != BackendType.Native || ownership.artifacts.format != NATIVE_ARTIFACT_FORMAT) {
            return BackendReconciliationResult(
                BackendReconciliationClassification.ConflictingArtifact,
                "Ownership does not describe an XDM native artifact set.",
            )
        }
        if (ownership.runtimeIdentity.instanceId != runtimeIdentity.instanceId) {
            return BackendReconciliationResult(
                BackendReconciliationClassification.ConflictingArtifact,
                "The native artifacts belong to a different backend installation instance.",
            )
        }
        val activeTask = ownership.backendTaskId?.let(tasks::get)
        if (activeTask != null) {
            return if (activeTask.artifacts == ownership.artifacts) {
                BackendReconciliationResult(
                    BackendReconciliationClassification.ActiveTaskVerified,
                    "The native task is active and its physical artifacts match the ownership record.",
                    backendTaskId = ownership.backendTaskId,
                )
            } else {
                BackendReconciliationResult(
                    BackendReconciliationClassification.ConflictingArtifact,
                    "The active native task points to a different artifact set.",
                )
            }
        }

        val partial = ownership.artifacts.primary.toFilePathOrNull()
            ?: return BackendReconciliationResult(
                BackendReconciliationClassification.ConflictingArtifact,
                "The native primary artifact is not a local file URI.",
            )
        val checkpointIdentity = ownership.artifacts.companions.firstOrNull { it.endsWith(".checkpoint.json") }
        val checkpoint = checkpointIdentity?.toFilePathOrNull()
        if (!Files.exists(partial)) {
            return BackendReconciliationResult(
                BackendReconciliationClassification.MissingArtifact,
                "The native partial file is missing; ownership remains quarantined.",
            )
        }
        if (checkpoint == null || !Files.exists(checkpoint)) {
            return BackendReconciliationResult(
                BackendReconciliationClassification.OrphanedArtifact,
                "A native partial file exists without its checkpoint.",
            )
        }
        val parsed = runCatching { checkpointStore.load(checkpoint) }.getOrElse { error ->
            return BackendReconciliationResult(
                BackendReconciliationClassification.ConflictingArtifact,
                "The native checkpoint is malformed: ${error.message ?: error::class.java.simpleName}",
            )
        } ?: return BackendReconciliationResult(
            BackendReconciliationClassification.MissingArtifact,
            "The native checkpoint disappeared during reconciliation.",
        )
        if (parsed.downloadId != ownership.downloadId || parsed.partialPath != partial.toString()) {
            return BackendReconciliationResult(
                BackendReconciliationClassification.ConflictingArtifact,
                "The native checkpoint belongs to another download or partial file.",
            )
        }
        return BackendReconciliationResult(
            BackendReconciliationClassification.ResumableArtifact,
            "The previous native session ended, but its partial file and checkpoint are safe to adopt.",
            safeToResume = true,
        )
    }

    override suspend fun inspectForMigration(ownership: BackendOwnership): BackendMigrationInspection {
        val partial = ownership.artifacts.primary.toFilePathOrNull()
            ?: return BackendMigrationInspection(BackendType.Native, 0, null, BackendMigrationReuse.Unsafe, true, "The native partial identity is not a local file.")
        if (!Files.exists(partial)) {
            return BackendMigrationInspection(BackendType.Native, 0, null, BackendMigrationReuse.Unsafe, true, "The native partial file is missing.")
        }
        val checkpointPath = ownership.artifacts.companions.firstOrNull { it.endsWith(".checkpoint.json") }?.toFilePathOrNull()
        val checkpoint = checkpointPath?.takeIf(Files::exists)?.let { runCatching { checkpointStore.load(it) }.getOrNull() }
        if (checkpoint == null) {
            val length = Files.size(partial)
            return BackendMigrationInspection(
                BackendType.Native,
                length,
                null,
                if (length == 0L) BackendMigrationReuse.Empty else BackendMigrationReuse.RestartRequired,
                length > 0,
                if (length == 0L) "The native staging file is empty and can switch backends safely." else "The native partial has no trustworthy checkpoint and must be preserved while the target restarts from zero.",
            )
        }
        val completed = checkpoint.segments.sumOf(NativeSegmentCheckpoint::completedBytes)
        val expected = checkpoint.expectedLength
        val reuse = when {
            completed == 0L -> BackendMigrationReuse.Empty
            expected != null && completed == expected -> BackendMigrationReuse.Complete
            checkpoint.segments.sortedBy(NativeSegmentCheckpoint::startByte).fold(0L) { cursor, segment ->
                if (segment.completedBytes == 0L) cursor
                else if (segment.startByte == cursor) cursor + segment.completedBytes
                else Long.MIN_VALUE
            } >= 0L -> BackendMigrationReuse.ContiguousPrefix
            else -> BackendMigrationReuse.RestartRequired
        }
        return BackendMigrationInspection(
            backend = BackendType.Native,
            bytesPresent = completed,
            expectedLength = expected,
            reuse = reuse,
            remoteValidationRequired = completed > 0,
            message = when (reuse) {
                BackendMigrationReuse.Empty -> "No native payload bytes need migration."
                BackendMigrationReuse.Complete -> "The native staging file is complete; finish verification instead of switching engines."
                BackendMigrationReuse.ContiguousPrefix -> "The native checkpoint contains a contiguous prefix. XDM preserves it and restarts the target backend unless a verified importer is available."
                BackendMigrationReuse.RestartRequired -> "The native checkpoint contains non-contiguous ranges; the target must restart while the original artifacts remain preserved."
                BackendMigrationReuse.Unsafe -> "The native artifacts cannot be inspected safely."
            },
        )
    }

    override suspend fun shutdown(): BackendShutdownResult {
        val active = tasks.filterValues { it.job?.isActive == true }.keys.toList()
        active.forEach { pause(it) }
        return BackendShutdownResult(clean = true, activeTaskIds = active)
    }

    private fun launch(control: TaskControl) {
        control.job = scope.launch transfer@ {
            try {
                runTransfer(control)
            } catch (_: CancellationException) {
                if (!control.pauseRequested && !control.cancelRequested) throw CancellationException()
            } catch (changed: RemoteObjectChangedException) {
                control.state.value = control.state.value.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = changed.message)
            } catch (error: Throwable) {
                control.activeCalls.forEach(Call::cancel)
                if (control.pauseRequested || control.cancelRequested) return@transfer
                control.state.value = control.state.value.copy(state = DownloadState.Failed, speedBytesPerSecond = 0, errorMessage = error.message ?: error::class.java.simpleName)
            } finally {
                control.activeCalls.clear()
                control.checkpointFlusher = null
            }
        }
    }

    private suspend fun runTransfer(control: TaskControl) = withContext(Dispatchers.IO) {
        control.state.value = control.state.value.copy(state = DownloadState.Connecting, errorMessage = null)
        val preparedDestination = control.preparedDestination
        val paths = NativeArtifactPaths(
            destinationIdentity = preparedDestination.destinationKey,
            partial = preparedDestination.artifacts.stagingFile.toPath(),
            checkpoint = preparedDestination.artifacts.checkpointFile.toPath(),
        )
        Files.createDirectories(paths.partial.parent)
        val previous = checkpointStore.load(paths.checkpoint)
        val metadata = probe(control, control.request)
        val trustedLength = metadata.totalLength ?: control.request.expectedLength ?: previous?.expectedLength
        val availableSpace = preparedDestination.availableSpace()
        val alreadyPresent = previous?.segments?.sumOf(NativeSegmentCheckpoint::completedBytes) ?: 0L
        if (trustedLength != null && availableSpace != null && (trustedLength - alreadyPresent).coerceAtLeast(0L) > availableSpace) {
            throw IOException("Insufficient destination space: ${(trustedLength - alreadyPresent).coerceAtLeast(0L)} bytes required, $availableSpace available")
        }
        validateResume(control.request, paths, previous, metadata)
        val segments = createSegments(control.request, paths, previous, metadata)
        val mutableSegments = segments.toMutableList()
        val checkpointMutex = Mutex()
        if (trustedLength != null && metadata.rangeSupported && mutableSegments.size > 1) {
            RandomAccessFile(paths.partial.toFile(), "rw").use { file -> file.setLength(trustedLength) }
        }
        saveCheckpoint(control.request, paths, metadata, mutableSegments, checkpointMutex)
        val startedAt = clock()
        control.state.value = control.state.value.copy(
            state = DownloadState.Downloading,
            totalBytes = trustedLength,
            effectiveUrl = metadata.effectiveUrl,
            etag = metadata.etag,
            lastModified = metadata.lastModified,
            rangeSupported = metadata.rangeSupported,
        )
        val semaphore = Semaphore(min(control.request.maxConnections.coerceAtLeast(1), config.defaultConnections.coerceAtLeast(1)))
        control.checkpointFlusher = { saveCheckpoint(control.request, paths, metadata, mutableSegments, checkpointMutex) }
        try {
            coroutineScope {
                mutableSegments.indices.map { segmentIndex ->
                    async {
                        semaphore.withPermit {
                            downloadSegment(control, paths, metadata, mutableSegments, segmentIndex, checkpointMutex, startedAt, trustedLength)
                        }
                    }
                }.awaitAll()
            }
        } catch (error: Throwable) {
            control.activeCalls.forEach(Call::cancel)
            throw error
        }
        RandomAccessFile(paths.partial.toFile(), "rw").use { it.channel.force(true) }
        trustedLength?.let { expected ->
            check(Files.size(paths.partial) == expected) { "Downloaded file length does not match the trusted length" }
        }
        control.state.value = control.state.value.copy(state = DownloadState.Finalizing, speedBytesPerSecond = 0)
        val promotion = preparedDestination.promote()
        checkpointStore.delete(paths.checkpoint)
        control.state.value = control.state.value.copy(
            state = DownloadState.Completed,
            bytesReceived = promotion.bytesCommitted,
            totalBytes = trustedLength ?: promotion.bytesCommitted,
            speedBytesPerSecond = 0,
            completedUri = promotion.committedUri,
        )
    }

    private suspend fun downloadSegment(
        control: TaskControl,
        paths: NativeArtifactPaths,
        metadata: RemoteMetadata,
        segments: MutableList<NativeSegmentCheckpoint>,
        segmentIndex: Int,
        checkpointMutex: Mutex,
        startedAt: Long,
        trustedLength: Long?,
    ) {
        var segment = checkpointMutex.withLock { segments[segmentIndex] }
        if (segment.complete) return
        val requestEnd = segment.endByteInclusive
        val retryHost = URI(metadata.effectiveUrl).host.orEmpty().lowercase()
        retrying(retryHost) {
            val requestStart = checkpointMutex.withLock {
                val current = segments[segmentIndex]
                if (!metadata.rangeSupported && current.completedBytes != 0L) {
                    segments[segmentIndex] = current.copy(completedBytes = 0, complete = false)
                    current.startByte
                } else {
                    current.startByte + current.completedBytes
                }
            }
            val bytesAtAttemptStart = checkpointMutex.withLock { segments.sumOf(NativeSegmentCheckpoint::completedBytes) }
            val useRange = metadata.rangeSupported && (requestStart > 0 || requestEnd != null || segments.size > 1)
            val builder = newTransferRequestBuilder(control.request, metadata.effectiveUrl)
            if (useRange) {
                builder.header("Range", "bytes=$requestStart-${requestEnd?.toString().orEmpty()}")
                val ifRange = metadata.etag ?: metadata.lastModified
                if (!ifRange.isNullOrBlank()) builder.header("If-Range", ifRange)
            }
            val host = URI(metadata.effectiveUrl).host.orEmpty().lowercase()
            val hostSemaphore = hostConnections.computeIfAbsent(host) { Semaphore(config.maximumConnectionsPerHost.coerceAtLeast(1)) }
            globalConnections.withPermit {
                hostSemaphore.withPermit {
                    execute(control, builder.build()).use { response ->
                        validateResponse(response, useRange, requestStart, requestEnd, trustedLength)
                        rejectUnexpectedHtmlOrCompressedResponse(response, control.request)
                        val body = requireNotNull(response.body) { "Server returned no response body" }
                        RandomAccessFile(paths.partial.toFile(), "rw").use { file ->
                            if (!metadata.rangeSupported) file.setLength(0)
                            file.seek(requestStart)
                            body.byteStream().use { input ->
                                val buffer = ByteArray(config.bufferBytes)
                                var bytesSinceCheckpoint = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    file.write(buffer, 0, read)
                                    bytesSinceCheckpoint += read
                                    checkpointMutex.withLock {
                                        val current = segments[segmentIndex]
                                        segment = current.copy(completedBytes = current.completedBytes + read)
                                        segments[segmentIndex] = segment
                                        val totalReceived = segments.sumOf(NativeSegmentCheckpoint::completedBytes)
                                        val elapsedMillis = (clock() - startedAt).coerceAtLeast(1)
                                        val currentAttemptBytes = (totalReceived - bytesAtAttemptStart).coerceAtLeast(0L)
                                        control.state.value = control.state.value.copy(
                                            bytesReceived = totalReceived,
                                            speedBytesPerSecond = currentAttemptBytes * 1000 / elapsedMillis,
                                        )
                                    }
                                    if (bytesSinceCheckpoint >= config.checkpointIntervalBytes) {
                                        file.channel.force(false)
                                        saveCheckpoint(control.request, paths, metadata, segments, checkpointMutex)
                                        bytesSinceCheckpoint = 0
                                    }
                                }
                            }
                            file.channel.force(false)
                        }
                    }
                }
            }
        }
        checkpointMutex.withLock {
            val current = segments[segmentIndex]
            val expectedBytes = current.endByteInclusive?.let { it - current.startByte + 1 }
            if (expectedBytes != null && current.completedBytes != expectedBytes) {
                throw IOException("Segment ${current.index} ended at ${current.completedBytes} of $expectedBytes bytes")
            }
            segments[segmentIndex] = current.copy(complete = true)
        }
        saveCheckpoint(control.request, paths, metadata, segments, checkpointMutex)
    }

    private suspend fun saveCheckpoint(
        request: DownloadRequest,
        paths: NativeArtifactPaths,
        metadata: RemoteMetadata,
        segments: List<NativeSegmentCheckpoint>,
        mutex: Mutex,
    ) = mutex.withLock {
        checkpointStore.save(
            paths.checkpoint,
            NativeCheckpoint(
                downloadId = request.id,
                sourceUrl = ExternalUrlPolicy.persistableUrl(request.sourceUrl) ?: request.sourceUrl.substringBefore('?'),
                effectiveUrl = ExternalUrlPolicy.persistableUrl(metadata.effectiveUrl) ?: metadata.effectiveUrl.substringBefore('?'),
                destinationPath = paths.destinationIdentity,
                partialPath = paths.partial.toString(),
                expectedLength = metadata.totalLength,
                etag = metadata.etag,
                lastModified = metadata.lastModified,
                rangeSupported = metadata.rangeSupported,
                segments = segments.toList(),
                persistedAtEpochMs = clock(),
            ),
        )
    }

    private fun createSegments(request: DownloadRequest, paths: NativeArtifactPaths, previous: NativeCheckpoint?, metadata: RemoteMetadata): List<NativeSegmentCheckpoint> {
        if (previous != null) return normalizePreviousSegments(previous.segments)
        val total = metadata.totalLength
        if (Files.exists(paths.partial)) Files.delete(paths.partial)
        if (!metadata.rangeSupported || total == null || total < config.segmentThresholdBytes || request.maxConnections <= 1) {
            val existing = 0L
            return listOf(NativeSegmentCheckpoint(0, 0, total?.minus(1), existing, total != null && existing == total))
        }
        val count = min(request.maxConnections, config.defaultConnections).coerceAtLeast(1)
        val segmentSize = (total + count - 1) / count
        return (0 until count).mapNotNull { index ->
            val start = index * segmentSize
            if (start >= total) return@mapNotNull null
            val end = min(total - 1, start + segmentSize - 1)
            NativeSegmentCheckpoint(index, start, end, 0, false)
        }
    }

    private fun validateResume(request: DownloadRequest, paths: NativeArtifactPaths, checkpoint: NativeCheckpoint?, metadata: RemoteMetadata) {
        request.expectedLength?.let { expected ->
            if (metadata.totalLength != null && metadata.totalLength != expected) throw RemoteObjectChangedException("Remote length differs from the expected length")
        }
        request.expectedEtag?.let { expected ->
            if (metadata.etag != null && metadata.etag != expected) throw RemoteObjectChangedException("Remote ETag differs from the expected ETag")
        }
        request.expectedLastModified?.let { expected ->
            if (metadata.lastModified != null && metadata.lastModified != expected) throw RemoteObjectChangedException("Remote Last-Modified differs from the expected value")
        }
        if (checkpoint == null) return
        val requestIdentity = ExternalUrlPolicy.persistableUrl(request.sourceUrl) ?: request.sourceUrl.substringBefore('?')
        if (checkpoint.downloadId != request.id || checkpoint.sourceUrl != requestIdentity) throw RemoteObjectChangedException("Checkpoint does not belong to this download")
        if (!destinationIdentityMatches(checkpoint.destinationPath, paths.destinationIdentity) || checkpoint.partialPath != paths.partial.toString()) {
            throw RemoteObjectChangedException("Checkpoint destination does not match this download")
        }
        val metadataIdentity = ExternalUrlPolicy.persistableUrl(metadata.effectiveUrl) ?: metadata.effectiveUrl.substringBefore('?')
        if (checkpoint.effectiveUrl != metadataIdentity) throw RemoteObjectChangedException("Remote redirect target changed since the checkpoint")
        if (checkpoint.expectedLength != null && metadata.totalLength != null && checkpoint.expectedLength != metadata.totalLength) throw RemoteObjectChangedException("Remote length changed since the checkpoint")
        if (checkpoint.etag != null && metadata.etag == null) throw RemoteObjectChangedException("Remote ETag validator disappeared since the checkpoint")
        if (checkpoint.etag != null && metadata.etag != checkpoint.etag) throw RemoteObjectChangedException("Remote ETag changed since the checkpoint")
        if (checkpoint.etag == null && checkpoint.lastModified != null && metadata.lastModified == null) throw RemoteObjectChangedException("Remote Last-Modified validator disappeared since the checkpoint")
        if (checkpoint.etag == null && checkpoint.lastModified != null && metadata.lastModified != checkpoint.lastModified) throw RemoteObjectChangedException("Remote Last-Modified changed since the checkpoint")
        if (!Files.exists(paths.partial)) throw RemoteObjectChangedException("Checkpoint exists but the partial file is missing")
        val completedBytes = checkpoint.segments.sumOf(NativeSegmentCheckpoint::completedBytes)
        if (checkpoint.segments.size > 1 && !metadata.rangeSupported) throw RemoteObjectChangedException("Server no longer supports byte ranges required by the segmented checkpoint")
        if (completedBytes > 0 && !metadata.rangeSupported) throw RemoteObjectChangedException("Server no longer supports byte ranges required by the checkpoint")
        checkpoint.segments.forEach { segment ->
            require(segment.startByte >= 0 && segment.completedBytes >= 0) { "Checkpoint contains a negative segment range" }
            val segmentLength = segment.endByteInclusive?.let { it - segment.startByte + 1 }
            if (segmentLength != null && segment.completedBytes > segmentLength) throw RemoteObjectChangedException("Checkpoint segment exceeds its declared range")
        }
        val minimumLength = checkpoint.segments.maxOfOrNull { it.startByte + it.completedBytes } ?: 0L
        val actualLength = Files.size(paths.partial)
        if (actualLength < minimumLength) throw RemoteObjectChangedException("Partial file is shorter than the persisted segment checkpoint")
        metadata.totalLength?.let { if (actualLength > it) throw RemoteObjectChangedException("Partial file is longer than the remote object") }
    }

    private fun probe(control: TaskControl, request: DownloadRequest): RemoteMetadata {
        val headBuilder = newTransferRequestBuilder(request, request.sourceUrl).head()
        val head = execute(control, headBuilder.build())
        head.use { response ->
            if (response.isSuccessful) {
                val length = response.header("Content-Length")?.toLongOrNull()
                if (length != null && response.code !in setOf(405, 501)) {
                    val range = rangeProbe(control, request, response.request.url.toString())
                    return range.copy(totalLength = range.totalLength ?: length)
                }
            }
        }
        return rangeProbe(control, request, request.sourceUrl)
    }

    private fun rangeProbe(control: TaskControl, request: DownloadRequest, url: String): RemoteMetadata {
        val builder = newTransferRequestBuilder(request, url)
        builder.header("Range", "bytes=0-0")
        execute(control, builder.build()).use { response ->
            if (!response.isSuccessful) throw HttpTransferException(response.code, metadataProbeFailureMessage(response.code, request))
            val total = if (response.code == 206) parseContentRange(response.header("Content-Range")).third else response.header("Content-Length")?.toLongOrNull()
            return metadataFrom(response, total, response.code == 206)
        }
    }

    private fun newTransferRequestBuilder(request: DownloadRequest, url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .applySecurityContext(request)
            .applyBrowserLikeDefaults(request)
        request.headers.forEach { (name, value) ->
            if (!isEngineOwnedHeader(name)) builder.header(name, value)
        }
        return builder
    }

    private fun isEngineOwnedHeader(name: String): Boolean = name.equals("Range", true) ||
        name.equals("If-Range", true) ||
        name.equals("Host", true) ||
        name.equals("Content-Length", true) ||
        name.equals("Connection", true) ||
        name.equals("Transfer-Encoding", true)

    private fun normalizePreviousSegments(segments: List<NativeSegmentCheckpoint>): List<NativeSegmentCheckpoint> = segments.map { segment ->
        val length = segment.endByteInclusive?.let { it - segment.startByte + 1 }
        if (length != null && segment.completedBytes == length && !segment.complete) segment.copy(complete = true) else segment
    }

    private fun Request.Builder.applySecurityContext(request: DownloadRequest): Request.Builder = apply {
        tag(
            NativeRequestSecurityContext::class.java,
            NativeRequestSecurityContext(
                originalScheme = URI(request.sourceUrl).scheme.orEmpty().lowercase(),
                privateNetworkApproved = request.privateNetworkApproved,
                cleartextCredentialsApproved = request.cleartextCredentialsApproved,
            ),
        )
    }

    private fun Request.Builder.applyBrowserLikeDefaults(request: DownloadRequest): Request.Builder = apply {
        val supplied = request.headers.keys.map { it.lowercase() }.toSet()
        if ("user-agent" !in supplied) header("User-Agent", DEFAULT_USER_AGENT)
        if ("accept" !in supplied) header("Accept", defaultAcceptHeader(request))
        if ("accept-language" !in supplied) header("Accept-Language", "en-US,en;q=0.9")
        if ("accept-encoding" !in supplied) header("Accept-Encoding", "identity")
        if (request.isMediaRequest && "sec-fetch-mode" !in supplied) header("Sec-Fetch-Mode", "cors")
        if (request.isMediaRequest && "sec-fetch-site" !in supplied) header("Sec-Fetch-Site", "cross-site")
    }

    private fun defaultAcceptHeader(request: DownloadRequest): String = when {
        request.mimeType?.startsWith("video/", ignoreCase = true) == true -> "video/*,*/*;q=0.8"
        request.mimeType?.startsWith("audio/", ignoreCase = true) == true -> "audio/*,*/*;q=0.8"
        request.isMediaRequest -> "video/*,audio/*,application/vnd.apple.mpegurl,application/dash+xml,*/*;q=0.8"
        else -> "*/*"
    }

    private fun metadataProbeFailureMessage(statusCode: Int, request: DownloadRequest): String {
        val sessionHint = if (request.headers.keys.any { it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true) }) {
            "The server rejected the supplied browser session; refresh the source page and hand it off again."
        } else {
            "The server requires browser session context such as cookies, authorization, or a valid referer; refresh the source page and share the media again from the active browser."
        }
        return if (statusCode == 401 || statusCode == 403) {
            "Server access was denied (HTTP $statusCode). Authentication required. $sessionHint"
        } else {
            "The server rejected the download probe (HTTP $statusCode). Try a refreshed browser handoff or another engine."
        }
    }

    private fun execute(control: TaskControl, request: Request): Response {
        val call = client.newCall(request)
        control.activeCalls += call
        try {
            return call.execute()
        } finally {
            control.activeCalls.remove(call)
        }
    }

    private fun metadataFrom(response: Response, length: Long?, ranges: Boolean) = RemoteMetadata(
        effectiveUrl = response.request.url.toString(),
        totalLength = length,
        etag = response.header("ETag"),
        lastModified = response.header("Last-Modified"),
        rangeSupported = ranges,
    )

    private fun validateResponse(response: Response, rangeExpected: Boolean, expectedStart: Long, expectedEnd: Long?, expectedTotal: Long?) {
        if (response.code == 429 || response.code in 500..599) throw HttpTransferException(response.code, "Retryable HTTP ${response.code}", response.retryAfterMillis())
        if (!response.isSuccessful) throw HttpTransferException(response.code, "HTTP ${response.code}")
        if (!rangeExpected) {
            if (response.code != 200) throw InvalidRangeResponseException("Expected a complete response but received HTTP ${response.code}")
            return
        }
        if (response.code != 206) throw InvalidRangeResponseException("Server ignored the requested byte range")
        val (start, end, total) = parseContentRange(response.header("Content-Range"))
        if (start != expectedStart || (expectedEnd != null && end != expectedEnd)) {
            throw InvalidRangeResponseException("Content-Range does not match the requested segment")
        }
        if (expectedTotal != null && total != null && total != expectedTotal) {
            throw InvalidRangeResponseException("Content-Range total does not match the remote length")
        }
    }

    private fun rejectUnexpectedHtmlOrCompressedResponse(response: Response, request: DownloadRequest) {
        val encoding = response.header("Content-Encoding")
        if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
            throw IOException("Compressed payload cannot be used for byte-accurate transfer: $encoding")
        }
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val expectsBinary = request.mimeType?.startsWith("video/", true) == true ||
            request.mimeType?.startsWith("audio/", true) == true ||
            request.fileName.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "zip", "iso", "apk", "exe", "7z", "rar")
        if (expectsBinary && (contentType.startsWith("text/html") || contentType.startsWith("application/json") || contentType.startsWith("text/xml") || contentType.startsWith("application/xml"))) {
            throw IOException("Server returned an HTML/XML/JSON error page instead of the requested file")
        }
    }

    private fun Response.retryAfterMillis(): Long? {
        val raw = header("Retry-After")?.trim().orEmpty()
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1000L }
        return null
    }

    private fun destinationIdentityMatches(checkpointDestination: String, currentDestination: String): Boolean {
        if (checkpointDestination == currentDestination) return true
        val currentUri = runCatching { URI(currentDestination) }.getOrNull()
        if (currentUri?.scheme.equals("file", ignoreCase = true)) {
            return runCatching { Paths.get(currentUri).toString() == checkpointDestination }.getOrDefault(false)
        }
        return false
    }

    private fun parseContentRange(value: String?): Triple<Long, Long, Long?> {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty()) ?: throw InvalidRangeResponseException("Missing or malformed Content-Range")
        return Triple(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].takeIf { it.isNotEmpty() && it != "*" }?.toLong())
    }

    private suspend fun <T> retrying(host: String, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val retryable = error is IOException && (error !is HttpTransferException || error.statusCode == 429 || error.statusCode >= 500)
                if (!retryable || attempt >= config.maximumRetries) throw error
                delay(hostRetryBackoff.delayMillis(host, error, attempt, config.baseRetryDelayMillis))
                attempt++
            }
        }
    }

    private fun DownloadRequest.toDestinationRequest() = DestinationRequest(
        downloadId = id,
        destinationUri = destinationUri,
        fileName = fileName,
        mimeType = mimeType,
        conflictPolicy = conflictPolicy,
    )

    private fun requireTask(taskId: String): TaskControl = requireNotNull(tasks[taskId]) { "Unknown task $taskId" }

    private data class NativePreparation(
        val downloadId: String,
        val destination: PreparedDestination,
        val artifacts: BackendArtifactIdentity,
    )

    private class HostRetryBackoff(private val clock: () -> Long, private val random: Random) {
        private val nextAllowedAt = ConcurrentHashMap<String, Long>()

        fun delayMillis(host: String, error: Throwable, attempt: Int, baseDelayMillis: Long): Long {
            val now = clock()
            val serverDelay = (error as? HttpTransferException)?.retryAfterMillis
            val exponential = baseDelayMillis * (1L shl attempt.coerceAtMost(20))
            val jitter = random.nextLong(0, baseDelayMillis.coerceAtLeast(1))
            val requested = serverDelay ?: exponential
            val floor = nextAllowedAt[host].orEmptyDelay(now)
            val delay = maxOf(floor, requested + jitter)
            nextAllowedAt[host] = now + delay
            return delay
        }

        private fun Long?.orEmptyDelay(now: Long): Long = this?.minus(now)?.coerceAtLeast(0L) ?: 0L
    }

    private class TaskControl(
        val request: DownloadRequest,
        val preparedDestination: PreparedDestination,
        val artifacts: BackendArtifactIdentity,
        val state: MutableStateFlow<BackendSnapshot>,
        var job: Job? = null,
        @Volatile var pauseRequested: Boolean = false,
        @Volatile var cancelRequested: Boolean = false,
        val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet(),
        @Volatile var checkpointFlusher: (suspend () -> Unit)? = null,
    )

    private fun com.mikeyphw.xdm.android.storage.DestinationArtifacts.toBackendArtifactIdentity() = BackendArtifactIdentity(
        format = NATIVE_ARTIFACT_FORMAT,
        primary = stagingFile.canonicalFile.toURI().normalize().toString(),
        companions = listOf(
            checkpointFile.canonicalFile.toURI().normalize().toString(),
            journalFile.canonicalFile.toURI().normalize().toString(),
        ),
    )

    private fun String.toFilePathOrNull() = runCatching {
        val uri = URI(this)
        if (!uri.scheme.equals("file", ignoreCase = true)) null else Paths.get(uri).toAbsolutePath().normalize()
    }.getOrNull()

    private companion object {
        const val NATIVE_ARTIFACT_FORMAT = "xdm-native-v1"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36"
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
        val TERMINAL_STATES = setOf(DownloadState.Completed, DownloadState.Cancelled)
    }
}


private object NativeAndroidNetworkSecurityPolicy {
    fun isCleartextTrafficPermitted(host: String): Boolean = try {
        android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
    } catch (error: RuntimeException) {
        if (error.isAndroidJvmUnitTestStub()) {
            // Android local unit tests run with SDK stubs whose platform methods throw
            // "not mocked". Treat the platform policy as permissive there so the
            // unit tests can validate native transfer behavior; release/runtime
            // builds still use the real NetworkSecurityPolicy result.
            true
        } else {
            throw error
        }
    }

    private fun RuntimeException.isAndroidJvmUnitTestStub(): Boolean =
        message.orEmpty().contains("not mocked", ignoreCase = true) &&
            stackTrace.any { it.className == "android.security.NetworkSecurityPolicy" }
}

private data class NativeRequestSecurityContext(
    val originalScheme: String,
    val privateNetworkApproved: Boolean,
    val cleartextCredentialsApproved: Boolean,
)

private class NativeRequestSecurityInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val context = request.tag(NativeRequestSecurityContext::class.java)
            ?: throw IOException("Missing XDM request security context")
        val host = request.url.host
        val scheme = request.url.scheme.lowercase()
        if (scheme == "http") {
            if (context.originalScheme == "https") throw IOException("HTTPS-to-HTTP redirect blocked")
            if (!NativeAndroidNetworkSecurityPolicy.isCleartextTrafficPermitted(host)) {
                throw IOException("Cleartext request blocked by Android network security policy")
            }
            val sensitive = request.headers.names().any { it.equals("Cookie", true) || it.equals("Authorization", true) }
            if (sensitive && !context.cleartextCredentialsApproved) {
                throw IOException("Sensitive headers cannot be sent over cleartext transport")
            }
        }
        val privateTarget = runCatching { InetAddress.getAllByName(host).any(::isPrivateOrSpecialAddress) }.getOrDefault(true)
        if (privateTarget && !context.privateNetworkApproved) throw IOException("Redirect to a private or unresolved network target blocked")
        return chain.proceed(request)
    }

    private fun isPrivateOrSpecialAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (address is Inet6Address && bytes.firstOrNull()?.toInt()?.and(0xFE) == 0xFC) return true
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0 || first >= 224 || first == 127 || (first == 100 && second in 64..127)) return true
        }
        return false
    }
}
