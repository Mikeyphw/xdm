package com.mikeyphw.xdm.android.model

enum class DownloadState {
    Created,
    Queued,
    Connecting,
    Downloading,
    Paused,
    WaitingForNetwork,
    WaitingForPower,
    Verifying,
    Repairing,
    Finalizing,
    Completed,
    Failed,
    Cancelled,
    RecoveryRequired,
}

enum class BackendType { Automatic, Native, Aria2 }
enum class ChecksumAlgorithm { Sha256, Sha512 }


enum class ChecksumSource { UserInput, Clipboard, ChecksumFile, Metalink, Generated }
enum class VerificationStatus { Pending, Running, Passed, Failed, NoExpectation, MissingFile }
enum class RepairBlockStatus { Trusted, Missing, Corrupt, Unknown }



enum class MediaCaptureStatus { Captured, MetadataReady, MetadataMissing, DownloadCreated, Expired }
enum class MediaSourceKind { DirectFile, ProgressiveMedia, HlsPlaylist, DashManifest, AudioStream, VideoStream, Unknown }
enum class MediaVariantKind { Primary, Video, Audio, Subtitle, Thumbnail }

data class MediaVariant(
    val id: String,
    val captureId: String,
    val url: String,
    val kind: MediaVariantKind,
    val mimeType: String?,
    val width: Int? = null,
    val height: Int? = null,
    val bitrateBitsPerSecond: Long? = null,
    val codecs: String? = null,
    val language: String? = null,
    val position: Int = 0,
)

data class MediaCaptureRecord(
    val id: String,
    val sourceUrl: String,
    val pageUrl: String?,
    val title: String,
    val status: MediaCaptureStatus,
    val kind: MediaSourceKind,
    val mimeType: String?,
    val container: String?,
    val codecs: String?,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    val fileName: String,
    val variantCount: Int,
    val downloadId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isPlaylist: Boolean get() = kind == MediaSourceKind.HlsPlaylist || kind == MediaSourceKind.DashManifest
    val hasMetadata: Boolean get() = mimeType != null || container != null || durationMs != null || thumbnailUrl != null
}

enum class FinalizationJournalStage {
    Prepared,
    VerificationComplete,
    PromotionStarted,
    DestinationStaged,
    DestinationCommitted,
    MetadataCommitted,
    Completed,
    RecoveryRequired,
}

enum class RecoveryAction { Resume, Validate, VerifyAndRepair, RestartFromZero, AdoptOrphan, LocateFile, RemoveRecord }

data class ChecksumExpectation(
    val id: String,
    val downloadId: String,
    val algorithm: ChecksumAlgorithm,
    val expectedHex: String,
    val source: ChecksumSource,
    val createdAtEpochMs: Long,
)

data class ChecksumResult(
    val id: String,
    val downloadId: String,
    val algorithm: ChecksumAlgorithm,
    val calculatedHex: String,
    val matchesExpectation: Boolean?,
    val verifiedAtEpochMs: Long,
    val bytesVerified: Long,
    val expectedHex: String? = null,
)

data class VerificationRecord(
    val id: String,
    val downloadId: String,
    val status: VerificationStatus,
    val algorithm: ChecksumAlgorithm?,
    val bytesVerified: Long,
    val totalBytes: Long?,
    val message: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class TrustedBlock(
    val index: Int,
    val startByte: Long,
    val endByteInclusive: Long,
    val checksumHex: String,
    val status: RepairBlockStatus = RepairBlockStatus.Trusted,
)

data class TrustedBlockManifest(
    val id: String,
    val downloadId: String,
    val fileLength: Long,
    val blockSize: Long,
    val algorithm: ChecksumAlgorithm,
    val blocks: List<TrustedBlock>,
    val createdAtEpochMs: Long,
)

data class SelectiveRepairRange(
    val blockIndex: Int,
    val startByte: Long,
    val endByteInclusive: Long,
    val reason: RepairBlockStatus,
)

data class SelectiveRepairPlan(
    val downloadId: String,
    val fileLength: Long,
    val blockSize: Long,
    val ranges: List<SelectiveRepairRange>,
) {
    val requiresNetwork: Boolean get() = ranges.isNotEmpty()
}
enum class RecoveryClassification {
    ReadyToResume,
    NeedsRemoteValidation,
    NeedsRepair,
    MissingPartialFile,
    RemoteFileChanged,
    CompletionRecovered,
    FinalizationInterrupted,
    BackendTaskOrphaned,
    OrphanedArtifact,
}

enum class BackendSelectionReason {
    UserForced,
    NativeOnlyCapability,
    Aria2OptimizedProtocol,
    SafRequiresNative,
    SelectiveRepairRequiresNative,
    MirrorWorkloadPrefersAria2,
    ExpiringRequestPrefersNative,
    AuthenticatedRequestPrefersNative,
    MediaWorkflowRequiresNative,
    LargeFilePrefersAria2,
    HostHistoryPrefersNative,
    HostHistoryPrefersAria2,
    BackendUnavailable,
    BackendIncompatible,
    BackendUnavailableFallback,
    BackendIncompatibleFallback,
    MigrationRequested,
    DefaultNative,
}

enum class BackendBatteryImpact { Low, Moderate, High }
enum class BackendDiagnosticDetail { Basic, Detailed, Forensic }

enum class BackendMigrationStage {
    Requested,
    SourcePaused,
    SourceInspected,
    TargetPrepared,
    OwnershipTransferred,
    TargetAttached,
    Completed,
    Failed,
    RecoveryRequired,
}

enum class BackendMigrationReuse { Empty, Complete, ContiguousPrefix, RestartRequired, Unsafe }

enum class BackendOwnershipStatus {
    Claimed,
    Active,
    Reconciling,
    Reconciled,
    Quarantined,
    Releasing,
}

enum class BackendReconciliationClassification {
    Pending,
    ActiveTaskVerified,
    ResumableArtifact,
    BackendTaskOrphaned,
    OrphanedArtifact,
    MissingArtifact,
    ConflictingArtifact,
    BackendUnavailable,
}

enum class DestinationType {
    AppPrivate,
    PublicDownloads,
    MediaStoreMovies,
    MediaStoreMusic,
    MediaStorePictures,
    MediaStoreDocuments,
    SafTree,
    DirectDocument,
    FileSystem,
}

enum class FilenameConflictPolicy { Overwrite, Resume, Rename, Skip, Compare }

enum class DestinationHealthStatus { Healthy, PermissionMissing, Unavailable, ReadOnly, LowSpace, Unknown }

data class DestinationPermission(
    val uri: String,
    val displayName: String,
    val type: DestinationType,
    val persistedRead: Boolean,
    val persistedWrite: Boolean,
    val status: DestinationHealthStatus,
    val lastValidatedAtEpochMs: Long,
    val lastError: String? = null,
)


data class Download(
    val id: String,
    val fileName: String,
    val sourceUrl: String,
    val destinationUri: String,
    val state: DownloadState,
    val backend: BackendType,
    val bytesReceived: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val queueId: String?,
    val priority: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val errorMessage: String? = null,
    val userLabel: String? = null,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
    val mimeType: String? = null,
    val requestedBackend: BackendType = BackendType.Automatic,
    val backendSelectionReason: BackendSelectionReason = BackendSelectionReason.DefaultNative,
    val backendSelectionExplanation: String = "",
    val allowBackendFallback: Boolean = true,
) {
    val progressFraction: Float
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesReceived.toDouble() / it).coerceIn(0.0, 1.0).toFloat() } ?: 0f
}

data class QueueDefinition(
    val id: String,
    val name: String,
    val isEnabled: Boolean,
    val maxConcurrent: Int,
    val createdAtEpochMs: Long,
)

data class ScheduleRule(
    val id: String,
    val queueId: String?,
    val name: String,
    val enabled: Boolean,
    val constraintsJson: String,
)

data class RecoveryRecord(
    val id: String,
    val downloadId: String?,
    val artifactPath: String,
    val classification: RecoveryClassification,
    val reason: String,
    val createdAtEpochMs: Long,
    val recommendedAction: RecoveryAction = RecoveryAction.Validate,
    val safeToResume: Boolean = false,
)

data class FinalizationJournal(
    val id: String,
    val downloadId: String,
    val stage: FinalizationJournalStage,
    val sourcePath: String,
    val stagingPath: String?,
    val destinationUri: String,
    val bytesExpected: Long?,
    val bytesPromoted: Long,
    val checksumAlgorithm: ChecksumAlgorithm?,
    val checksumHex: String?,
    val message: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isTerminal: Boolean get() = stage == FinalizationJournalStage.Completed
    val needsRecovery: Boolean get() = !isTerminal
}

data class BackendCapabilities(
    val protocols: Set<String>,
    val supportsSegmentation: Boolean,
    val supportsMirrors: Boolean,
    val supportsSelectiveRepair: Boolean,
    val supportsSafDestination: Boolean,
    val supportsAuthentication: Boolean = true,
    val supportsProxy: Boolean = true,
    val maxConnectionsPerDownload: Int = 1,
    val supportsMetalink: Boolean = false,
    val supportsExpiringUrls: Boolean = true,
    val supportsMediaPlaylists: Boolean = false,
    val supportsMigrationImport: Boolean = false,
    val batteryImpact: BackendBatteryImpact = BackendBatteryImpact.Moderate,
    val diagnosticDetail: BackendDiagnosticDetail = BackendDiagnosticDetail.Detailed,
)

data class BackendRecommendation(
    val backend: BackendType,
    val reason: BackendSelectionReason,
    val explanation: String,
    val requestedBackend: BackendType = BackendType.Automatic,
    val fallbackBackend: BackendType? = null,
    val fallbackAllowed: Boolean = true,
    val factors: List<String> = emptyList(),
    val compatible: Boolean = true,
    val compatibilityIssue: String? = null,
)

data class BackendCapabilityRow(
    val backend: BackendType,
    val available: Boolean,
    val protocols: Set<String>,
    val segmentation: Boolean,
    val mirrors: Boolean,
    val metalink: Boolean,
    val proxy: Boolean,
    val authentication: Boolean,
    val saf: Boolean,
    val selectiveRepair: Boolean,
    val media: Boolean,
    val diagnosticDetail: BackendDiagnosticDetail,
    val batteryImpact: BackendBatteryImpact,
    val summary: String,
)

data class BackendMigrationInspection(
    val backend: BackendType,
    val bytesPresent: Long,
    val expectedLength: Long?,
    val reuse: BackendMigrationReuse,
    val remoteValidationRequired: Boolean,
    val message: String,
)

data class BackendMigrationRecord(
    val id: String,
    val downloadId: String,
    val sourceBackend: BackendType,
    val targetBackend: BackendType,
    val sourceGeneration: Long,
    val targetGeneration: Long? = null,
    val sourceTaskId: String? = null,
    val targetTaskId: String? = null,
    val stage: BackendMigrationStage,
    val sourceArtifactIdentity: String,
    val targetArtifactIdentity: String? = null,
    val restartFromZero: Boolean,
    val message: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class BackendRuntimeIdentity(
    val instanceId: String,
    val sessionId: String,
)

data class BackendArtifactIdentity(
    val format: String,
    val primary: String,
    val companions: List<String> = emptyList(),
) {
    init {
        require(format.isNotBlank()) { "Artifact format must not be blank" }
        require(primary.isNotBlank()) { "Primary artifact identity must not be blank" }
        require(companions.none(String::isBlank)) { "Companion artifact identities must not be blank" }
        require(primary !in companions) { "Primary artifact identity must not be repeated as a companion" }
        require(companions.distinct().size == companions.size) { "Companion artifact identities must be unique" }
    }

    fun all(): List<String> = listOf(primary) + companions
}

data class BackendOwnership(
    val downloadId: String,
    val destinationKey: String,
    val artifacts: BackendArtifactIdentity,
    val backend: BackendType,
    val generation: Long,
    val status: BackendOwnershipStatus,
    val runtimeIdentity: BackendRuntimeIdentity,
    val backendTaskId: String? = null,
    val reconciliation: BackendReconciliationClassification = BackendReconciliationClassification.Pending,
    val reconciliationMessage: String? = null,
    val reconciledAtEpochMs: Long? = null,
    val claimedAtEpochMs: Long,
    val synchronizedAtEpochMs: Long,
) {
    val partialIdentity: String get() = artifacts.primary
}
