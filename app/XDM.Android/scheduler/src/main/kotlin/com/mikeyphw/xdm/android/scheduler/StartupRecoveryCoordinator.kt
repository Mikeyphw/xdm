package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.transfer.BackendMigrationStore
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.FinalizationJournalStore
import com.mikeyphw.xdm.android.transfer.RecoveryWorkflowStore
import java.io.File

class StartupRecoveryCoordinator(
    private val downloadStore: TransferDownloadStore,
    private val ownershipStore: BackendOwnershipStore,
    private val migrationStore: BackendMigrationStore,
    private val finalizationStore: FinalizationJournalStore,
    private val recoveryStore: RecoveryWorkflowStore,
    private val artifactRoots: List<File> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun scan(): StartupRecoveryReport {
        val records = linkedMapOf<String, RecoveryRecord>()
        scanInterruptedDownloads(records)
        scanOwnership(records)
        scanMigrations(records)
        scanFinalizationJournals(records)
        scanOrphanArtifacts(records)
        val unique = records.values.toList()
        if (unique.isNotEmpty()) recoveryStore.saveRecovery(unique)
        return StartupRecoveryReport(unique.size, unique.groupingBy { it.classification }.eachCount())
    }

    private suspend fun scanInterruptedDownloads(records: MutableMap<String, RecoveryRecord>) {
        val interrupted = downloadStore.findByStates(
            setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing),
        )
        interrupted.forEach { download ->
            val classification = if (download.state == DownloadState.Finalizing) RecoveryClassification.FinalizationInterrupted else RecoveryClassification.NeedsRemoteValidation
            records.putRecord(
                downloadId = download.id,
                artifactPath = download.destinationUri,
                classification = classification,
                reason = "Download was ${download.state.name} when the previous process stopped; it remains paused until the user acts.",
                action = if (classification == RecoveryClassification.FinalizationInterrupted) RecoveryAction.Validate else RecoveryAction.Resume,
                safeToResume = false,
            )
            downloadStore.save(download.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = "Startup recovery requires validation before resuming.", updatedAtEpochMs = clock()))
        }
    }

    private suspend fun scanOwnership(records: MutableMap<String, RecoveryRecord>) {
        ownershipStore.listAll().forEach { ownership ->
            val primary = ownership.artifacts.primary
            val file = primary.toFileOrNull()
            when {
                ownership.reconciliation == BackendReconciliationClassification.MissingArtifact -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.MissingPartialFile, ownership.reconciliationMessage ?: "Backend ownership points to a missing artifact.", RecoveryAction.RestartFromZero)
                file != null && !file.exists() -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.MissingPartialFile, "The owned backend artifact no longer exists.", RecoveryAction.RestartFromZero)
                ownership.reconciliation == BackendReconciliationClassification.BackendTaskOrphaned -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.BackendTaskOrphaned, ownership.reconciliationMessage ?: "A backend task exists without a verified active owner.", RecoveryAction.Validate)
                ownership.reconciliation == BackendReconciliationClassification.ResumableArtifact -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.ReadyToResume, ownership.reconciliationMessage ?: "A resumable backend artifact is available.", RecoveryAction.Resume, safeToResume = true)
                ownership.reconciliation == BackendReconciliationClassification.ConflictingArtifact -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.OrphanedArtifact, ownership.reconciliationMessage ?: "Conflicting backend artifacts require review.", RecoveryAction.AdoptOrphan)
            }
        }
    }

    private suspend fun scanMigrations(records: MutableMap<String, RecoveryRecord>) {
        migrationStore.listIncomplete().forEach { migration ->
            val classification = if (migration.stage == BackendMigrationStage.RecoveryRequired) RecoveryClassification.NeedsRepair else RecoveryClassification.BackendTaskOrphaned
            records.putRecord(
                downloadId = migration.downloadId,
                artifactPath = migration.targetArtifactIdentity ?: migration.sourceArtifactIdentity,
                classification = classification,
                reason = "Backend migration stopped at ${migration.stage.name}: ${migration.message}",
                action = RecoveryAction.Validate,
            )
        }
    }

    private suspend fun scanFinalizationJournals(records: MutableMap<String, RecoveryRecord>) {
        finalizationStore.listIncomplete().forEach { journal ->
            val classification = when (journal.stage) {
                FinalizationJournalStage.DestinationCommitted, FinalizationJournalStage.MetadataCommitted -> RecoveryClassification.CompletionRecovered
                else -> RecoveryClassification.FinalizationInterrupted
            }
            records.putRecord(
                downloadId = journal.downloadId,
                artifactPath = journal.stagingPath ?: journal.sourcePath,
                classification = classification,
                reason = "Finalization stopped at ${journal.stage.name}: ${journal.message}",
                action = if (classification == RecoveryClassification.CompletionRecovered) RecoveryAction.Validate else RecoveryAction.VerifyAndRepair,
            )
        }
    }

    private fun scanOrphanArtifacts(records: MutableMap<String, RecoveryRecord>) {
        artifactRoots.filter(File::isDirectory).flatMap { root -> root.walkTopDown().maxDepth(3).filter(File::isFile).toList() }.forEach { file ->
            val name = file.name
            if (name.endsWith(".xdm.part") || name.endsWith(".xdm.checkpoint.json") || name.endsWith(".aria2")) {
                records.putRecord(null, file.absolutePath, RecoveryClassification.OrphanedArtifact, "Startup found an orphaned transfer artifact in app-private storage.", RecoveryAction.AdoptOrphan)
            }
        }
    }

    private fun MutableMap<String, RecoveryRecord>.putRecord(
        downloadId: String?,
        artifactPath: String,
        classification: RecoveryClassification,
        reason: String,
        action: RecoveryAction,
        safeToResume: Boolean = false,
    ) {
        val id = "recovery-${downloadId ?: artifactPath.hashCode()}-${classification.name}"
        put(id, RecoveryRecord(id, downloadId, artifactPath, classification, reason, clock(), action, safeToResume))
    }
}

data class StartupRecoveryReport(val recordsCreated: Int, val classifications: Map<RecoveryClassification, Int>)

private fun String.toFileOrNull(): File? = runCatching { File(this.removePrefix("file://")) }.getOrNull()
