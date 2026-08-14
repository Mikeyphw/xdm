package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.transfer.BackendMigrationStore
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.FinalizationJournalStore
import com.mikeyphw.xdm.android.transfer.RecoveryWorkflowStore
import java.io.File
import java.net.URI

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
        scanPublicationJournals(records)
        scanInterruptedDownloads(records)
        scanOwnership(records)
        scanMigrations(records)
        scanFinalizationJournals(records)
        scanOrphanArtifacts(records)
        val unique = records.values.toList()
        if (unique.isNotEmpty()) recoveryStore.saveRecovery(unique)
        val activeScannerIds = unique.mapTo(linkedSetOf(), RecoveryRecord::id)
        recoveryStore.listRecovery()
            .asSequence()
            .filter { it.id.startsWith(SCANNER_RECORD_PREFIX) && it.id !in activeScannerIds }
            .forEach { recoveryStore.deleteRecovery(it.id) }
        return StartupRecoveryReport(unique.size, unique.groupingBy { it.classification }.eachCount())
    }

    private suspend fun scanInterruptedDownloads(records: MutableMap<String, RecoveryRecord>) {
        val interrupted = downloadStore.findByStates(
            setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing),
        )
        interrupted.forEach { download ->
            val finalization = finalizationStore.find(download.id)
                ?.takeIf { it.attemptGeneration == download.attemptGeneration && it.needsRecovery }
            if (finalization != null) {
                check(downloadStore.save(download.copy(
                    state = DownloadState.RecoveryRequired,
                    speedBytesPerSecond = 0,
                    errorMessage = "Startup found durable finalization evidence that requires recovery before transfer state can change.",
                    updatedAtEpochMs = maxOf(clock(), download.updatedAtEpochMs + 1L),
                ))) { "Startup recovery could not quarantine interrupted finalization for ${download.id}" }
                return@forEach
            }
            val classification = if (download.state == DownloadState.Finalizing) RecoveryClassification.FinalizationInterrupted else RecoveryClassification.NeedsRemoteValidation
            records.putRecord(
                downloadId = download.id,
                artifactPath = download.destinationUri,
                classification = classification,
                reason = "Download was ${download.state.name} when the previous process stopped; it remains paused until the user acts.",
                action = if (classification == RecoveryClassification.FinalizationInterrupted) RecoveryAction.Validate else RecoveryAction.Resume,
                safeToResume = false,
                attemptGeneration = download.attemptGeneration,
            )
            check(downloadStore.save(download.copy(state = DownloadState.RecoveryRequired, speedBytesPerSecond = 0, errorMessage = "Startup recovery requires validation before resuming.", updatedAtEpochMs = maxOf(clock(), download.updatedAtEpochMs + 1L)))) {
                "Startup recovery could not persist ${download.id}; a newer generation or state won the write"
            }
        }
    }

    private suspend fun scanOwnership(records: MutableMap<String, RecoveryRecord>) {
        ownershipStore.listAll().forEach { ownership ->
            val durableFinalization = finalizationStore.find(ownership.downloadId)
                ?.takeIf { journal ->
                    journal.attemptGeneration == ownership.generation &&
                        journal.stage in setOf(FinalizationJournalStage.DestinationCommitted, FinalizationJournalStage.MetadataCommitted)
                }
            if (durableFinalization != null) return@forEach
            val primary = ownership.artifacts.primary
            val file = primary.toFileOrNull()
            when {
                ownership.reconciliation == BackendReconciliationClassification.MissingArtifact -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.MissingPartialFile, ownership.reconciliationMessage ?: "Backend ownership points to a missing artifact.", RecoveryAction.RestartFromZero, attemptGeneration = ownership.generation)
                file != null && !file.exists() -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.MissingPartialFile, "The owned backend artifact no longer exists.", RecoveryAction.RestartFromZero, attemptGeneration = ownership.generation)
                ownership.reconciliation == BackendReconciliationClassification.BackendTaskOrphaned -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.BackendTaskOrphaned, ownership.reconciliationMessage ?: "A backend task exists without a verified active owner.", RecoveryAction.Validate, attemptGeneration = ownership.generation)
                ownership.reconciliation == BackendReconciliationClassification.ResumableArtifact -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.ReadyToResume, ownership.reconciliationMessage ?: "A resumable backend artifact is available.", RecoveryAction.Resume, safeToResume = true, attemptGeneration = ownership.generation)
                ownership.reconciliation == BackendReconciliationClassification.ConflictingArtifact -> records.putRecord(ownership.downloadId, primary, RecoveryClassification.OrphanedArtifact, ownership.reconciliationMessage ?: "Conflicting backend artifacts require review.", RecoveryAction.AdoptOrphan, attemptGeneration = ownership.generation)
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
                attemptGeneration = migration.targetGeneration ?: migration.sourceGeneration,
            )
        }
    }


    /**
     * Bridges the storage writer's fsynced local publication journal into Room when a process dies
     * after provider/filesystem commit but before TransferExecutionRuntime can create its Room
     * finalization journal. The local journal is retained until Room completion becomes terminal.
     */
    private suspend fun scanPublicationJournals(records: MutableMap<String, RecoveryRecord>) {
        val candidates = linkedSetOf<File>()
        ownershipStore.listAll().forEach { ownership ->
            ownership.artifacts.companions.mapNotNull { it.toFileOrNull() }
                .filter { it.name.endsWith(PUBLICATION_JOURNAL_SUFFIX) }
                .forEach { candidates += it.canonicalFile }
        }
        artifactRoots.filter(File::isDirectory).forEach { root ->
            root.walkTopDown().maxDepth(5).filter(File::isFile)
                .filter { it.name.endsWith(PUBLICATION_JOURNAL_SUFFIX) }
                .forEach { candidates += it.canonicalFile }
        }

        candidates.filter(File::isFile).forEach { file ->
            val evidence = parsePublicationJournal(file) ?: run {
                records.putRecord(
                    downloadId = null,
                    artifactPath = file.absolutePath,
                    classification = RecoveryClassification.OrphanedArtifact,
                    reason = "A malformed storage publication journal was found and left untouched for review.",
                    action = RecoveryAction.AdoptOrphan,
                )
                return@forEach
            }
            val current = downloadStore.find(evidence.downloadId)
            if (current == null || current.attemptGeneration != evidence.attemptGeneration) {
                records.putRecord(
                    downloadId = current?.id,
                    artifactPath = evidence.committedUri ?: evidence.stagingPath ?: file.absolutePath,
                    classification = RecoveryClassification.OrphanedArtifact,
                    reason = "Storage publication evidence does not match a current download attempt and was quarantined.",
                    action = RecoveryAction.AdoptOrphan,
                    attemptGeneration = evidence.attemptGeneration,
                )
                return@forEach
            }

            val existing = finalizationStore.find(evidence.downloadId)
            if (existing?.isTerminal == true && existing.attemptGeneration == evidence.attemptGeneration) {
                runCatching { file.delete() }
                return@forEach
            }
            if (existing != null) return@forEach

            val committed = evidence.committedUri?.takeIf(String::isNotBlank)
            val committedFile = committed?.toFileOrNull()?.takeIf(File::isFile)
            val localCommitRecovered = evidence.boundary == "DestinationCommitInProgress" && committedFile != null &&
                (evidence.bytesExpected == null || committedFile.length() == evidence.bytesExpected)
            val commitProven = committed != null && (
                evidence.boundary in setOf("DestinationCommitted", "MetadataReconciled") || localCommitRecovered
            )
            val recoveredBytes = if (commitProven && evidence.bytesCommitted == 0L && committedFile != null) {
                committedFile.length()
            } else {
                evidence.bytesCommitted
            }
            val now = maxOf(clock(), file.lastModified().coerceAtLeast(1L))
            finalizationStore.save(
                FinalizationJournal(
                    id = "finalize-recovered-${evidence.downloadId}-${evidence.attemptGeneration}",
                    downloadId = evidence.downloadId,
                    stage = if (commitProven) FinalizationJournalStage.DestinationCommitted else FinalizationJournalStage.RecoveryRequired,
                    sourcePath = committed ?: evidence.stagingPath ?: evidence.sourcePath,
                    stagingPath = if (commitProven) null else evidence.stagingPath,
                    destinationUri = committed ?: evidence.destinationSpec,
                    bytesExpected = evidence.bytesExpected,
                    bytesPromoted = recoveredBytes,
                    checksumAlgorithm = null,
                    checksumHex = null,
                    message = if (commitProven) {
                        "Recovered a storage publication journal after destination commit but before Room completion metadata."
                    } else {
                        "Recovered an interrupted storage publication journal; destination commit must be reviewed before reuse."
                    },
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    attemptGeneration = evidence.attemptGeneration,
                ),
            )
        }
    }

    private suspend fun scanFinalizationJournals(records: MutableMap<String, RecoveryRecord>) {
        finalizationStore.listIncomplete().forEach { journal ->
            val completed = downloadStore.find(journal.downloadId)?.takeIf { download ->
                download.state == DownloadState.Completed &&
                    download.completedArtifactGeneration == journal.attemptGeneration &&
                    download.completedArtifactUri == journal.destinationUri &&
                    download.completedArtifactBytes != null &&
                    download.completedArtifactBytes == journal.bytesPromoted
            }
            if (completed != null) {
                finalizationStore.save(
                    journal.copy(
                        stage = FinalizationJournalStage.Completed,
                        message = "Startup reconciled the finalization journal from already-durable completed artifact metadata.",
                        updatedAtEpochMs = maxOf(clock(), journal.updatedAtEpochMs + 1L),
                    ),
                )
                return@forEach
            }

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
                attemptGeneration = journal.attemptGeneration,
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
        attemptGeneration: Long = 1L,
    ) {
        val id = "$SCANNER_RECORD_PREFIX${downloadId ?: artifactPath.hashCode()}-${classification.name}"
        put(
            id,
            RecoveryRecord(
                id = id,
                downloadId = downloadId,
                artifactPath = artifactPath,
                classification = classification,
                reason = reason,
                createdAtEpochMs = clock(),
                recommendedAction = action,
                safeToResume = safeToResume,
                attemptGeneration = attemptGeneration,
            ),
        )
    }
    private data class PublicationJournalEvidence(
        val downloadId: String,
        val attemptGeneration: Long,
        val sourcePath: String,
        val stagingPath: String?,
        val destinationSpec: String,
        val committedUri: String?,
        val bytesExpected: Long?,
        val bytesCommitted: Long,
        val boundary: String,
    )

    private fun parsePublicationJournal(file: File): PublicationJournalEvidence? = runCatching {
        val values = file.readLines().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        val downloadId = values.getValue("downloadId").trim().also { require(it.isNotBlank()) }
        val attemptGeneration = values.getValue("attemptGeneration").toLong().also { require(it > 0L) }
        val sourcePath = values["sourcePath"].orEmpty().trim().also { require(it.isNotBlank()) }
        val destinationSpec = values["destinationSpec"].orEmpty().trim().also { require(it.isNotBlank()) }
        PublicationJournalEvidence(
            downloadId = downloadId,
            attemptGeneration = attemptGeneration,
            sourcePath = sourcePath,
            stagingPath = values["stagingPath"]?.trim()?.takeIf(String::isNotBlank),
            destinationSpec = destinationSpec,
            committedUri = values["committedUri"]?.trim()?.takeIf(String::isNotBlank),
            bytesExpected = values["bytesExpected"]?.toLongOrNull()?.takeIf { it >= 0L },
            bytesCommitted = values["bytesCommitted"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            boundary = values["boundary"].orEmpty(),
        )
    }.getOrNull()

    private companion object {
        const val SCANNER_RECORD_PREFIX = "recovery-"
        const val PUBLICATION_JOURNAL_SUFFIX = ".finalization.json"
    }
}

data class StartupRecoveryReport(val recordsCreated: Int, val classifications: Map<RecoveryClassification, Int>)

private fun String.toFileOrNull(): File? = runCatching {
    when {
        startsWith("file:", ignoreCase = true) -> File(URI(this))
        !contains("://") -> File(this)
        else -> null
    }
}.getOrNull()
