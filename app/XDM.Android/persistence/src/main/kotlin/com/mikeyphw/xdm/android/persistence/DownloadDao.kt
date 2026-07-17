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
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues ORDER BY createdAtEpochMs")
    fun observeAll(): Flow<List<QueueEntity>>

    @Query("SELECT COUNT(*) FROM queues")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entities: List<QueueEntity>)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_rules ORDER BY name")
    fun observeAll(): Flow<List<ScheduleRuleEntity>>

    @Upsert
    suspend fun upsertAll(entities: List<ScheduleRuleEntity>)
}

@Dao
interface RecoveryDao {
    @Query("SELECT * FROM recovery_records ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<RecoveryRecordEntity>>

    @Upsert
    suspend fun upsertAll(entities: List<RecoveryRecordEntity>)
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
