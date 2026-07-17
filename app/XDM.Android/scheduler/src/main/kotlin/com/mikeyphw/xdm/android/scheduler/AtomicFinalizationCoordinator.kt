package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
import com.mikeyphw.xdm.android.transfer.FinalizationJournalStore
import java.io.File
import java.util.UUID

class AtomicFinalizationCoordinator(
    private val journalStore: FinalizationJournalStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun prepare(download: Download, source: File, destinationUri: String = download.destinationUri): FinalizationJournal {
        val now = clock()
        val journal = FinalizationJournal(
            id = "finalize-${download.id}-${UUID.randomUUID()}",
            downloadId = download.id,
            stage = FinalizationJournalStage.Prepared,
            sourcePath = source.absolutePath,
            stagingPath = null,
            destinationUri = destinationUri,
            bytesExpected = source.takeIf(File::isFile)?.length(),
            bytesPromoted = 0,
            checksumAlgorithm = null,
            checksumHex = null,
            message = "Prepared finalization journal before destination promotion.",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        journalStore.save(journal)
        return journal
    }

    suspend fun advance(journal: FinalizationJournal, stage: FinalizationJournalStage, message: String, bytesPromoted: Long = journal.bytesPromoted): FinalizationJournal {
        require(stage.ordinal >= journal.stage.ordinal || stage == FinalizationJournalStage.RecoveryRequired) {
            "Finalization journal stages must move forward deterministically"
        }
        val updated = journal.copy(stage = stage, message = message, bytesPromoted = bytesPromoted, updatedAtEpochMs = clock())
        journalStore.save(updated)
        return updated
    }

    suspend fun markVerificationComplete(journal: FinalizationJournal, checksumHex: String? = null): FinalizationJournal =
        advance(journal.copy(checksumHex = checksumHex), FinalizationJournalStage.VerificationComplete, "Verification completed before promotion.")

    suspend fun recordPromotionStarted(journal: FinalizationJournal): FinalizationJournal =
        advance(journal, FinalizationJournalStage.PromotionStarted, "Destination promotion has started.")

    suspend fun recordDestinationStaged(journal: FinalizationJournal, stagingPath: String?, bytesPromoted: Long): FinalizationJournal =
        advance(journal.copy(stagingPath = stagingPath), FinalizationJournalStage.DestinationStaged, "Destination staging is durable.", bytesPromoted)

    suspend fun recordDestinationCommitted(journal: FinalizationJournal, bytesPromoted: Long): FinalizationJournal =
        advance(journal, FinalizationJournalStage.DestinationCommitted, "Destination bytes are committed.", bytesPromoted)

    suspend fun recordMetadataCommitted(journal: FinalizationJournal): FinalizationJournal =
        advance(journal, FinalizationJournalStage.MetadataCommitted, "Room metadata commit is durable.")

    suspend fun complete(journal: FinalizationJournal) {
        val completed = advance(journal, FinalizationJournalStage.Completed, "Finalization completed deterministically.")
        journalStore.save(completed)
    }

    suspend fun recover(journal: FinalizationJournal): FinalizationJournal =
        advance(journal, FinalizationJournalStage.RecoveryRequired, "Startup found an interrupted finalization journal.")

    fun finalizedState(download: Download): Download = download.copy(state = DownloadState.Completed, speedBytesPerSecond = 0, errorMessage = null, updatedAtEpochMs = clock())
}
