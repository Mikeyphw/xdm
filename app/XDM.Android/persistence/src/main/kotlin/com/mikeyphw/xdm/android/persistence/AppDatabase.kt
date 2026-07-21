package com.mikeyphw.xdm.android.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DownloadEntity::class,
        DownloadSourceEntity::class,
        MirrorEntity::class,
        TransferSegmentEntity::class,
        CheckpointEntity::class,
        ChecksumExpectationEntity::class,
        ChecksumResultEntity::class,
        VerificationRecordEntity::class,
        TrustedBlockManifestEntity::class,
        QueueEntity::class,
        ScheduleRuleEntity::class,
        BackendTaskEntity::class,
        RecoveryRecordEntity::class,
        FinalizationJournalEntity::class,
        MediaCaptureEntity::class,
        MediaVariantEntity::class,
        AutomationCommandEntity::class,
        NotificationRecordEntity::class,
        TagEntity::class,
        DownloadTagCrossRef::class,
        SavedSearchEntity::class,
        DuplicateUrlRuleEntity::class,
        DestinationRuleEntity::class,
        ClipboardInboxEntity::class,
        DestinationPermissionEntity::class,
        Aria2SessionMappingEntity::class,
        BackendMigrationEntity::class,
        DestinationClaimEntity::class,
        OwnershipCounterEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun queueDao(): QueueDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun recoveryDao(): RecoveryDao
    abstract fun backendOwnershipDao(): BackendOwnershipDao
    abstract fun backendTaskDao(): BackendTaskDao
    abstract fun destinationPermissionDao(): DestinationPermissionDao
    abstract fun aria2SessionMappingDao(): Aria2SessionMappingDao
    abstract fun backendMigrationDao(): BackendMigrationDao
    abstract fun checksumDao(): ChecksumDao
    abstract fun finalizationDao(): FinalizationDao
    abstract fun mediaCaptureDao(): MediaCaptureDao
    abstract fun automationCommandDao(): AutomationCommandDao
    abstract fun organizationDao(): OrganizationDao
}
