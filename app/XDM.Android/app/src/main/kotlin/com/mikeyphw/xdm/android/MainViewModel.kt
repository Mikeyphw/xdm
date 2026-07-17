package com.mikeyphw.xdm.android

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.ChecksumSource
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import com.mikeyphw.xdm.android.scheduler.TransferExecutionRuntime
import com.mikeyphw.xdm.android.scheduler.TransferExecutionStarter
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.storage.DestinationUris
import com.mikeyphw.xdm.android.transfer.BackendSelectionPolicy
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.newChecksumExpectationId
import com.mikeyphw.xdm.android.transfer.normalizeHex
import com.mikeyphw.xdm.android.transfer.aria2.Aria2CapabilityReport
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessManager
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Aria2DiagnosticsUi(
    val status: String = "Checking",
    val detail: String = "Inspecting the packaged runtime and private session directory.",
    val canRunSmokeTest: Boolean = false,
    val smokeTestRunning: Boolean = false,
)

data class MainUiState(
    val route: AppRoute = AppRoute.Downloads,
    val compactDensity: Boolean = false,
    val downloads: List<Download> = emptyList(),
    val queues: List<QueueDefinition> = emptyList(),
    val schedules: List<ScheduleRule> = emptyList(),
    val recovery: List<RecoveryRecord> = emptyList(),
    val activeTransfers: ActiveTransferSummary = ActiveTransferSummary(),
    val destinationUri: String = DestinationUris.PUBLIC_DOWNLOADS,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
    val destinationPermissions: List<DestinationPermission> = emptyList(),
    val aria2Diagnostics: Aria2DiagnosticsUi = Aria2DiagnosticsUi(),
    val backendCapabilities: List<BackendCapabilityRow> = emptyList(),
    val backendMigrations: List<BackendMigrationRecord> = emptyList(),
    val checksumResults: List<ChecksumResult> = emptyList(),
    val verificationRecords: List<VerificationRecord> = emptyList(),
)

class MainViewModel(
    private val repository: DownloadRepository,
    private val preferences: UserPreferencesStore,
    private val backendSelectionPolicy: BackendSelectionPolicy,
    private val transferRuntime: TransferExecutionRuntime,
    private val executionStarter: TransferExecutionStarter,
    private val destinationWriter: AndroidDestinationWriter,
    private val aria2ProcessManager: Aria2ProcessManager,
) : ViewModel() {
    private val routeOverride = MutableStateFlow<AppRoute?>(null)
    private val aria2Capability = MutableStateFlow<Aria2CapabilityReport?>(null)
    private val aria2SmokeMessage = MutableStateFlow<String?>(null)
    private val aria2SmokeRunning = MutableStateFlow(false)
    private val capabilitySnapshot = MutableStateFlow<Map<BackendType, BackendCapabilities>>(emptyMap())

    private data class RepositorySnapshot(
        val downloads: List<Download>,
        val queues: List<QueueDefinition>,
        val schedules: List<ScheduleRule>,
        val recovery: List<RecoveryRecord>,
        val destinationPermissions: List<DestinationPermission>,
        val backendMigrations: List<BackendMigrationRecord>,
        val checksumResults: List<ChecksumResult>,
        val verificationRecords: List<VerificationRecord>,
    )

    private data class RepositoryBaseSnapshot(
        val downloads: List<Download>,
        val queues: List<QueueDefinition>,
        val schedules: List<ScheduleRule>,
        val recovery: List<RecoveryRecord>,
        val destinationPermissions: List<DestinationPermission>,
    )

    private val repositoryBaseSnapshot = combine(
        repository.downloads,
        repository.queues,
        repository.schedules,
        repository.recoveryRecords,
        repository.destinationPermissions,
    ) { downloads, queues, schedules, recovery, permissions -> RepositoryBaseSnapshot(downloads, queues, schedules, recovery, permissions) }

    private val verificationSnapshot = combine(repository.checksumResults, repository.verificationRecords) { results, records -> results to records }

    private val repositorySnapshot = combine(repositoryBaseSnapshot, repository.backendMigrations, verificationSnapshot) { base, migrations, verification ->
        RepositorySnapshot(
            base.downloads,
            base.queues,
            base.schedules,
            base.recovery,
            base.destinationPermissions,
            migrations,
            verification.first,
            verification.second,
        )
    }

    private val aria2Diagnostics = combine(
        aria2ProcessManager.state,
        aria2Capability,
        aria2SmokeMessage,
        aria2SmokeRunning,
    ) { processState, capability, smokeMessage, smokeRunning ->
        val status = when (processState) {
            is Aria2ProcessState.Running -> "Running"
            is Aria2ProcessState.Starting -> "Starting"
            is Aria2ProcessState.Stopping -> "Stopping"
            is Aria2ProcessState.Failed -> "Failed"
            is Aria2ProcessState.Unavailable -> "Unavailable"
            Aria2ProcessState.Stopped -> if (capability?.isAvailable == true) "Ready" else "Unavailable"
        }
        val detail = smokeMessage ?: when (processState) {
            is Aria2ProcessState.Running -> "aria2 ${processState.version.version} is authenticated on ${processState.endpoint.url}."
            is Aria2ProcessState.Starting -> "Waiting for authenticated loopback RPC."
            is Aria2ProcessState.Stopping -> "Saving the aria2 session and stopping the managed process."
            is Aria2ProcessState.Failed -> processState.message
            is Aria2ProcessState.Unavailable -> processState.report.summary
            Aria2ProcessState.Stopped -> capability?.summary ?: "Inspecting the packaged runtime."
        }
        Aria2DiagnosticsUi(
            status = status,
            detail = detail,
            canRunSmokeTest = capability?.isAvailable == true && !smokeRunning,
            smokeTestRunning = smokeRunning,
        )
    }

    private val runtimeUi = combine(transferRuntime.summary, aria2Diagnostics, capabilitySnapshot) { active, aria2, capabilities ->
        Triple(active, aria2, backendSelectionPolicy.capabilityRows(capabilities))
    }

    val uiState: StateFlow<MainUiState> = combine(
        repositorySnapshot,
        preferences.values,
        routeOverride,
        runtimeUi,
    ) { snapshot, prefs, override, runtime ->
        MainUiState(
            route = override ?: prefs.lastRoute,
            compactDensity = prefs.compactDensity,
            downloads = snapshot.downloads,
            queues = snapshot.queues,
            schedules = snapshot.schedules,
            recovery = snapshot.recovery,
            activeTransfers = runtime.first,
            destinationUri = prefs.destinationUri,
            conflictPolicy = prefs.conflictPolicy,
            destinationPermissions = snapshot.destinationPermissions,
            aria2Diagnostics = runtime.second,
            backendCapabilities = runtime.third,
            backendMigrations = snapshot.backendMigrations,
            checksumResults = snapshot.checksumResults,
            verificationRecords = snapshot.verificationRecords,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            if (repository.countQueues() == 0) FakeDataSeeder(repository).seedQueuesOnly()
        }
        refreshAria2Probe()
        refreshBackendCapabilities()
    }

    fun navigate(route: AppRoute) {
        routeOverride.value = route
        viewModelScope.launch { preferences.setRoute(route) }
    }


    fun runAria2SmokeTest() {
        if (aria2SmokeRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            aria2SmokeRunning.value = true
            aria2SmokeMessage.value = "Starting an authenticated loopback smoke test."
            try {
                val result = aria2ProcessManager.smokeTest()
                aria2SmokeMessage.value = result.summary
                aria2Capability.value = aria2ProcessManager.probe()
                refreshBackendCapabilities()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                aria2SmokeMessage.value = "The aria2 probe failed safely before the runtime became ready."
                aria2Capability.value = aria2ProcessManager.probe()
                capabilitySnapshot.value = transferRuntime.backendCapabilities()
            } finally {
                aria2SmokeRunning.value = false
            }
        }
    }

    fun refreshAria2Probe() {
        viewModelScope.launch(Dispatchers.IO) {
            aria2Capability.value = aria2ProcessManager.probe()
            aria2SmokeMessage.value = null
            capabilitySnapshot.value = transferRuntime.backendCapabilities()
        }
    }

    fun refreshBackendCapabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            capabilitySnapshot.value = transferRuntime.backendCapabilities()
        }
    }

    fun setCompactDensity(compact: Boolean) {
        viewModelScope.launch { preferences.setCompactDensity(compact) }
    }

    fun addDownload(
        url: String,
        fileName: String,
        backend: BackendType,
        destination: String,
        conflictPolicy: FilenameConflictPolicy,
        allowFallback: Boolean,
        expectedChecksum: String,
        checksumAlgorithm: ChecksumAlgorithm,
    ) {
        if (url.isBlank() || destination.isBlank()) return
        val safeName = resolveFileName(url, fileName)
        val now = System.currentTimeMillis()
        val request = previewRequest(url, safeName, backend, destination, conflictPolicy, allowFallback)
        val recommendation = backendSelectionPolicy.recommend(request, capabilitySnapshot.value.ifEmpty(::previewCapabilities))
        if (!recommendation.compatible) return
        val resolvedBackend = recommendation.backend
        val download = Download(
            id = UUID.randomUUID().toString(),
            fileName = safeName,
            sourceUrl = url.trim(),
            destinationUri = destination,
            state = DownloadState.Queued,
            backend = resolvedBackend,
            bytesReceived = 0,
            totalBytes = null,
            speedBytesPerSecond = 0,
            queueId = "default",
            priority = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            conflictPolicy = conflictPolicy,
            requestedBackend = backend,
            backendSelectionReason = recommendation.reason,
            backendSelectionExplanation = recommendation.explanation,
            allowBackendFallback = allowFallback,
        )
        viewModelScope.launch {
            repository.save(download)
            val normalizedChecksum = normalizeHex(expectedChecksum)
            if (normalizedChecksum.isNotBlank()) {
                repository.saveChecksumExpectation(
                    ChecksumExpectation(
                        id = newChecksumExpectationId(download.id, checksumAlgorithm),
                        downloadId = download.id,
                        algorithm = checksumAlgorithm,
                        expectedHex = normalizedChecksum,
                        source = ChecksumSource.UserInput,
                        createdAtEpochMs = now,
                    ),
                )
            }
            executionStarter.start(download.id, userVisible = true)
            navigate(AppRoute.Downloads)
        }
    }

    fun backendRecommendation(
        url: String,
        fileName: String,
        backend: BackendType,
        destination: String,
        conflictPolicy: FilenameConflictPolicy,
        allowFallback: Boolean,
    ) = backendSelectionPolicy.recommend(
        previewRequest(url, resolveFileName(url, fileName), backend, destination, conflictPolicy, allowFallback),
        capabilitySnapshot.value.ifEmpty(::previewCapabilities),
    )

    fun migrateBackend(download: Download) {
        val target = if (download.backend == BackendType.Native) BackendType.Aria2 else BackendType.Native
        viewModelScope.launch(Dispatchers.IO) {
            try {
                transferRuntime.migrateBackend(download.id, target, restartFromZero = download.bytesReceived > 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val current = repository.findDownload(download.id) ?: download
                repository.save(
                    current.copy(
                        errorMessage = "Backend migration could not start: ${error.message ?: error::class.java.simpleName}",
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            } finally {
                capabilitySnapshot.value = transferRuntime.backendCapabilities()
            }
        }
    }

    fun setDestination(uri: String) {
        viewModelScope.launch { preferences.setDestination(uri) }
    }

    fun setConflictPolicy(policy: FilenameConflictPolicy) {
        viewModelScope.launch { preferences.setConflictPolicy(policy) }
    }

    fun registerSafDestination(uri: String) {
        viewModelScope.launch {
            val parsed = Uri.parse(uri)
            destinationWriter.persistTreePermission(parsed)
            val health = destinationWriter.health(uri)
            repository.saveDestinationPermission(
                DestinationPermission(
                    uri = uri,
                    displayName = health.displayName,
                    type = health.type,
                    persistedRead = true,
                    persistedWrite = health.status == com.mikeyphw.xdm.android.model.DestinationHealthStatus.Healthy,
                    status = health.status,
                    lastValidatedAtEpochMs = System.currentTimeMillis(),
                    lastError = health.message,
                ),
            )
            preferences.setDestination(uri)
        }
    }


    fun pauseAll() {
        viewModelScope.launch { transferRuntime.pauseAll() }
    }

    fun resumeAll() {
        viewModelScope.launch {
            val paused = repository.findDownloadsByStates(setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower))
            paused.forEach { executionStarter.start(it.id, it.totalBytes, userVisible = true) }
        }
    }

    fun togglePause(download: Download) {
        viewModelScope.launch {
            when (download.state) {
                DownloadState.Downloading, DownloadState.Connecting, DownloadState.Queued, DownloadState.Finalizing -> transferRuntime.pause(download.id)
                DownloadState.Paused, DownloadState.Failed, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower -> {
                    repository.save(download.copy(state = DownloadState.Queued, errorMessage = null, updatedAtEpochMs = System.currentTimeMillis()))
                    executionStarter.start(download.id, download.totalBytes, userVisible = true)
                }
                else -> Unit
            }
        }
    }

    private fun previewCapabilities() = mapOf(
        BackendType.Native to BackendCapabilities(setOf("http", "https"), true, false, true, true),
    )

    private fun previewRequest(
        url: String,
        fileName: String,
        backend: BackendType,
        destination: String,
        conflictPolicy: FilenameConflictPolicy,
        allowFallback: Boolean,
    ) = DownloadRequest(
        id = "preview",
        sourceUrl = url.trim(),
        destinationUri = destination,
        fileName = fileName,
        preferredBackend = backend,
        conflictPolicy = conflictPolicy,
        allowBackendFallback = allowFallback,
    )

    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
        .ifBlank { "download.bin" }
        .take(180)

    private fun resolveFileName(url: String, requestedName: String): String {
        if (requestedName.isNotBlank()) return sanitizeFileName(requestedName)
        val inferred = runCatching {
            Uri.parse(url.trim()).lastPathSegment
                ?.substringBefore('?')
                ?.substringBefore('#')
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return sanitizeFileName(inferred.orEmpty())
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
            container.repository,
            container.preferences,
            container.backendSelectionPolicy,
            container.transferRuntime,
            container.executionStarter,
            container.destinationWriter,
            container.aria2ProcessManager,
        ) as T
    }
}
