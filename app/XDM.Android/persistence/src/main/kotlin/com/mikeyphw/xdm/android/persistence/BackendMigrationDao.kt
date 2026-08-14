package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BackendMigrationDao {
    @Query("SELECT * FROM backend_migrations ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<BackendMigrationEntity>>

    @Query("SELECT * FROM backend_migrations WHERE id = :id")
    suspend fun find(id: String): BackendMigrationEntity?

    @Query("SELECT * FROM backend_migrations WHERE downloadId = :downloadId ORDER BY updatedAtEpochMs DESC")
    suspend fun listForDownload(downloadId: String): List<BackendMigrationEntity>

    @Query("SELECT * FROM backend_migrations WHERE stage NOT IN ('Completed', 'Failed') ORDER BY updatedAtEpochMs")
    suspend fun listIncomplete(): List<BackendMigrationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: BackendMigrationEntity): Long

    @Upsert
    suspend fun upsert(entity: BackendMigrationEntity)
}
