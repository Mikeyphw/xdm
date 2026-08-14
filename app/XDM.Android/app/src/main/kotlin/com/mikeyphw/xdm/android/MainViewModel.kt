package com.mikeyphw.xdm.android

import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionBuildConfig
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParseResult
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandIds
import com.mikeyphw.xdm.android.model.AutomationCommandRecord
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.AutomationRejectionReason
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
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
import com.mikeyphw.xdm.android.model.BrowserCaptureCandidateSummary
import com.mikeyphw.xdm.android.model.BrowserCaptureSessionSummary
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
import com.mikeyphw.xdm.android.model.CompletedArtifactCapabilities
import com.mikeyphw.xdm.android.model.DownloadActionKind
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.DuplicateUrlRule
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.media.MediaCaptureService
import com.mikeyphw.xdm.android.media.MediaCaptureIntakePlanner
import com.mikeyphw.xdm.android.media.MediaBatchIntakePlanner
import com.mikeyphw.xdm.android.media.MediaSniffingEngine
import com.mikeyphw.xdm.android.media.MediaPageProbe
import com.mikeyphw.xdm.android.media.BrowserHandoffMediaCoordinator
import com.mikeyphw.xdm.android.media.BrowserCaptureSessionRegistry
import com.mikeyphw.xdm.android.media.MediaSniffingInput
import com.mikeyphw.xdm.android.media.MediaSniffingPlan
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
import com.mikeyphw.xdm.android.model.PageObservationProof
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
import com.mikeyphw.xdm.android.storage.PersonalDirectStorage
import com.mikeyphw.xdm.android.termux.TermuxRunStatus
import com.mikeyphw.xdm.android.transfer.BackendSelectionPolicy
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.newChecksumExpectationId
import com.mikeyphw.xdm.android.transfer.parseExpectedChecksum
import com.mikeyphw.xdm.android.util.sanitizeFileName
import com.mikeyphw.xdm.android.transfer.aria2.Aria2CapabilityReport
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessManager
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessState
import com.mikeyphw.xdm.android.transfer.nativeengine.NativeStoragePathProbe
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
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val SessionHeaderAllowList = setOf("authorization", "cookie", "referer", "user-agent", "origin", "accept", "accept-language")

data class StorageDoctorUi(
    val status: String = "Not run",
    val detail: String = "Run the storage doctor to verify the selected direct folder, native destination path, embedded aria2, yt-dlp, and FFmpeg access.",
    val running: Boolean = false,
)

data class Aria2DiagnosticsUi(
    val status: String = "Checking",
    val detail: String = "Inspecting the packaged runtime and private session directory.",
    val canRunSmokeTest: Boolean = false,
    val canRepair: Boolean = false,
    val smokeTestRunning: Boolean = false,
    val storageDoctor: StorageDoctorUi = StorageDoctorUi(),
)

private const val CurrentRoomSchemaVersion = 18
private const val UnpinnedReleaseSigner = "UNPINNED"

private fun releaseSigningAttestationConfigured(): Boolean =
    BuildConfig.XDM_RELEASE_SIGNING_CONFIGURED &&
        BuildConfig.XDM_PINNED_RELEASE_SIGNER_SHA256.isNotBlank() &&
        BuildConfig.XDM_PINNED_RELEASE_SIGNER_SHA256 != UnpinnedReleaseSigner

enum class MediaIntakeFeedbackKind { Idle, Working, Found, NoMediaFound, NeedsBrowserCapture, AuthenticationRequired, Unsupported, Failed }

data class MediaIntakeFeedbackUi(
    val kind: MediaIntakeFeedbackKind = MediaIntakeFeedbackKind.Idle,
    val title: String = "",
    val detail: String = "",
    val diagnostics: List<String> = emptyList(),
) {
    val visible: Boolean get() = kind != MediaIntakeFeedbackKind.Idle
}

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
    val selectedRecoveryDownloadId: String? = null,
    val selectedRecoveryAction: String? = null,
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
    val mediaIntakeFeedback: MediaIntakeFeedbackUi = MediaIntakeFeedbackUi(),
    val browserCaptureSessions: List<BrowserCaptureSessionSummary> = emptyList(),
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
    val releasePackagingReport: ReleasePackagingReport = ReleasePackagingGate.report("0.21.0", 22, "com.mikeyphw.xdm.android"),
    val desktopParityReport: DesktopParityReport = DesktopParityGate.evaluate(true, true, true, true, true, true),
    val finalReleaseGateReport: FinalReleaseGateReport = FinalPublicReleaseGate.evaluate(
        versionName = "0.21.0",
        versionCode = 22,
        packageId = "com.mikeyphw.xdm.android",
        schemaVersion = CurrentRoomSchemaVersion,
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
        versionName = "0.21.0",
        schemaVersion = CurrentRoomSchemaVersion,
        buildType = "debug",
        debuggable = true,
        privacySafeDiagnostics = true,
        releaseSigningConfigured = false,
    ),
    val installUpdateReadinessReport: InstallUpdateReadinessReport = ReleaseInstallReadinessGate.evaluate(
        versionName = "0.21.0",
        versionCode = 22,
        packageId = "com.mikeyphw.xdm.android",
        schemaVersion = CurrentRoomSchemaVersion,
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
    private val downloadArtifactActionManager: DownloadArtifactActionManager,
    private val mediaResolverSelectionStore: MediaResolverSelectionStore,
    private val operationalActivityStore: OperationalActivityStore,
    private val browserExtensionExportManager: BrowserExtensionExportManager,
    private val browserHandoffMediaCoordinator: BrowserHandoffMediaCoordinator,
    private val browserCaptureEnvelopeManager: BrowserCaptureEnvelopeManager,
    private val browserCaptureSessionRegistry: BrowserCaptureSessionRegistry,
    private val debugEventRecorder: DebugEventRecorder,
) : ViewModel() {
    private data class NavigationOverride(
        val route: AppRoute? = null,
        val activityPanel: ActivityPanel = ActivityPanel.Attention,
        val settingsPanel: SettingsPanel = SettingsPanel.Overview,
        val selectedRecoveryDownloadId: String? = null,
        val selectedRecoveryAction: String? = null,
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
    private val storageDoctorMessage = MutableStateFlow<String?>(null)
    private val storageDoctorRunning = MutableStateFlow(false)
    private val storageDoctorUi = combine(storageDoctorMessage, storageDoctorRunning) { message, running ->
        StorageDoctorUi(
            status = when {
                running -> "Running"
                message == null -> "Not run"
                message.startsWith("PASS:") -> "Passed"
                else -> "Needs attention"
            },
            detail = message?.removePrefix("PASS:")?.removePrefix("FAIL:")?.trim()
                ?: StorageDoctorUi().detail,
            running = running,
        )
    }
    private val capabilitySnapshot = MutableStateFlow<Map<BackendType, BackendCapabilities>>(emptyMap())
    private val externalAddDraft = MutableStateFlow<DownloadIntakeDraft?>(null)
    private val mediaIntakeFeedback = MutableStateFlow(MediaIntakeFeedbackUi())
    private val mediaCaptureService = MediaCaptureService()
    private val mediaSniffingEngine = MediaSniffingEngine(mediaCaptureService, debugRecorder = debugEventRecorder)
    private val mediaPageProbe = MediaPageProbe(mediaSniffingEngine, debugRecorder = debugEventRecorder)
    private val mediaCaptureIntakePlanner = MediaCaptureIntakePlanner(mediaCaptureService)
    private val mediaBatchIntakePlanner = MediaBatchIntakePlanner(mediaCaptureService, sniffingEngine = mediaSniffingEngine, debugRecorder = debugEventRecorder)
    private val externalMediaReviewPlanner = ExternalMediaReviewPlanner(mediaCaptureService, sniffingEngine = mediaSniffingEngine, debugRecorder = debugEventRecorder)
    private val downloadIntakePlanner = DownloadIntakePlanner(debugRecorder = debugEventRecorder)
    private val mediaExecutionPlanner = MediaExecutionLibraryPlanner()
    private val nativeStoragePathProbe = NativeStoragePathProbe(destinationWriter)

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
        storageDoctorUi,
    ) { processState, capability, smokeMessage, smokeRunning, storageDoctor ->
        val status = when (processState) {
            is Aria2ProcessState.Running -> "Running"
            is Aria2ProcessState.Starting -> "Starting"
            is Aria2ProcessState.Stopping -> "Stopping"
            is Aria2ProcessState.Failed -> "Failed"
            is Aria2ProcessState.Unavailable -> "Unavailable"
            Aria2ProcessState.Stopped -> if (capability?.isAvailable == true) "Ready" else "Unavailable"
        }
        val detail = smokeMessage ?: when (processState) {
            is Aria2ProcessState.Running -> "aria2 ${processState.version.version} is authenticated on ${processState.endpoint.url}; pid=${processState.processId ?: "unknown"}; secret generation=${processState.secretGeneration}; started=${processState.startedAtEpochMs}; orphan recovery=${processState.orphanRecovery.name}."
            is Aria2ProcessState.Starting -> "Waiting for authenticated loopback RPC."
            is Aria2ProcessState.Stopping -> "Saving the aria2 session and stopping the managed process."
            is Aria2ProcessState.Failed -> buildString {
                append(processState.message)
                processState.diagnostic?.let { diagnostic ->
                    append("\nCategory: ${diagnostic.kind.name}")
                    append("\n${diagnostic.detail}")
                    diagnostic.exitCode?.let { append("\nExit code: $it") }
                    diagnostic.logTail?.takeIf(String::isNotBlank)?.let { append("\nRuntime log tail: $it") }
                }
            }
            is Aria2ProcessState.Unavailable -> processState.report.summary
            Aria2ProcessState.Stopped -> capability?.summary ?: "Inspecting the packaged runtime."
        }
        Aria2DiagnosticsUi(
            status = status,
            detail = detail,
            canRunSmokeTest = capability?.isAvailable == true && !smokeRunning,
            canRepair = capability?.isAvailable == true && !smokeRunning,
            smokeTestRunning = smokeRunning,
            storageDoctor = storageDoctor,
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
        val mediaIntakeFeedback: MediaIntakeFeedbackUi,
        val browserCaptureSessions: List<BrowserCaptureSessionSummary>,
    )

    private val reviewUiBase = combine(externalAddDraft, mediaResolverSelectionStore.selections, operationalActivityStore.snapshot) { draft, selections, activity ->
        Triple(draft, selections, activity)
    }

    private val reviewUi = combine(reviewUiBase, mediaIntakeFeedback, browserCaptureSessionRegistry.sessions) { base, feedback, sessions ->
        ReviewUiSnapshot(base.first, base.second, base.third, feedback, sessions)
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
                schemaVersion = CurrentRoomSchemaVersion,
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
            schemaVersion = CurrentRoomSchemaVersion,
            buildType = BuildConfig.BUILD_TYPE,
            debuggable = BuildConfig.DEBUG,
            privacySafeDiagnostics = true,
            releaseSigningConfigured = releaseSigningAttestationConfigured(),
        )
        val installUpdateReadinessReport = ReleaseInstallReadinessGate.evaluate(
            versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            versionCode = BuildConfig.VERSION_CODE,
            packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug"),
            schemaVersion = CurrentRoomSchemaVersion,
            buildType = BuildConfig.BUILD_TYPE,
            releaseSafetyComplete = true,
            recoverySurfaceReady = snapshot.finalizationJournals.none { it.needsRecovery } || snapshot.recovery.isNotEmpty() || snapshot.finalizationJournals.isEmpty(),
            diagnosticsExportRedacted = true,
            aria2PayloadGateRetained = true,
            updateKeepsPackageIdentity = true,
            releaseSigningConfigured = releaseSigningAttestationConfigured(),
        )
        val finalReleaseGateReport = FinalPublicReleaseGate.evaluate(
            versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            versionCode = BuildConfig.VERSION_CODE,
            packageId = BuildConfig.APPLICATION_ID.removeSuffix(".debug"),
            schemaVersion = CurrentRoomSchemaVersion,
            buildType = BuildConfig.BUILD_TYPE,
            releaseSafetyReady = true,
            installUpdateReady = true,
            diagnosticsRedacted = true,
            aria2PayloadVerified = false,
            staticValidatorsComplete = true,
            releaseDocsComplete = true,
            noNewTopLevelRoutes = true,
            fullValidationPassed = false,
            releaseSigningConfigured = releaseSigningAttestationConfigured(),
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
            selectedRecoveryDownloadId = navigation.selectedRecoveryDownloadId,
            selectedRecoveryAction = navigation.selectedRecoveryAction,
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
            mediaIntakeFeedback = review.mediaIntakeFeedback,
            browserCaptureSessions = review.browserCaptureSessions,
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
        viewModelScope.launch(Dispatchers.IO) {
            repository.pendingAutomationCommands().forEach { command ->
                when {
                    command.status == AutomationCommandStatus.Received || command.status == AutomationCommandStatus.Accepted ->
                        processPersistedAutomationCommand(command.id)
                    command.status in setOf(AutomationCommandStatus.Claimed, AutomationCommandStatus.Executing) && command.action == AutomationCommandAction.PromptAddDownload ->
                        openExternalAddDraft(command, ExternalAutomationDispatch.restore(command), "Recovered Add Download confirmation after process restart")
                    command.status == AutomationCommandStatus.Claimed || command.status == AutomationCommandStatus.Executing -> {
                        val recovered = repository.transitionAutomationCommand(
                            command.id,
                            listOf(AutomationCommandStatus.Claimed, AutomationCommandStatus.Executing),
                            AutomationCommandStatus.Received,
                            "Recovered interrupted durable automation claim after process restart.",
                        )
                        if (recovered == 1) processPersistedAutomationCommand(command.id)
                    }
                }
            }
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

    fun openRecoveryFor(download: Download, action: DownloadActionKind = DownloadActionKind.ReviewRecovery) {
        navigationOverride.value = NavigationOverride(
            route = AppRoute.Activity,
            activityPanel = ActivityPanel.Recovery,
            selectedRecoveryDownloadId = download.id,
            selectedRecoveryAction = action.name,
        )
        viewModelScope.launch { preferences.setRoute(AppRoute.Activity) }
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
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = name.trim().ifBlank { "Queue ${repository.countQueues() + 1}" }
            val queue = QueueDefinition(
                id = "queue-${UUID.randomUUID()}",
                name = trimmed.take(48),
                isEnabled = true,
                maxConcurrent = maxConcurrent.coerceIn(1, 16),
                createdAtEpochMs = System.currentTimeMillis(),
            )
            repository.saveQueue(queue)
            queueIntelligenceCoordinator.reconcile()
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            queueIntelligenceCoordinator.deleteQueueSafely(queue.id)
            queueIntelligenceCoordinator.reconcile()
        }
    }

    fun createSchedule(name: String, queueId: String?, constraintsJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = name.trim().ifBlank { "Schedule ${repository.countSchedules() + 1}" }
            val rule = ScheduleRule(
                id = "schedule-${UUID.randomUUID()}",
                queueId = queueId,
                name = trimmed.take(48),
                enabled = true,
                constraintsJson = constraintsJson.ifBlank { "{}" },
            )
            repository.saveSchedule(rule)
            queueIntelligenceCoordinator.reconcile()
        }
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

    fun repairEmbeddedAria2() {
        if (aria2SmokeRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            aria2SmokeRunning.value = true
            aria2SmokeMessage.value = "Repairing embedded aria2: stopping the managed process, clearing stale launch configs, and rotating the RPC secret."
            try {
                val result = aria2ProcessManager.repair()
                aria2SmokeMessage.value = if (result.started) {
                    "Embedded aria2 repaired: secret rotated and authenticated loopback RPC verified."
                } else {
                    (result.state as? Aria2ProcessState.Failed)?.message ?: "Embedded aria2 repair did not reach a running state."
                }
                aria2Capability.value = aria2ProcessManager.probe()
                refreshBackendCapabilities()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                aria2SmokeMessage.value = "Embedded aria2 repair failed safely: ${error.message ?: error::class.java.simpleName}"
                aria2Capability.value = aria2ProcessManager.probe()
            } finally {
                aria2SmokeRunning.value = false
            }
        }
    }

    fun runStorageDoctor() {
        if (storageDoctorRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            storageDoctorRunning.value = true
            storageDoctorMessage.value = "Checking direct-storage permission and filesystem operations."
            try {
                val selectedDestination = preferences.values.first().destinationUri
                val directDestination = if (PersonalDirectStorage.requiresAllFilesAccess(selectedDestination)) {
                    selectedDestination
                } else {
                    DestinationUris.DIRECT_DOWNLOADS
                }
                val local = destinationWriter.runDirectStorageDoctor(directDestination)
                if (!local.passed) {
                    storageDoctorMessage.value = "FAIL: ${local.summary}. ${local.steps.firstOrNull { !it.passed }?.detail.orEmpty()}"
                    return@launch
                }
                val directory = destinationWriter.directStorageDirectory(directDestination)
                val native = nativeStoragePathProbe.run(directDestination)
                if (!native.successful) {
                    storageDoctorMessage.value = "FAIL: ${local.summary}; ${native.summary}"
                    return@launch
                }
                val aria2 = aria2ProcessManager.storageProbe(directory)
                if (!aria2.successful) {
                    storageDoctorMessage.value = "FAIL: ${local.summary}; ${native.summary}; embedded aria2 failed: ${aria2.summary}"
                    return@launch
                }
                val termuxLaunch = termuxBridgeManager.runStoragePathProbe(directory.absolutePath)
                if (!termuxLaunch.started) {
                    storageDoctorMessage.value = "FAIL: ${local.summary}; ${native.summary}; ${aria2.summary}; yt-dlp/FFmpeg path probe could not start: ${termuxLaunch.error}"
                    return@launch
                }
                val termuxFinished = withTimeoutOrNull(20_000L) {
                    termuxBridgeManager.status.first { status ->
                        status.recentRuns.any { run -> run.runId == termuxLaunch.runId && run.status != TermuxRunStatus.Started }
                    }.recentRuns.first { run -> run.runId == termuxLaunch.runId }
                }
                if (termuxFinished == null) {
                    storageDoctorMessage.value = "FAIL: ${local.summary}; ${native.summary}; ${aria2.summary}; yt-dlp/FFmpeg path probe timed out."
                } else if (termuxFinished.status != TermuxRunStatus.Succeeded) {
                    storageDoctorMessage.value = "FAIL: ${local.summary}; ${native.summary}; ${aria2.summary}; yt-dlp/FFmpeg path probe failed: ${termuxFinished.error.ifBlank { termuxFinished.stderrPreview }}"
                } else {
                    storageDoctorMessage.value = "PASS: ${local.summary}; ${native.summary}; ${aria2.summary}; Termux yt-dlp and FFmpeg executed and wrote probe output in ${directory.absolutePath}."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                storageDoctorMessage.value = "FAIL: Storage doctor failed safely: ${error.message ?: error::class.java.simpleName}"
            } finally {
                storageDoctorRunning.value = false
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

    fun pauseTermuxMediaJob(jobId: String) = termuxMediaPipelineManager.pause(jobId)

    fun resumeTermuxMediaJob(jobId: String) = termuxMediaPipelineManager.resume(jobId)

    fun cancelTermuxMediaJob(jobId: String) = termuxMediaPipelineManager.cancel(jobId)

    fun forceCancelTermuxMediaJob(jobId: String) = termuxMediaPipelineManager.forceCancel(jobId)

    fun retryTermuxMediaJob(jobId: String) = termuxMediaPipelineManager.retry(jobId)

    fun recoverTermuxMediaPublication(jobId: String) = termuxMediaPipelineManager.recoverPublication(jobId)

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
        viewModelScope.launch(Dispatchers.IO) {
            val preferenceSnapshot = preferences.values.first()
            val current = preferenceSnapshot.browserExtension
            if (current.exportTreeUri.isBlank()) {
                browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Failed,
                    message = "Choose an export folder before generating the XPI.",
                )
                return@launch
            }
            val channel = when (BuildConfig.BUILD_TYPE.lowercase()) {
                "debug" -> BrowserExtensionSourceContract.Channel.Debug
                else -> BrowserExtensionSourceContract.Channel.Release
            }
            val resolvedTheme = current.resolvedTheme(preferenceSnapshot.themeMode)
            val config = BrowserExtensionBuildConfig(
                extensionVersion = BrowserExtensionSourceContract.DevelopmentVersion,
                appVersion = BuildConfig.VERSION_NAME,
                applicationId = BuildConfig.APPLICATION_ID,
                channel = channel,
                xdmScheme = BuildConfig.XDM_BROWSER_SCHEME,
                defaultTarget = current.defaultTarget,
                themeMode = resolvedTheme,
                captureKeyId = browserCaptureEnvelopeManager.keyId,
                capturePublicKeySpki = browserCaptureEnvelopeManager.publicKeySpkiBase64Url,
                captureOaepHash = browserCaptureEnvelopeManager.captureOaepHash,
            )
            browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                phase = BrowserExtensionExportPhase.Exporting,
                message = "Generating and validating the Firefox XPI…",
            )
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
        viewModelScope.launch(Dispatchers.IO) {
            val preferenceSnapshot = preferences.values.first()
            val status = browserExtensionExportManager.inspect(
                preferences = preferenceSnapshot.browserExtension,
                diagnostics = preferenceSnapshot.browserBridgeDiagnostics,
                appTheme = preferenceSnapshot.themeMode,
                appVersion = BuildConfig.VERSION_NAME,
                applicationId = BuildConfig.APPLICATION_ID,
                scheme = BuildConfig.XDM_BROWSER_SCHEME,
            )
            val uri = status.currentExportUri
            if (uri.isBlank()) {
                browserExtensionRuntime.value = BrowserExtensionRuntimeStatus(
                    phase = BrowserExtensionExportPhase.Failed,
                    message = "The last verified XPI is unavailable. Regenerate it.",
                )
                return@launch
            }
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
        viewModelScope.launch(Dispatchers.IO) {
            val currentTree = preferences.values.first().browserExtension.exportTreeUri
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
        val snapshot = preferences.values.first()
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
        viewModelScope.launch(Dispatchers.IO) {
            val priority = (repository.currentDestinationRules().maxOfOrNull(DestinationRule::priority) ?: 0) + 1
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
        viewModelScope.launch(Dispatchers.IO) {
            val items = ClipboardInboxPolicy.itemsFromText(text, repository.currentClipboardInbox(), System.currentTimeMillis())
            if (items.isNotEmpty()) repository.saveClipboardItems(items)
        }
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
                    authorization = ExternalCommandAuthorization.UserConfirmed,
                    privateNetworkApproved = true,
                ),
            )
        }
    }

    fun dismissClipboardItem(item: ClipboardInboxItem) {
        viewModelScope.launch(Dispatchers.IO) { repository.saveClipboardItem(item.copy(status = "Dismissed", updatedAtEpochMs = System.currentTimeMillis())) }
    }

    fun clearFinishedHistory() {
        val finished = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)
        viewModelScope.launch(Dispatchers.IO) {
            repository.findDownloadsByStates(finished).forEach { candidate ->
                repository.deleteDownloadEntryIfTerminal(candidate, finished)
            }
        }
    }

    suspend fun inspectCompletedArtifact(download: Download): CompletedArtifactCapabilities =
        downloadArtifactActionManager.inspect(download)

    suspend fun inspectResumeCapability(download: Download): Boolean =
        repository.hasDurableResumeEvidence(download.id)

    fun startNow(download: Download) {
        viewModelScope.launch(Dispatchers.IO) {
            queueIntelligenceCoordinator.requestStart(
                downloadId = download.id,
                userVisible = true,
                manual = true,
                policyOverride = download.errorMessage.orEmpty().startsWith("Queue policy:"),
            )
        }
    }

    fun removeDownloadFromHistory(download: Download) {
        deleteDownloadEntry(download) { }
    }

    fun deleteDownloadEntry(download: Download, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val current = repository.findDownload(download.id) ?: return@withContext "This download entry was already removed."
                if (current.state !in setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)) {
                    runCatching { transferRuntime.cancel(current.id) }.getOrElse {
                        return@withContext "The active transfer could not be stopped, so its entry was not removed."
                    }
                    val afterCancel = repository.findDownload(current.id)
                    if (afterCancel != null && afterCancel.state !in setOf(DownloadState.Cancelled, DownloadState.Failed, DownloadState.RecoveryRequired)) {
                        return@withContext "The transfer is still active. Its entry was not removed."
                    }
                }
                val terminalStates = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)
                val terminalCurrent = repository.findDownload(current.id) ?: return@withContext "This download entry was already removed."
                val deleted = repository.deleteDownloadEntryIfTerminal(terminalCurrent, terminalStates)
                if (!deleted) return@withContext "The transfer changed while deletion was being committed. Its entry was not removed."
                MediaRequestHandoffStore.forget(current.id)
                if (repository.findDownload(current.id) == null) "Deleted the download entry and its complete database graph after an atomic terminal-state check."
                else "The database did not confirm deletion of the download entry."
            }
            onResult(message)
        }
    }

    fun deleteSavedFile(download: Download, removeEntry: Boolean, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val outcome = downloadArtifactActionManager.delete(download)
            if (!outcome.success) {
                onResult(outcome.message)
                return@launch
            }
            val message = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val current = repository.findDownload(download.id) ?: download
                if (removeEntry) {
                    val terminalStates = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)
                    val terminalCurrent = repository.findDownload(current.id) ?: current
                    val deleted = repository.deleteDownloadEntryIfTerminal(terminalCurrent, terminalStates)
                    if (!deleted) return@withContext "The saved file was deleted, but the entry changed before atomic graph deletion and was retained for review."
                    MediaRequestHandoffStore.forget(current.id)
                    if (repository.findDownload(current.id) == null) "Deleted the saved file and download entry after an atomic terminal-state check."
                    else "The file was deleted, but the download entry could not be removed."
                } else {
                    repository.save(
                        current.copy(
                            state = DownloadState.RecoveryRequired,
                            speedBytesPerSecond = 0L,
                            errorMessage = "Saved artifact deleted; the download entry was retained.",
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    "Deleted the saved file. The retained entry now opens Recovery."
                }
            }
            onResult(message)
        }
    }

    fun renameCompletedFile(download: Download, requestedName: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val outcome = downloadArtifactActionManager.rename(download, requestedName)
            if (outcome.success) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val current = repository.findDownload(download.id) ?: download
                    repository.save(
                        current.copy(
                            fileName = outcome.displayName ?: current.fileName,
                            destinationUri = outcome.canonicalUri ?: current.destinationUri,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            onResult(outcome.message)
        }
    }

    fun refreshDownloadLink(download: Download, replacementUrl: String, onResult: (String) -> Unit) {
        val normalized = ExternalUrlPolicy.normalizedUrl(replacementUrl)
        if (normalized == null) {
            onResult("Enter a valid HTTP or HTTPS URL.")
            return
        }
        viewModelScope.launch {
            val message = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val current = repository.findDownload(download.id) ?: return@withContext "The download entry no longer exists."
                if (current.state in setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing)) {
                    return@withContext "Stop the active operation before replacing its source URL."
                }
                MediaRequestHandoffStore.replaceDownloadUrl(current.id, normalized)
                val persisted = ExternalUrlPolicy.persistableUrl(normalized) ?: normalized.substringBefore('?')
                repository.save(current.copy(sourceUrl = persisted, errorMessage = null, updatedAtEpochMs = System.currentTimeMillis()))
                "Replaced the source URL while preserving destination, queue, checksum, backend preference, and request context allowed for the new host."
            }
            onResult(message)
        }
    }

    fun redownload(download: Download) {
        redownloadPreserving(download) { }
    }

    fun redownloadPreserving(download: Download, onResult: (String) -> Unit) {
        if (download.sourceUrl.isBlank()) {
            onResult("This entry has no reusable source URL.")
            return
        }
        viewModelScope.launch {
            val message = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val current = repository.findDownload(download.id) ?: download
                val now = System.currentTimeMillis()
                val newId = UUID.randomUUID().toString()
                val exactUrl = MediaRequestHandoffStore.forDownload(current.id)?.exactUrl ?: current.sourceUrl
                val originalDestination = repository.finalizationForDownload(current.id)
                    ?.destinationUri
                    ?.takeIf(String::isNotBlank)
                    ?: current.destinationUri
                val request = previewRequest(
                    exactUrl,
                    current.fileName,
                    current.requestedBackend,
                    originalDestination,
                    current.conflictPolicy,
                    current.allowBackendFallback,
                    isMediaRequest = current.mimeType?.startsWith("video/") == true || current.mimeType?.startsWith("audio/") == true,
                    headers = MediaRequestHandoffStore.forDownload(current.id)?.headers.orEmpty(),
                    mimeType = current.mimeType,
                    isExpiringUrl = MediaRequestHandoffStore.forDownload(current.id)?.isExpiringUrl == true,
                )
                val recommendation = backendSelectionPolicy.recommend(request, capabilitySnapshot.value.ifEmpty(::previewCapabilities))
                if (!recommendation.compatible) return@withContext "No compatible backend can preserve this download's current requirements."
                val retry = current.copy(
                    id = newId,
                    sourceUrl = ExternalUrlPolicy.persistableUrl(exactUrl) ?: exactUrl.substringBefore('?'),
                    destinationUri = originalDestination,
                    state = DownloadState.Queued,
                    backend = recommendation.backend,
                    bytesReceived = 0L,
                    totalBytes = null,
                    speedBytesPerSecond = 0L,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    errorMessage = null,
                    backendSelectionReason = recommendation.reason,
                    backendSelectionExplanation = recommendation.explanation,
                    archived = false,
                    attemptGeneration = 1L,
                )
                repository.save(retry)
                MediaRequestHandoffStore.cloneDownload(current.id, newId, exactUrl)
                repository.checksumExpectations(current.id).forEach { expectation ->
                    repository.saveChecksumExpectation(
                        expectation.copy(
                            id = newChecksumExpectationId(newId, expectation.algorithm),
                            downloadId = newId,
                            createdAtEpochMs = now,
                            attemptGeneration = retry.attemptGeneration,
                        ),
                    )
                }
                repository.currentTagAssignments().filter { it.downloadId == current.id }.forEach { assignment ->
                    repository.assignTag(newId, assignment.tagId)
                }
                val clonedPostProcessingJobs = repository.clonePostProcessingJobsForRedownload(current.id, newId, now)
                queueIntelligenceCoordinator.requestStart(newId, userVisible = true, manual = true)
                "Created a fresh download generation with the original destination, queue, conflict policy, backend preference, checksums, request session, tags, global post-processing rules, and $clonedPostProcessingJobs explicit post-processing job/rule record(s)."
            }
            navigate(AppRoute.Downloads)
            onResult(message)
        }
    }

    fun restartFromZero(download: Download, onResult: (String) -> Unit) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { transferRuntime.cancel(download.id) }
                repository.deleteBackendTask(download.id)
                repository.deleteFinalizationForDownload(download.id)
            }
            redownloadPreserving(download, onResult)
        }
    }

    fun moveDownloadInQueue(download: Download, kind: DownloadActionKind) {
        val queueId = download.queueId ?: "default"
        val movableStates = setOf(DownloadState.Created, DownloadState.Queued, DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.findDownloadsByStates(movableStates)
                .filter { (it.queueId ?: "default") == queueId }
                .sortedWith(compareByDescending<Download> { it.priority }.thenBy { it.createdAtEpochMs })
            val from = current.indexOfFirst { it.id == download.id }
            if (from < 0) return@launch
            val to = when (kind) {
                DownloadActionKind.MoveToTop -> 0
                DownloadActionKind.MoveUp -> (from - 1).coerceAtLeast(0)
                DownloadActionKind.MoveDown -> (from + 1).coerceAtMost(current.lastIndex)
                DownloadActionKind.MoveToBottom -> current.lastIndex
                else -> from
            }
            if (from == to) return@launch
            val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
            val now = System.currentTimeMillis()
            val reprioritized = reordered.mapIndexed { index, item ->
                item.copy(priority = (reordered.size - index) * 10, updatedAtEpochMs = now)
            }
            if (!repository.saveAll(reprioritized)) {
                android.util.Log.w(
                    "XDMQueueMutation",
                    "Queue reprioritization was rejected because a newer download generation or state already exists.",
                )
            }
        }
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
        viewModelScope.launch {
        val duplicate = OrganizationPowerTools.duplicateFor(url, repository.findDownloadsByStates(DownloadState.entries.toSet()))
        if (duplicate != null) {
            navigate(AppRoute.Downloads)
            return@launch
        }
        val now = System.currentTimeMillis()
        val consumedExternalDraft = externalAddDraft.value
        val externalSessionHeaders = consumedExternalDraft?.requestHeaders.orEmpty()
        val mediaCandidate = mediaCaptureService.candidateFor(url)
        val resolvedDestination = OrganizationPowerTools.destinationFor(url, safeName, mediaCandidate?.mimeType, repository.currentDestinationRules(), destination)
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
        if (!recommendation.compatible) return@launch
        val resolvedBackend = recommendation.backend
        val download = Download(
            id = UUID.randomUUID().toString(),
            fileName = safeName,
            sourceUrl = ExternalUrlPolicy.persistableUrl(url) ?: url.trim().substringBefore('?'),
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
            MediaRequestHandoffStore.remember(
                downloadId = download.id,
                headers = externalSessionHeaders,
                redactedSummary = consumedExternalDraft?.redactedHeaderSummary.orEmpty(),
                isExpiringUrl = externalSessionHeaders.isNotEmpty() || ExternalUrlPolicy.hasCredentialBearingQuery(url),
                exactUrl = url.trim(),
                pageUrl = consumedExternalDraft?.pageUrl,
                privateNetworkApproved = true,
                cleartextCredentialsApproved = false,
            )
            if (!repository.save(download)) {
                MediaRequestHandoffStore.forget(download.id)
                consumedExternalDraft?.let { draft ->
                    repository.findAutomationCommand(draft.id)?.let { command ->
                        repository.saveAutomationCommand(
                            command.copy(
                                status = AutomationCommandStatus.Failed,
                                resultMessage = "Download persistence rejected the reviewed Add Download request",
                                rejectionReason = AutomationRejectionReason.ClaimLost,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                return@launch
            }
            consumedExternalDraft?.let { markExternalDraftDownloadCreated(it, download.id) }
            val normalizedChecksum = expectedChecksum.trim().takeIf { it.isNotBlank() }?.let { parseExpectedChecksum(it, checksumAlgorithm) }.orEmpty()
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
            val commandId = ExternalAutomationDispatch.persist(repository, draft) ?: return@launch
            processPersistedAutomationCommand(commandId)
        }
    }

    fun ingestPersistedAutomationCommand(commandId: String) {
        val normalizedId = commandId.trim().takeIf(String::isNotBlank) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            processPersistedAutomationCommand(normalizedId)
        }
    }

    private suspend fun processPersistedAutomationCommand(commandId: String) {
        val existing = repository.findAutomationCommand(commandId) ?: return
        when (existing.status) {
            AutomationCommandStatus.Applied,
            AutomationCommandStatus.Executed,
            -> {
                when {
                    existing.mediaCaptureId != null -> navigate(AppRoute.Media)
                    existing.downloadId != null -> navigate(AppRoute.Downloads)
                }
                return
            }
            AutomationCommandStatus.Duplicate,
            AutomationCommandStatus.Rejected,
            AutomationCommandStatus.Failed,
            -> return
            AutomationCommandStatus.Claimed,
            AutomationCommandStatus.Executing,
            -> return
            AutomationCommandStatus.Received,
            AutomationCommandStatus.Accepted,
            -> Unit
        }

        // This transaction is the durable single-consumer claim. Only its winner may run effects.
        if (!repository.markAutomationCommandExecuting(commandId)) return
        val command = repository.findAutomationCommand(commandId) ?: return
        val draft = ExternalAutomationDispatch.restore(command)
        val now = System.currentTimeMillis()
        when (command.action) {
            AutomationCommandAction.CaptureMedia -> executeCaptureMediaCommand(command, draft, now)
            AutomationCommandAction.PromptAddDownload -> openExternalAddDraft(command, draft, "External download awaiting Add Download confirmation")
            AutomationCommandAction.EnqueueDownload -> executeEnqueueCommand(command, draft, now)
            AutomationCommandAction.PauseAll -> {
                transferRuntime.pauseAll()
                repository.saveAutomationCommand(
                    command.copy(
                        status = AutomationCommandStatus.Applied,
                        resultMessage = "Pause all requested",
                        rejectionReason = AutomationRejectionReason.None,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            AutomationCommandAction.ResumeAll -> {
                val paused = repository.findDownloadsByStates(
                    setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower),
                )
                paused.forEach { queueIntelligenceCoordinator.requestStart(it.id, userVisible = true, manual = true) }
                repository.saveAutomationCommand(
                    command.copy(
                        status = AutomationCommandStatus.Applied,
                        resultMessage = "Resume requested for ${paused.size} download(s)",
                        rejectionReason = AutomationRejectionReason.None,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            AutomationCommandAction.Unknown -> repository.saveAutomationCommand(
                command.copy(
                    status = AutomationCommandStatus.Rejected,
                    resultMessage = "Unsupported automation action",
                    rejectionReason = AutomationRejectionReason.UnsupportedAction,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun resolveCapturedPlaylistIfPossible(
        record: MediaCaptureRecord,
        exactUrl: String,
        requestHeaders: Map<String, String>,
        now: Long = System.currentTimeMillis(),
    ): Pair<MediaCaptureRecord, List<MediaVariant>> {
        if (record.kind != MediaSourceKind.HlsPlaylist && record.kind != MediaSourceKind.DashManifest) return record to emptyList()
        val plan = mediaPageProbe.probePage(exactUrl, pageTitle = record.title, requestHeaders = requestHeaders)
        val sameCaptureVariants = plan.variants.filter { it.captureId == record.id }
        val acceptedVariants = sameCaptureVariants.ifEmpty {
            if (plan.records.size == 1 && plan.variants.isNotEmpty()) {
                plan.variants.map { it.rekeyForCapture(record.id) }
            } else {
                emptyList()
            }
        }
        return if (acceptedVariants.isNotEmpty()) {
            mediaCaptureService.refreshRecordAfterResolution(record, acceptedVariants, now) to acceptedVariants
        } else {
            record.copy(
                resolutionStatus = MediaResolutionStatus.RequiresRefresh,
                updatedAtEpochMs = now,
            ) to emptyList()
        }
    }

    private fun MediaVariant.rekeyForCapture(captureId: String): MediaVariant {
        val suffix = id.substringAfter(':', id).takeIf { it.isNotBlank() } ?: "variant"
        return copy(id = "$captureId:$suffix", captureId = captureId)
    }

    private suspend fun executeCaptureMediaCommand(command: AutomationCommandRecord, draft: AutomationCommandDraft, now: Long) {
        val text = draft.normalizedUrl
        if (text == null) {
            repository.saveAutomationCommand(
                command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing media URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = now),
            )
            return
        }
        val proposedHeaders = transientSessionHeaders(draft.proposedHeaders ?: draft.rawHeaders, draft.frameUrl ?: draft.pageUrl, text, draft.cleartextCredentialsApproved)
        val finalHeaders = transientSessionHeaders(draft.finalHeaders, draft.frameUrl ?: draft.pageUrl, text, draft.cleartextCredentialsApproved).takeIf { it.isNotEmpty() }
        val requestHeaders = finalHeaders ?: proposedHeaders
        val pageObservationProof = draft.toPageObservationProof()
        val sniffingPlan = mediaSniffingEngine.sniff(
            MediaSniffingInput(
                url = text,
                mimeType = draft.mimeType,
                contentLength = draft.contentLength,
                pageUrl = draft.frameUrl ?: draft.pageUrl,
                pageTitle = draft.pageTitle,
                requestHeaders = requestHeaders,
                source = MediaSniffingSource.BrowserExtension,
                durationMs = draft.durationMs,
                thumbnailUrl = draft.thumbnailUrl,
            ),
        )
        if (sniffingPlan.records.isEmpty()) {
            openExternalAddDraft(command, draft, "No media stream was detected; opened Add Download instead")
            return
        }
        val resolvedCaptures = sniffingPlan.records.map { record ->
            val existing = repository.findMediaCapture(record.id)
            val merged = if (existing?.downloadId != null) {
                record.copy(status = existing.status, downloadId = existing.downloadId, createdAtEpochMs = existing.createdAtEpochMs, updatedAtEpochMs = now)
            } else {
                record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs, updatedAtEpochMs = now)
            }
            if (existing?.downloadId != null) {
                merged to emptyList()
            } else {
                resolveCapturedPlaylistIfPossible(merged, record.sourceUrl, requestHeaders, now)
            }
        }
        val merged = resolvedCaptures.map { it.first }
        val resolvedVariants = resolvedCaptures.flatMap { it.second }
        val allVariants = (sniffingPlan.variants + resolvedVariants).distinctBy(MediaVariant::id)
        merged.forEach { record ->
            val session = browserHandoffMediaCoordinator.rememberBrowserRevision(
                requestUrl = record.sourceUrl,
                topPageUrl = draft.pageUrl,
                frameUrl = draft.frameUrl,
                kind = record.kind,
                mimeType = draft.mimeType,
                proposedHeaders = proposedHeaders,
                finalHeaders = finalHeaders,
                revision = draft.sessionRevision ?: now,
                expiresAtEpochMs = now + 24L * 60L * 60L * 1000L,
                declaredStableMediaId = draft.stableMediaId,
                pageObservationProof = pageObservationProof,
                requirePageObservationProof = draft.pageObservationNonce != null,
            )
            MediaRequestHandoffStore.rememberCapture(
                captureId = record.id,
                headers = session.usableHeaders,
                redactedSummary = session.redactedSummary,
                isExpiringUrl = session.usableHeaders.isNotEmpty() || ExternalUrlPolicy.hasCredentialBearingQuery(session.exactRequestUrl),
                exactUrl = session.exactRequestUrl,
                pageUrl = session.frameUrl ?: session.pageUrl ?: record.pageUrl,
                privateNetworkApproved = draft.privateNetworkApproved,
                cleartextCredentialsApproved = draft.cleartextCredentialsApproved,
            )
        }
        allVariants.forEach { variant ->
            MediaRequestHandoffStore.rememberVariant(
                variantId = variant.id,
                exactUrl = variant.url,
                headers = requestHeaders,
                redactedSummary = redactedSessionSummary(draft.rawHeaders, draft.pageUrl),
                expiresAtEpochMs = variant.expiresAtEpochMs ?: Long.MAX_VALUE,
            )
        }
        repository.saveMediaCapturesWithVariants(merged, allVariants, now)
        repository.saveAutomationCommand(
            command.copy(
                status = AutomationCommandStatus.Applied,
                resultMessage = "Captured ${merged.size} media item(s); resolved ${resolvedVariants.size} manifest variant(s)",
                mediaCaptureId = merged.firstOrNull()?.id,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        navigate(AppRoute.Media)
    }

    private suspend fun openExternalAddDraft(command: AutomationCommandRecord, draft: AutomationCommandDraft, message: String) {
        val url = draft.normalizedUrl
        if (url == null) {
            repository.saveAutomationCommand(
                command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing download URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = System.currentTimeMillis()),
            )
            return
        }
        val requestHeaders = transientSessionHeaders(draft.rawHeaders, draft.pageUrl, url, draft.cleartextCredentialsApproved)
        val intakeDraft = downloadIntakePlanner.fromExternal(
            id = command.id,
            url = url,
            fileName = draft.fileName,
            sourceLabel = sourceLabelFor(draft.source, draft.originPackage, draft.verifiedIntegrationId),
            origin = intakeOriginFor(draft.source),
            pageTitle = draft.pageTitle,
            pageUrl = draft.pageUrl,
            mimeType = draft.mimeType,
            contentLength = draft.contentLength,
            durationMs = draft.durationMs,
            thumbnailUrl = draft.thumbnailUrl,
            requestHeaders = requestHeaders,
            redactedHeaderSummary = redactedSessionSummary(draft.rawHeaders, draft.pageUrl),
        )
        if (intakeDraft == null) {
            repository.saveAutomationCommand(
                command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Unsupported download URL", rejectionReason = AutomationRejectionReason.UnsupportedUrl, updatedAtEpochMs = System.currentTimeMillis()),
            )
            return
        }
        externalAddDraft.value = intakeDraft
        repository.saveAutomationCommand(
            command.copy(
                status = AutomationCommandStatus.Executing,
                resultMessage = message,
                rejectionReason = AutomationRejectionReason.None,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        navigate(AppRoute.Add)
    }

    private suspend fun markExternalDraftDownloadCreated(draft: DownloadIntakeDraft, downloadId: String) {
        val command = repository.findAutomationCommand(draft.id) ?: return
        repository.saveAutomationCommand(
            command.copy(
                status = AutomationCommandStatus.Applied,
                resultMessage = "Download created from ${draft.sourceLabel}",
                downloadId = downloadId,
                rejectionReason = AutomationRejectionReason.None,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    fun dismissExternalAddDraft() {
        val draft = externalAddDraft.value ?: return
        externalAddDraft.value = null
        viewModelScope.launch(Dispatchers.IO) {
            repository.findAutomationCommand(draft.id)?.let { command ->
                if (command.status == AutomationCommandStatus.Executing || command.status == AutomationCommandStatus.Claimed) {
                    repository.saveAutomationCommand(
                        command.copy(
                            status = AutomationCommandStatus.Rejected,
                            resultMessage = "User dismissed Add Download review",
                            rejectionReason = AutomationRejectionReason.UserDeclined,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    private fun AutomationCommandDraft.toPageObservationProof(): PageObservationProof? {
        val nonce = pageObservationNonce?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return PageObservationProof(
            nonce = nonce,
            originPackage = originPackage,
            createdAtEpochMs = pageObservationCreatedAtEpochMs ?: receivedAtEpochMs,
            expiresAtEpochMs = pageObservationExpiresAtEpochMs ?: (receivedAtEpochMs + 10L * 60L * 1000L),
        )
    }

    private fun sourceLabelFor(source: AutomationCommandSource, originPackage: String? = null, verifiedIntegrationId: String? = null): String {
        val safePackage = originPackage
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 96 }
        val verified = verifiedIntegrationId?.takeIf(String::isNotBlank)
        return when (source) {
            AutomationCommandSource.ShareSheet -> safePackage?.let { "Shared from $it" } ?: "Shared link"
            AutomationCommandSource.ViewIntent, AutomationCommandSource.BrowserExtension -> safePackage?.let { "Download from $it" } ?: "External browser"
            AutomationCommandSource.Tasker -> verified?.let { "Trusted automation" } ?: "Tasker"
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

    private fun transientSessionHeaders(
        rawHeaders: String?,
        pageUrl: String? = null,
        targetUrl: String? = null,
        cleartextCredentialsApproved: Boolean = false,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        rawHeaders
            ?.lineSequence()
            ?.mapNotNull(::parseHeaderLine)
            ?.forEach { (name, value) -> headers[canonicalHeaderName(name)] = value }
        val referer = ExternalUrlPolicy.normalizedUrl(pageUrl)
        if (referer != null && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = referer
        }
        if (ExternalUrlPolicy.isCleartext(targetUrl) && !cleartextCredentialsApproved) {
            headers.keys.removeAll { it.equals("Cookie", true) || it.equals("Authorization", true) }
        }
        headers.keys.removeAll {
            it.equals("Range", true) || it.equals("Host", true) ||
                it.equals("Content-Length", true) || it.equals("Connection", true)
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
        "accept-language" -> "Accept-Language"
        else -> name.trim()
    }

    private suspend fun executeEnqueueCommand(command: AutomationCommandRecord, draft: AutomationCommandDraft, now: Long) {
        val url = draft.normalizedUrl
        if (url == null) {
            repository.saveAutomationCommand(
                command.copy(status = AutomationCommandStatus.Rejected, resultMessage = "Missing download URL", rejectionReason = AutomationRejectionReason.MissingUrl, updatedAtEpochMs = now),
            )
            return
        }
        val safeName = resolveFileName(url, draft.fileName.orEmpty())
        val sessionHeaders = transientSessionHeaders(draft.rawHeaders, draft.pageUrl, url, draft.cleartextCredentialsApproved)
        val mediaCandidate = mediaCaptureService.candidateFor(url)
        val currentPreferences = preferences.values.first()
        val destination = currentPreferences.destinationUri.ifBlank { DestinationUris.PUBLIC_DOWNLOADS }
        val conflictPolicy = currentPreferences.conflictPolicy
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
        val durableDownloadId = UUID.nameUUIDFromBytes("automation:${command.id}".toByteArray()).toString()
        val download = Download(
            id = durableDownloadId,
            fileName = safeName,
            sourceUrl = ExternalUrlPolicy.persistableUrl(url) ?: url.substringBefore('?'),
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
        MediaRequestHandoffStore.remember(
            downloadId = download.id,
            headers = sessionHeaders,
            redactedSummary = redactedSessionSummary(draft.rawHeaders, draft.pageUrl),
            isExpiringUrl = sessionHeaders.isNotEmpty() || ExternalUrlPolicy.hasCredentialBearingQuery(url),
            exactUrl = url,
            pageUrl = draft.normalizedPageUrl,
            privateNetworkApproved = draft.privateNetworkApproved,
            cleartextCredentialsApproved = draft.cleartextCredentialsApproved,
        )
        val existingDownload = repository.findDownload(durableDownloadId)
        if (existingDownload == null && !repository.save(download)) {
            MediaRequestHandoffStore.forget(download.id)
            repository.saveAutomationCommand(
                command.copy(
                    status = AutomationCommandStatus.Failed,
                    resultMessage = "Download persistence lost the execution claim",
                    rejectionReason = AutomationRejectionReason.ClaimLost,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return
        }
        val durableDownload = repository.findDownload(durableDownloadId)
        if (durableDownload == null) {
            MediaRequestHandoffStore.forget(download.id)
            repository.saveAutomationCommand(
                command.copy(
                    status = AutomationCommandStatus.Failed,
                    resultMessage = "Durable enqueue could not read back its deterministic download row",
                    rejectionReason = AutomationRejectionReason.ClaimLost,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return
        }
        if (!repository.saveAutomationCommand(command.copy(status = AutomationCommandStatus.Applied, resultMessage = "Queued download", downloadId = durableDownload.id, updatedAtEpochMs = System.currentTimeMillis()))) {
            return
        }
        queueIntelligenceCoordinator.requestStart(durableDownload.id, userVisible = true, manual = true)
        navigate(AppRoute.Downloads)
    }


    private fun publishMediaIntakeFeedback(feedback: MediaIntakeFeedbackUi, navigateToMedia: Boolean = true) {
        mediaIntakeFeedback.value = feedback.copy(
            title = BrowserBridgeDiagnosticsRedactor.sanitize(feedback.title).take(120),
            detail = BrowserBridgeDiagnosticsRedactor.sanitize(feedback.detail).take(512),
            diagnostics = feedback.diagnostics.map(BrowserBridgeDiagnosticsRedactor::sanitize).filter(String::isNotBlank).takeLast(6),
        )
        if (navigateToMedia) navigate(AppRoute.Media)
    }

    private fun mediaIntakeFailureDetail(error: Throwable): String =
        BrowserBridgeDiagnosticsRedactor.sanitize(error.message ?: error::class.java.simpleName)

    private fun feedbackForEmptyMediaPlan(plan: MediaSniffingPlan, sourceLabel: String): MediaIntakeFeedbackUi {
        val diagnostics = plan.diagnostics.map(BrowserBridgeDiagnosticsRedactor::sanitize).filter(String::isNotBlank).takeLast(6)
        val joined = diagnostics.joinToString(" ").lowercase()
        return when {
            "401" in joined || "403" in joined || "auth" in joined || "forbidden" in joined -> MediaIntakeFeedbackUi(
                MediaIntakeFeedbackKind.AuthenticationRequired,
                "Browser session required",
                "$sourceLabel could not access the media with the available request context. Capture it from Firefox so XDM can use the browser-observed request in a later capture session.",
                diagnostics,
            )
            "unsupported scheme" in joined || "rejected" in joined -> MediaIntakeFeedbackUi(
                MediaIntakeFeedbackKind.Unsupported,
                "Unsupported media input",
                "$sourceLabel was rejected before capture. Use an HTTP(S) page or media URL.",
                diagnostics,
            )
            "failed" in joined || "open failed" in joined -> MediaIntakeFeedbackUi(
                MediaIntakeFeedbackKind.Failed,
                "Media inspection failed",
                "$sourceLabel could not complete the probe. The diagnostic summary is shown below.",
                diagnostics,
            )
            plan.diagnostics.any { it.contains("no-js", ignoreCase = true) || it.contains("page-probe", ignoreCase = true) } -> MediaIntakeFeedbackUi(
                MediaIntakeFeedbackKind.NeedsBrowserCapture,
                "No media found in the static page probe",
                "The page was fetched, but XDM does not execute page JavaScript here. If playback creates the stream dynamically, play it in Firefox and send the captured media request to XDM.",
                diagnostics,
            )
            else -> MediaIntakeFeedbackUi(
                MediaIntakeFeedbackKind.NoMediaFound,
                "No media found",
                "$sourceLabel completed without producing a reviewable media candidate.",
                diagnostics,
            )
        }
    }

    fun capturePageUrl(pageUrl: String, pageTitle: String? = null) {
        val normalized = pageUrl.trim()
        if (normalized.isBlank()) {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Unsupported, "Paste a page or media URL", "XDM needs a non-empty HTTP(S) URL to inspect."))
            return
        }
        publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Working, "Inspecting page", "Fetching a bounded page prefix and checking it for media candidates."))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mediaPageProbe.probePage(normalized, pageTitle = pageTitle) }
                .onSuccess { plan ->
                    if (plan.records.isEmpty()) {
                        publishMediaIntakeFeedback(feedbackForEmptyMediaPlan(plan, "The page probe"))
                        return@onSuccess
                    }
                    val now = System.currentTimeMillis()
                    val merged = plan.records.map { record ->
                        val existing = repository.findMediaCapture(record.id)
                        if (existing?.downloadId != null) {
                            record.copy(status = existing.status, downloadId = existing.downloadId, createdAtEpochMs = existing.createdAtEpochMs, updatedAtEpochMs = now)
                        } else {
                            record.copy(createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs, updatedAtEpochMs = now)
                        }
                    }
                    repository.saveMediaCapturesWithVariants(merged, plan.variants, now)
                    publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Found, "Media captured", "${merged.size} reviewable media item(s) were added to the Media inbox.", plan.diagnostics.takeLast(4)))
                }
                .onFailure { error ->
                    publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Media inspection failed", mediaIntakeFailureDetail(error)))
                }
        }
    }

    fun captureSharedText(text: String, pageTitle: String? = null, pageUrl: String? = null) {
        if (text.isBlank() && pageUrl.isNullOrBlank()) {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Unsupported, "Nothing to inspect", "Share page text or an HTTP(S) URL to XDM."))
            return
        }
        val sniffingPlan = runCatching {
            mediaSniffingEngine.sniff(
                MediaSniffingInput(
                    url = pageUrl,
                    bodyPrefix = text,
                    pageUrl = pageUrl,
                    pageTitle = pageTitle,
                    source = MediaSniffingSource.SharedText,
                ),
            )
        }.getOrElse { error ->
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Shared media inspection failed", mediaIntakeFailureDetail(error)))
            return
        }
        if (sniffingPlan.records.isEmpty()) {
            publishMediaIntakeFeedback(feedbackForEmptyMediaPlan(sniffingPlan, "Shared content"))
            return
        }
        publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Working, "Importing shared media", "Saving detected candidates for review."))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val merged = sniffingPlan.records.map { record ->
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
                repository.saveMediaCapturesWithVariants(merged, sniffingPlan.variants, now)
                merged
            }.onSuccess { merged ->
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Found, "Media captured", "${merged.size} reviewable media item(s) were added from shared content.", sniffingPlan.diagnostics.takeLast(4)))
            }.onFailure { error ->
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Could not save captured media", mediaIntakeFailureDetail(error)))
            }
        }
    }

    fun ingestEncryptedBrowserCapture(payload: XdmBrowserDeepLinkPayload, originPackage: String? = null) {
        publishMediaIntakeFeedback(
            MediaIntakeFeedbackUi(
                MediaIntakeFeedbackKind.Working,
                "Receiving Firefox capture session",
                "Decrypting the browser capture and importing its reviewable media candidates.",
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            browserCaptureEnvelopeManager.decrypt(payload)
                .onSuccess { decoded -> importBrowserCaptureSession(decoded, originPackage) }
                .onFailure { error ->
                    publishMediaIntakeFeedback(
                        MediaIntakeFeedbackUi(
                            MediaIntakeFeedbackKind.Failed,
                            "Firefox capture session rejected",
                            mediaIntakeFailureDetail(error),
                        ),
                    )
                }
        }
    }

    private suspend fun importBrowserCaptureSession(
        decoded: BrowserCaptureEnvelopeManager.DecodedSession,
        originPackage: String?,
    ) {
        val alreadyImported = browserCaptureSessionRegistry.snapshot().any { session ->
            session.sessionId == decoded.sessionId && session.revision >= decoded.revision
        }
        if (alreadyImported) {
            publishMediaIntakeFeedback(
                MediaIntakeFeedbackUi(
                    MediaIntakeFeedbackKind.Found,
                    "Firefox capture already imported",
                    "This capture-session revision is already present in the Media inbox. Replay was ignored.",
                ),
            )
            navigate(AppRoute.Media)
            return
        }
        val now = System.currentTimeMillis()
        val importedRecords = mutableListOf<MediaCaptureRecord>()
        val importedVariants = mutableListOf<MediaVariant>()
        val summaries = mutableListOf<BrowserCaptureCandidateSummary>()

        decoded.candidates.forEach { candidate ->
            val facts = MediaRequestFacts(
                url = candidate.url,
                mimeType = candidate.mimeType,
                contentLength = candidate.contentLength,
                pageUrl = candidate.pageUrl,
                pageTitle = candidate.title ?: decoded.pageTitle,
                headers = candidate.finalHeaders.ifEmpty { candidate.proposedHeaders },
                frameUrl = candidate.frameUrl,
                stableMediaId = candidate.stableMediaId,
                sessionRevision = candidate.sessionRevision,
                proposedHeaders = candidate.proposedHeaders,
                finalHeaders = candidate.finalHeaders,
            )
            val plan = mediaSniffingEngine.sniff(
                MediaSniffingInput(
                    url = facts.url,
                    mimeType = facts.mimeType,
                    contentLength = facts.contentLength,
                    pageUrl = facts.frameUrl ?: facts.pageUrl,
                    pageTitle = facts.pageTitle,
                    requestHeaders = facts.finalHeaders.ifEmpty { facts.proposedHeaders },
                    source = MediaSniffingSource.BrowserExtension,
                ),
            )
            plan.records.forEach { rawRecord ->
                val existing = repository.findMediaCapture(rawRecord.id)
                val browserSession = browserHandoffMediaCoordinator.rememberBrowserRevision(
                    requestUrl = candidate.url,
                    topPageUrl = candidate.pageUrl ?: decoded.pageUrl,
                    frameUrl = candidate.frameUrl,
                    kind = rawRecord.kind,
                    mimeType = candidate.mimeType,
                    proposedHeaders = candidate.proposedHeaders,
                    finalHeaders = candidate.finalHeaders.takeIf { it.isNotEmpty() },
                    revision = candidate.sessionRevision,
                    expiresAtEpochMs = decoded.expiresAtEpochMs,
                    declaredStableMediaId = candidate.stableMediaId,
                )
                val candidateVariants = plan.variants.filter { it.captureId == rawRecord.id }
                val sanitizedVariants = candidateVariants.map { variant ->
                    val persistedVariantUrl = persistableBrowserCaptureUrl(variant.url)
                    MediaRequestHandoffStore.rememberVariant(
                        variantId = variant.id,
                        exactUrl = variant.url,
                        headers = browserSession.usableHeaders,
                        redactedSummary = browserSession.redactedSummary,
                        expiresAtEpochMs = variant.expiresAtEpochMs ?: decoded.expiresAtEpochMs,
                    )
                    variant.copy(url = persistedVariantUrl)
                }
                val sanitizedRawRecord = rawRecord.copy(
                    sourceUrl = persistableBrowserCaptureUrl(rawRecord.sourceUrl),
                    pageUrl = persistableBrowserCaptureUrlOrNull(rawRecord.pageUrl),
                    selectedVariantUrl = rawRecord.selectedVariantUrl?.let(::persistableBrowserCaptureUrl),
                )
                val record = if (existing?.downloadId != null) {
                    sanitizedRawRecord.copy(
                        status = existing.status,
                        downloadId = existing.downloadId,
                        createdAtEpochMs = existing.createdAtEpochMs,
                        updatedAtEpochMs = now,
                    )
                } else {
                    sanitizedRawRecord.copy(
                        createdAtEpochMs = existing?.createdAtEpochMs ?: rawRecord.createdAtEpochMs,
                        updatedAtEpochMs = now,
                    )
                }
                importedRecords += record
                importedVariants += sanitizedVariants
                MediaRequestHandoffStore.rememberCapture(
                    captureId = record.id,
                    headers = browserSession.usableHeaders,
                    redactedSummary = browserSession.redactedSummary,
                    isExpiringUrl = browserSession.usableHeaders.isNotEmpty() || ExternalUrlPolicy.hasCredentialBearingQuery(browserSession.exactRequestUrl),
                    exactUrl = browserSession.exactRequestUrl,
                    pageUrl = browserSession.frameUrl ?: browserSession.pageUrl ?: record.pageUrl,
                    privateNetworkApproved = false,
                    cleartextCredentialsApproved = false,
                )
                summaries += BrowserCaptureCandidateSummary(
                    captureId = record.id,
                    stableMediaId = browserSession.stableMediaId,
                    quality = candidate.quality,
                    reason = candidate.reason,
                    mediaKind = candidate.mediaKind,
                    evidence = candidate.evidence,
                )
            }
        }

        val distinctRecords = importedRecords.distinctBy(MediaCaptureRecord::id)
        if (distinctRecords.isEmpty()) {
            publishMediaIntakeFeedback(
                MediaIntakeFeedbackUi(
                    MediaIntakeFeedbackKind.NoMediaFound,
                    "Firefox capture had no reviewable media",
                    "The encrypted session reached XDM, but none of its ${decoded.candidates.size} browser candidates matched a downloadable media shape.",
                ),
            )
            return
        }
        val distinctVariants = importedVariants.distinctBy(MediaVariant::id)
        repository.saveMediaCapturesWithVariants(distinctRecords, distinctVariants, now)
        val pageHost = runCatching { URI(decoded.pageUrl.orEmpty()).host.orEmpty() }.getOrDefault("")
        browserCaptureSessionRegistry.record(
            BrowserCaptureSessionSummary(
                sessionId = decoded.sessionId,
                revision = decoded.revision,
                pageTitle = decoded.pageTitle,
                pageHost = pageHost,
                createdAtEpochMs = decoded.createdAtEpochMs,
                updatedAtEpochMs = now,
                totalCandidateCount = decoded.totalCandidateCount,
                importedCandidateCount = distinctRecords.size,
                truncated = decoded.truncated,
                candidates = summaries.distinctBy(BrowserCaptureCandidateSummary::captureId),
            ),
        )
        val source = originPackage?.takeIf(String::isNotBlank)?.let { " from $it" }.orEmpty()
        publishMediaIntakeFeedback(
            MediaIntakeFeedbackUi(
                MediaIntakeFeedbackKind.Found,
                "Firefox capture session imported",
                "${distinctRecords.size} reviewable item(s)$source are grouped in the Media inbox${if (decoded.truncated) "; the browser had more candidates than the bounded secure handoff could carry" else ""}.",
                diagnostics = listOf(
                    "session=${decoded.sessionId.take(48)}",
                    "browserCandidates=${decoded.totalCandidateCount}",
                    "imported=${distinctRecords.size}",
                ),
            ),
        )
        navigate(AppRoute.Media)
    }

    fun captureMediaRequest(facts: MediaRequestFacts) {
        val authenticated = runCatching {
            !facts.requiresPageObservationProof || browserHandoffMediaCoordinator.authenticatePageObservation(facts.pageObservationProof)
        }.getOrElse { error ->
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Firefox capture authentication failed", mediaIntakeFailureDetail(error)))
            return
        }
        if (!authenticated) {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Firefox capture rejected", "The browser observation proof could not be authenticated. Refresh the extension capture and try again."))
            return
        }
        val intake = runCatching { mediaCaptureIntakePlanner.plan(facts) }.getOrElse { error ->
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Firefox capture inspection failed", mediaIntakeFailureDetail(error)))
            return
        }
        if (intake == null) {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.NoMediaFound, "Firefox capture had no reviewable media", "The handoff reached XDM, but it did not contain a media request XDM can review."))
            return
        }
        publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Working, "Receiving Firefox capture", "Importing the browser-observed media request."))
        val proposedHeaders = facts.proposedHeaders.ifEmpty { facts.headers }
        val finalHeaders = facts.finalHeaders.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val session = browserHandoffMediaCoordinator.rememberBrowserRevision(
            requestUrl = facts.url,
            topPageUrl = facts.pageUrl,
            frameUrl = facts.frameUrl ?: facts.headers["X-XDM-Frame-Url"],
            kind = intake.record.kind,
            mimeType = facts.mimeType,
            proposedHeaders = proposedHeaders,
            finalHeaders = finalHeaders,
            revision = facts.sessionRevision ?: now,
            expiresAtEpochMs = now + 24L * 60L * 60L * 1000L,
            declaredStableMediaId = facts.stableMediaId,
            pageObservationProof = facts.pageObservationProof,
            requirePageObservationProof = facts.requiresPageObservationProof,
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                val (resolved, resolvedVariants) = resolveCapturedPlaylistIfPossible(merged, session.exactRequestUrl, session.usableHeaders, now)
                MediaRequestHandoffStore.rememberCapture(
                    captureId = resolved.id,
                    headers = session.usableHeaders,
                    redactedSummary = session.redactedSummary,
                    isExpiringUrl = true,
                    exactUrl = session.exactRequestUrl,
                    pageUrl = session.frameUrl ?: session.pageUrl,
                )
                val capturedVariants = (intake.candidate.variants + resolvedVariants).distinctBy(MediaVariant::id)
                capturedVariants.forEach { variant ->
                    MediaRequestHandoffStore.rememberVariant(
                        variantId = variant.id,
                        exactUrl = variant.url,
                        headers = session.usableHeaders,
                        redactedSummary = session.redactedSummary,
                        expiresAtEpochMs = variant.expiresAtEpochMs ?: Long.MAX_VALUE,
                    )
                }
                repository.saveMediaCaptureWithVariants(resolved, capturedVariants, now)
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Found, "Firefox media captured", "The browser-observed media request is ready for review."))
            } catch (error: Throwable) {
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Could not import Firefox capture", mediaIntakeFailureDetail(error)))
            }
        }
    }

    fun captureMediaBatchInput(text: String) {
        val plan = runCatching { mediaBatchIntakePlanner.plan(text) }.getOrElse { error ->
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Batch inspection failed", mediaIntakeFailureDetail(error)))
            return
        }
        if (plan.parse.acceptedCount == 0 && plan.parse.invalidCount == 0) {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Unsupported, "No URLs to inspect", "The batch did not contain an HTTP(S) media or page URL."))
            return
        }
        if (plan.records.isEmpty()) {
            publishMediaIntakeFeedback(feedbackForEmptyMediaPlan(MediaSniffingPlan(plan.sniffingCandidates, plan.records, plan.variants, plan.sniffingDiagnostics), "Batch inspection"))
        } else {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Working, "Importing media batch", "Saving ${plan.records.size} reviewable item(s)."))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                    repository.saveMediaCapturesWithVariants(merged, plan.variants, now)
                    publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Found, "Media batch captured", "${merged.size} reviewable media item(s) were added."), navigateToMedia = false)
                }
                navigate(AppRoute.Media)
            } catch (error: Throwable) {
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Could not import media batch", mediaIntakeFailureDetail(error)))
            }
        }
    }

    fun openDownloadReview(draft: DownloadIntakeDraft) {
        externalAddDraft.value = draft
        navigate(AppRoute.Add)
    }

    fun inspectManualMedia(url: String, fileName: String) {
        val draft = runCatching { downloadIntakePlanner.fromManual(url = url, fileName = fileName) }.getOrElse { error ->
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Media input inspection failed", mediaIntakeFailureDetail(error)))
            return
        }
        if (draft == null) {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Unsupported, "Unsupported media URL", "XDM could not create a review request from that URL."))
            return
        }
        inspectExternalMedia(draft)
    }

    fun inspectExternalMedia(draft: DownloadIntakeDraft) {
        val intake = runCatching { externalMediaReviewPlanner.plan(draft) }.getOrElse { error ->
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Media inspection failed", mediaIntakeFailureDetail(error)))
            return
        }
        if (intake == null) {
            publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.NoMediaFound, "No reviewable media found", "The supplied URL reached XDM, but it did not produce a media item that can be reviewed."))
            return
        }
        publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Working, "Inspecting media", "Resolving the supplied media request and saving reviewable variants."))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
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
                val inspectNow = System.currentTimeMillis()
                val (resolved, resolvedVariants) = resolveCapturedPlaylistIfPossible(merged, intake.record.sourceUrl, draft.requestHeaders, inspectNow)
                MediaRequestHandoffStore.rememberCapture(
                    captureId = resolved.id,
                    headers = draft.requestHeaders,
                    redactedSummary = draft.redactedHeaderSummary.orEmpty(),
                    isExpiringUrl = draft.requestHeaders.isNotEmpty() || ExternalUrlPolicy.hasCredentialBearingQuery(intake.record.sourceUrl),
                    exactUrl = intake.record.sourceUrl,
                    pageUrl = resolved.pageUrl,
                    privateNetworkApproved = true,
                )
                intake.variants.forEach { variant ->
                    MediaRequestHandoffStore.rememberVariant(
                        variantId = variant.id,
                        exactUrl = variant.url,
                        headers = draft.requestHeaders,
                        redactedSummary = draft.redactedHeaderSummary.orEmpty(),
                        expiresAtEpochMs = variant.expiresAtEpochMs ?: Long.MAX_VALUE,
                    )
                }
                val externalVariants = (intake.variants + resolvedVariants).distinctBy(MediaVariant::id)
                externalVariants.forEach { variant ->
                    MediaRequestHandoffStore.rememberVariant(
                        variantId = variant.id,
                        exactUrl = variant.url,
                        headers = draft.requestHeaders,
                        redactedSummary = draft.redactedHeaderSummary.orEmpty(),
                        expiresAtEpochMs = variant.expiresAtEpochMs ?: Long.MAX_VALUE,
                    )
                }
                repository.saveMediaCaptureWithVariants(resolved, externalVariants, inspectNow)
                repository.findAutomationCommand(draft.id)?.let { command ->
                    repository.saveAutomationCommand(
                        command.copy(
                            status = AutomationCommandStatus.Applied,
                            resultMessage = if (intake.isPageProbe) "External page opened in media resolver" else "External media opened in media resolver",
                            mediaCaptureId = resolved.id,
                            rejectionReason = AutomationRejectionReason.None,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
                externalAddDraft.value = null
                Pair(resolved, externalVariants)
            }.onSuccess { (_, externalVariants) ->
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Found, "Media ready for review", "Saved ${externalVariants.size.coerceAtLeast(1)} media candidate(s) for review."))
                navigate(AppRoute.Media)
            }.onFailure { error ->
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Could not inspect media", mediaIntakeFailureDetail(error)))
            }
        }
    }

    fun downloadMediaCapture(record: MediaCaptureRecord, selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = record.selectedVariantId)) {
        mediaResolverSelectionStore.save(record.id, selection)
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val storedVariants = repository.variantsForMediaCapture(record.id)
            val captureHandoff = MediaRequestHandoffStore.forCapture(record.id)
            val exactRecord = record.copy(
                sourceUrl = captureHandoff?.exactUrl ?: record.sourceUrl,
                pageUrl = captureHandoff?.pageUrl ?: record.pageUrl,
            )
            val variants = storedVariants.map { variant ->
                variant.copy(url = MediaRequestHandoffStore.forVariant(variant.id)?.exactUrl ?: variant.url)
            }
            val spec = mediaExecutionPlanner.queueSpec(
                capture = exactRecord,
                variants = variants,
                selection = selection,
                destinationUri = DestinationUris.PUBLIC_DOWNLOADS,
                sessionHeaders = captureHandoff?.headers.orEmpty().map { (name, value) -> MediaSessionHeader(name, value) },
            )
            val enginePlan = mediaExecutionPlanner.enginePlan(spec, androidSdkInt = android.os.Build.VERSION.SDK_INT)
            if (spec.requiresTermuxYtDlp) {
                val download = Download(
                    id = UUID.randomUUID().toString(),
                    fileName = sanitizeFileName(spec.fileName),
                    sourceUrl = ExternalUrlPolicy.persistableUrl(spec.sourceUrl) ?: spec.sourceUrl.substringBefore('?'),
                    destinationUri = DestinationUris.PUBLIC_DOWNLOADS,
                    state = DownloadState.Queued,
                    backend = BackendType.Automatic,
                    bytesReceived = 0,
                    totalBytes = null,
                    speedBytesPerSecond = 0,
                    queueId = "termux-media",
                    priority = 0,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    conflictPolicy = FilenameConflictPolicy.Rename,
                    mimeType = record.mimeType,
                    requestedBackend = BackendType.Automatic,
                    backendSelectionExplanation = listOf(
                        "Managed by Termux yt-dlp because this media candidate requires playlist/page extraction outside the app queue.",
                        spec.safeExplanation,
                        enginePlan.safeSummary,
                    ).filter(String::isNotBlank).joinToString(" ").take(900),
                    allowBackendFallback = false,
                    userLabel = spec.userLabel,
                )
                val creation = repository.createDownloadFromMediaCapture(record.id, download, now)
                if (creation.isFailure) {
                    val reason = creation.exceptionOrNull()?.message ?: "Download creation failed before the media capture could be linked."
                    debugEventRecorder.record(
                        area = com.mikeyphw.xdm.android.model.DebugArea.AddDownload,
                        severity = com.mikeyphw.xdm.android.model.DebugSeverity.Error,
                        action = "media-termux-download-create",
                        result = "rolled-back",
                        safeDetails = mapOf("captureId" to record.id, "reason" to reason),
                    )
                    publishMediaIntakeFeedback(
                        MediaIntakeFeedbackUi(
                            MediaIntakeFeedbackKind.Failed,
                            "Could not add Termux download",
                            reason,
                        ),
                        navigateToMedia = false,
                    )
                    navigate(AppRoute.Media)
                    return@launch
                }
                val job = termuxMediaPipelineManager.downloadWithYtDlp(
                    record = exactRecord.copy(downloadId = download.id),
                    variants = variants,
                    selection = selection,
                    destination = DestinationUris.PUBLIC_DOWNLOADS,
                    downloadId = download.id,
                )
                captureHandoff?.let { MediaRequestHandoffStore.forgetCapture(record.id) }
                debugEventRecorder.record(
                    area = com.mikeyphw.xdm.android.model.DebugArea.AddDownload,
                    action = "media-termux-download-create",
                    result = "committed",
                    safeDetails = mapOf(
                        "captureId" to record.id,
                        "downloadId" to download.id,
                        "postProcessingJobId" to job.id,
                        "state" to download.state.name,
                    ),
                )
                navigate(AppRoute.Downloads)
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
            if (!recommendation.compatible) {
                repository.saveMediaCapture(
                    record.copy(
                        resolutionStatus = MediaResolutionStatus.Failed,
                        updatedAtEpochMs = now,
                    ),
                )
                navigate(AppRoute.Media)
                return@launch
            }
            val download = Download(
                id = UUID.randomUUID().toString(),
                fileName = sanitizeFileName(spec.fileName),
                sourceUrl = ExternalUrlPolicy.persistableUrl(spec.sourceUrl) ?: spec.sourceUrl.substringBefore('?'),
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
                exactUrl = spec.sourceUrl,
                pageUrl = captureHandoff?.pageUrl,
                privateNetworkApproved = captureHandoff?.privateNetworkApproved ?: true,
                cleartextCredentialsApproved = captureHandoff?.cleartextCredentialsApproved ?: false,
                cleanupActions = enginePlan.cleanupActions,
                tempCookieFileName = enginePlan.tempCookieFile?.fileName,
            )
            val creation = repository.createDownloadFromMediaCapture(record.id, download, now)
            if (creation.isFailure) {
                MediaRequestHandoffStore.forget(download.id)
                val reason = creation.exceptionOrNull()?.message ?: "Download creation failed before the media capture could be linked."
                debugEventRecorder.record(
                    area = com.mikeyphw.xdm.android.model.DebugArea.AddDownload,
                    severity = com.mikeyphw.xdm.android.model.DebugSeverity.Error,
                    action = "media-download-create",
                    result = "rolled-back",
                    safeDetails = mapOf("captureId" to record.id, "reason" to reason),
                )
                publishMediaIntakeFeedback(
                    MediaIntakeFeedbackUi(
                        MediaIntakeFeedbackKind.Failed,
                        "Could not add download",
                        reason,
                    ),
                    navigateToMedia = false,
                )
                navigate(AppRoute.Media)
                return@launch
            }
            captureHandoff?.let { MediaRequestHandoffStore.forgetCapture(record.id) }
            debugEventRecorder.record(
                area = com.mikeyphw.xdm.android.model.DebugArea.AddDownload,
                action = "media-download-create",
                result = "committed",
                safeDetails = mapOf("captureId" to record.id, "downloadId" to download.id, "state" to download.state.name),
            )
            queueIntelligenceCoordinator.requestStart(download.id, userVisible = true, manual = true)
            navigate(AppRoute.Downloads)
        }
    }

    fun resolveMediaCapture(record: MediaCaptureRecord) {
        publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Working, "Checking media again", "Refreshing the captured manifest and its selectable variants."), navigateToMedia = false)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val handoff = MediaRequestHandoffStore.forCapture(record.id)
                val probeUrl = handoff?.exactUrl ?: record.sourceUrl
                val now = System.currentTimeMillis()
                val (refreshed, variants) = resolveCapturedPlaylistIfPossible(
                    record = record.copy(sourceUrl = probeUrl),
                    exactUrl = probeUrl,
                    requestHeaders = handoff?.headers.orEmpty(),
                    now = now,
                )
                Triple(now, refreshed, variants)
            }.onSuccess { (now, refreshed, variants) ->
                if (variants.isEmpty()) {
                    repository.saveMediaCapture(record.copy(resolutionStatus = MediaResolutionStatus.RequiresRefresh, updatedAtEpochMs = now))
                    publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.NeedsBrowserCapture, "No fresh media variants found", "Check again completed, but this manifest still needs a fresh browser-observed request or session."), navigateToMedia = false)
                } else {
                    repository.saveMediaCaptureWithVariants(refreshed.copy(sourceUrl = record.sourceUrl), variants, now)
                    publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Found, "Media refreshed", "Found ${variants.size} selectable media variant(s)."), navigateToMedia = false)
                }
            }.onFailure { error ->
                publishMediaIntakeFeedback(MediaIntakeFeedbackUi(MediaIntakeFeedbackKind.Failed, "Check again failed", mediaIntakeFailureDetail(error)), navigateToMedia = false)
            }
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
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteMediaCapture(record.id)
            MediaRequestHandoffStore.forgetCapture(record.id)
            browserCaptureSessionRegistry.removeCapture(record.id)
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
        viewModelScope.launch {
            queueIntelligenceCoordinator.pauseAllDurably()
            transferRuntime.pauseAll()
        }
    }

    fun resumeAll() {
        viewModelScope.launch {
            val paused = repository.findDownloadsByStates(setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower))
            paused.forEach { queueIntelligenceCoordinator.requestStart(it.id, userVisible = true, manual = true) }
        }
    }

    fun cancelDownload(download: Download) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { transferRuntime.cancel(download.id) }
                .onFailure { error ->
                    val current = repository.findDownload(download.id) ?: return@onFailure
                    repository.save(
                        current.copy(
                            state = DownloadState.RecoveryRequired,
                            speedBytesPerSecond = 0L,
                            errorMessage = "Cancellation could not be confirmed: ${error.message ?: error::class.java.simpleName}",
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
        }
    }

    fun togglePause(download: Download) {
        viewModelScope.launch {
            when (download.state) {
                DownloadState.Downloading, DownloadState.Connecting, DownloadState.Queued, DownloadState.Finalizing -> transferRuntime.pause(download.id)
                DownloadState.Paused, DownloadState.Failed, DownloadState.RecoveryRequired, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower -> {
                    repository.save(download.copy(state = DownloadState.Queued, errorMessage = null, updatedAtEpochMs = System.currentTimeMillis()))
                    queueIntelligenceCoordinator.requestStart(download.id, userVisible = true, manual = true)
                }
                else -> Unit
            }
        }
    }


    private fun persistableBrowserCaptureUrl(url: String): String {
        val normalized = ExternalUrlPolicy.normalizedUrl(url) ?: return url.substringBefore('?')
        return ExternalUrlPolicy.persistableUrl(normalized) ?: normalized.substringBefore('?')
    }

    private fun persistableBrowserCaptureUrlOrNull(url: String?): String? =
        url?.let(::persistableBrowserCaptureUrl)

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
        privateNetworkApproved: Boolean = true,
        cleartextCredentialsApproved: Boolean = false,
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
        privateNetworkApproved = privateNetworkApproved,
        cleartextCredentialsApproved = cleartextCredentialsApproved,
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
            container.downloadArtifactActionManager,
            container.mediaResolverSelectionStore,
            container.operationalActivityStore,
            container.browserExtensionExportManager,
            container.browserHandoffMediaCoordinator,
            container.browserCaptureEnvelopeManager,
            container.browserCaptureSessionRegistry,
            container.debugEventRecorder,
        ) as T
    }
}
