package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendMigrationRecord

interface BackendMigrationStore {
    /** Atomically creates the sole active migration claim for a download. */
    suspend fun tryCreate(record: BackendMigrationRecord): Boolean
    suspend fun save(record: BackendMigrationRecord)
    suspend fun find(id: String): BackendMigrationRecord?
    suspend fun listForDownload(downloadId: String): List<BackendMigrationRecord>
    suspend fun listIncomplete(): List<BackendMigrationRecord>
}

class InMemoryBackendMigrationStore : BackendMigrationStore {
    private val records = linkedMapOf<String, BackendMigrationRecord>()

    override suspend fun tryCreate(record: BackendMigrationRecord): Boolean = synchronized(this) {
        val hasActive = records.values.any { it.downloadId == record.downloadId && it.stage !in TERMINAL_STAGES }
        if (hasActive) false else { records[record.id] = record; true }
    }

    override suspend fun save(record: BackendMigrationRecord) {
        synchronized(this) { records[record.id] = record }
    }

    override suspend fun find(id: String): BackendMigrationRecord? = synchronized(this) { records[id] }

    override suspend fun listForDownload(downloadId: String): List<BackendMigrationRecord> = synchronized(this) {
        records.values.filter { it.downloadId == downloadId }.sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun listIncomplete(): List<BackendMigrationRecord> = synchronized(this) {
        records.values.filter { it.stage !in TERMINAL_STAGES }.sortedBy { it.updatedAtEpochMs }
    }

    private companion object {
        val TERMINAL_STAGES = setOf(
            com.mikeyphw.xdm.android.model.BackendMigrationStage.Completed,
            com.mikeyphw.xdm.android.model.BackendMigrationStage.Failed,
        )
    }
}
