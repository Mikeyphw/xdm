package com.mikeyphw.xdm.android

import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionBuildConfig
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParseResult
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandIds
import com.mikeyphw.xdm.android.model.AutomationCommandRecord
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.AutomationRejectionReason
import com.mikeyphw.xdm.android.model.BackupRestorePolicy
import com.mikeyphw.xdm.android.model.BackupRestoreReport
import com.mikeyphw.xdm.android.model.DownloadIntakeDraft
import com.mikeyphw.xdm.android.model.DownloadIntakeOrigin
import com.mikeyphw.xdm.android.model.DownloadIntakePlanner
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
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
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DebugWorkbenchShellPolicy
import com.mikeyphw.xdm.android.model.DebugWorkbenchShellReport
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.DestinationRule
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadActionKind
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.DuplicateUrlRule
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.media.MediaCaptureService
import com.mikeyphw.xdm.android.media.MediaCaptureIntakePlanner
import com.mikeyphw.xdm.android.media.MediaBatchIntakePlanner
import com.mikeyphw.xdm.android.media.MediaSniffingEngine
import com.mikeyphw.xdm.android.media.MediaSniffingInput
import com.mikeyphw.xdm.android.media.MediaSniffingSource
import com.mikeyphw.xdm.android.media.ExternalMediaReviewPlanner
import com.mikeyphw.xdm.android.media.MediaRequestFacts
import com.mikeyphw.xdm.android.media.MediaSessionHeader
import com.mikeyphw.xdm.android.media.MediaExecutionLibraryPlanner
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.InstallUpdateReadinessReport
import com.mikeyphw.xdm.android.model.FinalPublicReleaseGate
import com.mikeyphw.xdm.android.model.FinalReleaseGateReport
import com.mikeyphw.xdm.android.model.ReleaseInstallReadinessGate
import com.mikeyphw.xdm.android.model.ReleaseSecurityGate
import com.mikeyphw.xdm.android.model.ReleaseSecurityReport
import com.mikeyphw.xdm.android.model.SupportBundleReleaseReadinessPlanner
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.model.DesktopParityGate
import com.mikeyphw.xdm.android.model.DesktopParityReport
import com.mikeyphw.xdm.android.model.HistoryManagementPolicy
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.OrganizationPowerTools
import com.mikeyphw.xdm.android.model.OrganizationPowerToolsReport
import com.mikeyphw.xdm.android.model.OperationalActivityEvent
import com.mikeyphw.xdm.android.model.OperationalActivityPlanner
import com.mikeyphw.xdm.android.model.OperationalActivitySummary
import com.mikeyphw.xdm.android.model.OperationalDiagnosticsContext
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
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
import com.mikeyphw.xdm.android.scheduler.QueueIntelligenceCoordinator
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
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
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val SessionHeaderAllowList = setOf("authorization", "cookie", "referer", "user-agent", "origin", "accept", "range")

data class Aria2DiagnosticsUi(
    val status: String = "Checking",
    val detail: String = "Inspecting the packaged runtime and private session directory.",
    val canRunSmokeTest: Boolean = false,
    val smokeTestRunning: Boolean = false,
)

data class MainUiState(
    val route: AppRoute = AppRoute.Downloads,
    val compactDensity: Boolean = false,
    val themeMode: XdmThemeMode = XdmThemeMode.Dark,
    val developerOptionsEnabled: Boolean = false,
    val browserExtension: BrowserExtensionExportPreferences = BrowserExtensionExportPreferences(),
    val browserExtensionRuntime: BrowserExtensionRuntimeStatus = BrowserExtensionRuntimeStatus(),
    val browserBridgeStatus: BrowserBridgeIntegrationStatus = BrowserBridgeIntegrationStatus(),
    val browserBridgeDiagnostics: BrowserBridgeDiagnosticsPreferences = BrowserBridgeDiagnosticsPreferences(),
    val downloads: List<Download> = emptyList(),
    val queues: List<QueueDefinition> = emptyList(),
    val schedules: List<ScheduleRule> = emptyList(),
    val recovery: List<RecoveryRecord> = emptyList(),
    val activeTransfers: ActiveTransferSummary = ActiveTransferSummary(),
    val queueIntelligence: QueueIntelligenceSummary = QueueIntelligenceSummary(),
    val activityPanel: ActivityPanel = ActivityPanel.Attention,
    val settingsPanel: SettingsPanel = SettingsPanel.Overview,
    val activityEvents: List<OperationalActivityEvent> = emptyList(),
    val activitySummary: OperationalActivitySummary = OperationalActivitySummary(),
    val activityDiagnosticsExport: String = "",
    val supportReportText: String = "",
    val debugWorkbenchReport: DebugWorkbenchShellReport = DebugWorkbenchShellPolicy.evaluate(
        recorderInstalled = true,
        redactionReady = true,
        supportBundleReady = true,
        instrumentationHooksReady = true,
        supportReportAvailable = false,
        developerOptionsEnabled = false,
        activeDownloads = 0,
        mediaCaptures = 0,
        automationHandoffs = 0,
    ),
    val destinationUri: String = DestinationUris.PUBLIC_DOWNLOADS,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
    val externalAddDraft: DownloadIntakeDraft? = null,
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
    val mediaTrackSelections: Map<String, MediaTrackSelection> = emptyMap(),
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
    private val queueIntelligenceCoordinator: QueueIntelligenceCoordinator,
    private val destinationWriter: AndroidDestinationWriter,
    private val aria2ProcessManager: Aria2ProcessManager,
    private val termuxBridgeManager: TermuxBridgeManager,
    private val termuxAria2CockpitManager: TermuxAria2CockpitManager,
    private val termuxMediaPipelineManager: TermuxMediaPipelineManager,
    private val postProcessingAutomationManager: PostProcessingAutomationManager,
    private val mediaResolverSelectionStore: MediaResolverSelectionStore,
    private val operationalActivityStore: OperationalActivityStore,
    private val browserExtensionExportManager: BrowserExtensionExportManager,
    private val debugEventRecorder: DebugEventRecorder,
) : ViewModel() {
    private data class NavigationOverride(
        val route: AppRoute? = null,
        val activityPanel: ActivityPanel = ActivityPanel.Attention,
        val settingsPanel: SettingsPanel = SettingsPanel.Overview,
    )

    private val navigationOverride = MutableStateFlow(NavigationOverride())
    private val browserExtensionRuntime = MutableStateFlow(BrowserExtensionRuntimeStatus())
    private val browserBridgeStatus = MutableStateFlow(BrowserBridgeIntegrationStatus())
    private val preferencesAndBrowserExtension = combine(
        preferences.values,
        browserExtensionRuntime,
        browserBridgeStatus,
    ) { prefs, runtime, status -> Triple(prefs, runtime, status) }
    private val aria2Capability = MutableStateFlow<Aria2CapabilityReport?>(null)
    private val aria2SmokeMessage = MutableStateFlow<String?>(null)
    private val aria2SmokeRunning = MutableStateFlow(false)
    private val capabilitySnapshot = MutableStateFlow<Map<BackendType, BackendCapabilities>>(emptyMap())
    private val externalAddDraft = MutableStateFlow<DownloadIntakeDraft?>(null)
    private val mediaCaptureService = MediaCaptureService()
    private val mediaSniffingEngine = MediaSniffingEngine(mediaCaptureService, debugRecorder = debugEventRecorder)
    private val mediaCaptureIntakePlanner = MediaCaptureIntakePlanner(mediaCaptureService)
    private val mediaBatchIntakePlanner = MediaBatchIntakePlanner(mediaCaptureService, sniffingEngine = mediaSniffingEngine, debugRecorder = debugEventRecorder)
    private val externalMediaReviewPlanner = ExternalMediaReviewPlanner(mediaCaptureService, sniffingEngine = mediaSniffingEngine, debugRecorder = debugEventRecorder)
    private val downloadIntakePlanner = DownloadIntakePlanner(debugRecorder = debugEventRecorder)
    private val mediaExecutionPlanner = MediaExecutionLibraryPlanner()

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
        val queueIntelligence: QueueIntelligenceSummary,
        val aria2: Aria2DiagnosticsUi,
        val capabilities: List<BackendCapabilityRow>,
        val termuxBridge: TermuxBridgeStatus,
        val termuxAria2: TermuxAria2CockpitStatus,
        val termuxMediaPipeline: TermuxMediaPipelineStatus,
        val postProcessingAutomation: PostProcessingAutomationStatus,
    )

    private data class ReviewUiSnapshot(
        val externalAddDraft: DownloadIntakeDraft?,
        val mediaSelections: Map<String, MediaTrackSelection>,
        val activity: OperationalActivityStoreSnapshot,
    )

    private val reviewUi = combine(externalAddDraft, mediaResolverSelectionStore.selections, operationalActivityStore.snapshot) { draft, selections, activity ->
        ReviewUiSnapshot(draft, selections, activity)
    }

    private data class TermuxUiSnapshot(
        val bridge: TermuxBridgeStatus,
        val aria2: TermuxAria2CockpitStatus,
        val mediaPipeline: TermuxMediaPipelineStatus,
        val postProcessingAutomation: PostProcessingAutomationStatus,
    )

    private val termuxUi = combine(termuxBridgeManager.status, termuxAria2CockpitManager.status, termuxMediaPipelineManager.status, postProcessingAutomationManager.status) { bridge, aria2, mediaPipeline, postAutomation ->
        TermuxUiSnapshot(bridge, aria2, mediaPipeline, postAutomation)
    }

    private val runtimeUi = combine(transferRuntime.summary, queueIntelligenceCoordinator.status, aria2Diagnostics, capabilitySnapshot, termuxUi) { active, queueIntelligence, aria2, capabilities, termux ->
        RuntimeUiSnapshot(active, queueIntelligence, aria2, backendSelectionPolicy.capabilityRows(capabilities), termux.bridge, termux.aria2, termux.mediaPipeline, termux.postProcessingAutomation)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repositorySnapshot,
        preferencesAndBrowserExtension,
        navigationOverride,
        runtimeUi,
        reviewUi,
    ) { snapshot, preferenceSnapshot, navigation, runtime, review ->
        val prefs = preferenceSnapshot.first
        val browserExtensionRuntimeStatus = preferenceSnapshot.second
        val browserBridgeIntegrationStatus = preferenceSnapshot.third
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
        val activityEvents = OperationalActivityPlanner.timeline(
            storedEvents = review.activity.events,
            queueDecisions = runtime.queueIntelligence.recentDecisions,
            downloads = snapshot.downloads,
            recoveryRecords = snapshot.recovery,
            verificationRecords = snapshot.verificationRecords,
            finalizationJournals = snapshot.finalizationJournals,
            automationCommands = snapshot.automationCommands,
            dismissedEventIds = review.activity.dismissedEventIds,
        )
        val activitySummary = OperationalActivityPlanner.summarize(activityEvents)
        val activityDiagnosticsExport = OperationalActivityPlanner.diagnosticsExport(
            context = OperationalDiagnosticsContext(
                appVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
                versionCode = BuildConfig.VERSION_CODE,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                schemaVersion = 14,
                enabledEngines = listOf("Native", "aria2", "Termux", "yt-dlp").filter { engine ->
                    engine == "Native" || engine == "aria2" || snapshot.downloads.isNotEmpty() || snapshot.mediaCaptures.isNotEmpty()
                },
                generatedAtEpochMs = System.currentTimeMillis(),
            ),
            events = activityEvents,
            summary = activitySummary,
        )
        val releaseSecurityReport = ReleaseSecurityGate.evaluate(
            versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            schemaVersion = 14,
            buildType = BuildConfig.BUILD_TYPE,
            debuggable = BuildConfig.DEBUG,
            privacySafeDiagnostics = true,
            releaseSigningConfigured = !BuildConfig.DEBUG,
        )
        val installUpdateReadinessReport = ReleaseInstallReadinessGate.evaluate(
            versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            versionCode = BuildConfig.VERSION_CODE,
            packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug"),
            schemaVersion = 14,
            buildType = BuildConfig.BUILD_TYPE,
            releaseSafetyComplete = true,
            recoverySurfaceReady = snapshot.finalizationJournals.none { it.needsRecovery } || snapshot.recovery.isNotEmpty() || snapshot.finalizationJournals.isEmpty(),
            diagnosticsExportRedacted = true,
            aria2PayloadGateRetained = true,
            updateKeepsPackageIdentity = true,
            releaseSigningConfigured = !BuildConfig.DEBUG,
        )
        val finalReleaseGateReport = FinalPublicReleaseGate.evaluate(
            versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            versionCode = BuildConfig.VERSION_CODE,
            packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug"),
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
        )
        val supportBundleSeal = SupportBundleReleaseReadinessPlanner.evaluate(
            operationalDiagnosticsIncluded = activityDiagnosticsExport.isNotBlank(),
            releaseSecurityIncluded = true,
            installUpdateReadinessIncluded = true,
            finalReleaseWarningsExplained = true,
            realDeviceSmokeStatusIncluded = true,
            redactedReportsOnly = true,
            rawUrlsExcluded = true,
            rawHeadersExcluded = true,
            sessionValuesPersisted = false,
            copyReportAvailable = true,
        )
        val supportReportText = buildString {
            appendLine(activityDiagnosticsExport.trim())
            appendLine()
            appendLine(PrivacyDiagnosticsRedactor.redactedHealthSummary(
                report = releaseSecurityReport,
                downloadCount = snapshot.downloads.size,
                mediaCaptureCount = snapshot.mediaCaptures.size,
                automationCount = snapshot.automationCommands.size,
                rejectedHandoffCount = snapshot.automationCommands.count { it.status == AutomationCommandStatus.Rejected },
            ))
            appendLine()
            appendLine(installUpdateReadinessReport.redactedSummary())
            appendLine()
            appendLine(finalReleaseGateReport.redactedExplanationSummary())
            appendLine()
            appendLine(supportBundleSeal.redactedSummary())
        }
        MainUiState(
            route = navigation.route ?: prefs.lastRoute,
            compactDensity = prefs.compactDensity,
            themeMode = prefs.themeMode,
            developerOptionsEnabled = prefs.developerOptionsEnabled,
            browserExtension = prefs.browserExtension,
            browserExtensionRuntime = browserExtensionRuntimeStatus,
            browserBridgeStatus = browserBridgeIntegrationStatus,
            browserBridgeDiagnostics = prefs.browserBridgeDiagnostics,
            downloads = snapshot.downloads,
            queues = snapshot.queues,
            schedules = snapshot.schedules,
            recovery = snapshot.recovery,
            activeTransfers = runtime.activeTransfers,
            queueIntelligence = runtime.queueIntelligence,
            activityPanel = navigation.activityPanel.normalized(prefs.developerOptionsEnabled),
            settingsPanel = navigation.settingsPanel,
            activityEvents = activityEvents,
            activitySummary = activitySummary,
            activityDiagnosticsExport = activityDiagnosticsExport,
            supportReportText = supportReportText,
            debugWorkbenchReport = DebugWorkbenchShellPolicy.evaluate(
                recorderInstalled = true,
                redactionReady = true,
                supportBundleReady = true,
                instrumentationHooksReady = true,
                supportReportAvailable = supportReportText.isNotBlank(),
                developerOptionsEnabled = prefs.developerOptionsEnabled,
                activeDownloads = snapshot.downloads.count { it.state == DownloadState.Downloading },
                mediaCaptures = snapshot.mediaCaptures.size,
                automationHandoffs = snapshot.automationCommands.size,
            ),
            destinationUri = prefs.destinationUri,
            conflictPolicy = prefs.conflictPolicy,
            externalAddDraft = review.externalAddDraft,
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
            mediaTrackSelections = review.mediaSelections,
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
                versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
                versionCode = BuildConfig.VERSION_CODE,
                packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug"),
            ),
            desktopParityReport = DesktopParityGate.evaluate(
                settingsImportExport = true,
                historyManagement = true,
                proxyCredentials = true,
                conversionPostProcessing = true,
                protocolExpansion = true,
                releasePackaging = true,
            ),
            releaseSecurityReport = releaseSecurityReport,
            installUpdateReadinessReport = installUpdateReadinessReport,
            finalReleaseGateReport = finalReleaseGateReport,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            if (repository.countQueues() == 0) FakeDataSeeder(repository).seedQueuesOnly()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.downloads.collectLatest { downloads -> operationalActivityStore.observeDownloads(downloads) }
        }
        refreshAria2Probe()
        refreshBackendCapabilities()
        termuxBridgeManager.refreshStatus()
        termuxMediaPipelineManager.refreshStatus()
        postProcessingAutomationManager.refreshStatus()
        viewModelScope.launch(Dispatchers.IO) {
            preferences.values.collectLatest(::refreshBrowserBridgeStatus)
        }
    }

    fun navigate(route: AppRoute) {
        navigationOverride.value = navigationOverride.value.copy(route = route)
        viewModelScope.launch { preferences.setRoute(route) }
    }

    fun navigateActivity(panel: ActivityPanel) {
        navigationOverride.value = NavigationOverride(route = AppRoute.Activity, activityPanel = panel.normalized(uiState.value.developerOptionsEnabled))
        viewModelScope.launch { preferences.setRoute(AppRoute.Activity) }
    }

    fun selectActivityPanel(panel: ActivityPanel) {
        navigationOverride.value = navigationOverride.value.copy(activityPanel = panel.normalized(uiState.value.developerOptionsEnabled))
    }

    fun selectSettingsPanel(panel: SettingsPanel) {
        navigationOverride.value = navigationOverride.value.copy(route = AppRoute.Settings, settingsPanel = panel)
        viewModelScope.launch {
            preferences.setRoute(AppRoute.Settings)
            if (panel == SettingsPanel.BrowserExtension) refreshBrowserBridgeStatusFromCurrent()
        }
    }

    fun openDeveloperTools() {
        navigationOverride.value = NavigationOverride(
            route = AppRoute.Settings,
            activityPanel = ActivityPanel.Attention,
            settingsPanel = SettingsPanel.DeveloperTools,
        )
        viewModelScope.launch { preferences.setRoute(AppRoute.Settings) }
    }

    fun dismissActivityEvent(eventId: String) {
        operationalActivityStore.dismiss(eventId)
    }

    fun clearActivityHistory() {
        operationalActivityStore.clearHistory(preserveUnresolved = true)
        queueIntelligenceCoordinator.clearDecisionHistory()
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
        viewModelScope.launch(Dispatchers.IO) { repository.saveQueue(queue); queueIntelligenceCoordinator.reconcile() }
    }

    fun updateQueue(queue: QueueDefinition, name: String, maxConcurrent: Int, enabled: Boolean) {
        val updated = queue.copy(
            name = name.trim().ifBlank { queue.name }.take(48),
            maxConcurrent = maxConcurrent.coerceIn(1, 16),
            isEnabled = enabled,
        )
        viewModelScope.launch(Dispatchers.IO) { repository.saveQueue(updated); queueIntelligenceCoordinator.reconcile() }
    }

    fun setQueueEnabled(queue: QueueDefinition, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.saveQueue(queue.copy(isEnabled = enabled)); queueIntelligenceCoordinator.reconcile() }
    }

    fun deleteQueue(queue: QueueDefinition) {
        if (queue.id == "default") return
        viewModelScope.launch(Dispatchers.IO) { repository.deleteQueue(queue.id); queueIntelligenceCoordinator.reconcile() }
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
        viewModelScope.launch(Dispatchers.IO) { repository.saveSchedule(rule); queueIntelligenceCoordinator.reconcile() }
    }

    fun updateSchedule(rule: ScheduleRule, name: String, queueId: String?, enabled: Boolean, constraintsJson: String) {
        val updated = rule.copy(
            name = name.trim().ifBlank { rule.name }.take(48),
            queueId = queueId,
            enabled = enabled,
            constraintsJson = constraintsJson.ifBlank { "{}" },
        )
        viewModelScope.launch(Dispatchers.IO) { repository.saveSchedule(updated); queueIntelligenceCoordinator.reconcile() }
    }

    fun setScheduleEnabled(rule: ScheduleRule, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.saveSchedule(rule.copy(enabled = enabled)); queueIntelligenceCoordinator.reconcile() }
    }

    fun deleteSchedule(rule: ScheduleRule) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteSchedule(rule.id); queueIntelligenceCoordinator.reconcile() }
    }


    fun runQueueIntelligenceNow() {
        viewModelScope.launch(Dispatchers.IO) { queueIntelligenceCoordinator.reconcile() }
    }

    fun startIgnoringQueuePolicy(download: Download) {
        viewModelScope.launch(Dispatchers.IO) {
            queueIntelligenceCoordinator.requestStart(
                downloadId = download.id,
                userVisible = true,
                manual = true,
                policyOverride = true,
            )
        }
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

    fun extractMediaMetadataWithTermux(record: MediaCaptureRecord, selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = record.selectedVariantId)) {
        viewModelScope.launch(Dispatchers.IO) {
            val variants = repository.variantsForMediaCapture(record.id)
            termuxMediaPipelineManager.extractMetadata(record, variants, selection)
        }
    }

    fun inspectMediaWithTermuxFfprobe(record: MediaCaptureRecord) {
        termuxMediaPipelineManager.inspectWithFfprobe(record)
    }

    fun downloadMediaWithTermuxYtDlp(record: MediaCaptureRecord, selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = record.selectedVariantId)) {
        viewModelScope.launch(Dispatchers.IO) {
            val variants = repository.variantsForMediaCapture(record.id)
            termuxMediaPipelineManager.downloadWithYtDlp(record, variants, selection)
        }
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


    fun registerBrowserExtensionExportDirectory(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = browserExtensionExportManager.persistDirectoryPermission(uri)
            if (result.isSuccess) {
                preferences.setBrowserExtensionExportTreeUri(uri)
                preferences.recordBrowserBridgeGeneration("idle", "Export folder permission retained. Generate the XPI when ready.", System.currentTimeMillis())
                browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Idle,
                    message = "Export folder saved. Generate the XPI when ready.",
                )
            } else {
                val message = BrowserBridgeDiagnosticsRedactor.sanitize(
                    result.exceptionOrNull()?.message ?: "The export folder could not be saved.",
                )
                preferences.recordBrowserBridgeGeneration("failed", message, System.currentTimeMillis())
                browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Failed,
                    message = message,
                )
            }
        }
    }

    fun setBrowserExtensionDefaultTarget(target: BrowserExtensionSourceContract.Target) {
        viewModelScope.launch { preferences.setBrowserExtensionDefaultTarget(target) }
    }

    fun setBrowserExtensionTheme(theme: BrowserExtensionSourceContract.ThemeSelection) {
        viewModelScope.launch { preferences.setBrowserExtensionRequestedTheme(theme) }
    }

    fun generateBrowserExtensionXpi() {
        if (browserExtensionRuntime.value.phase == BrowserExtensionExportPhase.Exporting) return
        val current = uiState.value.browserExtension
        if (current.exportTreeUri.isBlank()) {
            browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                phase = BrowserExtensionExportPhase.Failed,
                message = "Choose an export folder before generating the XPI.",
            )
            return
        }
        val channel = when (BuildConfig.BUILD_TYPE.lowercase()) {
            "debug" -> BrowserExtensionSourceContract.Channel.Debug
            else -> BrowserExtensionSourceContract.Channel.Release
        }
        val resolvedTheme = current.resolvedTheme(uiState.value.themeMode)
        val config = BrowserExtensionBuildConfig(
            extensionVersion = BrowserExtensionSourceContract.DevelopmentVersion,
            appVersion = BuildConfig.VERSION_NAME,
            applicationId = BuildConfig.APPLICATION_ID,
            channel = channel,
            xdmScheme = BuildConfig.XDM_BROWSER_SCHEME,
            defaultTarget = current.defaultTarget,
            themeMode = resolvedTheme,
        )
        browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
            phase = BrowserExtensionExportPhase.Exporting,
            message = "Generating and validating the Firefox XPI…",
        )
        viewModelScope.launch(Dispatchers.IO) {
            preferences.recordBrowserBridgeGeneration(
                phase = "exporting",
                message = "Generating and validating the Firefox XPI.",
                epochMs = System.currentTimeMillis(),
            )
            val result = browserExtensionExportManager.export(current.exportTreeUri, config)
            result.onSuccess { exported ->
                val now = System.currentTimeMillis()
                preferences.recordBrowserExtensionExport(
                    theme = resolvedTheme,
                    target = current.defaultTarget,
                    appVersion = BuildConfig.VERSION_NAME,
                    applicationId = BuildConfig.APPLICATION_ID,
                    scheme = BuildConfig.XDM_BROWSER_SCHEME,
                    extensionVersion = BrowserExtensionSourceContract.DevelopmentVersion,
                    contractVersion = BrowserExtensionSourceContract.ContractVersion,
                    sha256 = exported.sha256,
                    epochMs = now,
                    fileName = exported.fileName,
                    byteCount = exported.byteCount,
                    documentUri = exported.uri,
                )
                preferences.recordBrowserBridgeGeneration(
                    phase = "succeeded",
                    message = "Exported ${exported.fileName} and verified its checksum.",
                    epochMs = now,
                )
                browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Succeeded,
                    message = "Exported ${exported.fileName} and verified its checksum.",
                    exportedUri = exported.uri,
                )
            }.onFailure { failure ->
                val message = BrowserBridgeDiagnosticsRedactor.sanitize(
                    failure.message ?: "The Firefox XPI export failed safely.",
                )
                preferences.recordBrowserBridgeGeneration(
                    phase = "failed",
                    message = message,
                    epochMs = System.currentTimeMillis(),
                )
                browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Failed,
                    message = message,
                )
            }
        }
    }

    fun recordBrowserDeepLinkResult(result: XdmBrowserDeepLinkParseResult) {
        val now = System.currentTimeMillis()
        when (result) {
            XdmBrowserDeepLinkParseResult.NotApplicable -> Unit
            is XdmBrowserDeepLinkParseResult.Accepted -> viewModelScope.launch(Dispatchers.IO) {
                preferences.recordBrowserBridgeAccepted(
                    BrowserBridgeDiagnosticsRedactor.acceptedSummary(result.payload),
                    now,
                )
            }
            is XdmBrowserDeepLinkParseResult.Rejected -> viewModelScope.launch(Dispatchers.IO) {
                preferences.recordBrowserBridgeRejected(
                    result.reason.code,
                    BrowserBridgeDiagnosticsRedactor.rejectedSummary(result.reason),
                    now,
                )
            }
        }
    }

    fun refreshBrowserExtensionStatus() {
        viewModelScope.launch(Dispatchers.IO) { refreshBrowserBridgeStatusFromCurrent() }
    }

    fun openBrowserExtensionXpi() {
        val uri = uiState.value.browserBridgeStatus.currentExportUri
        if (uri.isBlank()) {
            browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                phase = BrowserExtensionExportPhase.Failed,
                message = "The last verified XPI is unavailable. Regenerate it.",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = browserExtensionExportManager.openExportedFile(uri)
            browserExtensionRuntime.value = if (result.isSuccess) {
                BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Succeeded,
                    message = "Opened the verified XPI with an installed file handler.",
                    exportedUri = uri,
                )
            } else {
                BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Failed,
                    message = BrowserBridgeDiagnosticsRedactor.sanitize(
                        result.exceptionOrNull()?.message ?: "No installed app can open the exported XPI.",
                    ),
                )
            }
        }
    }

    fun clearBrowserExtensionExportFolder() {
        val currentTree = uiState.value.browserExtension.exportTreeUri
        viewModelScope.launch(Dispatchers.IO) {
            if (currentTree.isNotBlank()) browserExtensionExportManager.releaseDirectoryPermission(currentTree)
            preferences.setBrowserExtensionExportTreeUri("")
            preferences.recordBrowserBridgeGeneration(
                phase = "idle",
                message = "Export folder cleared. Choose a folder before generating again.",
                epochMs = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun refreshBrowserBridgeStatusFromCurrent() {
        val snapshot = uiState.value
        browserBridgeStatus.value = browserExtensionExportManager.inspect(
            preferences = snapshot.browserExtension,
            diagnostics = snapshot.browserBridgeDiagnostics,
            appTheme = snapshot.themeMode,
            appVersion = BuildConfig.VERSION_NAME,
            applicationId = BuildConfig.APPLICATION_ID,
            scheme = BuildConfig.XDM_BROWSER_SCHEME,
        )
    }

    private fun refreshBrowserBridgeStatus(preferenceSnapshot: UserPreferences) {
        browserBridgeStatus.value = browserExtensionExportManager.inspect(
            preferences = preferenceSnapshot.browserExtension,
            diagnostics = preferenceSnapshot.browserBridgeDiagnostics,
            appTheme = preferenceSnapshot.themeMode,
            appVersion = BuildConfig.VERSION_NAME,
            applicationId = BuildConfig.APPLICATION_ID,
            scheme = BuildConfig.XDM_BROWSER_SCHEME,
        )
    }

    fun setCompactDensity(compact: Boolean) {
        viewModelScope.launch { preferences.setCompactDensity(compact) }
    }

    fun setThemeMode(mode: XdmThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setDeveloperOptionsEnabled(enabled: Boolean) {
        if (!enabled && navigationOverride.value.settingsPanel == SettingsPanel.DeveloperTools) {
            navigationOverride.value = navigationOverride.value.copy(settingsPanel = SettingsPanel.Overview)
        }
        viewModelScope.launch { preferences.setDeveloperOptionsEnabled(enabled) }
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
            candidates.forEach { queueIntelligenceCoordinator.requestStart(it.id, userVisible = true, manual = true) }
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
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.findDownload(download.id) ?: download
            if (current.state != DownloadState.Completed) {
                runCatching { transferRuntime.cancel(current.id) }
            }
            removeDownloadRecord(current.id)
        }
    }

    fun cancelDownload(download: Download) {
        if (download.state == DownloadState.Completed) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { transferRuntime.cancel(download.id) }
            val current = repository.findDownload(download.id) ?: download
            if (current.state != DownloadState.Completed) {
                repository.save(
                    current.copy(
                        state = DownloadState.Cancelled,
                        speedBytesPerSecond = 0,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun removeDownloadRecord(downloadId: String) {
        repository.deleteBackendTask(downloadId)
        repository.deleteRecoveryForDownload(downloadId)
        repository.deleteFinalizationForDownload(downloadId)
        repository.deleteDownload(downloadId)
    }

    fun redownload(download: Download) {
        if (download.sourceUrl.isBlank()) return
        val now = System.currentTimeMillis()
        val safeName = resolveFileName(download.sourceUrl, download.fileName)
        val destination = uiState.value.destinationUri.ifBlank { DestinationUris.PUBLIC_DOWNLOADS }
        val mediaCandidate = mediaCaptureService.candidateFor(download.sourceUrl)
        val resolvedDestination = OrganizationPowerTools.destinationFor(download.sourceUrl, safeName, mediaCandidate?.mimeType, uiState.value.destinationRules, destination)
        val request = previewRequest(download.sourceUrl, safeName, BackendType.Automatic, resolvedDestination, FilenameConflictPolicy.Rename, allowFallback = true, isMediaRequest = mediaCandidate != null)
        val recommendation = backendSelectionPolicy.recommend(request, capabilitySnapshot.value.ifEmpty(::previewCapabilities))
        if (!recommendation.compatible) return
        val retry = Download(
            id = UUID.randomUUID().toString(),
            fileName = safeName,
            sourceUrl = download.sourceUrl.trim(),
            destinationUri = resolvedDestination,
            state = DownloadState.Queued,
            backend = recommendation.backend,
            bytesReceived = 0,
            totalBytes = null,
            speedBytesPerSecond = 0,
            queueId = download.queueId ?: "default",
            priority = download.priority,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            conflictPolicy = FilenameConflictPolicy.Rename,
            mimeType = mediaCandidate?.mimeType ?: download.mimeType,
            requestedBackend = BackendType.Automatic,
            backendSelectionReason = recommendation.reason,
            backendSelectionExplanation = recommendation.explanation,
            allowBackendFallback = true,
        )
        viewModelScope.launch {
            repository.save(retry)
            queueIntelligenceCoordinator.requestStart(retry.id, userVisible = true, manual = true)
            navigate(AppRoute.Downloads)
        }
    }

    fun moveDownloadInQueue(download: Download, kind: DownloadActionKind) {
        val queueId = download.queueId ?: "default"
        val movableStates = setOf(DownloadState.Created, DownloadState.Queued, DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)
        val current = uiState.value.downloads
            .filter { (it.queueId ?: "default") == queueId && it.state in movableStates }
            .sortedWith(compareByDescending<Download> { it.priority }.thenBy { it.createdAtEpochMs })
        val from = current.indexOfFirst { it.id == download.id }
        if (from < 0) return
        val to = when (kind) {
            DownloadActionKind.MoveToTop -> 0
            DownloadActionKind.MoveUp -> (from - 1).coerceAtLeast(0)
            DownloadActionKind.MoveDown -> (from + 1).coerceAtMost(current.lastIndex)
            DownloadActionKind.MoveToBottom -> current.lastIndex
            else -> from
        }
        if (from == to) return
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        val now = System.currentTimeMillis()
        val reprioritized = reordered.mapIndexed { index, item ->
            item.copy(priority = (reordered.size - index) * 10, updatedAtEpochMs = now)
        }
        viewModelScope.launch(Dispatchers.IO) { repository.saveAll(reprioritized) }
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
        val externalSessionHeaders = consumedExternalDraft?.requestHeaders.orEmpty()
        val mediaCandidate = mediaCaptureService.candidateFor(url)
        val resolvedDestination = OrganizationPowerTools.destinationFor(url, safeName, mediaCandidate?.mimeType, uiState.value.destinationRules, destination)
        val request = previewRequest(
            url,
            safeName,
            backend,
            resolvedDestination,
            conflictPolicy,
            allowFallback,
            isMediaRequest = mediaCandidate != null || externalSessionHeaders.isNotEmpty(),
            headers = externalSessionHeaders,
            isExpiringUrl = externalSessionHeaders.isNotEmpty(),
        )
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
            if (externalSessionHeaders.isNotEmpty()) {
                MediaRequestHandoffStore.remember(
                    downloadId = download.id,
                    headers = externalSessionHeaders,
                    redactedSummary = consumedExternalDraft?.redactedHeaderSummary.orEmpty(),
                    isExpiringUrl = true,
                )
            }
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
            queueIntelligenceCoordinator.requestStart(download.id, userVisible = true, manual = true)
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
            queueIntelligenceCoordinator.requestStart(downloadId, userVisible = true, manual = true)
        }
    }

    fun validateAllRecoveryRecords(records: List<RecoveryRecord>) {
        viewModelScope.launch(Dispatchers.IO) {
            records.forEach { record ->
                val downloadId = record.downloadId ?: return@forEach
                val current = repository.findDownload(downloadId) ?: return@forEach
                repository.save(
                    current.copy(
                        state = DownloadState.Queued,
                        errorMessage = null,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                queueIntelligenceCoordinator.requestStart(downloadId, userVisible = true, manual = true)
            }
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
                externalAddDraft.value = downloadIntakePlanner.fromExternal(
                    id = existing.id,
                    url = url,
                    fileName = existing.fileName,
                    sourceLabel = sourceLabelFor(existing.source, existing.originPackage),
                    origin = intakeOriginFor(existing.source),
                    pageTitle = existing.pageTitle,
                    pageUrl = existing.pageUrl,
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
            AutomationCommandAction.PromptAddDownload -> openExternalAddDraft(accepted, draft, "External download opened Add Download prompt")
            AutomationCommandAction.EnqueueDownload -> executeEnqueueCommand(accepted, draft, now)
            AutomationCommandAction.PauseAll -> {
                transferRuntime.pauseAll()
                repository.saveAutomationCommand(accepted.copy(status = AutomationCommandStatus.Executed, resultMessage = "Pause all requested", updatedAtEpochMs = System.currentTimeMillis()))
            }
            AutomationCommandAction.ResumeAll -> {
                val paused = repository.findDownloadsByStates(setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower))
                paused.forEach { queueIntelligenceCoordinator.requestStart(it.id, userVisible = true, manual = true) }
                repository.saveAutomationCommand(accepted.copy(status = AutomationCommandStatus.Executed, resultMessage = "Resume requested for ${paused.size} download(s)", updatedAtEpochMs = System.currentTimeMillis()))
            }
            AutomationCommandAction.Unknown -> repository.saveAutomationCommand(accepted.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Unsupported automation action", rejectionReason = AutomationRejectionReason.UnsupportedAction, updatedAtEpochMs = System.currentTimeMillis()))
        }
    }

    private suspend fun executeCaptureMediaCommand(command: AutomationCommandRecord, draft: AutomationCommandDraft, now: Long) {
        val text = draft.normalizedUrl ?: return repository.saveAutomationCommand(
            command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing media URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = now),
        )
        val requestHeaders = transientSessionHeaders(draft.rawHeaders, draft.pageUrl)
        val sniffingPlan = mediaSniffingEngine.sniff(
            MediaSniffingInput(
                url = text,
                mimeType = draft.mimeType,
                contentLength = draft.contentLength,
                pageUrl = draft.pageUrl,
                pageTitle = draft.pageTitle,
                requestHeaders = requestHeaders,
                source = MediaSniffingSource.BrowserExtension,
            ),
        )
        if (sniffingPlan.records.isEmpty()) {
            openExternalAddDraft(command, draft, "No media stream was detected; opened Add Download instead")
            return
        }
        val merged = sniffingPlan.records.map { record ->
            val existing = repository.findMediaCapture(record.id)
            if (existing?.downloadId != null) {
                record.copy(status = existing.status, downloadId = existing.downloadId, createdAtEpochMs = existing.createdAtEpochMs, updatedAtEpochMs = System.currentTimeMillis())
            } else {
                record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs)
            }
        }
        repository.saveMediaCaptures(merged)
        if (requestHeaders.isNotEmpty()) {
            val redactedSummary = redactedSessionSummary(draft.rawHeaders, draft.pageUrl)
            merged.forEach { record ->
                MediaRequestHandoffStore.rememberCapture(
                    captureId = record.id,
                    headers = requestHeaders,
                    redactedSummary = redactedSummary,
                    isExpiringUrl = true,
                )
            }
        }
        repository.saveMediaVariants(sniffingPlan.variants)
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
        val requestHeaders = transientSessionHeaders(draft.rawHeaders, draft.pageUrl)
        externalAddDraft.value = downloadIntakePlanner.fromExternal(
            id = command.id,
            url = url,
            fileName = draft.fileName,
            sourceLabel = sourceLabelFor(draft.source, draft.originPackage),
            origin = intakeOriginFor(draft.source),
            pageTitle = draft.pageTitle,
            pageUrl = draft.pageUrl,
            mimeType = draft.mimeType,
            contentLength = draft.contentLength,
            requestHeaders = requestHeaders,
            redactedHeaderSummary = redactedSessionSummary(draft.rawHeaders, draft.pageUrl),
        ) ?: return repository.saveAutomationCommand(
            command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Unsupported download URL", rejectionReason = AutomationRejectionReason.UnsupportedUrl, updatedAtEpochMs = System.currentTimeMillis()),
        )
        repository.saveAutomationCommand(command.copy(status = AutomationCommandStatus.Executed, resultMessage = message, updatedAtEpochMs = System.currentTimeMillis()))
        navigate(AppRoute.Add)
    }

    private suspend fun markExternalDraftDownloadCreated(draft: DownloadIntakeDraft, downloadId: String) {
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

    private fun sourceLabelFor(source: AutomationCommandSource, originPackage: String? = null): String {
        val safePackage = originPackage
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 96 }
        return when (source) {
            AutomationCommandSource.ShareSheet -> safePackage?.let { "Shared from $it" } ?: "Shared link"
            AutomationCommandSource.ViewIntent, AutomationCommandSource.BrowserExtension -> safePackage?.let { "Download from $it" } ?: "External browser"
            AutomationCommandSource.Tasker -> "Tasker"
            AutomationCommandSource.DeepLink -> "XDM link"
            AutomationCommandSource.Internal -> "External app"
        }
    }

    private fun intakeOriginFor(source: AutomationCommandSource): DownloadIntakeOrigin = when (source) {
        AutomationCommandSource.ShareSheet -> DownloadIntakeOrigin.ExternalShare
        AutomationCommandSource.ViewIntent -> DownloadIntakeOrigin.ExternalView
        AutomationCommandSource.BrowserExtension -> DownloadIntakeOrigin.BrowserExtension
        AutomationCommandSource.Tasker, AutomationCommandSource.DeepLink, AutomationCommandSource.Internal -> DownloadIntakeOrigin.Automation
    }

    private fun transientSessionHeaders(rawHeaders: String?, pageUrl: String? = null): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        rawHeaders
            ?.lineSequence()
            ?.mapNotNull(::parseHeaderLine)
            ?.forEach { (name, value) -> headers[canonicalHeaderName(name)] = value }
        val referer = ExternalUrlPolicy.normalizedUrl(pageUrl)
        if (referer != null && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = referer
        }
        return headers
    }

    private fun redactedSessionSummary(rawHeaders: String?, pageUrl: String? = null): String = buildString {
        val referer = ExternalUrlPolicy.normalizedUrl(pageUrl)
        append("referer=").append(referer?.let { PrivacyDiagnosticsRedactor.redactUrl(it).orEmpty() } ?: "none")
        val redactedHeaders = PrivacyDiagnosticsRedactor.redactHeaders(rawHeaders).orEmpty()
        if (redactedHeaders.isNotBlank()) append("; headers=").append(redactedHeaders.replace("\n", "; ").take(420))
    }.take(500)

    private fun parseHeaderLine(line: String): Pair<String, String>? {
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        if (name.lowercase(Locale.US) !in SessionHeaderAllowList) return null
        if (value.isBlank() || name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) return null
        return name to value.take(8192)
    }

    private fun canonicalHeaderName(name: String): String = when (name.lowercase(Locale.US)) {
        "cookie" -> "Cookie"
        "authorization" -> "Authorization"
        "referer" -> "Referer"
        "user-agent" -> "User-Agent"
        "origin" -> "Origin"
        "accept" -> "Accept"
        "range" -> "Range"
        else -> name.trim()
    }

    private suspend fun executeEnqueueCommand(command: AutomationCommandRecord, draft: AutomationCommandDraft, now: Long) {
        val url = draft.normalizedUrl ?: return repository.saveAutomationCommand(
            command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing download URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = now),
        )
        val safeName = resolveFileName(url, draft.fileName.orEmpty())
        val sessionHeaders = transientSessionHeaders(draft.rawHeaders, draft.pageUrl)
        val mediaCandidate = mediaCaptureService.candidateFor(url)
        val destination = uiState.value.destinationUri.ifBlank { DestinationUris.PUBLIC_DOWNLOADS }
        val conflictPolicy = uiState.value.conflictPolicy
        val request = previewRequest(
            url,
            safeName,
            BackendType.Automatic,
            destination,
            conflictPolicy,
            allowFallback = true,
            isMediaRequest = mediaCandidate != null || sessionHeaders.isNotEmpty(),
            headers = sessionHeaders,
            isExpiringUrl = sessionHeaders.isNotEmpty(),
        )
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
        if (sessionHeaders.isNotEmpty()) {
            MediaRequestHandoffStore.remember(
                downloadId = download.id,
                headers = sessionHeaders,
                redactedSummary = redactedSessionSummary(draft.rawHeaders, draft.pageUrl),
                isExpiringUrl = true,
            )
        }
        repository.saveAutomationCommand(command.copy(status = AutomationCommandStatus.Executed, resultMessage = "Queued download", downloadId = download.id, updatedAtEpochMs = System.currentTimeMillis()))
        queueIntelligenceCoordinator.requestStart(download.id, userVisible = true, manual = true)
        navigate(AppRoute.Downloads)
    }

    fun captureSharedText(text: String, pageTitle: String? = null, pageUrl: String? = null) {
        val sniffingPlan = mediaSniffingEngine.sniff(
            MediaSniffingInput(
                url = pageUrl,
                bodyPrefix = text,
                pageUrl = pageUrl,
                pageTitle = pageTitle,
                source = MediaSniffingSource.SharedText,
            ),
        )
        if (sniffingPlan.records.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val merged = sniffingPlan.records.map { record ->
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
            repository.saveMediaVariants(sniffingPlan.variants)
            navigate(AppRoute.Media)
        }
    }

    fun captureMediaRequest(facts: MediaRequestFacts) {
        val intake = mediaCaptureIntakePlanner.plan(facts) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.findMediaCapture(intake.record.id)
            val merged = if (existing?.downloadId != null) {
                intake.record.copy(
                    status = existing.status,
                    downloadId = existing.downloadId,
                    createdAtEpochMs = existing.createdAtEpochMs,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            } else {
                intake.record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: intake.record.createdAtEpochMs)
            }
            repository.saveMediaCapture(merged)
            repository.saveMediaVariants(intake.candidate.variants)
        }
    }

    fun captureMediaBatchInput(text: String) {
        val plan = mediaBatchIntakePlanner.plan(text)
        if (plan.parse.acceptedCount == 0 && plan.parse.invalidCount == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            if (plan.records.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val merged = plan.records.map { record ->
                    val existing = repository.findMediaCapture(record.id)
                    if (existing?.downloadId != null) {
                        record.copy(
                            status = existing.status,
                            downloadId = existing.downloadId,
                            createdAtEpochMs = existing.createdAtEpochMs,
                            updatedAtEpochMs = now,
                        )
                    } else {
                        record.copy(
                            createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs,
                            updatedAtEpochMs = now,
                        )
                    }
                }
                repository.saveMediaCaptures(merged)
                if (plan.variants.isNotEmpty()) repository.saveMediaVariants(plan.variants)
            }
            navigate(AppRoute.Media)
        }
    }

    fun openDownloadReview(draft: DownloadIntakeDraft) {
        externalAddDraft.value = draft
        navigate(AppRoute.Add)
    }

    fun inspectManualMedia(url: String, fileName: String) {
        val draft = downloadIntakePlanner.fromManual(url = url, fileName = fileName) ?: return
        inspectExternalMedia(draft)
    }

    fun inspectExternalMedia(draft: DownloadIntakeDraft) {
        val intake = externalMediaReviewPlanner.plan(draft) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.findMediaCapture(intake.record.id)
            val merged = if (existing?.downloadId != null) {
                intake.record.copy(
                    status = existing.status,
                    downloadId = existing.downloadId,
                    createdAtEpochMs = existing.createdAtEpochMs,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            } else {
                intake.record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: intake.record.createdAtEpochMs)
            }
            repository.saveMediaCapture(merged)
            if (draft.requestHeaders.isNotEmpty()) {
                MediaRequestHandoffStore.rememberCapture(
                    captureId = merged.id,
                    headers = draft.requestHeaders,
                    redactedSummary = draft.redactedHeaderSummary.orEmpty(),
                    isExpiringUrl = true,
                )
            }
            if (intake.variants.isNotEmpty()) repository.saveMediaVariants(intake.variants)
            repository.findAutomationCommand(draft.id)?.let { command ->
                repository.saveAutomationCommand(
                    command.copy(
                        status = AutomationCommandStatus.Executed,
                        resultMessage = if (intake.isPageProbe) "External page opened in media resolver" else "External media opened in media resolver",
                        mediaCaptureId = merged.id,
                        rejectionReason = AutomationRejectionReason.None,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            externalAddDraft.value = null
            navigate(AppRoute.Media)
        }
    }

    fun downloadMediaCapture(record: MediaCaptureRecord, selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = record.selectedVariantId)) {
        mediaResolverSelectionStore.save(record.id, selection)
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val variants = repository.variantsForMediaCapture(record.id)
            val captureHandoff = MediaRequestHandoffStore.forCapture(record.id)
            val spec = mediaExecutionPlanner.queueSpec(
                capture = record,
                variants = variants,
                selection = selection,
                destinationUri = DestinationUris.PUBLIC_DOWNLOADS,
                sessionHeaders = captureHandoff?.headers.orEmpty().map { (name, value) -> MediaSessionHeader(name, value) },
            )
            val enginePlan = mediaExecutionPlanner.enginePlan(spec, androidSdkInt = android.os.Build.VERSION.SDK_INT)
            if (spec.requiresTermuxYtDlp) {
                val job = termuxMediaPipelineManager.downloadWithYtDlp(record, variants, selection)
                repository.markMediaDownloadCreated(record.id, job.id, now)
                navigate(AppRoute.Media)
                return@launch
            }
            if (!spec.canUseAppQueue) {
                repository.saveMediaCapture(record.copy(resolutionStatus = com.mikeyphw.xdm.android.model.MediaResolutionStatus.Failed, updatedAtEpochMs = now))
                navigate(AppRoute.Media)
                return@launch
            }
            val request = previewRequest(
                url = spec.sourceUrl,
                fileName = spec.fileName,
                backend = spec.requestedBackend,
                destination = DestinationUris.PUBLIC_DOWNLOADS,
                conflictPolicy = FilenameConflictPolicy.Rename,
                allowFallback = true,
                isMediaRequest = true,
                headers = spec.requestHeaders,
                mimeType = record.mimeType,
                isExpiringUrl = spec.isExpiringUrl,
            )
            val recommendation = backendSelectionPolicy.recommend(request, capabilitySnapshot.value.ifEmpty(::previewCapabilities))
            if (!recommendation.compatible) return@launch
            val download = Download(
                id = UUID.randomUUID().toString(),
                fileName = sanitizeFileName(spec.fileName),
                sourceUrl = spec.sourceUrl,
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
                requestedBackend = spec.requestedBackend,
                backendSelectionReason = recommendation.reason,
                backendSelectionExplanation = listOf(recommendation.explanation, spec.safeExplanation, enginePlan.safeSummary).filter(String::isNotBlank).joinToString(" ").take(900),
                allowBackendFallback = true,
                userLabel = spec.userLabel,
            )
            MediaRequestHandoffStore.remember(
                downloadId = download.id,
                headers = spec.requestHeaders.ifEmpty { captureHandoff?.headers.orEmpty() },
                redactedSummary = spec.redactedSessionSummary.ifBlank { captureHandoff?.redactedSummary.orEmpty() },
                isExpiringUrl = spec.isExpiringUrl || captureHandoff?.isExpiringUrl == true,
                cleanupActions = enginePlan.cleanupActions,
                tempCookieFileName = enginePlan.tempCookieFile?.fileName,
            )
            captureHandoff?.let { MediaRequestHandoffStore.forgetCapture(record.id) }
            repository.save(download)
            repository.markMediaDownloadCreated(record.id, download.id, now)
            queueIntelligenceCoordinator.requestStart(download.id, userVisible = true, manual = true)
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
        val current = mediaResolverSelectionStore.selections.value[record.id] ?: MediaTrackSelection(videoVariantId = record.selectedVariantId)
        mediaResolverSelectionStore.save(record.id, current.copy(videoVariantId = variantId))
        viewModelScope.launch(Dispatchers.IO) {
            val variants = repository.variantsForMediaCapture(record.id)
            val selected = variants.firstOrNull { it.id == variantId } ?: return@launch
            repository.selectMediaVariant(record.id, selected)
        }
    }

    fun updateMediaTrackSelection(record: MediaCaptureRecord, selection: MediaTrackSelection) {
        mediaResolverSelectionStore.save(record.id, selection)
    }

    fun removeMediaCapture(record: MediaCaptureRecord) {
        mediaResolverSelectionStore.remove(record.id)
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
            paused.forEach { queueIntelligenceCoordinator.requestStart(it.id, userVisible = true, manual = true) }
        }
    }

    fun togglePause(download: Download) {
        viewModelScope.launch {
            when (download.state) {
                DownloadState.Downloading, DownloadState.Connecting, DownloadState.Queued, DownloadState.Finalizing -> transferRuntime.pause(download.id)
                DownloadState.Paused, DownloadState.Failed, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower -> {
                    repository.save(download.copy(state = DownloadState.Queued, errorMessage = null, updatedAtEpochMs = System.currentTimeMillis()))
                    queueIntelligenceCoordinator.requestStart(download.id, userVisible = true, manual = true)
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
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        isExpiringUrl: Boolean = false,
    ) = DownloadRequest(
        id = "preview",
        sourceUrl = url.trim(),
        destinationUri = destination,
        fileName = fileName,
        preferredBackend = backend,
        headers = headers,
        conflictPolicy = conflictPolicy,
        mimeType = mimeType,
        allowBackendFallback = allowFallback,
        isExpiringUrl = isExpiringUrl,
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
            container.queueIntelligenceCoordinator,
            container.destinationWriter,
            container.aria2ProcessManager,
            container.termuxBridgeManager,
            container.termuxAria2CockpitManager,
            container.termuxMediaPipelineManager,
            container.postProcessingAutomationManager,
            container.mediaResolverSelectionStore,
            container.operationalActivityStore,
            container.browserExtensionExportManager,
            container.debugEventRecorder,
        ) as T
    }
}
