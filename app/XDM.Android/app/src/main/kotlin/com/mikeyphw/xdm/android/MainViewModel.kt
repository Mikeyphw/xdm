package com.mikeyphw.xdm.android

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandIds
import com.mikeyphw.xdm.android.model.AutomationCommandRecord
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.AutomationRejectionReason
import com.mikeyphw.xdm.android.model.BackupRestorePolicy
import com.mikeyphw.xdm.android.model.BackupRestoreReport
import com.mikeyphw.xdm.android.model.BrowserIntegrationStatus
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ClipboardInboxItem
import com.mikeyphw.xdm.android.model.ClipboardInboxPolicy
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.ChecksumSource
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.DestinationRule
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.DuplicateUrlRule
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.media.MediaCaptureService
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.InstallUpdateReadinessReport
import com.mikeyphw.xdm.android.model.FinalPublicReleaseGate
import com.mikeyphw.xdm.android.model.FinalReleaseGateReport
import com.mikeyphw.xdm.android.model.ReleaseInstallReadinessGate
import com.mikeyphw.xdm.android.model.ReleaseSecurityGate
import com.mikeyphw.xdm.android.model.ReleaseSecurityReport
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.model.DesktopParityGate
import com.mikeyphw.xdm.android.model.DesktopParityReport
import com.mikeyphw.xdm.android.model.HistoryManagementPolicy
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.OrganizationPowerTools
import com.mikeyphw.xdm.android.model.OrganizationPowerToolsReport
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProtocolExpansionPolish
import com.mikeyphw.xdm.android.model.ProtocolExpansionReport
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.ReleasePackagingGate
import com.mikeyphw.xdm.android.model.ReleasePackagingReport
import com.mikeyphw.xdm.android.model.SettingsExchangeCodec
import com.mikeyphw.xdm.android.model.SettingsExchangeSnapshot
import com.mikeyphw.xdm.android.model.SavedSearch
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
import com.mikeyphw.xdm.android.util.sanitizeFileName
import com.mikeyphw.xdm.android.transfer.aria2.Aria2CapabilityReport
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessManager
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessState
import com.mikeyphw.xdm.android.termux.TermuxRootMode
import com.mikeyphw.xdm.android.termux.TermuxBridgeStatus
import com.mikeyphw.xdm.android.termux.TermuxBridgeManager
import com.mikeyphw.xdm.android.termux.TermuxAria2CockpitManager
import com.mikeyphw.xdm.android.termux.TermuxAria2CockpitStatus
import com.mikeyphw.xdm.android.termux.TermuxMediaPipelineManager
import com.mikeyphw.xdm.android.termux.TermuxMediaPipelineStatus
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationManager
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationStatus
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

data class ExternalAddDraft(
    val id: String,
    val url: String,
    val fileName: String = "",
    val sourceLabel: String = "External app",
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
    val externalAddDraft: ExternalAddDraft? = null,
    val destinationPermissions: List<DestinationPermission> = emptyList(),
    val aria2Diagnostics: Aria2DiagnosticsUi = Aria2DiagnosticsUi(),
    val termuxBridge: TermuxBridgeStatus = TermuxBridgeStatus(),
    val termuxAria2: TermuxAria2CockpitStatus = TermuxAria2CockpitStatus(),
    val termuxMediaPipeline: TermuxMediaPipelineStatus = TermuxMediaPipelineStatus(),
    val postProcessingAutomation: PostProcessingAutomationStatus = PostProcessingAutomationStatus(),
    val backendCapabilities: List<BackendCapabilityRow> = emptyList(),
    val backendMigrations: List<BackendMigrationRecord> = emptyList(),
    val checksumResults: List<ChecksumResult> = emptyList(),
    val verificationRecords: List<VerificationRecord> = emptyList(),
    val finalizationJournals: List<FinalizationJournal> = emptyList(),
    val mediaCaptures: List<MediaCaptureRecord> = emptyList(),
    val mediaVariants: List<MediaVariant> = emptyList(),
    val automationCommands: List<AutomationCommandRecord> = emptyList(),
    val tags: List<DownloadTag> = emptyList(),
    val tagAssignments: List<DownloadTagAssignment> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val destinationRules: List<DestinationRule> = emptyList(),
    val duplicateRules: List<DuplicateUrlRule> = emptyList(),
    val clipboardInbox: List<ClipboardInboxItem> = emptyList(),
    val proxySettings: ProxyCredentialSettings = ProxyCredentialSettings(),
    val postProcessingSettings: PostProcessingSettings = PostProcessingSettings(),
    val settingsSnapshot: SettingsExchangeSnapshot = SettingsExchangeSnapshot(),
    val settingsExportText: String = SettingsExchangeSnapshot().toPortableText(),
    val historyReport: HistoryManagementReport = HistoryManagementPolicy.summarize(emptyList()),
    val organizationReport: OrganizationPowerToolsReport = OrganizationPowerTools.summarize(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    val browserIntegrationStatus: BrowserIntegrationStatus = BrowserIntegrationStatus(true, true, true, 0, 0),
    val backupRestoreReport: BackupRestoreReport = BackupRestorePolicy.evaluate(SettingsExchangeSnapshot().toPortableText()),
    val protocolExpansionReport: ProtocolExpansionReport = ProtocolExpansionPolish.summarize(emptyList()),
    val releasePackagingReport: ReleasePackagingReport = ReleasePackagingGate.report("0.18.0-rc01", 19, "com.mikeyphw.xdm.android"),
    val desktopParityReport: DesktopParityReport = DesktopParityGate.evaluate(true, true, true, true, true, true),
    val finalReleaseGateReport: FinalReleaseGateReport = FinalPublicReleaseGate.evaluate(
        versionName = "0.18.0-rc01",
        versionCode = 19,
        packageId = "com.mikeyphw.xdm.android",
        schemaVersion = 14,
        buildType = "debug",
        releaseSafetyReady = true,
        installUpdateReady = true,
        diagnosticsRedacted = true,
        aria2PayloadVerified = false,
        staticValidatorsComplete = true,
        releaseDocsComplete = true,
        noNewTopLevelRoutes = true,
        fullValidationPassed = false,
        releaseSigningConfigured = false,
    ),
    val releaseSecurityReport: ReleaseSecurityReport = ReleaseSecurityGate.evaluate(
        versionName = "0.18.0-rc01",
        schemaVersion = 14,
        buildType = "debug",
        debuggable = true,
        privacySafeDiagnostics = true,
        releaseSigningConfigured = false,
    ),
    val installUpdateReadinessReport: InstallUpdateReadinessReport = ReleaseInstallReadinessGate.evaluate(
        versionName = "0.18.0-rc01",
        versionCode = 19,
        packageId = "com.mikeyphw.xdm.android",
        schemaVersion = 14,
        buildType = "debug",
        releaseSafetyComplete = true,
        recoverySurfaceReady = true,
        diagnosticsExportRedacted = true,
        aria2PayloadGateRetained = true,
        updateKeepsPackageIdentity = true,
        releaseSigningConfigured = false,
    ),
)

class MainViewModel(
    private val repository: DownloadRepository,
    private val preferences: UserPreferencesStore,
    private val backendSelectionPolicy: BackendSelectionPolicy,
    private val transferRuntime: TransferExecutionRuntime,
    private val executionStarter: TransferExecutionStarter,
    private val destinationWriter: AndroidDestinationWriter,
    private val aria2ProcessManager: Aria2ProcessManager,
    private val termuxBridgeManager: TermuxBridgeManager,
    private val termuxAria2CockpitManager: TermuxAria2CockpitManager,
    private val termuxMediaPipelineManager: TermuxMediaPipelineManager,
    private val postProcessingAutomationManager: PostProcessingAutomationManager,
) : ViewModel() {
    private val routeOverride = MutableStateFlow<AppRoute?>(null)
    private val aria2Capability = MutableStateFlow<Aria2CapabilityReport?>(null)
    private val aria2SmokeMessage = MutableStateFlow<String?>(null)
    private val aria2SmokeRunning = MutableStateFlow(false)
    private val capabilitySnapshot = MutableStateFlow<Map<BackendType, BackendCapabilities>>(emptyMap())
    private val externalAddDraft = MutableStateFlow<ExternalAddDraft?>(null)
    private val mediaCaptureService = MediaCaptureService()

    private data class RepositorySnapshot(
        val downloads: List<Download>,
        val queues: List<QueueDefinition>,
        val schedules: List<ScheduleRule>,
        val recovery: List<RecoveryRecord>,
        val destinationPermissions: List<DestinationPermission>,
        val backendMigrations: List<BackendMigrationRecord>,
        val checksumResults: List<ChecksumResult>,
        val verificationRecords: List<VerificationRecord>,
        val finalizationJournals: List<FinalizationJournal>,
        val mediaCaptures: List<MediaCaptureRecord>,
        val mediaVariants: List<MediaVariant>,
        val automationCommands: List<AutomationCommandRecord>,
        val tags: List<DownloadTag>,
        val tagAssignments: List<DownloadTagAssignment>,
        val savedSearches: List<SavedSearch>,
        val destinationRules: List<DestinationRule>,
        val duplicateRules: List<DuplicateUrlRule>,
        val clipboardInbox: List<ClipboardInboxItem>,
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

    private val mediaSnapshot = combine(repository.mediaCaptures, repository.mediaVariants) { captures, variants -> captures to variants }

    private val mediaAutomationSnapshot = combine(mediaSnapshot, repository.automationCommands) { media, automation -> media to automation }

    private data class OrganizationSnapshot(
        val tags: List<DownloadTag>,
        val tagAssignments: List<DownloadTagAssignment>,
        val savedSearches: List<SavedSearch>,
        val destinationRules: List<DestinationRule>,
        val duplicateRules: List<DuplicateUrlRule>,
        val clipboardInbox: List<ClipboardInboxItem>,
    )

    private data class RuleSnapshot(
        val destinationRules: List<DestinationRule>,
        val duplicateRules: List<DuplicateUrlRule>,
        val clipboardInbox: List<ClipboardInboxItem>,
    )

    private val organizationBaseSnapshot = combine(repository.tags, repository.tagAssignments, repository.savedSearches) { tags, assignments, searches ->
        Triple(tags, assignments, searches)
    }

    private val ruleSnapshot = combine(repository.destinationRules, repository.duplicateRules, repository.clipboardInbox) { destinations, duplicates, clipboard ->
        RuleSnapshot(destinations, duplicates, clipboard)
    }

    private val organizationSnapshot = combine(organizationBaseSnapshot, ruleSnapshot) { base, rules ->
        OrganizationSnapshot(base.first, base.second, base.third, rules.destinationRules, rules.duplicateRules, rules.clipboardInbox)
    }

    private data class RepositoryRuntimeSnapshot(
        val base: RepositoryBaseSnapshot,
        val migrations: List<BackendMigrationRecord>,
        val verification: Pair<List<ChecksumResult>, List<VerificationRecord>>,
    )

    private val repositoryRuntimeSnapshot = combine(repositoryBaseSnapshot, repository.backendMigrations, verificationSnapshot) { base, migrations, verification ->
        RepositoryRuntimeSnapshot(base, migrations, verification)
    }

    private data class RepositoryMediaSnapshot(
        val finalization: List<FinalizationJournal>,
        val mediaAutomation: Pair<Pair<List<MediaCaptureRecord>, List<MediaVariant>>, List<AutomationCommandRecord>>,
        val organization: OrganizationSnapshot,
    )

    private val repositoryMediaSnapshot = combine(repository.finalizationJournals, mediaAutomationSnapshot, organizationSnapshot) { finalization, mediaAutomation, organization ->
        RepositoryMediaSnapshot(finalization, mediaAutomation, organization)
    }

    private val repositorySnapshot = combine(repositoryRuntimeSnapshot, repositoryMediaSnapshot) { runtime, extra ->
        val base = runtime.base
        val migrations = runtime.migrations
        val verification = runtime.verification
        val finalization = extra.finalization
        val mediaAutomation = extra.mediaAutomation
        val organization = extra.organization
        val media = mediaAutomation.first
        val automation = mediaAutomation.second
        RepositorySnapshot(
            base.downloads,
            base.queues,
            base.schedules,
            base.recovery,
            base.destinationPermissions,
            migrations,
            verification.first,
            verification.second,
            finalization,
            media.first,
            media.second,
            automation,
            organization.tags,
            organization.tagAssignments,
            organization.savedSearches,
            organization.destinationRules,
            organization.duplicateRules,
            organization.clipboardInbox,
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

    private data class RuntimeUiSnapshot(
        val activeTransfers: ActiveTransferSummary,
        val aria2: Aria2DiagnosticsUi,
        val capabilities: List<BackendCapabilityRow>,
        val termuxBridge: TermuxBridgeStatus,
        val termuxAria2: TermuxAria2CockpitStatus,
        val termuxMediaPipeline: TermuxMediaPipelineStatus,
        val postProcessingAutomation: PostProcessingAutomationStatus,
    )

    private data class TermuxUiSnapshot(
        val bridge: TermuxBridgeStatus,
        val aria2: TermuxAria2CockpitStatus,
        val mediaPipeline: TermuxMediaPipelineStatus,
        val postProcessingAutomation: PostProcessingAutomationStatus,
    )

    private val termuxUi = combine(termuxBridgeManager.status, termuxAria2CockpitManager.status, termuxMediaPipelineManager.status, postProcessingAutomationManager.status) { bridge, aria2, mediaPipeline, postAutomation ->
        TermuxUiSnapshot(bridge, aria2, mediaPipeline, postAutomation)
    }

    private val runtimeUi = combine(transferRuntime.summary, aria2Diagnostics, capabilitySnapshot, termuxUi) { active, aria2, capabilities, termux ->
        RuntimeUiSnapshot(active, aria2, backendSelectionPolicy.capabilityRows(capabilities), termux.bridge, termux.aria2, termux.mediaPipeline, termux.postProcessingAutomation)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repositorySnapshot,
        preferences.values,
        routeOverride,
        runtimeUi,
        externalAddDraft,
    ) { snapshot, prefs, override, runtime, addDraft ->
        val settingsSnapshot = SettingsExchangeSnapshot(
            compactDensity = prefs.compactDensity,
            destinationUri = prefs.destinationUri,
            conflictPolicy = prefs.conflictPolicy,
            proxy = prefs.proxySettings,
            postProcessing = prefs.postProcessingSettings,
            savedSearches = snapshot.savedSearches,
            destinationRules = snapshot.destinationRules,
            duplicateRules = snapshot.duplicateRules,
        )
        val settingsExportText = settingsSnapshot.toPortableText()
        MainUiState(
            route = override ?: prefs.lastRoute,
            compactDensity = prefs.compactDensity,
            downloads = snapshot.downloads,
            queues = snapshot.queues,
            schedules = snapshot.schedules,
            recovery = snapshot.recovery,
            activeTransfers = runtime.activeTransfers,
            destinationUri = prefs.destinationUri,
            conflictPolicy = prefs.conflictPolicy,
            externalAddDraft = addDraft,
            destinationPermissions = snapshot.destinationPermissions,
            aria2Diagnostics = runtime.aria2,
            termuxBridge = runtime.termuxBridge,
            termuxAria2 = runtime.termuxAria2,
            termuxMediaPipeline = runtime.termuxMediaPipeline,
            postProcessingAutomation = runtime.postProcessingAutomation,
            backendCapabilities = runtime.capabilities,
            backendMigrations = snapshot.backendMigrations,
            checksumResults = snapshot.checksumResults,
            verificationRecords = snapshot.verificationRecords,
            finalizationJournals = snapshot.finalizationJournals,
            mediaCaptures = snapshot.mediaCaptures,
            mediaVariants = snapshot.mediaVariants,
            automationCommands = snapshot.automationCommands,
            tags = snapshot.tags,
            tagAssignments = snapshot.tagAssignments,
            savedSearches = snapshot.savedSearches,
            destinationRules = snapshot.destinationRules,
            duplicateRules = snapshot.duplicateRules,
            clipboardInbox = snapshot.clipboardInbox,
            proxySettings = prefs.proxySettings,
            postProcessingSettings = prefs.postProcessingSettings,
            settingsSnapshot = settingsSnapshot,
            settingsExportText = settingsExportText,
            historyReport = HistoryManagementPolicy.summarize(snapshot.downloads),
            organizationReport = OrganizationPowerTools.summarize(snapshot.tags, snapshot.savedSearches, snapshot.destinationRules, snapshot.duplicateRules, snapshot.downloads),
            browserIntegrationStatus = BrowserIntegrationStatus(
                shareHandoff = true,
                viewHandoff = true,
                clipboardInbox = true,
                recentOrigins = snapshot.automationCommands.mapNotNull { it.originHost }.distinct().size,
                rejectedHandoffs = snapshot.automationCommands.count { it.status == AutomationCommandStatus.Rejected },
            ),
            backupRestoreReport = BackupRestorePolicy.evaluate(settingsExportText),
            protocolExpansionReport = ProtocolExpansionPolish.summarize(runtime.capabilities),
            releasePackagingReport = ReleasePackagingGate.report(
                versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-beta"),
                versionCode = BuildConfig.VERSION_CODE,
                packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug").removeSuffix(".beta"),
            ),
            desktopParityReport = DesktopParityGate.evaluate(
                settingsImportExport = true,
                historyManagement = true,
                proxyCredentials = true,
                conversionPostProcessing = true,
                protocolExpansion = true,
                releasePackaging = true,
            ),
            releaseSecurityReport = ReleaseSecurityGate.evaluate(
                versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-beta"),
                schemaVersion = 14,
                buildType = BuildConfig.BUILD_TYPE,
                debuggable = BuildConfig.DEBUG,
                privacySafeDiagnostics = true,
                releaseSigningConfigured = !BuildConfig.DEBUG,
            ),
            installUpdateReadinessReport = ReleaseInstallReadinessGate.evaluate(
                versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-beta"),
                versionCode = BuildConfig.VERSION_CODE,
                packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug").removeSuffix(".beta"),
                schemaVersion = 14,
                buildType = BuildConfig.BUILD_TYPE,
                releaseSafetyComplete = true,
                recoverySurfaceReady = snapshot.finalizationJournals.none { it.needsRecovery } || snapshot.recovery.isNotEmpty() || snapshot.finalizationJournals.isEmpty(),
                diagnosticsExportRedacted = true,
                aria2PayloadGateRetained = true,
                updateKeepsPackageIdentity = true,
                releaseSigningConfigured = !BuildConfig.DEBUG,
            ),
            finalReleaseGateReport = FinalPublicReleaseGate.evaluate(
                versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-beta"),
                versionCode = BuildConfig.VERSION_CODE,
                packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug").removeSuffix(".beta"),
                schemaVersion = 14,
                buildType = BuildConfig.BUILD_TYPE,
                releaseSafetyReady = true,
                installUpdateReady = true,
                diagnosticsRedacted = true,
                aria2PayloadVerified = false,
                staticValidatorsComplete = true,
                releaseDocsComplete = true,
                noNewTopLevelRoutes = true,
                fullValidationPassed = false,
                releaseSigningConfigured = !BuildConfig.DEBUG,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            if (repository.countQueues() == 0) FakeDataSeeder(repository).seedQueuesOnly()
        }
        refreshAria2Probe()
        refreshBackendCapabilities()
        termuxBridgeManager.refreshStatus()
        termuxMediaPipelineManager.refreshStatus()
        postProcessingAutomationManager.refreshStatus()
    }

    fun navigate(route: AppRoute) {
        routeOverride.value = route
        viewModelScope.launch { preferences.setRoute(route) }
    }



    fun createQueue(name: String, maxConcurrent: Int) {
        val trimmed = name.trim().ifBlank { "Queue ${uiState.value.queues.size + 1}" }
        val queue = QueueDefinition(
            id = "queue-${UUID.randomUUID()}",
            name = trimmed.take(48),
            isEnabled = true,
            maxConcurrent = maxConcurrent.coerceIn(1, 16),
            createdAtEpochMs = System.currentTimeMillis(),
        )
        viewModelScope.launch(Dispatchers.IO) { repository.saveQueue(queue) }
    }

    fun updateQueue(queue: QueueDefinition, name: String, maxConcurrent: Int, enabled: Boolean) {
        val updated = queue.copy(
            name = name.trim().ifBlank { queue.name }.take(48),
            maxConcurrent = maxConcurrent.coerceIn(1, 16),
            isEnabled = enabled,
        )
        viewModelScope.launch(Dispatchers.IO) { repository.saveQueue(updated) }
    }

    fun setQueueEnabled(queue: QueueDefinition, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.saveQueue(queue.copy(isEnabled = enabled)) }
    }

    fun deleteQueue(queue: QueueDefinition) {
        if (queue.id == "default") return
        viewModelScope.launch(Dispatchers.IO) { repository.deleteQueue(queue.id) }
    }

    fun createSchedule(name: String, queueId: String?, constraintsJson: String) {
        val trimmed = name.trim().ifBlank { "Schedule ${uiState.value.schedules.size + 1}" }
        val rule = ScheduleRule(
            id = "schedule-${UUID.randomUUID()}",
            queueId = queueId,
            name = trimmed.take(48),
            enabled = true,
            constraintsJson = constraintsJson.ifBlank { "{}" },
        )
        viewModelScope.launch(Dispatchers.IO) { repository.saveSchedule(rule) }
    }

    fun updateSchedule(rule: ScheduleRule, name: String, queueId: String?, enabled: Boolean, constraintsJson: String) {
        val updated = rule.copy(
            name = name.trim().ifBlank { rule.name }.take(48),
            queueId = queueId,
            enabled = enabled,
            constraintsJson = constraintsJson.ifBlank { "{}" },
        )
        viewModelScope.launch(Dispatchers.IO) { repository.saveSchedule(updated) }
    }

    fun setScheduleEnabled(rule: ScheduleRule, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.saveSchedule(rule.copy(enabled = enabled)) }
    }

    fun deleteSchedule(rule: ScheduleRule) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteSchedule(rule.id) }
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
        termuxBridgeManager.refreshStatus()
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

    fun runTermuxToolProbe() {
        termuxBridgeManager.runToolProbe()
    }

    fun openTermux() {
        termuxBridgeManager.openTermux()
        termuxBridgeManager.refreshStatus()
        termuxMediaPipelineManager.refreshStatus()
        postProcessingAutomationManager.refreshStatus()
    }

    fun setTermuxRootMode(mode: TermuxRootMode) {
        termuxBridgeManager.setRootMode(mode)
    }

    fun runTermuxRootProbe() {
        termuxBridgeManager.runRootProbe()
    }

    fun collectTermuxRootProcessDiagnostics() {
        termuxBridgeManager.collectRootProcessDiagnostics()
    }

    fun killStuckTermuxAria2WithRoot() {
        termuxBridgeManager.killStuckTermuxAria2Daemon()
    }

    fun fixTermuxDownloadPermissionsWithRoot() {
        termuxBridgeManager.fixTermuxDownloadPermissions("storage/downloads/XDM")
    }

    fun setTermuxAria2Enabled(enabled: Boolean) {
        termuxAria2CockpitManager.setEnabled(enabled)
    }

    fun startTermuxAria2Daemon() {
        termuxAria2CockpitManager.startDaemon()
    }

    fun stopTermuxAria2Daemon() {
        termuxAria2CockpitManager.stopDaemon()
    }

    fun probeTermuxAria2Daemon() {
        termuxAria2CockpitManager.probeDaemon()
    }

    fun saveTermuxAria2Session() {
        termuxAria2CockpitManager.saveSession()
    }

    fun refreshTermuxAria2Tasks() {
        termuxAria2CockpitManager.refreshTasks()
    }

    fun pauseAllTermuxAria2Tasks() {
        termuxAria2CockpitManager.pauseAll()
    }

    fun resumeAllTermuxAria2Tasks() {
        termuxAria2CockpitManager.resumeAll()
    }

    fun rotateTermuxAria2Secret() {
        termuxAria2CockpitManager.rotateSecret()
    }

    fun extractMediaMetadataWithTermux(record: MediaCaptureRecord) {
        termuxMediaPipelineManager.extractMetadata(record)
    }

    fun inspectMediaWithTermuxFfprobe(record: MediaCaptureRecord) {
        termuxMediaPipelineManager.inspectWithFfprobe(record)
    }

    fun downloadMediaWithTermuxYtDlp(record: MediaCaptureRecord) {
        termuxMediaPipelineManager.downloadWithYtDlp(record)
    }

    fun convertMediaWithTermux(record: MediaCaptureRecord, preset: ConversionPreset) {
        termuxMediaPipelineManager.convert(record, preset)
    }

    fun clearCompletedTermuxMediaJobs() {
        termuxMediaPipelineManager.clearCompleted()
    }


    fun setPostProcessingAutomationEnabled(enabled: Boolean) {
        postProcessingAutomationManager.setEnabled(enabled)
    }

    fun previewPostProcessingForDownload(download: Download) {
        postProcessingAutomationManager.preview(download)
    }

    fun runPostProcessingForDownload(download: Download) {
        postProcessingAutomationManager.runForDownload(download)
    }

    fun previewPostProcessingForMedia(record: MediaCaptureRecord) {
        postProcessingAutomationManager.preview(record)
    }

    fun runPostProcessingForMedia(record: MediaCaptureRecord) {
        postProcessingAutomationManager.runForMedia(record)
    }

    fun retryFailedPostProcessing() {
        postProcessingAutomationManager.retryLastFailed()
    }

    fun clearPostProcessingEvents() {
        postProcessingAutomationManager.clearEvents()
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

    fun setProxySettings(settings: ProxyCredentialSettings) {
        viewModelScope.launch { preferences.setProxySettings(settings) }
    }

    fun setPostProcessingSettings(settings: PostProcessingSettings) {
        viewModelScope.launch { preferences.setPostProcessingSettings(settings) }
    }

    fun importSettingsSnapshot(text: String) {
        val snapshot = SettingsExchangeCodec.decode(text) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            preferences.importSnapshot(snapshot)
            snapshot.savedSearches.forEach { repository.saveSavedSearch(it.copy(createdAtEpochMs = if (it.createdAtEpochMs == 0L) System.currentTimeMillis() else it.createdAtEpochMs)) }
            snapshot.destinationRules.forEach { repository.saveDestinationRule(it) }
            snapshot.duplicateRules.forEach { repository.saveDuplicateRule(it) }
        }
    }

    fun createTag(name: String) {
        val trimmed = name.trim().take(32)
        if (trimmed.isBlank()) return
        val tag = DownloadTag("tag-${UUID.nameUUIDFromBytes(trimmed.lowercase().toByteArray())}", trimmed, 0xff4f7cff)
        viewModelScope.launch(Dispatchers.IO) { repository.saveTag(tag) }
    }

    fun assignTag(download: Download, tag: DownloadTag) {
        viewModelScope.launch(Dispatchers.IO) { repository.assignTag(download.id, tag.id) }
    }

    fun saveSearch(name: String, query: String, state: DownloadState?, includeArchived: Boolean) {
        val trimmed = name.trim().take(48)
        if (trimmed.isBlank()) return
        val search = SavedSearch(
            id = "search-${UUID.randomUUID()}",
            name = trimmed,
            query = query.trim().take(160),
            state = state,
            includeArchived = includeArchived,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        viewModelScope.launch(Dispatchers.IO) { repository.saveSavedSearch(search) }
    }

    fun deleteSavedSearch(search: SavedSearch) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteSavedSearch(search.id) }
    }

    fun archiveDownloads(downloads: List<Download>, archived: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.setArchived(downloads.map { it.id }, archived) }
    }

    fun bulkPause(downloads: List<Download>) {
        val ids = downloads.filter { it.state in setOf(DownloadState.Queued, DownloadState.Connecting, DownloadState.Downloading) }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id -> repository.findDownload(id)?.let { repository.save(it.copy(state = DownloadState.Paused, updatedAtEpochMs = System.currentTimeMillis())) } }
        }
    }

    fun bulkResume(downloads: List<Download>) {
        val candidates = downloads.filter { it.state in setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower) }
        if (candidates.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            candidates.forEach { executionStarter.start(it.id, it.totalBytes, userVisible = true) }
        }
    }

    fun saveDestinationRule(name: String, match: DestinationRuleMatch, pattern: String, destinationUri: String) {
        val trimmed = name.trim().take(48)
        val matchText = pattern.trim().take(96)
        if (trimmed.isBlank() || matchText.isBlank() || destinationUri.isBlank()) return
        val priority = uiState.value.destinationRules.size + 1
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveDestinationRule(DestinationRule("dest-${UUID.randomUUID()}", trimmed, match, matchText, destinationUri, true, priority))
        }
    }

    fun saveDuplicateRule(hostPattern: String, action: DuplicateUrlAction) {
        val host = hostPattern.trim().lowercase().take(96)
        if (host.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveDuplicateRule(DuplicateUrlRule("dup-${UUID.randomUUID()}", host, action, true))
        }
    }

    fun scanClipboardText(text: String) {
        val items = ClipboardInboxPolicy.itemsFromText(text, uiState.value.clipboardInbox, System.currentTimeMillis())
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { repository.saveClipboardItems(items) }
    }

    fun acceptClipboardItem(item: ClipboardInboxItem) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveClipboardItem(item.copy(status = "Accepted", updatedAtEpochMs = System.currentTimeMillis()))
            processAutomationCommand(
                AutomationCommandDraft(
                    source = AutomationCommandSource.DeepLink,
                    action = AutomationCommandAction.CaptureMedia,
                    url = item.url,
                    pageTitle = item.title,
                    explicitIdempotencyKey = item.id,
                ),
            )
        }
    }

    fun dismissClipboardItem(item: ClipboardInboxItem) {
        viewModelScope.launch(Dispatchers.IO) { repository.saveClipboardItem(item.copy(status = "Dismissed", updatedAtEpochMs = System.currentTimeMillis())) }
    }

    fun clearFinishedHistory() {
        val finished = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)
        val candidates = uiState.value.downloads.filter { it.state in finished }
        if (candidates.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { candidates.forEach { repository.deleteDownload(it.id) } }
    }

    fun removeDownloadFromHistory(download: Download) {
        if (!HistoryManagementPolicy.isSafeToRemoveFromHistory(download)) return
        viewModelScope.launch(Dispatchers.IO) { repository.deleteDownload(download.id) }
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
        val duplicate = OrganizationPowerTools.duplicateFor(url, uiState.value.downloads)
        if (duplicate != null) {
            navigate(AppRoute.Downloads)
            return
        }
        val now = System.currentTimeMillis()
        val consumedExternalDraft = externalAddDraft.value
        val mediaCandidate = mediaCaptureService.candidateFor(url)
        val resolvedDestination = OrganizationPowerTools.destinationFor(url, safeName, mediaCandidate?.mimeType, uiState.value.destinationRules, destination)
        val request = previewRequest(url, safeName, backend, resolvedDestination, conflictPolicy, allowFallback, isMediaRequest = mediaCandidate != null)
        val recommendation = backendSelectionPolicy.recommend(request, capabilitySnapshot.value.ifEmpty(::previewCapabilities))
        if (!recommendation.compatible) return
        val resolvedBackend = recommendation.backend
        val download = Download(
            id = UUID.randomUUID().toString(),
            fileName = safeName,
            sourceUrl = url.trim(),
            destinationUri = resolvedDestination,
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
            mimeType = mediaCandidate?.mimeType,
            requestedBackend = backend,
            backendSelectionReason = recommendation.reason,
            backendSelectionExplanation = recommendation.explanation,
            allowBackendFallback = allowFallback,
        )
        viewModelScope.launch {
            repository.save(download)
            consumedExternalDraft?.let { markExternalDraftDownloadCreated(it, download.id) }
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
            externalAddDraft.value = null
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
        previewRequest(url, resolveFileName(url, fileName), backend, destination, conflictPolicy, allowFallback, isMediaRequest = mediaCaptureService.candidateFor(url) != null),
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

    fun removeRecoveryRecord(record: RecoveryRecord) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteRecovery(record.id) }
    }

    fun validateRecoveryRecord(record: RecoveryRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloadId = record.downloadId ?: return@launch
            val current = repository.findDownload(downloadId) ?: return@launch
            repository.save(current.copy(state = DownloadState.Queued, errorMessage = null, updatedAtEpochMs = System.currentTimeMillis()))
            executionStarter.start(downloadId, current.totalBytes, userVisible = true)
        }
    }



    fun ingestAutomationCommand(draft: AutomationCommandDraft) {
        viewModelScope.launch(Dispatchers.IO) {
            processAutomationCommand(draft)
        }
    }

    private suspend fun processAutomationCommand(draft: AutomationCommandDraft) {
        val key = draft.stableIdempotencyKey
        val now = System.currentTimeMillis()
        val existing = repository.findAutomationCommandByKey(key)
        if (existing != null) {
            if (existing.mediaCaptureId != null) {
                repository.saveAutomationCommand(existing.copy(status = AutomationCommandStatus.Duplicate, resultMessage = "Duplicate media handoff reopened", rejectionReason = AutomationRejectionReason.Duplicate, updatedAtEpochMs = now))
                navigate(AppRoute.Media)
                return
            }
            if (existing.downloadId != null) {
                repository.saveAutomationCommand(existing.copy(status = AutomationCommandStatus.Duplicate, resultMessage = "Duplicate download handoff reopened", rejectionReason = AutomationRejectionReason.Duplicate, updatedAtEpochMs = now))
                navigate(AppRoute.Downloads)
                return
            }
            val url = existing.url
            if (!url.isNullOrBlank()) {
                externalAddDraft.value = ExternalAddDraft(
                    id = existing.id,
                    url = url,
                    fileName = existing.fileName.orEmpty(),
                    sourceLabel = sourceLabelFor(existing.source),
                )
                repository.saveAutomationCommand(existing.copy(status = AutomationCommandStatus.Duplicate, resultMessage = "Duplicate link reopened in Add Download", rejectionReason = AutomationRejectionReason.Duplicate, updatedAtEpochMs = now))
                navigate(AppRoute.Add)
                return
            }
            repository.saveAutomationCommand(existing.copy(status = AutomationCommandStatus.Duplicate, resultMessage = "Duplicate command ignored", rejectionReason = AutomationRejectionReason.Duplicate, updatedAtEpochMs = now))
            return
        }
        val accepted = AutomationCommandRecord(
            id = AutomationCommandIds.commandId(key),
            idempotencyKey = key,
            source = draft.source,
            action = draft.action,
            url = draft.normalizedUrl,
            fileName = draft.fileName?.trim()?.takeIf { it.isNotBlank() },
            pageTitle = draft.pageTitle?.trim()?.takeIf { it.isNotBlank() },
            pageUrl = draft.normalizedPageUrl,
            mediaCaptureId = null,
            downloadId = null,
            status = AutomationCommandStatus.Accepted,
            resultMessage = "Accepted",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            originPackage = draft.originPackage?.trim()?.takeIf { it.isNotBlank() },
            originHost = draft.originHost,
            sanitizedHeaders = draft.sanitizedHeaders,
        )
        repository.saveAutomationCommand(accepted)
        when (draft.action) {
            AutomationCommandAction.CaptureMedia -> executeCaptureMediaCommand(accepted, draft, now)
            AutomationCommandAction.EnqueueDownload -> executeEnqueueCommand(accepted, draft, now)
            AutomationCommandAction.PauseAll -> {
                transferRuntime.pauseAll()
                repository.saveAutomationCommand(accepted.copy(status = AutomationCommandStatus.Executed, resultMessage = "Pause all requested", updatedAtEpochMs = System.currentTimeMillis()))
            }
            AutomationCommandAction.ResumeAll -> {
                val paused = repository.findDownloadsByStates(setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower))
                paused.forEach { executionStarter.start(it.id, it.totalBytes, userVisible = true) }
                repository.saveAutomationCommand(accepted.copy(status = AutomationCommandStatus.Executed, resultMessage = "Resume requested for ${paused.size} download(s)", updatedAtEpochMs = System.currentTimeMillis()))
            }
            AutomationCommandAction.Unknown -> repository.saveAutomationCommand(accepted.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Unsupported automation action", rejectionReason = AutomationRejectionReason.UnsupportedAction, updatedAtEpochMs = System.currentTimeMillis()))
        }
    }

    private suspend fun executeCaptureMediaCommand(command: AutomationCommandRecord, draft: AutomationCommandDraft, now: Long) {
        val text = draft.normalizedUrl ?: return repository.saveAutomationCommand(
            command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing media URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = now),
        )
        val records = mediaCaptureService.detect(text, draft.pageTitle, draft.pageUrl)
        if (records.isEmpty()) {
            openExternalAddDraft(command, draft, "No media stream was detected; opened Add Download instead")
            return
        }
        val merged = records.map { record ->
            val existing = repository.findMediaCapture(record.id)
            if (existing?.downloadId != null) {
                record.copy(status = existing.status, downloadId = existing.downloadId, createdAtEpochMs = existing.createdAtEpochMs, updatedAtEpochMs = System.currentTimeMillis())
            } else {
                record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs)
            }
        }
        repository.saveMediaCaptures(merged)
        repository.saveMediaVariants(merged.mapNotNull { mediaCaptureService.candidateFor(it.sourceUrl)?.variants }.flatten())
        repository.saveAutomationCommand(
            command.copy(
                status = AutomationCommandStatus.Executed,
                resultMessage = "Captured ${merged.size} media item(s)",
                mediaCaptureId = merged.firstOrNull()?.id,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        navigate(AppRoute.Media)
    }

    private suspend fun openExternalAddDraft(command: AutomationCommandRecord, draft: AutomationCommandDraft, message: String) {
        val url = draft.normalizedUrl ?: return repository.saveAutomationCommand(
            command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing download URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = System.currentTimeMillis()),
        )
        externalAddDraft.value = ExternalAddDraft(
            id = command.id,
            url = url,
            fileName = draft.fileName?.trim()?.takeIf { it.isNotBlank() }.orEmpty(),
            sourceLabel = sourceLabelFor(draft.source),
        )
        repository.saveAutomationCommand(command.copy(status = AutomationCommandStatus.Executed, resultMessage = message, updatedAtEpochMs = System.currentTimeMillis()))
        navigate(AppRoute.Add)
    }

    private suspend fun markExternalDraftDownloadCreated(draft: ExternalAddDraft, downloadId: String) {
        val command = repository.findAutomationCommand(draft.id) ?: return
        repository.saveAutomationCommand(
            command.copy(
                status = AutomationCommandStatus.Executed,
                resultMessage = "Download created from ${draft.sourceLabel}",
                downloadId = downloadId,
                rejectionReason = AutomationRejectionReason.None,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun sourceLabelFor(source: AutomationCommandSource): String = when (source) {
        AutomationCommandSource.ShareSheet -> "Shared link"
        AutomationCommandSource.ViewIntent -> "Browser handoff"
        else -> "External app"
    }

    private suspend fun executeEnqueueCommand(command: AutomationCommandRecord, draft: AutomationCommandDraft, now: Long) {
        val url = draft.normalizedUrl ?: return repository.saveAutomationCommand(
            command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing download URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = now),
        )
        val safeName = resolveFileName(url, draft.fileName.orEmpty())
        val mediaCandidate = mediaCaptureService.candidateFor(url)
        val destination = uiState.value.destinationUri.ifBlank { DestinationUris.PUBLIC_DOWNLOADS }
        val conflictPolicy = uiState.value.conflictPolicy
        val request = previewRequest(url, safeName, BackendType.Automatic, destination, conflictPolicy, allowFallback = true, isMediaRequest = mediaCandidate != null)
        val recommendation = backendSelectionPolicy.recommend(request, capabilitySnapshot.value.ifEmpty(::previewCapabilities))
        if (!recommendation.compatible) {
            repository.saveAutomationCommand(command.copy(status = AutomationCommandStatus.Rejected, resultMessage = recommendation.explanation, rejectionReason = AutomationRejectionReason.BackendUnavailable, updatedAtEpochMs = System.currentTimeMillis()))
            return
        }
        val download = Download(
            id = UUID.randomUUID().toString(),
            fileName = safeName,
            sourceUrl = url,
            destinationUri = destination,
            state = DownloadState.Queued,
            backend = recommendation.backend,
            bytesReceived = 0,
            totalBytes = null,
            speedBytesPerSecond = 0,
            queueId = "default",
            priority = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            conflictPolicy = conflictPolicy,
            mimeType = mediaCandidate?.mimeType,
            requestedBackend = BackendType.Automatic,
            backendSelectionReason = recommendation.reason,
            backendSelectionExplanation = recommendation.explanation,
            allowBackendFallback = true,
        )
        repository.save(download)
        repository.saveAutomationCommand(command.copy(status = AutomationCommandStatus.Executed, resultMessage = "Queued download", downloadId = download.id, updatedAtEpochMs = System.currentTimeMillis()))
        executionStarter.start(download.id, userVisible = true)
        navigate(AppRoute.Downloads)
    }

    fun captureSharedText(text: String, pageTitle: String? = null, pageUrl: String? = null) {
        val records = mediaCaptureService.detect(text, pageTitle, pageUrl)
        if (records.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val merged = records.map { record ->
                val existing = repository.findMediaCapture(record.id)
                if (existing?.downloadId != null) {
                    record.copy(
                        status = existing.status,
                        downloadId = existing.downloadId,
                        createdAtEpochMs = existing.createdAtEpochMs,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                } else {
                    record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs)
                }
            }
            repository.saveMediaCaptures(merged)
            repository.saveMediaVariants(merged.mapNotNull { mediaCaptureService.candidateFor(it.sourceUrl)?.variants }.flatten())
            navigate(AppRoute.Media)
        }
    }

    fun captureBrowserMediaUrl(url: String, pageTitle: String? = null, pageUrl: String? = null, mimeType: String? = null) {
        val candidate = mediaCaptureService.candidateFor(url, pageTitle, pageUrl, mimeTypeHint = mimeType) ?: return
        val record = mediaCaptureService.recordFor(candidate)
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.findMediaCapture(record.id)
            val merged = if (existing?.downloadId != null) {
                record.copy(
                    status = existing.status,
                    downloadId = existing.downloadId,
                    createdAtEpochMs = existing.createdAtEpochMs,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            } else {
                record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs)
            }
            repository.saveMediaCapture(merged)
            repository.saveMediaVariants(candidate.variants)
        }
    }

    fun openAddFromBrowser(url: String, pageTitle: String? = null) {
        val normalized = url.trim().takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) } ?: return
        externalAddDraft.value = ExternalAddDraft(
            id = "browser-${UUID.randomUUID()}",
            url = normalized,
            fileName = "",
            sourceLabel = pageTitle?.takeIf { it.isNotBlank() }?.let { "Browser: ${it.take(48)}" } ?: "Built-in browser",
        )
        navigate(AppRoute.Add)
    }

    fun downloadMediaCapture(record: MediaCaptureRecord) {
        val now = System.currentTimeMillis()
        val selectedUrl = record.selectedVariantUrl ?: record.sourceUrl
        val request = previewRequest(
            url = selectedUrl,
            fileName = record.fileName,
            backend = BackendType.Automatic,
            destination = DestinationUris.PUBLIC_DOWNLOADS,
            conflictPolicy = FilenameConflictPolicy.Rename,
            allowFallback = true,
            isMediaRequest = true,
        )
        val recommendation = backendSelectionPolicy.recommend(request, capabilitySnapshot.value.ifEmpty(::previewCapabilities))
        if (!recommendation.compatible) return
        val download = Download(
            id = UUID.randomUUID().toString(),
            fileName = sanitizeFileName(record.fileName),
            sourceUrl = selectedUrl,
            destinationUri = DestinationUris.PUBLIC_DOWNLOADS,
            state = DownloadState.Queued,
            backend = recommendation.backend,
            bytesReceived = 0,
            totalBytes = null,
            speedBytesPerSecond = 0,
            queueId = "default",
            priority = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            conflictPolicy = FilenameConflictPolicy.Rename,
            mimeType = record.mimeType,
            requestedBackend = BackendType.Automatic,
            backendSelectionReason = recommendation.reason,
            backendSelectionExplanation = recommendation.explanation,
            allowBackendFallback = true,
        )
        viewModelScope.launch(Dispatchers.IO) {
            repository.save(download)
            repository.markMediaDownloadCreated(record.id, download.id, now)
            executionStarter.start(download.id, userVisible = true)
            navigate(AppRoute.Downloads)
        }
    }

    fun resolveMediaCapture(record: MediaCaptureRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = mediaCaptureService.candidateFor(record.sourceUrl) ?: return@launch
            val variants = candidate.variants.ifEmpty {
                listOfNotNull(
                    record.selectedVariantUrl?.let { url ->
                        MediaVariant(
                            id = record.id + ":selected",
                            captureId = record.id,
                            url = url,
                            kind = MediaVariantKind.Primary,
                            mimeType = record.mimeType,
                            displayLabel = "Selected",
                        )
                    },
                )
            }
            val refreshed = mediaCaptureService.refreshRecordAfterResolution(record, variants)
            repository.saveMediaCapture(refreshed)
            repository.saveMediaVariants(variants)
        }
    }

    fun selectMediaVariant(record: MediaCaptureRecord, variantId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val variants = repository.variantsForMediaCapture(record.id)
            val selected = variants.firstOrNull { it.id == variantId } ?: return@launch
            repository.selectMediaVariant(record.id, selected)
        }
    }

    fun removeMediaCapture(record: MediaCaptureRecord) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteMediaCapture(record.id) }
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
        isMediaRequest: Boolean = false,
    ) = DownloadRequest(
        id = "preview",
        sourceUrl = url.trim(),
        destinationUri = destination,
        fileName = fileName,
        preferredBackend = backend,
        conflictPolicy = conflictPolicy,
        allowBackendFallback = allowFallback,
        isMediaRequest = isMediaRequest,
    )


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
            container.termuxBridgeManager,
            container.termuxAria2CockpitManager,
            container.termuxMediaPipelineManager,
            container.postProcessingAutomationManager,
        ) as T
    }
}
