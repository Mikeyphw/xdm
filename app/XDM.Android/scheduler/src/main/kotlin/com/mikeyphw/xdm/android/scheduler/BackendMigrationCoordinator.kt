package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackendMigrationReuse
import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.BackendMigrationStore
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendPreparation
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.BackendRegistry
import com.mikeyphw.xdm.android.transfer.BackendSelectionPolicy
import com.mikeyphw.xdm.android.transfer.BackendTask
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.OwnershipClaimResult
import java.util.UUID

sealed interface BackendMigrationOutcome {
    data class Started(val task: BackendTask, val record: BackendMigrationRecord) : BackendMigrationOutcome
    data class Rejected(val record: BackendMigrationRecord) : BackendMigrationOutcome
}

class BackendMigrationCoordinator(
    private val store: TransferDownloadStore,
    private val ownershipStore: BackendOwnershipStore,
    private val migrationStore: BackendMigrationStore,
    private val registry: BackendRegistry,
    private val selectionPolicy: BackendSelectionPolicy = BackendSelectionPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun migrate(downloadId: String, targetBackend: BackendType, restartFromZero: Boolean): BackendMigrationOutcome {
        require(targetBackend != BackendType.Automatic) { "Migration target must be an explicit backend" }
        val download = requireNotNull(store.find(downloadId)) { "Unknown download $downloadId" }
        require(download.backend != targetBackend) { "Download already uses $targetBackend" }
        require(download.state in MIGRATABLE_STATES) { "Active downloads must be paused before migration" }
        val sourceOwnership = requireNotNull(ownershipStore.findByDownload(downloadId)) {
            "No backend ownership exists for $downloadId"
        }
        require(sourceOwnership.backend == download.backend) { "Download and ownership backend disagree" }

        val now = clock()
        var record = BackendMigrationRecord(
            id = UUID.randomUUID().toString(),
            downloadId = downloadId,
            sourceBackend = sourceOwnership.backend,
            targetBackend = targetBackend,
            sourceGeneration = sourceOwnership.generation,
            sourceTaskId = sourceOwnership.backendTaskId,
            stage = BackendMigrationStage.Requested,
            sourceArtifactIdentity = sourceOwnership.artifacts.primary,
            restartFromZero = restartFromZero,
            message = "Migration requested from ${sourceOwnership.backend} to $targetBackend.",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        migrationStore.save(record)

        val request = download.toRequest(targetBackend)
        val capabilities = registry.capabilitySnapshot()
        val compatibilityIssue = selectionPolicy.compatibilityIssue(request, capabilities[targetBackend])
        if (compatibilityIssue != null) {
            record = record.advance(BackendMigrationStage.Failed, "The target backend is unavailable or incompatible: $compatibilityIssue.")
            migrationStore.save(record)
            return BackendMigrationOutcome.Rejected(record)
        }

        val sourceBackend = registry.require(sourceOwnership.backend)
        val target = registry.require(targetBackend)
        val inspection = try {
            sourceOwnership.backendTaskId?.let { taskId -> sourceBackend.pause(taskId) }
            record = record.advance(BackendMigrationStage.SourcePaused, "The source backend is paused and cannot write during migration.")
            migrationStore.save(record)
            sourceBackend.inspectForMigration(sourceOwnership).also { result ->
                record = record.advance(BackendMigrationStage.SourceInspected, result.message)
                migrationStore.save(record)
            }
        } catch (error: Throwable) {
            record = record.advance(
                BackendMigrationStage.RecoveryRequired,
                "The source backend could not be stopped and inspected safely: ${error.message ?: error::class.java.simpleName}",
            )
            migrationStore.save(record)
            store.save(
                download.copy(
                    state = DownloadState.RecoveryRequired,
                    speedBytesPerSecond = 0,
                    errorMessage = record.message,
                    updatedAtEpochMs = clock(),
                ),
            )
            return BackendMigrationOutcome.Rejected(record)
        }
        if (inspection.reuse == BackendMigrationReuse.Complete) {
            record = record.advance(BackendMigrationStage.Failed, "The source output is complete; XDM must verify and finalize it instead of migrating.")
            migrationStore.save(record)
            return BackendMigrationOutcome.Rejected(record)
        }
        if (inspection.bytesPresent > 0 && !restartFromZero) {
            record = record.advance(
                BackendMigrationStage.RecoveryRequired,
                "Partial bytes cannot be silently reinterpreted by another backend. Retry with restart-from-zero to preserve the source artifacts and create a fresh target checkpoint.",
            )
            migrationStore.save(record)
            return BackendMigrationOutcome.Rejected(record)
        }

        var preparation: BackendPreparation? = null
        var sourceRetired = false
        var targetOwnershipGeneration: Long? = null
        var targetTask: BackendTask? = null
        try {
            val prepared = target.prepare(request)
            preparation = prepared
            record = record.copy(
                stage = BackendMigrationStage.TargetPrepared,
                targetArtifactIdentity = prepared.artifacts.primary,
                message = "The target backend prepared a distinct artifact set; the source artifacts remain untouched.",
                updatedAtEpochMs = clock(),
            )
            migrationStore.save(record)

            sourceOwnership.backendTaskId?.let { taskId ->
                val retired = sourceBackend.retireForMigration(taskId)
                check(retired) { "The source backend could not be retired safely" }
                sourceRetired = true
            }
            store.deleteBackendTask(downloadId)
            ownershipStore.recordReconciliation(
                downloadId,
                sourceOwnership.generation,
                BackendReconciliationResult(
                    classification = BackendReconciliationClassification.ResumableArtifact,
                    message = "Source backend control was retired for migration; physical artifacts remain preserved by the migration journal.",
                    safeToResume = true,
                ),
            )

            val transferred = ownershipStore.transfer(
                downloadId = downloadId,
                expectedGeneration = sourceOwnership.generation,
                sourceBackend = sourceOwnership.backend,
                destinationKey = prepared.destinationKey,
                artifacts = prepared.artifacts,
                targetBackend = targetBackend,
                runtimeIdentity = prepared.runtimeIdentity,
            )
            val targetOwnership = when (transferred) {
                is OwnershipClaimResult.Claimed -> transferred.ownership
                is OwnershipClaimResult.Conflict -> error("Ownership transfer conflicted with generation ${transferred.existing.generation}")
            }
            targetOwnershipGeneration = targetOwnership.generation
            record = record.copy(
                stage = BackendMigrationStage.OwnershipTransferred,
                targetGeneration = targetOwnership.generation,
                message = "Destination ownership moved transactionally to $targetBackend generation ${targetOwnership.generation}.",
                updatedAtEpochMs = clock(),
            )
            migrationStore.save(record)

            val started = target.add(request, prepared)
            targetTask = started
            val active = ownershipStore.attachTask(downloadId, targetOwnership.generation, started.taskId)
            target.onOwnershipAttached(started.taskId, active)
            if (started.requiresActivation) target.activate(started.taskId)
            store.saveBackendTask(downloadId, targetBackend, started.taskId, active)
            store.save(
                download.copy(
                    backend = targetBackend,
                    requestedBackend = targetBackend,
                    backendSelectionReason = BackendSelectionReason.MigrationRequested,
                    backendSelectionExplanation = if (inspection.bytesPresent > 0) {
                        "Switched to $targetBackend with a fresh partial file; ${inspection.bytesPresent} source bytes remain preserved for recovery."
                    } else {
                        "Switched to $targetBackend before payload bytes were written."
                    },
                    state = DownloadState.Queued,
                    bytesReceived = 0,
                    speedBytesPerSecond = 0,
                    errorMessage = null,
                    updatedAtEpochMs = clock(),
                ),
            )
            record = record.copy(
                stage = BackendMigrationStage.Completed,
                targetTaskId = started.taskId,
                message = "The target task is durably attached and owns the destination. Source artifacts were never reused silently.",
                updatedAtEpochMs = clock(),
            )
            migrationStore.save(record)
            return BackendMigrationOutcome.Started(started, record)
        } catch (error: Throwable) {
            targetTask?.let { runCatching { target.detach(it.taskId) } }
            targetOwnershipGeneration?.let { generation ->
                runCatching {
                    ownershipStore.recordReconciliation(
                        downloadId,
                        generation,
                        BackendReconciliationResult(
                            classification = BackendReconciliationClassification.ResumableArtifact,
                            message = "Target migration failed after ownership transfer; target artifacts are quarantined for recovery.",
                            safeToResume = true,
                            backendTaskId = targetTask?.taskId,
                        ),
                    )
                }
            }
            val recoveryRequired = sourceRetired || targetOwnershipGeneration != null
            record = record.advance(
                if (recoveryRequired) BackendMigrationStage.RecoveryRequired else BackendMigrationStage.Failed,
                "Migration stopped safely: ${error.message ?: error::class.java.simpleName}",
            )
            migrationStore.save(record)
            val current = store.find(downloadId) ?: download
            store.save(
                current.copy(
                    state = if (recoveryRequired) DownloadState.RecoveryRequired else DownloadState.Paused,
                    speedBytesPerSecond = 0,
                    errorMessage = record.message,
                    updatedAtEpochMs = clock(),
                ),
            )
            return BackendMigrationOutcome.Rejected(record)
        } finally {
            preparation?.let { runCatching { target.discardPreparation(it) } }
        }
    }

    private fun BackendMigrationRecord.advance(stage: BackendMigrationStage, message: String) = copy(
        stage = stage,
        message = message,
        updatedAtEpochMs = clock(),
    )

    private fun Download.toRequest(target: BackendType) = DownloadRequest(
        id = id,
        sourceUrl = sourceUrl,
        destinationUri = destinationUri,
        fileName = fileName,
        preferredBackend = target,
        expectedLength = totalBytes,
        conflictPolicy = conflictPolicy,
        mimeType = mimeType,
        allowBackendFallback = false,
    )

    private companion object {
        val MIGRATABLE_STATES = setOf(
            DownloadState.Created,
            DownloadState.Queued,
            DownloadState.Paused,
            DownloadState.Failed,
            DownloadState.RecoveryRequired,
        )
    }
}
