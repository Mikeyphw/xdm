package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DestinationPermissionDao {
    @Query("SELECT * FROM destination_permissions ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<DestinationPermissionEntity>>

    @Query("SELECT * FROM destination_permissions WHERE uri = :uri")
    suspend fun find(uri: String): DestinationPermissionEntity?

    @Upsert
    suspend fun upsert(entity: DestinationPermissionEntity)

    @Query("DELETE FROM destination_permissions WHERE uri = :uri")
    suspend fun delete(uri: String)
}
