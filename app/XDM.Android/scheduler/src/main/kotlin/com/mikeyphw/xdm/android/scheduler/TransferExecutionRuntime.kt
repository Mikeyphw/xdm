package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.BackendCoordinator
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendRegistry
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val registry = BackendRegistry(backends)
    private val coordinator = BackendCoordinator(registry, ownershipStore)
    private val ownershipStore = ownershipStore
    private val jobs = ConcurrentHashMap<String, Job>()
    private val backendTaskIds = ConcurrentHashMap<String, Pair<BackendType, String>>()
    private val snapshots = MutableStateFlow<Map<String, BackendSnapshot>>(emptyMap())
    private val fileNames = ConcurrentHashMap<String, String>()
    private val _summary = MutableStateFlow(ActiveTransferSummary())
    val summary: StateFlow<ActiveTransferSummary> = _summary
    private val _terminalEvents = MutableSharedFlow<TransferTerminalEvent>(extraBufferCapacity = 32)
    val terminalEvents: SharedFlow<TransferTerminalEvent> = _terminalEvents

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
        val stale = ownershipStore.findByDownload(download.id)
        if (stale != null && backendTaskIds[download.id] == null) ownershipStore.release(download.id, stale.generation)
        val request = DownloadRequest(
            id = download.id,
            sourceUrl = download.sourceUrl,
            destinationUri = download.destinationUri,
            fileName = download.fileName,
            preferredBackend = download.backend,
            expectedLength = download.totalBytes,
            conflictPolicy = download.conflictPolicy,
            mimeType = download.mimeType,
        )
        try {
            fileNames[download.id] = download.fileName
            val coordinated = coordinator.add(request)
            backendTaskIds[download.id] = coordinated.task.backend to coordinated.task.taskId
            store.saveBackendTask(download.id, coordinated.task.backend, coordinated.task.taskId, coordinated.ownership)
            val finalSnapshot = registry.require(coordinated.task.backend).observe(coordinated.task.taskId).first { snapshot ->
                publish(download, snapshot)
                snapshot.state in RUN_END_STATES
            }
            if (finalSnapshot.state in TERMINAL_STATES || finalSnapshot.state == DownloadState.RecoveryRequired) {
                _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, finalSnapshot.state, finalSnapshot.errorMessage))
            }
        } catch (error: Throwable) {
            val current = store.find(download.id) ?: download
            store.save(current.copy(state = DownloadState.Failed, speedBytesPerSecond = 0, errorMessage = error.message, updatedAtEpochMs = System.currentTimeMillis()))
            _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, DownloadState.Failed, error.message))
        } finally {
            backendTaskIds.remove(download.id)
            store.deleteBackendTask(download.id)
            coordinator.release(download.id)
            snapshots.value = snapshots.value - download.id
            fileNames.remove(download.id)
            updateSummary()
        }
    }

    private suspend fun publish(original: Download, snapshot: BackendSnapshot) {
        snapshots.value = snapshots.value + (original.id to snapshot)
        val current = store.find(original.id) ?: original
        store.save(
            current.copy(
                state = snapshot.state,
                backend = backendTaskIds[original.id]?.first ?: current.backend,
                bytesReceived = snapshot.bytesReceived,
                totalBytes = snapshot.totalBytes ?: current.totalBytes,
                speedBytesPerSecond = snapshot.speedBytesPerSecond,
                errorMessage = snapshot.errorMessage,
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
        val RUN_END_STATES = TERMINAL_STATES + setOf(DownloadState.Paused, DownloadState.RecoveryRequired)
    }
}
