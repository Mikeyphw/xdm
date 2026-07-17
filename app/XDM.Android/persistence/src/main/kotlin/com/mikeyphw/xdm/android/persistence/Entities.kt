package com.mikeyphw.xdm.android.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "downloads", indices = [Index("state"), Index("queueId"), Index("updatedAtEpochMs")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val sourceUrl: String,
    val destinationUri: String,
    val state: String,
    val backend: String,
    @ColumnInfo(defaultValue = "'Automatic'") val requestedBackend: String,
    @ColumnInfo(defaultValue = "'DefaultNative'") val backendSelectionReason: String,
    @ColumnInfo(defaultValue = "''") val backendSelectionExplanation: String,
    @ColumnInfo(defaultValue = "1") val allowBackendFallback: Boolean,
    val bytesReceived: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val queueId: String?,
    val priority: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val errorMessage: String?,
    val userLabel: String?,
    @ColumnInfo(defaultValue = "'Rename'") val conflictPolicy: String,
    val mimeType: String?,
)

@Entity(tableName = "download_sources", foreignKeys = [ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE)], indices = [Index("downloadId")])
data class DownloadSourceEntity(@PrimaryKey val id: String, val downloadId: String, val url: String, val position: Int)

@Entity(tableName = "mirrors", foreignKeys = [ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE)], indices = [Index("downloadId")])
data class MirrorEntity(@PrimaryKey val id: String, val downloadId: String, val url: String, val priority: Int, val lastFailure: String?)

@Entity(tableName = "transfer_segments", foreignKeys = [ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE)], indices = [Index("downloadId")])
data class TransferSegmentEntity(@PrimaryKey val id: String, val downloadId: String, val startByte: Long, val endByteInclusive: Long, val bytesReceived: Long, val state: String)

@Entity(tableName = "checkpoints", indices = [Index("downloadId", unique = true)])
data class CheckpointEntity(@PrimaryKey val id: String, val downloadId: String, val checkpointJson: String, val persistedAtEpochMs: Long)

@Entity(tableName = "checksum_expectations", indices = [Index("downloadId"), Index(value = ["downloadId", "algorithm"], unique = true)])
data class ChecksumExpectationEntity(@PrimaryKey val id: String, val downloadId: String, val algorithm: String, val expectedHex: String, val source: String)

@Entity(tableName = "checksum_results", indices = [Index("downloadId"), Index(value = ["downloadId", "algorithm"], unique = true)])
data class ChecksumResultEntity(@PrimaryKey val id: String, val downloadId: String, val algorithm: String, val calculatedHex: String, val matchesExpectation: Boolean?, val verifiedAtEpochMs: Long, @ColumnInfo(defaultValue = "0") val bytesVerified: Long, val expectedHex: String?)

@Entity(tableName = "verification_records", indices = [Index("downloadId"), Index("status"), Index("updatedAtEpochMs")])
data class VerificationRecordEntity(
    @PrimaryKey val id: String,
    val downloadId: String,
    val status: String,
    val algorithm: String?,
    val bytesVerified: Long,
    val totalBytes: Long?,
    val message: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "trusted_block_manifests", indices = [Index("downloadId", unique = true), Index("createdAtEpochMs")])
data class TrustedBlockManifestEntity(
    @PrimaryKey val id: String,
    val downloadId: String,
    val fileLength: Long,
    val blockSize: Long,
    val algorithm: String,
    val blocksJson: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "queues")
data class QueueEntity(@PrimaryKey val id: String, val name: String, val isEnabled: Boolean, val maxConcurrent: Int, val createdAtEpochMs: Long)

@Entity(tableName = "schedule_rules", indices = [Index("queueId")])
data class ScheduleRuleEntity(@PrimaryKey val id: String, val queueId: String?, val name: String, val enabled: Boolean, val constraintsJson: String)

@Entity(tableName = "backend_tasks", indices = [Index("downloadId", unique = true), Index("backendTaskId")])
data class BackendTaskEntity(
    @PrimaryKey val id: String,
    val downloadId: String,
    val backend: String,
    val backendTaskId: String,
    @ColumnInfo(defaultValue = "''") val destinationKey: String,
    @ColumnInfo(defaultValue = "''") val partialIdentity: String,
    @ColumnInfo(defaultValue = "'legacy-partial-v1'") val artifactFormat: String,
    @ColumnInfo(defaultValue = "''") val companionArtifactIdentities: String,
    @ColumnInfo(defaultValue = "''") val backendInstanceId: String,
    @ColumnInfo(defaultValue = "''") val backendSessionId: String,
    val ownershipGeneration: Long,
    @ColumnInfo(defaultValue = "'Active'") val ownershipStatus: String,
    @ColumnInfo(defaultValue = "'Pending'") val reconciliation: String,
    val reconciliationMessage: String?,
    val reconciledAtEpochMs: Long?,
    val lastSynchronizedAtEpochMs: Long,
)

@Entity(tableName = "recovery_records", indices = [Index("downloadId"), Index("classification"), Index("recommendedAction")])
data class RecoveryRecordEntity(
    @PrimaryKey val id: String,
    val downloadId: String?,
    val artifactPath: String,
    val classification: String,
    val reason: String,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "'Validate'") val recommendedAction: String,
    @ColumnInfo(defaultValue = "0") val safeToResume: Boolean,
)

@Entity(tableName = "finalization_journals", indices = [Index("downloadId", unique = true), Index("stage"), Index("updatedAtEpochMs")])
data class FinalizationJournalEntity(
    @PrimaryKey val id: String,
    val downloadId: String,
    val stage: String,
    val sourcePath: String,
    val destinationUri: String,
    val updatedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "''") val stagingPath: String,
    val bytesExpected: Long?,
    @ColumnInfo(defaultValue = "0") val bytesPromoted: Long,
    val checksumAlgorithm: String?,
    val checksumHex: String?,
    @ColumnInfo(defaultValue = "''") val message: String,
    @ColumnInfo(defaultValue = "0") val createdAtEpochMs: Long,
)

@Entity(tableName = "media_captures", indices = [Index("downloadId"), Index("status"), Index("kind"), Index("updatedAtEpochMs")])
data class MediaCaptureEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val pageUrl: String?,
    val title: String,
    val status: String,
    val kind: String,
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
)

@Entity(tableName = "notification_records", indices = [Index("downloadId"), Index("createdAtEpochMs")])
data class NotificationRecordEntity(@PrimaryKey val id: String, val downloadId: String?, val title: String, val message: String, val severity: String, val dismissed: Boolean, val createdAtEpochMs: Long)

@Entity(tableName = "tags")
data class TagEntity(@PrimaryKey val id: String, val name: String, val colorArgb: Long)

@Entity(tableName = "download_tags", primaryKeys = ["downloadId", "tagId"], indices = [Index("tagId")])
data class DownloadTagCrossRef(val downloadId: String, val tagId: String)

@Entity(tableName = "destination_permissions", indices = [Index("providerType"), Index("status")])
data class DestinationPermissionEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val providerType: String,
    val persistedRead: Boolean,
    val persistedWrite: Boolean,
    @ColumnInfo(defaultValue = "'Unknown'") val status: String,
    val lastValidatedAtEpochMs: Long,
    val lastError: String?,
)

@Entity(tableName = "aria2_session_mappings", indices = [Index("downloadId", unique = true), Index("gid", unique = true), Index("status")])
data class Aria2SessionMappingEntity(
    @PrimaryKey val id: String,
    val downloadId: String,
    val gid: String,
    val sourceUrl: String,
    @ColumnInfo(defaultValue = "''") val mirrorUrls: String,
    val destinationUri: String,
    val destinationKey: String,
    val fileName: String,
    val conflictPolicy: String,
    val mimeType: String?,
    val outputPath: String,
    val controlPath: String,
    val ownershipMetadataPath: String,
    val sessionFilePath: String,
    val expectedLength: Long?,
    val ownershipGeneration: Long,
    val backendInstanceId: String,
    val backendSessionId: String,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastSynchronizedAtEpochMs: Long,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
)



@Entity(
    tableName = "backend_migrations",
    indices = [Index("downloadId"), Index("stage"), Index("updatedAtEpochMs")],
)
data class BackendMigrationEntity(
    @PrimaryKey val id: String,
    val downloadId: String,
    val sourceBackend: String,
    val targetBackend: String,
    val sourceGeneration: Long,
    val targetGeneration: Long?,
    val sourceTaskId: String?,
    val targetTaskId: String?,
    val stage: String,
    val sourceArtifactIdentity: String,
    val targetArtifactIdentity: String?,
    val restartFromZero: Boolean,
    val message: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "destination_claims", indices = [Index("downloadId", unique = true)])
data class DestinationClaimEntity(
    @PrimaryKey val destinationKey: String,
    val downloadId: String,
    val backend: String,
    val partialIdentity: String,
    @ColumnInfo(defaultValue = "'legacy-partial-v1'") val artifactFormat: String,
    @ColumnInfo(defaultValue = "''") val companionArtifactIdentities: String,
    @ColumnInfo(defaultValue = "''") val backendInstanceId: String,
    @ColumnInfo(defaultValue = "''") val backendSessionId: String,
    val generation: Long,
    val status: String,
    @ColumnInfo(defaultValue = "'Pending'") val reconciliation: String,
    val reconciliationMessage: String?,
    val reconciledAtEpochMs: Long?,
    val claimedAtEpochMs: Long,
    val synchronizedAtEpochMs: Long,
)

@Entity(tableName = "ownership_counters")
data class OwnershipCounterEntity(@PrimaryKey val name: String, val value: Long)
