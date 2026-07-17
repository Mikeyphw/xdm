package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.RecoveryRecord

interface RecoveryWorkflowStore {
    suspend fun saveRecovery(record: RecoveryRecord)
    suspend fun saveRecovery(records: List<RecoveryRecord>)
    suspend fun listRecovery(): List<RecoveryRecord>
    suspend fun deleteRecovery(id: String)
}

interface FinalizationJournalStore {
    suspend fun save(journal: FinalizationJournal)
    suspend fun find(downloadId: String): FinalizationJournal?
    suspend fun listIncomplete(): List<FinalizationJournal>
    suspend fun delete(downloadId: String)
}

class InMemoryRecoveryWorkflowStore : RecoveryWorkflowStore {
    private val records = linkedMapOf<String, RecoveryRecord>()
    override suspend fun saveRecovery(record: RecoveryRecord) { synchronized(this) { records[record.id] = record } }
    override suspend fun saveRecovery(records: List<RecoveryRecord>) { synchronized(this) { records.forEach { this.records[it.id] = it } } }
    override suspend fun listRecovery(): List<RecoveryRecord> = synchronized(this) { records.values.sortedByDescending { it.createdAtEpochMs } }
    override suspend fun deleteRecovery(id: String) { synchronized(this) { records.remove(id) } }
}

class InMemoryFinalizationJournalStore : FinalizationJournalStore {
    private val journals = linkedMapOf<String, FinalizationJournal>()
    override suspend fun save(journal: FinalizationJournal) { synchronized(this) { journals[journal.downloadId] = journal } }
    override suspend fun find(downloadId: String): FinalizationJournal? = synchronized(this) { journals[downloadId] }
    override suspend fun listIncomplete(): List<FinalizationJournal> = synchronized(this) { journals.values.filter { it.needsRecovery }.sortedBy { it.updatedAtEpochMs } }
    override suspend fun delete(downloadId: String) { synchronized(this) { journals.remove(downloadId) } }
}
