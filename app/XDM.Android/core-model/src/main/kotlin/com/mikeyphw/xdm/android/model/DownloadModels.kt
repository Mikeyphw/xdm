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
    DefaultNative,
}

enum class BackendOwnershipStatus { Claimed, Active, Releasing }

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
)

data class BackendCapabilities(
    val protocols: Set<String>,
    val supportsSegmentation: Boolean,
    val supportsMirrors: Boolean,
    val supportsSelectiveRepair: Boolean,
    val supportsSafDestination: Boolean,
    val supportsAuthentication: Boolean = true,
    val supportsProxy: Boolean = true,
    val maxConnectionsPerDownload: Int = 1,
)

data class BackendRecommendation(
    val backend: BackendType,
    val reason: BackendSelectionReason,
    val explanation: String,
)

data class BackendOwnership(
    val downloadId: String,
    val destinationKey: String,
    val partialIdentity: String,
    val backend: BackendType,
    val generation: Long,
    val status: BackendOwnershipStatus,
    val backendTaskId: String? = null,
    val claimedAtEpochMs: Long,
    val synchronizedAtEpochMs: Long,
)
