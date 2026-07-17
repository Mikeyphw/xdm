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
        QueueEntity::class,
        ScheduleRuleEntity::class,
        BackendTaskEntity::class,
        RecoveryRecordEntity::class,
        FinalizationJournalEntity::class,
        NotificationRecordEntity::class,
        TagEntity::class,
        DownloadTagCrossRef::class,
        DestinationPermissionEntity::class,
        Aria2SessionMappingEntity::class,
        BackendMigrationEntity::class,
        DestinationClaimEntity::class,
        OwnershipCounterEntity::class,
    ],
    version = 7,
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
}
