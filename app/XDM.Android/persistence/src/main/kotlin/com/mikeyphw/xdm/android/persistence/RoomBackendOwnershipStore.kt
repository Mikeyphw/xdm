package com.mikeyphw.xdm.android.persistence

import androidx.room.withTransaction
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.OwnershipClaimResult

class RoomBackendOwnershipStore(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackendOwnershipStore {
    private val dao get() = database.backendOwnershipDao()

    override suspend fun claim(
        downloadId: String,
        destinationKey: String,
        partialIdentity: String,
        backend: BackendType,
    ): OwnershipClaimResult = database.withTransaction {
        dao.findClaimByDownload(downloadId)?.let { existing ->
            return@withTransaction if (existing.destinationKey == destinationKey && existing.backend == backend.name) {
                OwnershipClaimResult.Claimed(existing.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId))
            } else {
                OwnershipClaimResult.Conflict(existing.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId))
            }
        }
        dao.findClaimByDestination(destinationKey)?.let { return@withTransaction OwnershipClaimResult.Conflict(it.toModel()) }
        val now = clock()
        dao.seedCounter(OwnershipCounterEntity(GENERATION_COUNTER, now.coerceAtLeast(1)))
        check(dao.incrementCounter(GENERATION_COUNTER) == 1) { "Could not advance ownership generation" }
        val entity = DestinationClaimEntity(
            destinationKey = destinationKey,
            downloadId = downloadId,
            backend = backend.name,
            partialIdentity = partialIdentity,
            generation = dao.readCounter(GENERATION_COUNTER),
            status = BackendOwnershipStatus.Claimed.name,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        if (dao.insertClaim(entity) == -1L) {
            val conflict = requireNotNull(dao.findClaimByDestination(destinationKey))
            OwnershipClaimResult.Conflict(conflict.toModel())
        } else {
            OwnershipClaimResult.Claimed(entity.toModel())
        }
    }

    override suspend fun attachTask(downloadId: String, generation: Long, backendTaskId: String): BackendOwnership = database.withTransaction {
        val claim = requireNotNull(dao.findClaimByDownload(downloadId)) { "No ownership for $downloadId" }
        require(claim.generation == generation) { "Stale ownership generation" }
        val now = clock()
        val active = claim.copy(status = BackendOwnershipStatus.Active.name, synchronizedAtEpochMs = now)
        dao.deleteClaim(downloadId, generation)
        check(dao.insertClaim(active) != -1L) { "Could not activate ownership" }
        dao.upsertTask(
            BackendTaskEntity(
                id = "${downloadId}:${generation}",
                downloadId = downloadId,
                backend = claim.backend,
                backendTaskId = backendTaskId,
                destinationKey = claim.destinationKey,
                partialIdentity = claim.partialIdentity,
                ownershipGeneration = generation,
                ownershipStatus = BackendOwnershipStatus.Active.name,
                lastSynchronizedAtEpochMs = now,
            ),
        )
        active.toModel(backendTaskId)
    }

    override suspend fun findByDownload(downloadId: String): BackendOwnership? = dao.findClaimByDownload(downloadId)?.let { claim ->
        claim.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId)
    }
    override suspend fun findByDestination(destinationKey: String): BackendOwnership? = dao.findClaimByDestination(destinationKey)?.let { claim ->
        claim.toModel(dao.findTaskByDownload(claim.downloadId)?.backendTaskId)
    }

    override suspend fun release(downloadId: String, generation: Long): Boolean = database.withTransaction {
        dao.deleteTask(downloadId, generation)
        dao.deleteClaim(downloadId, generation) > 0
    }

    private companion object {
        const val GENERATION_COUNTER = "backend-ownership"
    }
}

private fun DestinationClaimEntity.toModel(backendTaskId: String? = null) = BackendOwnership(
    downloadId = downloadId,
    destinationKey = destinationKey,
    partialIdentity = partialIdentity,
    backend = BackendType.valueOf(backend),
    generation = generation,
    status = BackendOwnershipStatus.valueOf(status),
    backendTaskId = backendTaskId,
    claimedAtEpochMs = claimedAtEpochMs,
    synchronizedAtEpochMs = synchronizedAtEpochMs,
)
