package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.BackendCoordinator
import com.mikeyphw.xdm.android.transfer.BackendMigrationStore
import com.mikeyphw.xdm.android.transfer.BackendSelectionPolicy
import com.mikeyphw.xdm.android.transfer.InMemoryBackendMigrationStore
import com.mikeyphw.xdm.android.transfer.BackendOwnershipReconciler
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendRegistry
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.ChecksumWorkflowStore
import com.mikeyphw.xdm.android.transfer.InMemoryChecksumWorkflowStore
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TransferExecutionRuntime(
    private val store: TransferDownloadStore,
    ownershipStore: BackendOwnershipStore,
    backends: Collection<DownloadBackend>,
    migrationStore: BackendMigrationStore = InMemoryBackendMigrationStore(),
    checksumStore: ChecksumWorkflowStore = InMemoryChecksumWorkflowStore(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val registry = BackendRegistry(backends)
    private val selectionPolicy = BackendSelectionPolicy()
    private val coordinator = BackendCoordinator(registry, ownershipStore, selectionPolicy)
    private val reconciler = BackendOwnershipReconciler(registry, ownershipStore)
    private val ownershipStore = ownershipStore
    private val migrationCoordinator = BackendMigrationCoordinator(store, ownershipStore, migrationStore, registry, selectionPolicy)
    private val completionVerifier = CompletionVerificationCoordinator(checksumStore, ownershipStore)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val backendTaskIds = ConcurrentHashMap<String, Pair<BackendType, String>>()
    private val snapshots = MutableStateFlow<Map<String, BackendSnapshot>>(emptyMap())
    private val fileNames = ConcurrentHashMap<String, String>()
    private val _summary = MutableStateFlow(ActiveTransferSummary())
    val summary: StateFlow<ActiveTransferSummary> = _summary
    private val _terminalEvents = MutableSharedFlow<TransferTerminalEvent>(extraBufferCapacity = 32)
    val terminalEvents: SharedFlow<TransferTerminalEvent> = _terminalEvents


    suspend fun backendCapabilities(): Map<BackendType, BackendCapabilities> = registry.capabilitySnapshot()

    suspend fun capabilityMatrix(): List<BackendCapabilityRow> =
        selectionPolicy.capabilityRows(backendCapabilities())

    suspend fun migrateBackend(downloadId: String, targetBackend: BackendType, restartFromZero: Boolean): BackendMigrationOutcome {
        val outcome = migrationCoordinator.migrate(downloadId, targetBackend, restartFromZero)
        if (outcome is BackendMigrationOutcome.Started) {
            backendTaskIds[downloadId] = outcome.task.backend to outcome.task.taskId
            launch(downloadId)
        }
        return outcome
    }

    suspend fun execute(downloadId: String): DownloadState {
        val download = store.find(downloadId) ?: return DownloadState.Failed
        if (download.state in TERMINAL_STATES) return download.state
        jobs[downloadId]?.let { existing ->
            existing.join()
            return store.find(downloadId)?.state ?: DownloadState.Failed
        }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runDownload(download) }
        jobs[downloadId] = job
        job.start()
        try {
            job.join()
        } finally {
            jobs.remove(downloadId, job)
        }
        return store.find(downloadId)?.state ?: DownloadState.Failed
    }

    fun launch(downloadId: String) {
        if (jobs[downloadId]?.isActive == true) return
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) job@{
            try {
                val download = store.find(downloadId) ?: return@job
                runDownload(download)
            } finally {
                jobs.remove(downloadId)
            }
        }
        jobs[downloadId] = job
        job.start()
    }

    suspend fun pause(downloadId: String) {
        val mapping = backendTaskIds[downloadId]
        if (mapping != null) registry.require(mapping.first).pause(mapping.second)
        jobs[downloadId]?.join()
        store.find(downloadId)?.let {
            if (it.state !in TERMINAL_STATES) store.save(it.copy(state = DownloadState.Paused, speedBytesPerSecond = 0, updatedAtEpochMs = System.currentTimeMillis()))
        }
    }

    suspend fun resume(downloadId: String) {
        store.find(downloadId)?.let {
            store.save(it.copy(state = DownloadState.Queued, errorMessage = null, updatedAtEpochMs = System.currentTimeMillis()))
        }
        launch(downloadId)
    }

    suspend fun cancel(downloadId: String) {
        val mapping = backendTaskIds[downloadId]
        if (mapping != null) registry.require(mapping.first).cancel(mapping.second)
        store.find(downloadId)?.let {
            store.save(it.copy(state = DownloadState.Cancelled, speedBytesPerSecond = 0, updatedAtEpochMs = System.currentTimeMillis()))
        }
    }

    suspend fun pauseAll(): Int {
        val ids = store.findByStates(ACTIVE_STATES).map(Download::id)
        ids.forEach { pause(it) }
        return ids.size
    }

    suspend fun resumeAll(): Int {
        val ids = store.findByStates(setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)).map(Download::id)
        ids.forEach { resume(it) }
        return ids.size
    }

    suspend fun reconcilePersistedOwnership(): Int {
        val results = reconciler.reconcileAll()
        results.forEach { (ownership, result) ->
            val download = store.find(ownership.downloadId) ?: return@forEach
            val updated = when (result.classification) {
                BackendReconciliationClassification.ActiveTaskVerified -> download
                BackendReconciliationClassification.ResumableArtifact -> download.copy(
                    state = DownloadState.Paused,
                    speedBytesPerSecond = 0,
                    errorMessage = result.message,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                else -> download.copy(
                    state = DownloadState.RecoveryRequired,
                    speedBytesPerSecond = 0,
                    errorMessage = result.message,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
            if (updated != download) store.save(updated)
        }
        return results.size
    }

    suspend fun restoreInterruptedTransfers(): Int {
        val interrupted = store.findByStates(INTERRUPTED_STATES)
        interrupted.forEach { download ->
            store.save(
                download.copy(
                    state = DownloadState.Paused,
                    speedBytesPerSecond = 0,
                    errorMessage = "Interrupted by process exit or reboot; checkpoint preserved.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        return interrupted.size
    }

    suspend fun findDownload(downloadId: String): Download? = store.find(downloadId)

    suspend fun shutdown(): Boolean {
        val activeBackends = backendTaskIds.values.groupBy({ it.first }, { it.second })
        val results = activeBackends.keys.map { registry.require(it).shutdown() }
        return results.all { it.clean }
    }

    private suspend fun runDownload(download: Download) {
        val existingMapping = backendTaskIds[download.id]
        if (existingMapping != null) {
            observeExistingTask(download, existingMapping)
            return
        }

        val existingOwnership = ownershipStore.findByDownload(download.id)
        if (existingOwnership != null) {
            val alreadyReconciled = existingOwnership.status == BackendOwnershipStatus.Reconciled &&
                existingOwnership.reconciliation == BackendReconciliationClassification.ResumableArtifact
            val reconciliation = if (alreadyReconciled) {
                BackendReconciliationResult(
                    classification = existingOwnership.reconciliation,
                    message = existingOwnership.reconciliationMessage ?: "Persisted backend artifacts are ready for controlled adoption.",
                    safeToResume = true,
                )
            } else {
                reconciler.reconcile(download.id)
            }
            val reconciledTaskId = reconciliation?.backendTaskId
            if (reconciliation?.classification == BackendReconciliationClassification.ActiveTaskVerified &&
                reconciledTaskId != null
            ) {
                val mapping = existingOwnership.backend to reconciledTaskId
                backendTaskIds[download.id] = mapping
                observeExistingTask(download, mapping)
                return
            }
            if (reconciliation?.safeToResume != true) {
                val message = reconciliation?.message ?: "Persisted backend ownership could not be reconciled."
                store.save(
                    download.copy(
                        state = DownloadState.RecoveryRequired,
                        speedBytesPerSecond = 0,
                        errorMessage = message,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, DownloadState.RecoveryRequired, message))
                return
            }
        }
        val request = DownloadRequest(
            id = download.id,
            sourceUrl = download.sourceUrl,
            destinationUri = download.destinationUri,
            fileName = download.fileName,
            preferredBackend = download.requestedBackend,
            expectedLength = download.totalBytes,
            conflictPolicy = download.conflictPolicy,
            mimeType = download.mimeType,
            allowBackendFallback = download.allowBackendFallback,
        )
        try {
            fileNames[download.id] = download.fileName
            val coordinated = coordinator.add(request)
            val selected = (store.find(download.id) ?: download).copy(
                backend = coordinated.task.backend,
                backendSelectionReason = coordinated.recommendation.reason,
                backendSelectionExplanation = coordinated.recommendation.explanation,
                allowBackendFallback = download.allowBackendFallback,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            store.save(selected)
            val mapping = coordinated.task.backend to coordinated.task.taskId
            backendTaskIds[download.id] = mapping
            observeTaskUntilRunEnd(selected, mapping)
        } catch (error: Throwable) {
            handleRuntimeFailure(download, error)
        } finally {
            cleanUpFinishedTask(download.id)
        }
    }

    private suspend fun observeExistingTask(download: Download, mapping: Pair<BackendType, String>) {
        try {
            fileNames[download.id] = download.fileName
            val backend = registry.require(mapping.first)
            val current = backend.query(mapping.second)
            if (current?.state == DownloadState.Paused) backend.resume(mapping.second)
            observeTaskUntilRunEnd(download, mapping)
        } catch (error: Throwable) {
            handleRuntimeFailure(download, error)
        } finally {
            cleanUpFinishedTask(download.id)
        }
    }

    private suspend fun handleRuntimeFailure(download: Download, error: Throwable) {
        val mapping = backendTaskIds.remove(download.id)
        val reconciliation = if (mapping != null) {
            val detached = runCatching { registry.require(mapping.first).detach(mapping.second) }.getOrDefault(false)
            if (detached) {
                runCatching { reconciler.reconcile(download.id) }.getOrNull()
            } else {
                val ownership = ownershipStore.findByDownload(download.id)
                val result = BackendReconciliationResult(
                    classification = BackendReconciliationClassification.BackendTaskOrphaned,
                    message = "The backend task could not be safely detached after an execution failure.",
                    backendTaskId = mapping.second,
                )
                if (ownership != null) {
                    runCatching { ownershipStore.recordReconciliation(download.id, ownership.generation, result) }
                }
                result
            }
        } else {
            null
        }
        val state = when {
            mapping == null -> DownloadState.Failed
            reconciliation?.safeToResume == true -> DownloadState.Paused
            else -> DownloadState.RecoveryRequired
        }
        val message = reconciliation?.message ?: error.message ?: error::class.java.simpleName
        val current = store.find(download.id) ?: download
        store.save(
            current.copy(
                state = state,
                speedBytesPerSecond = 0,
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        if (state == DownloadState.Failed || state == DownloadState.RecoveryRequired) {
            _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, state, message))
        }
    }

    private suspend fun observeTaskUntilRunEnd(download: Download, mapping: Pair<BackendType, String>) {
        val finalSnapshot = registry.require(mapping.first).observe(mapping.second).first { snapshot ->
            publish(download, snapshot)
            snapshot.state in RUN_END_STATES
        }
        val storedAfterCompletion = store.find(download.id)
        val finalState = storedAfterCompletion?.state ?: finalSnapshot.state
        val finalMessage = storedAfterCompletion?.errorMessage ?: finalSnapshot.errorMessage
        if (finalState in TERMINAL_STATES || finalState == DownloadState.RecoveryRequired) {
            _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, finalState, finalMessage))
        }
    }

    private suspend fun cleanUpFinishedTask(downloadId: String) {
        val state = store.find(downloadId)?.state
        val mapping = backendTaskIds[downloadId]
        if (state in RELEASE_OWNERSHIP_STATES) {
            if (mapping != null) runCatching { registry.require(mapping.first).remove(mapping.second) }
            backendTaskIds.remove(downloadId)
            coordinator.release(downloadId)
            snapshots.value = snapshots.value - downloadId
            fileNames.remove(downloadId)
            updateSummary()
        } else if (state !in ACTIVE_STATES) {
            snapshots.value = snapshots.value - downloadId
            updateSummary()
        }
    }

    private suspend fun publish(original: Download, snapshot: BackendSnapshot) {
        val verifiedSnapshot = completionVerifier.complete(original, snapshot)
        snapshots.value = snapshots.value + (original.id to verifiedSnapshot)
        val current = store.find(original.id) ?: original
        store.save(
            current.copy(
                state = verifiedSnapshot.state,
                backend = backendTaskIds[original.id]?.first ?: current.backend,
                bytesReceived = verifiedSnapshot.bytesReceived,
                totalBytes = verifiedSnapshot.totalBytes ?: current.totalBytes,
                speedBytesPerSecond = verifiedSnapshot.speedBytesPerSecond,
                errorMessage = verifiedSnapshot.errorMessage,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        updateSummary()
    }

    private fun updateSummary() {
        val active = snapshots.value.entries.filter { it.value.state in ACTIVE_STATES }
        val totalKnown = active.mapNotNull { it.value.totalBytes }
        val primary = active.firstOrNull()
        _summary.value = ActiveTransferSummary(
            activeCount = active.size,
            bytesReceived = active.sumOf { it.value.bytesReceived },
            totalBytes = if (totalKnown.size == active.size && active.isNotEmpty()) totalKnown.sum() else null,
            speedBytesPerSecond = active.sumOf { it.value.speedBytesPerSecond },
            primaryDownloadId = primary?.key,
            primaryFileName = primary?.key?.let(fileNames::get),
            primaryState = primary?.value?.state,
        )
    }

    companion object {
        val ACTIVE_STATES = setOf(DownloadState.Queued, DownloadState.Connecting, DownloadState.Downloading, DownloadState.Finalizing, DownloadState.Repairing)
        val INTERRUPTED_STATES = setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Finalizing, DownloadState.Repairing, DownloadState.Verifying)
        val TERMINAL_STATES = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)
        val RELEASE_OWNERSHIP_STATES = setOf(DownloadState.Completed, DownloadState.Cancelled)
        val RUN_END_STATES = TERMINAL_STATES + setOf(DownloadState.Paused, DownloadState.RecoveryRequired)
    }
}
