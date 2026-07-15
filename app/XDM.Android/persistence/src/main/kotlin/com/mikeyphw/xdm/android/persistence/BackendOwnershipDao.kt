package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BackendOwnershipDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClaim(entity: DestinationClaimEntity): Long

    @Query("SELECT * FROM destination_claims WHERE destinationKey = :destinationKey")
    suspend fun findClaimByDestination(destinationKey: String): DestinationClaimEntity?

    @Query("SELECT * FROM destination_claims WHERE downloadId = :downloadId")
    suspend fun findClaimByDownload(downloadId: String): DestinationClaimEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedCounter(entity: OwnershipCounterEntity): Long

    @Query("UPDATE ownership_counters SET value = value + 1 WHERE name = :name")
    suspend fun incrementCounter(name: String): Int

    @Query("SELECT value FROM ownership_counters WHERE name = :name")
    suspend fun readCounter(name: String): Long

    @Query("DELETE FROM destination_claims WHERE downloadId = :downloadId AND generation = :generation")
    suspend fun deleteClaim(downloadId: String, generation: Long): Int

    @Upsert
    suspend fun upsertTask(entity: BackendTaskEntity)

    @Query("SELECT * FROM backend_tasks WHERE downloadId = :downloadId")
    suspend fun findTaskByDownload(downloadId: String): BackendTaskEntity?

    @Query("DELETE FROM backend_tasks WHERE downloadId = :downloadId AND ownershipGeneration = :generation")
    suspend fun deleteTask(downloadId: String, generation: Long): Int
}
