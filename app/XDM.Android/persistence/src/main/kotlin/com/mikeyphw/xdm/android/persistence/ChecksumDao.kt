package com.mikeyphw.xdm.android.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChecksumDao {
    @Query("SELECT * FROM checksum_expectations WHERE downloadId = :downloadId ORDER BY algorithm")
    suspend fun expectations(downloadId: String): List<ChecksumExpectationEntity>

    @Query("SELECT * FROM checksum_expectations ORDER BY downloadId, algorithm")
    fun observeExpectations(): Flow<List<ChecksumExpectationEntity>>

    @Upsert
    suspend fun upsertExpectation(entity: ChecksumExpectationEntity)

    @Query("SELECT * FROM checksum_results WHERE downloadId = :downloadId ORDER BY verifiedAtEpochMs DESC")
    suspend fun results(downloadId: String): List<ChecksumResultEntity>

    @Query("SELECT * FROM checksum_results ORDER BY verifiedAtEpochMs DESC")
    fun observeResults(): Flow<List<ChecksumResultEntity>>

    @Upsert
    suspend fun upsertResult(entity: ChecksumResultEntity)

    @Upsert
    suspend fun upsertVerification(entity: VerificationRecordEntity)

    @Query("SELECT * FROM verification_records WHERE downloadId = :downloadId ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun latestVerification(downloadId: String): VerificationRecordEntity?

    @Query("SELECT * FROM verification_records ORDER BY updatedAtEpochMs DESC")
    fun observeVerifications(): Flow<List<VerificationRecordEntity>>

    @Upsert
    suspend fun upsertTrustedManifest(entity: TrustedBlockManifestEntity)

    @Query("SELECT * FROM trusted_block_manifests WHERE downloadId = :downloadId LIMIT 1")
    suspend fun trustedManifest(downloadId: String): TrustedBlockManifestEntity?
}
