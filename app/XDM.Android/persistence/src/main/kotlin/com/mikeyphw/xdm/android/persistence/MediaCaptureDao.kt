package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaCaptureDao {
    @Query("SELECT * FROM media_captures ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<MediaCaptureEntity>>

    @Query("SELECT * FROM media_captures WHERE id = :id")
    suspend fun findById(id: String): MediaCaptureEntity?

    @Upsert
    suspend fun upsert(entity: MediaCaptureEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MediaCaptureEntity>)

    @Query("UPDATE media_captures SET status = :status, downloadId = :downloadId, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id")
    suspend fun markDownloadCreated(id: String, status: String, downloadId: String, updatedAtEpochMs: Long)

    @Query("DELETE FROM media_captures WHERE id = :id")
    suspend fun delete(id: String)
}
