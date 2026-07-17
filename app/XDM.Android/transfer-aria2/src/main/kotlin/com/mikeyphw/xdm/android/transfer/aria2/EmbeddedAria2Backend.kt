package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.storage.DestinationWriter
import com.mikeyphw.xdm.android.storage.FileDestinationWriter
import com.mikeyphw.xdm.android.storage.PreparedDestination
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import com.mikeyphw.xdm.android.transfer.Aria2TaskMappingStore
import com.mikeyphw.xdm.android.transfer.BackendPreparation
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.BackendShutdownResult
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.BackendTask
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.InMemoryAria2TaskMappingStore
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Operational embedded aria2 backend with durable GID ownership and provisional completion handoff. */
class EmbeddedAria2Backend(
    private val processManager: Aria2ProcessManager,
    private val sessionStore: Aria2RuntimeFiles,
    private val mappingStore: Aria2TaskMappingStore = InMemoryAria2TaskMappingStore(),
    private val destinationWriter: DestinationWriter = FileDestinationWriter(),
    private val eventSource: Aria2TaskEventSource = Aria2EventPoller(processManager),
    override val runtimeIdentity: BackendRuntimeIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) : DownloadBackend {
    override val backendId: String = "aria2"
    private val preparations = ConcurrentHashMap<String, Aria2Preparation>()
    private val controls = ConcurrentHashMap<String, Aria2Control>()
    private val snapshots = ConcurrentHashMap<String, BackendSnapshot>()
    private val finalizationGates = ConcurrentHashMap<String, Mutex>()

    override suspend fun capabilities(): BackendCapabilities {
        val available = processManager.probe().isAvailable
        return BackendCapabilities(
            protocols = if (available) setOf("http", "https", "ftp", "sftp", "magnet") else emptySet(),
            supportsSegmentation = available,
            supportsMirrors = available,
            supportsSelectiveRepair = false,
            supportsSafDestination = false,
            supportsAuthentication = available,
            supportsProxy = available,
            maxConnectionsPerDownload = if (available) 16 else 0,
        )
    }

    override suspend fun prepare(request: DownloadRequest): BackendPreparation {
        require(request.destinationUri.substringBefore(':').lowercase() !in setOf("content", "xdm")) {
            "aria2 currently requires an app-private or filesystem staging destination; Android document providers use the native backend"
        }
        val destination = destinationWriter.prepare(request.toDestinationRequest())
        val files = sessionStore.taskFiles(request.id, destination.artifacts.stagingFile)
        val preparationId = UUID.randomUUID().toString()
        preparations[preparationId] = Aria2Preparation(request.id, destination, files)
        return BackendPreparation(
            preparationId = preparationId,
            downloadId = request.id,
            backend = BackendType.Aria2,
            destinationKey = destination.destinationKey,
            artifacts = files.artifacts(),
            runtimeIdentity = runtimeIdentity,
        )
    }

    override suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask {
        require(preparation.backend == BackendType.Aria2) { "aria2 received a foreign preparation" }
        require(preparation.downloadId == request.id) { "aria2 preparation belongs to a different download" }
        require(preparation.runtimeIdentity == runtimeIdentity) { "aria2 preparation belongs to a stale application session" }
        val prepared = requireNotNull(preparations.remove(preparation.preparationId)) { "Unknown or consumed aria2 preparation" }
        require(prepared.files.artifacts() == preparation.artifacts) { "aria2 artifact identity changed after ownership preparation" }
        val rpc = processManager.rpc()
        val outputDirectory = requireNotNull(prepared.files.output.canonicalFile.parentFile) {
            "aria2 staging output has no parent directory"
        }
        val gid = rpc.addUri(
            uris = (listOf(request.sourceUrl) + request.mirrors).distinct(),
            options = Aria2TaskOptions(
                directory = outputDirectory.canonicalPath,
                outputName = prepared.files.output.name,
                pause = true,
                split = request.maxConnections,
                maxConnectionsPerServer = request.maxConnections,
                headers = request.headers,
            ),
        )
        val now = clock()
        val mapping = Aria2TaskMapping(
            downloadId = request.id,
            gid = gid,
            sourceUrl = request.sourceUrl,
            mirrorUrls = request.mirrors,
            destinationUri = request.destinationUri,
            destinationKey = preparation.destinationKey,
            fileName = prepared.destination.displayName,
            conflictPolicy = request.conflictPolicy.name,
            mimeType = request.mimeType,
            outputPath = prepared.files.output.canonicalPath,
            controlPath = prepared.files.control.canonicalPath,
            ownershipMetadataPath = prepared.files.ownershipMetadata.canonicalPath,
            sessionFilePath = prepared.files.session.canonicalPath,
            expectedLength = request.expectedLength,
            ownershipGeneration = 0,
            backendInstanceId = runtimeIdentity.instanceId,
            backendSessionId = runtimeIdentity.sessionId,
            status = MAPPING_CREATED_PAUSED,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lastSynchronizedAtEpochMs = now,
        )
        try {
            mappingStore.upsert(mapping)
            sessionStore.writeOwnershipMetadata(prepared.files, mapping)
            rpc.saveSession()
        } catch (error: Throwable) {
            runCatching { rpc.remove(gid, force = true) }
            runCatching { rpc.removeDownloadResult(gid) }
            runCatching { mappingStore.deleteByGid(gid) }
            sessionStore.deleteTaskMetadata(prepared.files)
            throw error
        }
        controls[gid] = Aria2Control(request, prepared.destination, prepared.files)
        val paused = BackendSnapshot(gid, DownloadState.Paused, 0, request.expectedLength, 0)
        snapshots[gid] = paused
        return BackendTask(gid, BackendType.Aria2, requiresActivation = true)
    }

    override suspend fun onOwnershipAttached(taskId: String, ownership: BackendOwnership) {
        val mapping = requireNotNull(mappingStore.findByGid(taskId)) { "aria2 GID has no durable application mapping" }
        require(mapping.downloadId == ownership.downloadId) { "aria2 GID belongs to another download" }
        require(mapping.destinationKey == ownership.destinationKey) { "aria2 destination identity changed before activation" }
        require(ownership.artifacts.primary == File(mapping.outputPath).canonicalFile.toURI().toString()) { "aria2 output is not the owned partial file" }
        val now = clock()
        val attached = mapping.copy(
            ownershipGeneration = ownership.generation,
            backendInstanceId = ownership.runtimeIdentity.instanceId,
            backendSessionId = ownership.runtimeIdentity.sessionId,
            status = MAPPING_ATTACHED,
            updatedAtEpochMs = now,
            lastSynchronizedAtEpochMs = now,
        )
        mappingStore.upsert(attached)
        sessionStore.writeOwnershipMetadata(attached.files(), attached)
    }

    override suspend fun activate(taskId: String) {
        val mapping = requireNotNull(mappingStore.findByGid(taskId)) { "aria2 GID has no durable mapping" }
        require(mapping.ownershipGeneration > 0) { "aria2 task cannot write before ownership attachment" }
        processManager.rpc().unpause(taskId)
        updateMapping(mapping, MAPPING_ACTIVE)
    }

    override suspend fun discardPreparation(preparation: BackendPreparation) {
        preparations.remove(preparation.preparationId)
    }

    override suspend fun pause(taskId: String) {
        val rpc = processManager.rpc()
        val status = runCatching { rpc.tellStatus(taskId) }.getOrNull()
        if (status?.status !in TERMINAL_RPC_STATES) runCatching { rpc.pause(taskId, force = true) }.getOrThrow()
        mappingStore.findByGid(taskId)?.let { updateMapping(it, MAPPING_PAUSED) }
        snapshots[taskId] = status?.toSnapshot(DownloadState.Paused) ?: snapshots[taskId]?.copy(state = DownloadState.Paused, speedBytesPerSecond = 0)
            ?: BackendSnapshot(taskId, DownloadState.Paused, 0, null, 0)
        rpc.saveSession()
    }

    override suspend fun resume(taskId: String) {
        val rpc = processManager.rpc()
        val status = rpc.tellStatus(taskId)
        if (status.status in setOf(Aria2TaskStatusValue.Paused, Aria2TaskStatusValue.Waiting)) rpc.unpause(taskId)
        mappingStore.findByGid(taskId)?.let { updateMapping(it, MAPPING_ACTIVE) }
    }

    override suspend fun cancel(taskId: String) {
        val rpc = processManager.rpc()
        val status = runCatching { rpc.tellStatus(taskId) }.getOrNull()
        if (status?.status !in TERMINAL_RPC_STATES) runCatching { rpc.remove(taskId, force = true) }
        mappingStore.findByGid(taskId)?.let { updateMapping(it, MAPPING_REMOVED) }
        snapshots[taskId] = BackendSnapshot(taskId, DownloadState.Cancelled, status?.completedLength ?: 0, status?.totalLength, 0)
        rpc.saveSession()
    }

    override suspend fun remove(taskId: String) {
        val mapping = mappingStore.findByGid(taskId)
        val rpc = runCatching { processManager.rpc() }.getOrNull()
        val status = rpc?.let { runCatching { it.tellStatus(taskId) }.getOrNull() }
        if (status?.status !in TERMINAL_RPC_STATES) runCatching { rpc?.remove(taskId, force = true) }
        runCatching { rpc?.removeDownloadResult(taskId) }
        controls.remove(taskId)?.let { control -> runCatching { control.destination.deleteArtifacts() } }
        mapping?.let {
            val files = it.files()
            files.output.delete()
            files.control.delete()
            sessionStore.deleteTaskMetadata(files)
            mappingStore.deleteByDownload(it.downloadId)
        }
        snapshots.remove(taskId)
        finalizationGates.remove(taskId)
        runCatching { rpc?.saveSession() }
    }

    override suspend fun detach(taskId: String): Boolean = runCatching {
        pause(taskId)
        processManager.rpc().saveSession()
        true
    }.getOrDefault(false)

    override suspend fun query(taskId: String): BackendSnapshot? {
        val mapping = mappingStore.findByGid(taskId) ?: return snapshots[taskId]
        return try {
            val status = processManager.rpc().tellStatus(taskId)
            statusToBackendSnapshot(mapping, status)
        } catch (error: Throwable) {
            snapshots[taskId]?.copy(
                state = DownloadState.RecoveryRequired,
                speedBytesPerSecond = 0,
                errorMessage = safeMessage(error),
            ) ?: BackendSnapshot(taskId, DownloadState.RecoveryRequired, 0, mapping.expectedLength, 0, errorMessage = safeMessage(error))
        }
    }

    override fun observe(taskId: String): Flow<BackendSnapshot> = flow {
        val mapping = requireNotNull(mappingStore.findByGid(taskId)) { "aria2 GID has no durable mapping" }
        try {
            eventSource.observe(taskId).collect { status ->
                if (status.status == Aria2TaskStatusValue.Complete) {
                    emit(status.toSnapshot(DownloadState.Verifying))
                }
                emit(statusToBackendSnapshot(mappingStore.findByGid(taskId) ?: mapping, status))
            }
        } catch (error: Throwable) {
            emit(
                BackendSnapshot(
                    taskId = taskId,
                    state = DownloadState.RecoveryRequired,
                    bytesReceived = snapshots[taskId]?.bytesReceived ?: 0,
                    totalBytes = mapping.expectedLength,
                    speedBytesPerSecond = 0,
                    errorMessage = safeMessage(error),
                ),
            )
        }
    }

    override suspend fun reconcile(ownership: BackendOwnership): BackendReconciliationResult {
        val report = processManager.probe()
        if (!report.isAvailable) return BackendReconciliationResult(
            BackendReconciliationClassification.BackendUnavailable,
            report.summary,
            backendTaskId = ownership.backendTaskId,
        )
        val mapping = mappingStore.findByDownload(ownership.downloadId) ?: return artifactOnlyResult(ownership)
        val mismatch = validateMapping(ownership, mapping)
        if (mismatch != null) return BackendReconciliationResult(
            BackendReconciliationClassification.ConflictingArtifact,
            mismatch,
            backendTaskId = mapping.gid,
        )
        return try {
            val status = processManager.rpc().tellStatus(mapping.gid)
            val statusMismatch = validateRpcStatus(mapping, status)
            if (statusMismatch != null) {
                BackendReconciliationResult(
                    BackendReconciliationClassification.ConflictingArtifact,
                    statusMismatch,
                    backendTaskId = mapping.gid,
                )
            } else {
                refreshRecoveredMapping(mapping, status)
                when (status.status) {
                Aria2TaskStatusValue.Active,
                Aria2TaskStatusValue.Waiting,
                Aria2TaskStatusValue.Paused,
                Aria2TaskStatusValue.Complete,
                -> BackendReconciliationResult(
                    BackendReconciliationClassification.ActiveTaskVerified,
                    "aria2 GID ${mapping.gid} matches its XDM ownership, source, output, and generation.",
                    safeToResume = true,
                    backendTaskId = mapping.gid,
                )
                Aria2TaskStatusValue.Error -> BackendReconciliationResult(
                    BackendReconciliationClassification.ResumableArtifact,
                    "aria2 reported ${status.errorMessage ?: "an error"}; validated partial data is available for controlled retry.",
                    safeToResume = File(mapping.outputPath).isFile,
                    backendTaskId = mapping.gid,
                )
                Aria2TaskStatusValue.Removed,
                Aria2TaskStatusValue.Unknown,
                -> artifactOnlyResult(ownership, mapping)
                }
            }
        } catch (error: Aria2RpcException) {
            artifactOnlyResult(ownership, mapping, safeMessage(error))
        } catch (error: Throwable) {
            BackendReconciliationResult(
                BackendReconciliationClassification.BackendUnavailable,
                "aria2 reconciliation could not reach the authenticated local RPC service: ${safeMessage(error)}",
                backendTaskId = mapping.gid,
            )
        }
    }

    override suspend fun shutdown(): BackendShutdownResult {
        val active = mappingStore.listAll().map(Aria2TaskMapping::gid)
        val stop = processManager.stop()
        return BackendShutdownResult(clean = stop.clean || (stop.sessionSaved && stop.exitCode == null), activeTaskIds = active)
    }

    private suspend fun statusToBackendSnapshot(mapping: Aria2TaskMapping, status: Aria2TaskStatus): BackendSnapshot {
        val snapshot = when (status.status) {
            Aria2TaskStatusValue.Active -> status.toSnapshot(DownloadState.Downloading)
            Aria2TaskStatusValue.Waiting -> status.toSnapshot(DownloadState.Queued)
            Aria2TaskStatusValue.Paused -> status.toSnapshot(DownloadState.Paused)
            Aria2TaskStatusValue.Error -> status.toSnapshot(DownloadState.Failed, status.errorMessage ?: "aria2 error ${status.errorCode.orEmpty()}")
            Aria2TaskStatusValue.Removed -> status.toSnapshot(DownloadState.Cancelled)
            Aria2TaskStatusValue.Unknown -> status.toSnapshot(DownloadState.RecoveryRequired, "aria2 returned an unknown task state")
            Aria2TaskStatusValue.Complete -> finalizeCompleted(mapping, status)
        }
        snapshots[status.gid] = snapshot
        if (status.status != Aria2TaskStatusValue.Complete) {
            updateMappingFromStatus(mapping, status)
        }
        return snapshot
    }

    private suspend fun finalizeCompleted(mapping: Aria2TaskMapping, status: Aria2TaskStatus): BackendSnapshot =
        finalizationGates.computeIfAbsent(mapping.gid) { Mutex() }.withLock {
            snapshots[mapping.gid]?.takeIf { it.state == DownloadState.Completed }?.let { return@withLock it }
            val output = File(mapping.outputPath)
            check(output.isFile) { "aria2 completed but its owned staging file is missing" }
            val physicalLength = output.length()
            val reportedLength = status.totalLength.takeIf { it > 0 }
            mapping.expectedLength?.let { expected ->
                check(physicalLength == expected) { "aria2 output length $physicalLength does not match expected length $expected" }
            }
            reportedLength?.let { expected ->
                check(physicalLength == expected) { "aria2 output length $physicalLength does not match reported length $expected" }
            }
            val destination = preparedDestination(mapping)
            require(destination.artifacts.stagingFile.canonicalFile == output.canonicalFile) {
                "Recovered aria2 destination no longer resolves to the owned staging file"
            }
            snapshots[mapping.gid] = status.toSnapshot(DownloadState.Finalizing)
            val promotion = destination.promote()
            val completed = BackendSnapshot(
                taskId = mapping.gid,
                state = DownloadState.Completed,
                bytesReceived = promotion.bytesCommitted,
                totalBytes = reportedLength ?: mapping.expectedLength ?: promotion.bytesCommitted,
                speedBytesPerSecond = 0,
                effectiveUrl = status.primaryUri(),
            )
            updateMapping(mapping, MAPPING_COMPLETED)
            completed
        }

    private suspend fun preparedDestination(mapping: Aria2TaskMapping): PreparedDestination = controls[mapping.gid]?.destination
        ?: destinationWriter.prepare(
            DestinationRequest(
                downloadId = mapping.downloadId,
                destinationUri = mapping.destinationUri,
                fileName = mapping.fileName,
                mimeType = mapping.mimeType,
                conflictPolicy = runCatching { FilenameConflictPolicy.valueOf(mapping.conflictPolicy) }.getOrDefault(FilenameConflictPolicy.Rename),
                stagingSuffix = ARIA2_STAGING_SUFFIX,
            ),
        )

    private suspend fun updateMapping(mapping: Aria2TaskMapping, status: String, code: String? = null, message: String? = null) {
        val now = clock()
        val updated = mapping.copy(
            status = status,
            updatedAtEpochMs = now,
            lastSynchronizedAtEpochMs = now,
            lastErrorCode = code,
            lastErrorMessage = message?.let(::redact),
        )
        mappingStore.upsert(updated)
        sessionStore.writeOwnershipMetadata(updated.files(), updated)
    }

    private suspend fun updateMappingFromStatus(mapping: Aria2TaskMapping, status: Aria2TaskStatus) {
        updateMapping(mapping, status.status.name, status.errorCode, status.errorMessage)
    }

    private suspend fun refreshRecoveredMapping(mapping: Aria2TaskMapping, status: Aria2TaskStatus) {
        val now = clock()
        val refreshed = mapping.copy(
            backendSessionId = runtimeIdentity.sessionId,
            status = status.status.name,
            updatedAtEpochMs = now,
            lastSynchronizedAtEpochMs = now,
            lastErrorCode = status.errorCode,
            lastErrorMessage = status.errorMessage?.let(::redact),
        )
        mappingStore.upsert(refreshed)
        sessionStore.writeOwnershipMetadata(refreshed.files(), refreshed)
    }

    private fun validateMapping(ownership: BackendOwnership, mapping: Aria2TaskMapping): String? {
        if (mapping.gid != ownership.backendTaskId && ownership.backendTaskId != null) return "The persisted aria2 GID does not match the backend task ownership record."
        if (mapping.destinationKey != ownership.destinationKey) return "The persisted aria2 destination identity does not match ownership."
        if (mapping.ownershipGeneration != ownership.generation) return "The persisted aria2 ownership generation is stale."
        if (mapping.backendInstanceId != ownership.runtimeIdentity.instanceId) return "The aria2 task belongs to another XDM installation identity."
        val outputIdentity = File(mapping.outputPath).canonicalFile.toURI().toString()
        if (outputIdentity != ownership.artifacts.primary) return "The persisted aria2 output path is not the owned partial artifact."
        if (mapping.sessionFilePath != sessionStore.sessionFile.canonicalPath) return "The aria2 task references an unexpected session file."
        return null
    }

    private fun validateRpcStatus(mapping: Aria2TaskMapping, status: Aria2TaskStatus): String? {
        if (status.gid != mapping.gid) return "aria2 returned a different GID."
        val output = status.files.firstOrNull()?.path?.takeIf(String::isNotBlank)?.let(::File)?.canonicalPath
        if (output != null && output != File(mapping.outputPath).canonicalPath) return "aria2 is writing to a different output path."
        val knownUris = status.files.flatMap { file -> file.uris.map(Aria2RpcUri::uri) }.filter(String::isNotBlank).toSet()
        if (knownUris.isNotEmpty() && mapping.sourceUrl !in knownUris && mapping.mirrorUrls.none(knownUris::contains)) {
            return "aria2 GID source URIs do not match the XDM download."
        }
        mapping.expectedLength?.let { expected ->
            if (status.totalLength > 0 && status.totalLength != expected) return "aria2 reported a different expected length."
        }
        return null
    }

    private fun artifactOnlyResult(
        ownership: BackendOwnership,
        mapping: Aria2TaskMapping? = null,
        detail: String? = null,
    ): BackendReconciliationResult {
        val output = mapping?.outputPath?.let(::File) ?: artifactFile(ownership.artifacts.primary)
        val control = mapping?.controlPath?.let(::File) ?: ownership.artifacts.companions.mapNotNull(::artifactFile).firstOrNull { it.name.endsWith(".aria2") }
        return if (output?.isFile == true || control?.isFile == true) {
            BackendReconciliationResult(
                BackendReconciliationClassification.ResumableArtifact,
                "aria2 no longer exposes the GID, but its owned partial/control artifacts remain${detail?.let { ": $it" }.orEmpty()}.",
                safeToResume = true,
                backendTaskId = null,
            )
        } else {
            BackendReconciliationResult(
                BackendReconciliationClassification.MissingArtifact,
                "The aria2 ownership record has no live GID or matching partial artifact${detail?.let { ": $it" }.orEmpty()}.",
                backendTaskId = mapping?.gid ?: ownership.backendTaskId,
            )
        }
    }

    private fun Aria2TaskStatus.toSnapshot(state: DownloadState, error: String? = null) = BackendSnapshot(
        taskId = gid,
        state = state,
        bytesReceived = completedLength,
        totalBytes = totalLength.takeIf { it > 0 },
        speedBytesPerSecond = downloadSpeed,
        effectiveUrl = primaryUri(),
        errorMessage = error,
    )

    private fun Aria2TaskStatus.primaryUri(): String? = files.asSequence().flatMap { it.uris.asSequence() }.map(Aria2RpcUri::uri).firstOrNull(String::isNotBlank)

    private fun DownloadRequest.toDestinationRequest() = DestinationRequest(
        downloadId = id,
        destinationUri = destinationUri,
        fileName = fileName,
        mimeType = mimeType,
        conflictPolicy = conflictPolicy,
        stagingSuffix = ARIA2_STAGING_SUFFIX,
    )

    private fun Aria2TaskMapping.files(): Aria2TaskFiles {
        val ownershipMetadata = File(ownershipMetadataPath)
        val taskDirectory = requireNotNull(ownershipMetadata.parentFile) {
            "aria2 ownership metadata has no parent directory"
        }
        return Aria2TaskFiles(
            directory = taskDirectory,
            output = File(outputPath),
            control = File(controlPath),
            ownershipMetadata = ownershipMetadata,
            session = File(sessionFilePath),
        )
    }

    private fun artifactFile(identity: String): File? = runCatching {
        URI(identity).takeIf { it.scheme.equals("file", ignoreCase = true) }?.let(::File)
    }.getOrNull()

    private fun safeMessage(error: Throwable): String = redact(error.message ?: error::class.java.simpleName)
    private fun redact(value: String): String = value
        .replace(Regex("token:[^\\s,]+"), "token:<redacted>")
        .replace(Regex("rpc-secret=[^\\s,]+"), "rpc-secret=<redacted>")
        .take(500)

    private data class Aria2Preparation(
        val downloadId: String,
        val destination: PreparedDestination,
        val files: Aria2TaskFiles,
    )

    private data class Aria2Control(
        val request: DownloadRequest,
        val destination: PreparedDestination,
        val files: Aria2TaskFiles,
    )

    private companion object {
        const val ARIA2_STAGING_SUFFIX = ".xdm.aria2.part"
        const val MAPPING_CREATED_PAUSED = "CreatedPaused"
        const val MAPPING_ATTACHED = "Attached"
        const val MAPPING_ACTIVE = "Active"
        const val MAPPING_PAUSED = "Paused"
        const val MAPPING_REMOVED = "Removed"
        const val MAPPING_COMPLETED = "Completed"
        val TERMINAL_RPC_STATES = setOf(Aria2TaskStatusValue.Error, Aria2TaskStatusValue.Complete, Aria2TaskStatusValue.Removed)
    }
}
