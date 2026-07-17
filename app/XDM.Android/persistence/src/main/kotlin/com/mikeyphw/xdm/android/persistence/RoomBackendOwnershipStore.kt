package com.mikeyphw.xdm.android.persistence

import androidx.room.withTransaction
import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.OwnershipClaimResult

class RoomBackendOwnershipStore(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackendOwnershipStore {
    private val dao get() = database.backendOwnershipDao()

    override suspend fun claim(
        downloadId: String,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult = database.withTransaction {
        dao.findClaimByDownload(downloadId)?.let { existing ->
            val model = existing.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId)
            val idempotent = model.destinationKey == destinationKey &&
                model.backend == backend &&
                model.artifacts == artifacts &&
                model.runtimeIdentity == runtimeIdentity &&
                model.status == BackendOwnershipStatus.Claimed
            return@withTransaction if (idempotent) OwnershipClaimResult.Claimed(model) else OwnershipClaimResult.Conflict(model)
        }
        dao.findClaimByDestination(destinationKey)?.let { existing ->
            return@withTransaction OwnershipClaimResult.Conflict(existing.toModel(dao.findTaskByDownload(existing.downloadId)?.backendTaskId))
        }
        createClaim(downloadId, destinationKey, artifacts, backend, runtimeIdentity)
    }

    override suspend fun adopt(
        downloadId: String,
        expectedGeneration: Long,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult = database.withTransaction {
        val existingEntity = dao.findClaimByDownload(downloadId)
            ?: return@withTransaction createClaim(downloadId, destinationKey, artifacts, backend, runtimeIdentity)
        val existing = existingEntity.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId)
        val adoptable = existing.generation == expectedGeneration &&
            existing.destinationKey == destinationKey &&
            existing.backend == backend &&
            existing.artifacts == artifacts &&
            existing.status == BackendOwnershipStatus.Reconciled &&
            existing.reconciliation == BackendReconciliationClassification.ResumableArtifact
        if (!adoptable) return@withTransaction OwnershipClaimResult.Conflict(existing)

        val now = clock()
        val generation = nextGeneration(now)
        dao.deleteTask(downloadId, expectedGeneration)
        val adopted = existingEntity.copy(
            partialIdentity = artifacts.primary,
            artifactFormat = artifacts.format,
            companionArtifactIdentities = artifacts.companions.encodeCompanions(),
            backendInstanceId = runtimeIdentity.instanceId,
            backendSessionId = runtimeIdentity.sessionId,
            generation = generation,
            status = BackendOwnershipStatus.Claimed.name,
            reconciliation = BackendReconciliationClassification.Pending.name,
            reconciliationMessage = null,
            reconciledAtEpochMs = null,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        dao.upsertClaim(adopted)
        OwnershipClaimResult.Claimed(adopted.toModel())
    }

    override suspend fun attachTask(downloadId: String, generation: Long, backendTaskId: String): BackendOwnership = database.withTransaction {
        val claim = requireClaim(downloadId, generation)
        val now = clock()
        val active = claim.copy(
            status = BackendOwnershipStatus.Active.name,
            reconciliation = BackendReconciliationClassification.ActiveTaskVerified.name,
            reconciliationMessage = "Backend task attached to the current ownership generation.",
            reconciledAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        dao.upsertClaim(active)
        dao.upsertTask(active.toTaskEntity(backendTaskId, now))
        active.toModel(backendTaskId)
    }

    override suspend fun transfer(
        downloadId: String,
        expectedGeneration: Long,
        sourceBackend: BackendType,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        targetBackend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult = database.withTransaction {
        val existingEntity = dao.findClaimByDownload(downloadId)
            ?: error("No ownership exists for $downloadId")
        val existing = existingEntity.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId)
        val transferable = existing.generation == expectedGeneration &&
            existing.backend == sourceBackend &&
            existing.destinationKey == destinationKey &&
            existing.status in setOf(BackendOwnershipStatus.Reconciled, BackendOwnershipStatus.Quarantined)
        if (!transferable) return@withTransaction OwnershipClaimResult.Conflict(existing)
        dao.findClaimByDestination(destinationKey)?.let { owner ->
            if (owner.downloadId != downloadId) {
                return@withTransaction OwnershipClaimResult.Conflict(owner.toModel(dao.findTaskByDownload(owner.downloadId)?.backendTaskId))
            }
        }
        val now = clock()
        val generation = nextGeneration(now)
        dao.deleteTask(downloadId, expectedGeneration)
        val transferred = existingEntity.copy(
            backend = targetBackend.name,
            partialIdentity = artifacts.primary,
            artifactFormat = artifacts.format,
            companionArtifactIdentities = artifacts.companions.encodeCompanions(),
            backendInstanceId = runtimeIdentity.instanceId,
            backendSessionId = runtimeIdentity.sessionId,
            generation = generation,
            status = BackendOwnershipStatus.Claimed.name,
            reconciliation = BackendReconciliationClassification.Pending.name,
            reconciliationMessage = "Ownership transferred from $sourceBackend to $targetBackend; target task is not attached yet.",
            reconciledAtEpochMs = null,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        dao.upsertClaim(transferred)
        OwnershipClaimResult.Claimed(transferred.toModel())
    }

    override suspend fun markReconciling(downloadId: String, generation: Long): BackendOwnership = database.withTransaction {
        val claim = requireClaim(downloadId, generation)
        val updated = claim.copy(
            status = BackendOwnershipStatus.Reconciling.name,
            synchronizedAtEpochMs = clock(),
        )
        dao.upsertClaim(updated)
        updated.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId)
    }

    override suspend fun recordReconciliation(
        downloadId: String,
        generation: Long,
        result: BackendReconciliationResult,
    ): BackendOwnership = database.withTransaction {
        val claim = requireClaim(downloadId, generation)
        val now = clock()
        val status = when (result.classification) {
            BackendReconciliationClassification.ActiveTaskVerified -> BackendOwnershipStatus.Active
            BackendReconciliationClassification.ResumableArtifact -> BackendOwnershipStatus.Reconciled
            BackendReconciliationClassification.Pending -> BackendOwnershipStatus.Claimed
            else -> BackendOwnershipStatus.Quarantined
        }
        val updated = claim.copy(
            status = status.name,
            reconciliation = result.classification.name,
            reconciliationMessage = result.message,
            reconciledAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        dao.upsertClaim(updated)
        val task = dao.findTaskByDownload(downloadId)
        val taskId = result.backendTaskId ?: task?.backendTaskId
        if (taskId != null) dao.upsertTask(updated.toTaskEntity(taskId, now))
        updated.toModel(taskId)
    }

    override suspend fun findByDownload(downloadId: String): BackendOwnership? = dao.findClaimByDownload(downloadId)?.let { claim ->
        claim.toModel(dao.findTaskByDownload(downloadId)?.backendTaskId)
    }

    override suspend fun findByDestination(destinationKey: String): BackendOwnership? = dao.findClaimByDestination(destinationKey)?.let { claim ->
        claim.toModel(dao.findTaskByDownload(claim.downloadId)?.backendTaskId)
    }

    override suspend fun listAll(): List<BackendOwnership> {
        val ownership = mutableListOf<BackendOwnership>()
        for (claim in dao.listClaims()) {
            ownership += claim.toModel(dao.findTaskByDownload(claim.downloadId)?.backendTaskId)
        }
        return ownership
    }

    override suspend fun release(downloadId: String, generation: Long): Boolean = database.withTransaction {
        dao.deleteTask(downloadId, generation)
        dao.deleteClaim(downloadId, generation) > 0
    }

    private suspend fun createClaim(
        downloadId: String,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult {
        val now = clock()
        val entity = DestinationClaimEntity(
            destinationKey = destinationKey,
            downloadId = downloadId,
            backend = backend.name,
            partialIdentity = artifacts.primary,
            artifactFormat = artifacts.format,
            companionArtifactIdentities = artifacts.companions.encodeCompanions(),
            backendInstanceId = runtimeIdentity.instanceId,
            backendSessionId = runtimeIdentity.sessionId,
            generation = nextGeneration(now),
            status = BackendOwnershipStatus.Claimed.name,
            reconciliation = BackendReconciliationClassification.Pending.name,
            reconciliationMessage = null,
            reconciledAtEpochMs = null,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        if (dao.insertClaim(entity) == -1L) {
            val conflict = dao.findClaimByDestination(destinationKey)
                ?: dao.findClaimByDownload(downloadId)
                ?: error("Destination claim conflicted without a visible owner")
            return OwnershipClaimResult.Conflict(conflict.toModel(dao.findTaskByDownload(conflict.downloadId)?.backendTaskId))
        }
        return OwnershipClaimResult.Claimed(entity.toModel())
    }

    private suspend fun nextGeneration(now: Long): Long {
        dao.seedCounter(OwnershipCounterEntity(GENERATION_COUNTER, now.coerceAtLeast(1)))
        check(dao.incrementCounter(GENERATION_COUNTER) == 1) { "Could not advance ownership generation" }
        return dao.readCounter(GENERATION_COUNTER)
    }

    private suspend fun requireClaim(downloadId: String, generation: Long): DestinationClaimEntity {
        val claim = requireNotNull(dao.findClaimByDownload(downloadId)) { "No ownership for $downloadId" }
        require(claim.generation == generation) { "Stale ownership generation" }
        return claim
    }

    private companion object {
        const val GENERATION_COUNTER = "backend-ownership"
    }
}

private fun DestinationClaimEntity.toTaskEntity(backendTaskId: String, synchronizedAt: Long) = BackendTaskEntity(
    id = "$downloadId:$generation",
    downloadId = downloadId,
    backend = backend,
    backendTaskId = backendTaskId,
    destinationKey = destinationKey,
    partialIdentity = partialIdentity,
    artifactFormat = artifactFormat,
    companionArtifactIdentities = companionArtifactIdentities,
    backendInstanceId = backendInstanceId,
    backendSessionId = backendSessionId,
    ownershipGeneration = generation,
    ownershipStatus = status,
    reconciliation = reconciliation,
    reconciliationMessage = reconciliationMessage,
    reconciledAtEpochMs = reconciledAtEpochMs,
    lastSynchronizedAtEpochMs = synchronizedAt,
)

private fun DestinationClaimEntity.toModel(backendTaskId: String? = null) = BackendOwnership(
    downloadId = downloadId,
    destinationKey = destinationKey,
    artifacts = BackendArtifactIdentity(
        format = artifactFormat,
        primary = partialIdentity,
        companions = companionArtifactIdentities.decodeCompanions(),
    ),
    backend = BackendType.valueOf(backend),
    generation = generation,
    status = BackendOwnershipStatus.valueOf(status),
    runtimeIdentity = BackendRuntimeIdentity(backendInstanceId, backendSessionId),
    backendTaskId = backendTaskId,
    reconciliation = BackendReconciliationClassification.valueOf(reconciliation),
    reconciliationMessage = reconciliationMessage,
    reconciledAtEpochMs = reconciledAtEpochMs,
    claimedAtEpochMs = claimedAtEpochMs,
    synchronizedAtEpochMs = synchronizedAtEpochMs,
)

private fun List<String>.encodeCompanions(): String = joinToString("\n")
private fun String.decodeCompanions(): List<String> = lineSequence().filter(String::isNotBlank).toList()
