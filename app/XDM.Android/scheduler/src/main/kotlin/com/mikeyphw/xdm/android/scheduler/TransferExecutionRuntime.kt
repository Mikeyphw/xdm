package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
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
import com.mikeyphw.xdm.android.transfer.InMemoryRecoveryWorkflowStore
import com.mikeyphw.xdm.android.transfer.RecoveryWorkflowStore
import com.mikeyphw.xdm.android.transfer.InMemoryFinalizationJournalStore
import com.mikeyphw.xdm.android.transfer.FinalizationJournalStore
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.inferDownloadRequestKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TransferExecutionRuntime(
    private val store: TransferDownloadStore,
    ownershipStore: BackendOwnershipStore,
    migrationStore: BackendMigrationStore = InMemoryBackendMigrationStore(),
    checksumStore: ChecksumWorkflowStore = InMemoryChecksumWorkflowStore(),
    finalizationStore: FinalizationJournalStore = InMemoryFinalizationJournalStore(),
    recoveryStore: RecoveryWorkflowStore = InMemoryRecoveryWorkflowStore(),
    backends: Collection<DownloadBackend>,
    artifactRoots: List<File> = emptyList(),
    completedArtifactReader: CompletedArtifactReader = FileCompletedArtifactReader(),
    private val requestSecurityGuard: TransferRequestSecurityGuard = TransferRequestSecurityGuard.AllowAll,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val registry = BackendRegistry(backends)
    private val selectionPolicy = BackendSelectionPolicy()
    private val coordinator = BackendCoordinator(registry, ownershipStore, selectionPolicy)
    private val reconciler = BackendOwnershipReconciler(registry, ownershipStore)
    private val ownershipStore = ownershipStore
    private val migrationCoordinator = BackendMigrationCoordinator(store, ownershipStore, migrationStore, registry, selectionPolicy, requestSecurityGuard)
    private val completionVerifier = CompletionVerificationCoordinator(checksumStore, ownershipStore, completedArtifactReader)
    private val finalizationCoordinator = AtomicFinalizationCoordinator(finalizationStore)
    private val startupRecoveryCoordinator = StartupRecoveryCoordinator(store, ownershipStore, migrationStore, finalizationStore, recoveryStore, artifactRoots)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val commandControls = ConcurrentHashMap<String, DownloadCommandControl>()
    private val backendTaskIds = ConcurrentHashMap<String, Pair<BackendType, String>>()
    private val attemptGenerations = ConcurrentHashMap<String, Long>()
    private val snapshots = MutableStateFlow<Map<String, BackendSnapshot>>(emptyMap())
    private val fileNames = ConcurrentHashMap<String, String>()
    private val _summary = MutableStateFlow(ActiveTransferSummary())
    val summary: StateFlow<ActiveTransferSummary> = _summary
    private val _terminalEvents = MutableSharedFlow<TransferTerminalEvent>(extraBufferCapacity = 32)
    val terminalEvents: SharedFlow<TransferTerminalEvent> = _terminalEvents


    suspend fun scanStartupRecovery(): StartupRecoveryReport = startupRecoveryCoordinator.scan()

    suspend fun backendCapabilities(): Map<BackendType, BackendCapabilities> = registry.capabilitySnapshot()

    /** Reuses the exact transfer-security boundary for app-side probes before network I/O. */
    suspend fun validateRequestSecurity(request: DownloadRequest) = requestSecurityGuard.validate(request)

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

    suspend fun execute(downloadId: String, queueClaimToken: Long): DownloadState {
        val before = store.find(downloadId) ?: return DownloadState.Failed
        if (before.state in TERMINAL_STATES) return before.state
        val job = ensureExecutionJob(downloadId, queueClaimToken) ?: return store.find(downloadId)?.state ?: DownloadState.Failed
        job.join()
        return store.find(downloadId)?.state ?: DownloadState.Failed
    }

    private fun launch(downloadId: String) {
        scope.launch { ensureExecutionJob(downloadId, queueClaimToken = 0L) }
    }

    fun requestPauseAsync(downloadId: String) { scope.launch { pause(downloadId) } }
    fun requestPauseOwnedAsync(downloadId: String, queueClaimToken: Long) {
        scope.launch {
            try {
                pauseOwned(downloadId, queueClaimToken)
            } finally {
                AndroidExecutionClaimRegistry.release(downloadId, queueClaimToken)
            }
        }
    }

    /**
     * Stops only the Android execution owner represented by [queueClaimToken]. The registry lock
     * serializes replacement-owner installation with teardown, while the durable row check catches
     * the interval after a newer Room claim commits but before its component callback arrives.
     */
    suspend fun pauseOwned(downloadId: String, queueClaimToken: Long): Boolean =
        AndroidExecutionClaimRegistry.withCurrentClaim(downloadId, queueClaimToken) {
            val current = store.find(downloadId) ?: return@withCurrentClaim false
            if (current.state == DownloadState.Connecting && current.updatedAtEpochMs != queueClaimToken) {
                return@withCurrentClaim false
            }
            if (current.state !in ACTIVE_STATES) return@withCurrentClaim false
            pause(downloadId)
            true
        } ?: false

    fun activeAttemptGenerationOwned(downloadId: String, queueClaimToken: Long): Long? =
        AndroidExecutionClaimRegistry.attemptGeneration(downloadId, queueClaimToken)

    private sealed interface BackendControlResolution {
        data class Live(val mapping: Pair<BackendType, String>, val generation: Long) : BackendControlResolution
        data class InactiveSafe(val generation: Long?) : BackendControlResolution
        data class Unsafe(val message: String) : BackendControlResolution
    }

    private suspend fun resolveBackendControl(downloadId: String): BackendControlResolution {
        backendTaskIds[downloadId]?.let { mapping ->
            return BackendControlResolution.Live(mapping, attemptGenerations[downloadId] ?: requestGeneration(downloadId))
        }
        val ownership = ownershipStore.findByDownload(downloadId) ?: return BackendControlResolution.InactiveSafe(null)
        val result = reconciler.reconcile(downloadId)
            ?: return BackendControlResolution.Unsafe("Persisted backend ownership could not be reconciled.")
        return when (result.classification) {
            BackendReconciliationClassification.ActiveTaskVerified -> {
                val taskId = result.backendTaskId ?: return BackendControlResolution.Unsafe("Backend reported an active task without a durable task identity.")
                val mapping = ownership.backend to taskId
                backendTaskIds[downloadId] = mapping
                attemptGenerations[downloadId] = ownership.generation
                BackendControlResolution.Live(mapping, ownership.generation)
            }
            BackendReconciliationClassification.ResumableArtifact -> BackendControlResolution.InactiveSafe(ownership.generation)
            else -> BackendControlResolution.Unsafe(result.message)
        }
    }

    suspend fun pause(downloadId: String) {
        val control = commandControl(downloadId)
        val generation = control.request(DesiredTransferState.PauseRequested)
        if (finalizationCoordinator.findIncomplete(downloadId) != null) {
            jobs[downloadId]?.join()
            quarantineInterruptedFinalization(
                downloadId,
                "Pause was requested after the destination artifact committed; XDM preserved the artifact and requires recovery verification instead of relabeling it Paused.",
            )
            return
        }
        when (val resolution = resolveBackendControl(downloadId)) {
            is BackendControlResolution.Live -> registry.require(resolution.mapping.first).pause(resolution.mapping.second)
            is BackendControlResolution.InactiveSafe -> jobs[downloadId]?.cancel()
            is BackendControlResolution.Unsafe -> {
                store.find(downloadId)?.let { current ->
                    persistOrThrow(current.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = resolution.message, updatedAtEpochMs = current.nextUpdatedAt()))
                }
                return
            }
        }
        jobs[downloadId]?.join()
        val latest = store.find(downloadId)
        if (latest != null && latest.state !in TERMINAL_STATES && control.generation.get() == generation) {
            persistOrThrow(latest.copy(state = DownloadState.Paused, speedBytesPerSecond = 0, updatedAtEpochMs = latest.nextUpdatedAt()))
        }
    }

    suspend fun cancel(downloadId: String) {
        val control = commandControl(downloadId)
        val generation = control.request(DesiredTransferState.CancelRequested)
        if (finalizationCoordinator.findIncomplete(downloadId) != null) {
            jobs[downloadId]?.join()
            quarantineInterruptedFinalization(
                downloadId,
                "Cancel was requested after the destination artifact committed; XDM preserved the artifact and requires recovery review instead of falsely recording it Cancelled.",
            )
            return
        }
        when (val resolution = resolveBackendControl(downloadId)) {
            is BackendControlResolution.Live -> registry.require(resolution.mapping.first).cancel(resolution.mapping.second)
            is BackendControlResolution.InactiveSafe -> {
                jobs[downloadId]?.cancel()
                if (resolution.generation != null) coordinator.release(downloadId)
            }
            is BackendControlResolution.Unsafe -> {
                store.find(downloadId)?.let { current -> persistOrThrow(current.copy(state = DownloadState.RecoveryRequired, errorMessage = resolution.message, updatedAtEpochMs = current.nextUpdatedAt())) }
                return
            }
        }
        jobs[downloadId]?.join()
        val latest = store.find(downloadId)
        if (latest != null && latest.state != DownloadState.Completed && control.generation.get() == generation) {
            persistOrThrow(latest.copy(state = DownloadState.Cancelled, speedBytesPerSecond = 0, updatedAtEpochMs = latest.nextUpdatedAt()))
        }
    }

    suspend fun pauseAll(): Int {
        val ids = store.findByStates(ACTIVE_STATES + setOf(DownloadState.Verifying)).map(Download::id)
        ids.forEach { id -> runCatching { pause(id) } }
        return ids.size
    }

    private suspend fun ensureExecutionJob(downloadId: String, queueClaimToken: Long): Job? {
        val control = commandControl(downloadId)
        return control.mutex.withLock {
            val current = store.find(downloadId)
            when {
                current == null -> null
                current.state in TERMINAL_STATES -> null
                else -> jobs[downloadId]?.takeIf { it.isActive } ?: scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val download = store.find(downloadId) ?: return@launch
                        runDownload(download, queueClaimToken)
                    } finally {
                        jobs.remove(downloadId)
                    }
                }.also { job ->
                    jobs[downloadId] = job
                    job.start()
                }
            }
        }
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
                    updatedAtEpochMs = download.nextUpdatedAt(),
                )
                else -> download.copy(
                    state = DownloadState.RecoveryRequired,
                    speedBytesPerSecond = 0,
                    errorMessage = result.message,
                    updatedAtEpochMs = download.nextUpdatedAt(),
                )
            }
            if (updated != download) persistOrThrow(updated)
        }
        return results.size
    }

    suspend fun restoreInterruptedTransfers(): Int {
        val interrupted = store.findByStates(INTERRUPTED_STATES)
        var restored = 0
        interrupted.forEach { download ->
            // Backend-owned rows are reconciled by ownership, never blindly overwritten by a
            // second reboot recovery path.
            if (ownershipStore.findByDownload(download.id) != null) return@forEach
            persistOrThrow(
                download.copy(
                    state = DownloadState.Paused,
                    speedBytesPerSecond = 0,
                    errorMessage = "Interrupted by process exit or reboot; no active backend ownership remained.",
                    updatedAtEpochMs = download.nextUpdatedAt(),
                ),
            )
            restored++
        }
        return restored
    }

    data class RuntimeStartupRecovery(
        val scanSucceeded: Boolean,
        val ownershipSucceeded: Boolean,
        val interruptedSucceeded: Boolean,
        val restoredCount: Int,
        val reconciledCount: Int,
    ) {
        val admissionSafe: Boolean get() = scanSucceeded && ownershipSucceeded && interruptedSucceeded
    }

    /** Common startup/boot recovery path with isolated phases and ownership-first restoration. */
    suspend fun recoverForStartup(): RuntimeStartupRecovery {
        val scan = runCatching { scanStartupRecovery() }
        val reconcile = runCatching { reconcilePersistedOwnership() }
        val restore = runCatching { restoreInterruptedTransfers() }
        return RuntimeStartupRecovery(
            scanSucceeded = scan.isSuccess,
            ownershipSucceeded = reconcile.isSuccess,
            interruptedSucceeded = restore.isSuccess,
            restoredCount = restore.getOrDefault(0),
            reconciledCount = reconcile.getOrDefault(0),
        )
    }

    suspend fun findDownload(downloadId: String): Download? = store.find(downloadId)

    /** Backend ownership generation currently attached in this process, if one exists. */
    fun activeAttemptGeneration(downloadId: String): Long? = attemptGenerations[downloadId]

    suspend fun shutdown(): Boolean {
        val activeBackends = backendTaskIds.values.groupBy({ it.first }, { it.second })
        val results = activeBackends.keys.map { registry.require(it).shutdown() }
        return results.all { it.clean }
    }

    private suspend fun runDownload(download: Download, queueClaimToken: Long) {
        when (commandControl(download.id).desired) {
            DesiredTransferState.CancelRequested -> {
                persistOrThrow(download.copy(state = DownloadState.Cancelled, speedBytesPerSecond = 0, updatedAtEpochMs = download.nextUpdatedAt()))
                return
            }
            DesiredTransferState.PauseRequested -> {
                persistOrThrow(download.copy(state = DownloadState.Paused, speedBytesPerSecond = 0, updatedAtEpochMs = download.nextUpdatedAt()))
                return
            }
            else -> Unit
        }
        attemptGenerations[download.id]?.let { AndroidExecutionClaimRegistry.bindAttemptGeneration(download.id, queueClaimToken, it) }
        val existingMapping = backendTaskIds[download.id]
        if (existingMapping != null) {
            observeExistingTask(download, existingMapping, queueClaimToken)
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
                attemptGenerations[download.id] = existingOwnership.generation
                AndroidExecutionClaimRegistry.bindAttemptGeneration(download.id, queueClaimToken, existingOwnership.generation)
                val generationBound = if (download.attemptGeneration == existingOwnership.generation) {
                    download
                } else {
                    download.copy(
                        attemptGeneration = existingOwnership.generation,
                        updatedAtEpochMs = download.nextUpdatedAt(),
                    ).also { persistOrThrow(it) }
                }
                observeExistingTask(generationBound, mapping, queueClaimToken)
                return
            }
            if (reconciliation?.safeToResume != true) {
                val message = reconciliation?.message ?: "Persisted backend ownership could not be reconciled."
                persistOrThrow(
                    download.copy(
                        state = DownloadState.RecoveryRequired,
                        speedBytesPerSecond = 0,
                        errorMessage = message,
                        attemptGeneration = existingOwnership.generation,
                        updatedAtEpochMs = download.nextUpdatedAt(),
                    ),
                )
                _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, DownloadState.RecoveryRequired, message, download.destinationUri, download.mimeType, existingOwnership.generation))
                return
            }
        }
        val mediaHandoff = MediaRequestHandoffStore.forDownload(download.id)
        val request = DownloadRequest(
            id = download.id,
            sourceUrl = mediaHandoff?.exactUrl ?: download.sourceUrl,
            destinationUri = download.destinationUri,
            fileName = download.fileName,
            preferredBackend = download.requestedBackend,
            headers = mediaHandoff?.headers.orEmpty(),
            mirrors = mediaHandoff?.mirrors.orEmpty(),
            requestKind = mediaHandoff?.requestKind ?: inferDownloadRequestKind(mediaHandoff?.exactUrl ?: download.sourceUrl),
            expectedLength = download.totalBytes,
            conflictPolicy = download.conflictPolicy,
            mimeType = download.mimeType,
            allowBackendFallback = download.allowBackendFallback,
            isExpiringUrl = mediaHandoff?.isExpiringUrl == true,
            isMediaRequest = mediaHandoff != null,
            privateNetworkApproved = mediaHandoff?.privateNetworkApproved == true,
            cleartextCredentialsApproved = mediaHandoff?.cleartextCredentialsApproved == true,
            privateNetworkApprovalScopes = mediaHandoff?.privateNetworkApprovalScopes.orEmpty(),
            cleartextCredentialApprovalScopes = mediaHandoff?.cleartextCredentialApprovalScopes.orEmpty(),
            attemptGeneration = mediaHandoff?.attemptGeneration ?: 0L,
        )
        try {
            requestSecurityGuard.validate(request)
            fileNames[download.id] = download.fileName
            val coordinated = coordinator.add(request)
            attemptGenerations[download.id] = coordinated.ownership.generation
            AndroidExecutionClaimRegistry.bindAttemptGeneration(download.id, queueClaimToken, coordinated.ownership.generation)
            val selectedBase = store.find(download.id) ?: download
            val selected = selectedBase.copy(
                backend = coordinated.task.backend,
                backendSelectionReason = coordinated.recommendation.reason,
                backendSelectionExplanation = coordinated.recommendation.explanation,
                allowBackendFallback = download.allowBackendFallback,
                attemptGeneration = coordinated.ownership.generation,
                updatedAtEpochMs = selectedBase.nextUpdatedAt(),
            )
            persistOrThrow(selected)
            val mapping = coordinated.task.backend to coordinated.task.taskId
            backendTaskIds[download.id] = mapping
            when (commandControl(download.id).desired) {
                DesiredTransferState.CancelRequested -> {
                    registry.require(mapping.first).cancel(mapping.second)
                    persistOrThrow(selected.copy(state = DownloadState.Cancelled, speedBytesPerSecond = 0, updatedAtEpochMs = selected.nextUpdatedAt()))
                    return
                }
                DesiredTransferState.PauseRequested -> {
                    registry.require(mapping.first).pause(mapping.second)
                    persistOrThrow(selected.copy(state = DownloadState.Paused, speedBytesPerSecond = 0, updatedAtEpochMs = selected.nextUpdatedAt()))
                    return
                }
                else -> Unit
            }
            observeTaskUntilRunEnd(selected, mapping)
        } catch (error: Throwable) {
            handleRuntimeFailure(download, error)
        } finally {
            cleanUpFinishedTask(download.id)
        }
    }

    private suspend fun observeExistingTask(download: Download, mapping: Pair<BackendType, String>, queueClaimToken: Long) {
        try {
            fileNames[download.id] = download.fileName
            val backend = registry.require(mapping.first)
            val current = backend.query(mapping.second)
            if (current?.state == DownloadState.Failed) {
                runCatching { backend.remove(mapping.second) }
                backendTaskIds.remove(download.id, mapping)
                coordinator.release(download.id)
                runDownload(download.copy(state = DownloadState.Queued, errorMessage = null, updatedAtEpochMs = download.nextUpdatedAt()), queueClaimToken)
                return
            }
            when (commandControl(download.id).desired) {
                DesiredTransferState.CancelRequested -> { backend.cancel(mapping.second); return }
                DesiredTransferState.PauseRequested -> { backend.pause(mapping.second); return }
                else -> Unit
            }
            if (
                current?.state == DownloadState.Paused ||
                (mapping.first == BackendType.Native && current?.state == DownloadState.RecoveryRequired && current.errorMessage.orEmpty().startsWith("Final save failed"))
            ) {
                backend.resume(mapping.second)
            }
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
        if (current.state == DownloadState.Completed &&
            current.completedArtifactGeneration == current.attemptGeneration &&
            !current.completedArtifactUri.isNullOrBlank()
        ) {
            // Completion metadata is authoritative once durably committed. Cleanup/journal-close
            // failures must not rewrite a verified artifact into Paused/Failed/RecoveryRequired.
            // If detach itself failed, preserve the task mapping so final cleanup cannot release
            // durable ownership while a backend task may still exist.
            if (mapping != null && reconciliation?.classification == BackendReconciliationClassification.BackendTaskOrphaned) {
                backendTaskIds[download.id] = mapping
            }
            return
        }
        val storedMessage = if (state == DownloadState.Paused) null else message
        persistOrThrow(
            current.copy(
                state = state,
                speedBytesPerSecond = 0,
                errorMessage = storedMessage,
                updatedAtEpochMs = current.nextUpdatedAt(),
            ),
        )
        if (state == DownloadState.Paused || state == DownloadState.Failed || state == DownloadState.RecoveryRequired) {
            _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, state, storedMessage, current.destinationUri, current.mimeType, attemptGenerations[download.id] ?: requestGeneration(download.id)))
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
        if (finalState in TERMINAL_STATES || finalState == DownloadState.Paused || finalState == DownloadState.RecoveryRequired) {
            val storedDestination = storedAfterCompletion?.let { stored ->
                if (finalState == DownloadState.Completed && stored.completedArtifactGeneration == stored.attemptGeneration) {
                    stored.completedArtifactUri
                } else {
                    stored.destinationUri
                }
            } ?: download.destinationUri
            val storedMimeType = storedAfterCompletion?.mimeType ?: download.mimeType
            _terminalEvents.tryEmit(TransferTerminalEvent(download.id, download.fileName, finalState, finalMessage, storedDestination, storedMimeType, attemptGenerations[download.id] ?: requestGeneration(download.id)))
        }
    }

    private suspend fun requestGeneration(downloadId: String): Long =
        ownershipStore.findByDownload(downloadId)?.generation
            ?: MediaRequestHandoffStore.forDownload(downloadId)?.attemptGeneration?.takeIf { it > 0L }
            ?: 0L

    private suspend fun cleanUpFinishedTask(downloadId: String) {
        val state = store.find(downloadId)?.state
        val mapping = backendTaskIds[downloadId]
        if (state in RELEASE_OWNERSHIP_STATES) {
            val removed = if (mapping != null) runCatching { registry.require(mapping.first).remove(mapping.second); true }.getOrDefault(false) else true
            if (removed) {
                backendTaskIds.remove(downloadId)
                coordinator.release(downloadId)
            } else {
                store.find(downloadId)?.let { current ->
                    if (current.state == DownloadState.Completed &&
                        current.completedArtifactGeneration == current.attemptGeneration &&
                        !current.completedArtifactUri.isNullOrBlank()
                    ) {
                        ownershipStore.findByDownload(downloadId)?.let { ownership ->
                            runCatching {
                                ownershipStore.recordReconciliation(
                                    downloadId,
                                    ownership.generation,
                                    BackendReconciliationResult(
                                        classification = BackendReconciliationClassification.BackendTaskOrphaned,
                                        message = "Completed artifact metadata is durable, but backend ownership cleanup still requires reconciliation.",
                                        backendTaskId = mapping?.second,
                                    ),
                                )
                            }
                        }
                    } else {
                        persistOrThrow(current.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = "Backend ownership could not be removed safely; artifacts remain quarantined.", updatedAtEpochMs = current.nextUpdatedAt()))
                    }
                }
            }
            if (removed && (state == DownloadState.Completed || state == DownloadState.Cancelled)) {
                MediaRequestHandoffStore.forget(downloadId)
            }
            snapshots.value = snapshots.value - downloadId
            fileNames.remove(downloadId)
            attemptGenerations.remove(downloadId)
            updateSummary()
        } else if (state !in ACTIVE_STATES) {
            // Paused, failed, and recovery-required attempts retain their encrypted request
            // envelope so process death does not silently discard authentication or signed URLs.
            snapshots.value = snapshots.value - downloadId
            updateSummary()
        }
    }

    private suspend fun publish(original: Download, snapshot: BackendSnapshot) {
        val ownership = ownershipStore.findByDownload(original.id)
        if (ownership == null ||
            snapshot.attemptGeneration != ownership.generation ||
            snapshot.backendInstanceId != ownership.runtimeIdentity.instanceId
        ) {
            val reason = when {
                ownership == null -> "Backend snapshot arrived without durable ownership."
                snapshot.attemptGeneration != ownership.generation ->
                    "Backend snapshot generation ${snapshot.attemptGeneration} does not match owned generation ${ownership.generation}."
                else -> "Backend snapshot belongs to another installation identity."
            }
            snapshots.value = snapshots.value + (
                original.id to snapshot.copy(
                    state = DownloadState.RecoveryRequired,
                    speedBytesPerSecond = 0,
                    errorMessage = reason,
                )
            )
            val current = store.find(original.id) ?: original
            persistOrThrow(
                current.copy(
                    state = DownloadState.RecoveryRequired,
                    speedBytesPerSecond = 0,
                    errorMessage = reason,
                    updatedAtEpochMs = current.nextUpdatedAt(),
                ),
            )
            return
        }
        val control = commandControl(original.id)
        if (control.desired == DesiredTransferState.CancelRequested && snapshot.state != DownloadState.Completed) {
            store.find(original.id)?.let { current ->
                persistOrThrow(current.copy(state = DownloadState.Cancelled, speedBytesPerSecond = 0, updatedAtEpochMs = current.nextUpdatedAt()))
            }
            return
        }

        val generationBeforeVerification = control.generation.get()
        var journal: FinalizationJournal? = null
        if (snapshot.state == DownloadState.Completed) {
            val committedUri = snapshot.completedUri?.trim()?.takeIf(String::isNotBlank)
                ?: run {
                    val reason = "Backend completed without a committed artifact identity."
                    val current = store.find(original.id) ?: original
                    persistOrThrow(current.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = reason, updatedAtEpochMs = current.nextUpdatedAt()))
                    snapshots.value = snapshots.value + (original.id to snapshot.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = reason))
                    return
                }
            val current = store.find(original.id) ?: original
            journal = finalizationCoordinator.prepareCommitted(
                download = current,
                committedUri = committedUri,
                bytesCommitted = snapshot.bytesReceived,
                attemptGeneration = ownership.generation,
            )
            persistOrThrow(
                current.copy(
                    state = DownloadState.Verifying,
                    backend = backendTaskIds[original.id]?.first ?: current.backend,
                    bytesReceived = snapshot.bytesReceived,
                    totalBytes = snapshot.totalBytes ?: current.totalBytes,
                    speedBytesPerSecond = 0,
                    errorMessage = null,
                    updatedAtEpochMs = current.nextUpdatedAt(),
                ),
            )
            snapshots.value = snapshots.value + (original.id to snapshot.copy(state = DownloadState.Verifying, speedBytesPerSecond = 0))
        }

        val verifiedSnapshot = try {
            completionVerifier.complete(original, snapshot)
        } catch (error: Throwable) {
            val activeJournal = journal ?: throw error
            val reason = "Committed artifact verification was interrupted: ${error.message ?: error::class.java.simpleName}"
            finalizationCoordinator.recover(activeJournal, reason)
            val current = store.find(original.id) ?: original
            persistOrThrow(current.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = reason, updatedAtEpochMs = current.nextUpdatedAt()))
            snapshots.value = snapshots.value + (original.id to snapshot.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = reason))
            updateSummary()
            return
        }

        if (journal != null && control.generation.get() != generationBeforeVerification &&
            control.desired in setOf(DesiredTransferState.PauseRequested, DesiredTransferState.CancelRequested)
        ) {
            val reason = if (control.desired == DesiredTransferState.CancelRequested) {
                "Cancel was requested while validating a committed artifact; the artifact is preserved for explicit recovery review."
            } else {
                "Pause was requested while validating a committed artifact; the artifact is preserved for recovery verification."
            }
            finalizationCoordinator.recover(journal, reason)
            val current = store.find(original.id) ?: original
            persistOrThrow(current.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = reason, updatedAtEpochMs = current.nextUpdatedAt()))
            snapshots.value = snapshots.value + (original.id to verifiedSnapshot.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = reason))
            updateSummary()
            return
        }

        snapshots.value = snapshots.value + (original.id to verifiedSnapshot)
        val current = store.find(original.id) ?: original
        if (verifiedSnapshot.state == DownloadState.Completed) {
            val committedUri = verifiedSnapshot.completedUri?.trim()?.takeIf(String::isNotBlank)
                ?: error("Verified completion is missing committed artifact identity")
            val committedBytes = verifiedSnapshot.bytesReceived
            var activeJournal = requireNotNull(journal) { "Completed publication must own a durable finalization journal before verification" }
            activeJournal = finalizationCoordinator.markVerificationComplete(activeJournal)
            activeJournal = finalizationCoordinator.recordDestinationCommitted(activeJournal, committedBytes)
            persistOrThrow(
                current.copy(
                    state = DownloadState.Completed,
                    backend = backendTaskIds[original.id]?.first ?: current.backend,
                    bytesReceived = committedBytes,
                    totalBytes = verifiedSnapshot.totalBytes ?: committedBytes,
                    speedBytesPerSecond = 0,
                    errorMessage = null,
                    completedArtifactUri = committedUri,
                    completedArtifactGeneration = ownership.generation,
                    completedArtifactBytes = committedBytes,
                    updatedAtEpochMs = current.nextUpdatedAt(),
                ),
            )
            val journalClosed = runCatching {
                activeJournal = finalizationCoordinator.recordMetadataCommitted(activeJournal)
                finalizationCoordinator.complete(activeJournal)
            }.isSuccess
            if (journalClosed) {
                retirePublicationJournal(verifiedSnapshot.publicationJournalPath)
            }
        } else {
            journal?.let { activeJournal ->
                finalizationCoordinator.recover(
                    activeJournal,
                    verifiedSnapshot.errorMessage ?: "Committed artifact did not pass completion verification.",
                )
            }
            val clearStaleArtifact = current.completedArtifactGeneration != null && current.completedArtifactGeneration != ownership.generation
            persistOrThrow(
                current.copy(
                    state = verifiedSnapshot.state,
                    backend = backendTaskIds[original.id]?.first ?: current.backend,
                    bytesReceived = verifiedSnapshot.bytesReceived,
                    totalBytes = verifiedSnapshot.totalBytes ?: current.totalBytes,
                    speedBytesPerSecond = verifiedSnapshot.speedBytesPerSecond,
                    errorMessage = verifiedSnapshot.errorMessage,
                    completedArtifactUri = if (clearStaleArtifact) null else current.completedArtifactUri,
                    completedArtifactGeneration = if (clearStaleArtifact) null else current.completedArtifactGeneration,
                    completedArtifactBytes = if (clearStaleArtifact) null else current.completedArtifactBytes,
                    updatedAtEpochMs = current.nextUpdatedAt(),
                ),
            )
        }
        updateSummary()
    }

    private suspend fun quarantineInterruptedFinalization(downloadId: String, message: String) {
        val journal = finalizationCoordinator.findIncomplete(downloadId) ?: return
        if (journal.stage != FinalizationJournalStage.RecoveryRequired) {
            finalizationCoordinator.recover(journal, message)
        }
        store.find(downloadId)?.let { current ->
            if (current.state != DownloadState.Completed && current.state != DownloadState.RecoveryRequired) {
                persistOrThrow(
                    current.copy(
                        state = DownloadState.RecoveryRequired,
                        speedBytesPerSecond = 0,
                        errorMessage = message,
                        updatedAtEpochMs = current.nextUpdatedAt(),
                    ),
                )
            }
        }
    }

    private fun retirePublicationJournal(path: String?) {
        val journal = path?.trim()?.takeIf(String::isNotBlank)?.let(::File) ?: return
        if (!journal.name.endsWith(".finalization.json")) return
        runCatching {
            if (journal.isFile) journal.delete()
            journal.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
        }
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

    private fun commandControl(downloadId: String): DownloadCommandControl =
        commandControls.computeIfAbsent(downloadId) { DownloadCommandControl() }

    private class DownloadCommandControl {
        val mutex = Mutex()
        val generation = AtomicLong(0)
        @Volatile var desired: DesiredTransferState = DesiredTransferState.None

        fun request(state: DesiredTransferState): Long {
            desired = state
            return generation.incrementAndGet()
        }
    }

    private enum class DesiredTransferState { None, PauseRequested, ResumeRequested, CancelRequested }

    companion object {
        val ACTIVE_STATES = setOf(DownloadState.Queued, DownloadState.Connecting, DownloadState.Downloading, DownloadState.Finalizing, DownloadState.Verifying, DownloadState.Repairing)
        val INTERRUPTED_STATES = setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Finalizing, DownloadState.Repairing, DownloadState.Verifying)
        val TERMINAL_STATES = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)
        val RELEASE_OWNERSHIP_STATES = setOf(DownloadState.Completed, DownloadState.Cancelled, DownloadState.Failed)
        val RUN_END_STATES = TERMINAL_STATES + setOf(DownloadState.Paused, DownloadState.RecoveryRequired)
    }

    private fun Download.nextUpdatedAt(nowEpochMs: Long = System.currentTimeMillis()): Long =
        maxOf(nowEpochMs, updatedAtEpochMs + 1L)

    private suspend fun persistOrThrow(download: Download) {
        check(store.save(download)) {
            "Rejected stale transfer write for ${download.id} generation ${download.attemptGeneration} at ${download.updatedAtEpochMs}"
        }
    }

}
