package com.mikeyphw.xdm.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import com.mikeyphw.xdm.android.scheduler.TransferExecutionRuntime
import com.mikeyphw.xdm.android.scheduler.TransferExecutionStarter
import com.mikeyphw.xdm.android.transfer.BackendSelectionPolicy
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import java.util.UUID
import android.net.Uri
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.storage.DestinationUris
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
)

class MainViewModel(
    private val repository: DownloadRepository,
    private val preferences: UserPreferencesStore,
    private val backendSelectionPolicy: BackendSelectionPolicy,
    private val transferRuntime: TransferExecutionRuntime,
    private val executionStarter: TransferExecutionStarter,
    private val destinationWriter: AndroidDestinationWriter,
) : ViewModel() {
    private val routeOverride = MutableStateFlow<AppRoute?>(null)

    private data class RepositorySnapshot(
        val downloads: List<Download>,
        val queues: List<QueueDefinition>,
        val schedules: List<ScheduleRule>,
        val recovery: List<RecoveryRecord>,
        val destinationPermissions: List<DestinationPermission>,
    )

    private val repositorySnapshot = combine(
        repository.downloads,
        repository.queues,
        repository.schedules,
        repository.recoveryRecords,
        repository.destinationPermissions,
    ) { downloads, queues, schedules, recovery, permissions -> RepositorySnapshot(downloads, queues, schedules, recovery, permissions) }

    val uiState: StateFlow<MainUiState> = combine(
        repositorySnapshot,
        preferences.values,
        routeOverride,
        transferRuntime.summary,
    ) { snapshot, prefs, override, active ->
        MainUiState(
            route = override ?: prefs.lastRoute,
            compactDensity = prefs.compactDensity,
            downloads = snapshot.downloads,
            queues = snapshot.queues,
            schedules = snapshot.schedules,
            recovery = snapshot.recovery,
            activeTransfers = active,
            destinationUri = prefs.destinationUri,
            conflictPolicy = prefs.conflictPolicy,
            destinationPermissions = snapshot.destinationPermissions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            if (repository.countQueues() == 0) FakeDataSeeder(repository).seedQueuesOnly()
        }
    }

    fun navigate(route: AppRoute) {
        routeOverride.value = route
        viewModelScope.launch { preferences.setRoute(route) }
    }

    fun setCompactDensity(compact: Boolean) {
        viewModelScope.launch { preferences.setCompactDensity(compact) }
    }

    fun addDownload(url: String, fileName: String, backend: BackendType, destination: String, conflictPolicy: FilenameConflictPolicy) {
        if (url.isBlank() || fileName.isBlank() || destination.isBlank()) return
        val safeName = sanitizeFileName(fileName)
        val now = System.currentTimeMillis()
        val request = previewRequest(url, safeName, backend, destination, conflictPolicy)
        val resolvedBackend = backendSelectionPolicy.recommend(request).backend
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
        )
        viewModelScope.launch {
            repository.save(download)
            executionStarter.start(download.id, userVisible = true)
            navigate(AppRoute.Downloads)
        }
    }

    fun backendRecommendation(url: String, fileName: String, backend: BackendType, destination: String, conflictPolicy: FilenameConflictPolicy) =
        backendSelectionPolicy.recommend(previewRequest(url, sanitizeFileName(fileName), backend, destination, conflictPolicy))

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

    private fun previewRequest(url: String, fileName: String, backend: BackendType, destination: String, conflictPolicy: FilenameConflictPolicy) = DownloadRequest(
        id = "preview",
        sourceUrl = url.trim(),
        destinationUri = destination,
        fileName = fileName,
        preferredBackend = backend,
        conflictPolicy = conflictPolicy,
    )

    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
        .ifBlank { "download.bin" }
        .take(180)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
            container.repository,
            container.preferences,
            container.backendSelectionPolicy,
            container.transferRuntime,
            container.executionStarter,
            container.destinationWriter,
        ) as T
    }
}
