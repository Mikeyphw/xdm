package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun findById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAtEpochMs")
    suspend fun listAll(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int

    @Query("SELECT * FROM downloads WHERE state IN (:states) ORDER BY priority DESC, updatedAtEpochMs ASC")
    suspend fun findByStates(states: List<String>): List<DownloadEntity>

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DownloadEntity>)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE downloads SET archived = :archived, updatedAtEpochMs = :updatedAtEpochMs WHERE id IN (:ids)")
    suspend fun setArchived(ids: List<String>, archived: Boolean, updatedAtEpochMs: Long)

    @Query("SELECT COUNT(*) FROM checkpoints WHERE downloadId = :downloadId AND length(trim(checkpointJson)) > 2")
    suspend fun countDurableCheckpoints(downloadId: String): Int
}

@Dao
interface OrganizationDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM download_tags")
    fun observeTagAssignments(): Flow<List<DownloadTagCrossRef>>

    @Query("SELECT * FROM download_tags")
    suspend fun listTagAssignments(): List<DownloadTagCrossRef>

    @Query("SELECT * FROM saved_searches ORDER BY createdAtEpochMs DESC")
    fun observeSavedSearches(): Flow<List<SavedSearchEntity>>

    @Query("SELECT * FROM destination_rules ORDER BY priority DESC, name")
    fun observeDestinationRules(): Flow<List<DestinationRuleEntity>>

    @Query("SELECT * FROM destination_rules ORDER BY priority DESC, name")
    suspend fun listDestinationRules(): List<DestinationRuleEntity>

    @Query("SELECT * FROM duplicate_url_rules ORDER BY hostPattern")
    fun observeDuplicateRules(): Flow<List<DuplicateUrlRuleEntity>>

    @Query("SELECT * FROM duplicate_url_rules ORDER BY hostPattern")
    suspend fun listDuplicateRules(): List<DuplicateUrlRuleEntity>

    @Query("SELECT * FROM clipboard_inbox ORDER BY updatedAtEpochMs DESC")
    fun observeClipboardInbox(): Flow<List<ClipboardInboxEntity>>

    @Query("SELECT * FROM clipboard_inbox ORDER BY updatedAtEpochMs DESC")
    suspend fun listClipboardInbox(): List<ClipboardInboxEntity>

    @Upsert
    suspend fun upsertTag(entity: TagEntity)

    @Upsert
    suspend fun upsertTagAssignment(entity: DownloadTagCrossRef)

    @Query("DELETE FROM download_tags WHERE downloadId = :downloadId AND tagId = :tagId")
    suspend fun deleteTagAssignment(downloadId: String, tagId: String)

    @Upsert
    suspend fun upsertSavedSearch(entity: SavedSearchEntity)

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun deleteSavedSearch(id: String)

    @Upsert
    suspend fun upsertDestinationRule(entity: DestinationRuleEntity)

    @Upsert
    suspend fun upsertDuplicateRule(entity: DuplicateUrlRuleEntity)

    @Upsert
    suspend fun upsertClipboardItems(entities: List<ClipboardInboxEntity>)

    @Upsert
    suspend fun upsertClipboardItem(entity: ClipboardInboxEntity)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues ORDER BY createdAtEpochMs")
    fun observeAll(): Flow<List<QueueEntity>>

    @Query("SELECT COUNT(*) FROM queues")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entities: List<QueueEntity>)

    @Query("""UPDATE queues SET name = :newName, isEnabled = :newEnabled, maxConcurrent = :newMaxConcurrent
        WHERE id = :id AND name = :expectedName AND isEnabled = :expectedEnabled
          AND maxConcurrent = :expectedMaxConcurrent AND createdAtEpochMs = :expectedCreatedAtEpochMs""")
    suspend fun updateIfUnchanged(
        id: String, expectedName: String, expectedEnabled: Boolean, expectedMaxConcurrent: Int,
        expectedCreatedAtEpochMs: Long, newName: String, newEnabled: Boolean, newMaxConcurrent: Int,
    ): Int

    @Query("DELETE FROM queues WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_rules ORDER BY name")
    fun observeAll(): Flow<List<ScheduleRuleEntity>>

    @Query("SELECT COUNT(*) FROM schedule_rules")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entities: List<ScheduleRuleEntity>)

    @Query("""UPDATE schedule_rules SET queueId = :newQueueId, name = :newName, enabled = :newEnabled, constraintsJson = :newConstraintsJson
        WHERE id = :id
          AND ((queueId IS NULL AND :expectedQueueId IS NULL) OR queueId = :expectedQueueId)
          AND name = :expectedName AND enabled = :expectedEnabled AND constraintsJson = :expectedConstraintsJson""")
    suspend fun updateIfUnchanged(
        id: String, expectedQueueId: String?, expectedName: String, expectedEnabled: Boolean, expectedConstraintsJson: String,
        newQueueId: String?, newName: String, newEnabled: Boolean, newConstraintsJson: String,
    ): Int

    @Query("DELETE FROM schedule_rules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RecoveryDao {
    @Query("SELECT * FROM recovery_records ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<RecoveryRecordEntity>>

    @Query("SELECT * FROM recovery_records ORDER BY createdAtEpochMs DESC")
    suspend fun listAll(): List<RecoveryRecordEntity>

    @Query("SELECT * FROM recovery_records WHERE id = :id")
    suspend fun find(id: String): RecoveryRecordEntity?

    @Upsert
    suspend fun upsert(entity: RecoveryRecordEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RecoveryRecordEntity>)

    @Query("DELETE FROM recovery_records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recovery_records WHERE downloadId = :downloadId")
    suspend fun deleteByDownload(downloadId: String)

    @Query("DELETE FROM recovery_records WHERE downloadId = :downloadId AND attemptGeneration = :attemptGeneration AND classification = :classification")
    suspend fun deleteByDownloadAttemptClassification(downloadId: String, attemptGeneration: Long, classification: String): Int

    @Query("SELECT * FROM recovery_records WHERE downloadId = :downloadId AND attemptGeneration = :attemptGeneration AND artifactIdentity = :artifactIdentity AND classification = :classification LIMIT 1")
    suspend fun findAttemptRecord(downloadId: String, attemptGeneration: Long, artifactIdentity: String, classification: String): RecoveryRecordEntity?
}

@Dao
interface BackendTaskDao {
    @Upsert
    suspend fun upsert(entity: BackendTaskEntity)

    @Query("SELECT * FROM backend_tasks WHERE downloadId = :downloadId")
    suspend fun findByDownload(downloadId: String): BackendTaskEntity?

    @Query("DELETE FROM backend_tasks WHERE downloadId = :downloadId")
    suspend fun deleteByDownload(downloadId: String)
}

@Dao
interface Aria2SessionMappingDao {
    @Upsert
    suspend fun upsert(entity: Aria2SessionMappingEntity)

    @Query("SELECT * FROM aria2_session_mappings WHERE downloadId = :downloadId")
    suspend fun findByDownload(downloadId: String): Aria2SessionMappingEntity?

    @Query("SELECT * FROM aria2_session_mappings WHERE gid = :gid")
    suspend fun findByGid(gid: String): Aria2SessionMappingEntity?

    @Query("SELECT * FROM aria2_session_mappings ORDER BY updatedAtEpochMs DESC")
    suspend fun listAll(): List<Aria2SessionMappingEntity>

    @Query("DELETE FROM aria2_session_mappings WHERE downloadId = :downloadId")
    suspend fun deleteByDownload(downloadId: String)

    @Query("DELETE FROM aria2_session_mappings WHERE gid = :gid")
    suspend fun deleteByGid(gid: String)
}
