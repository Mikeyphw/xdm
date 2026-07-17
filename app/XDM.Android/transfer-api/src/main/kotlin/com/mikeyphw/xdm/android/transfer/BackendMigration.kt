package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendMigrationRecord

interface BackendMigrationStore {
    suspend fun save(record: BackendMigrationRecord)
    suspend fun find(id: String): BackendMigrationRecord?
    suspend fun listForDownload(downloadId: String): List<BackendMigrationRecord>
    suspend fun listIncomplete(): List<BackendMigrationRecord>
}

class InMemoryBackendMigrationStore : BackendMigrationStore {
    private val records = linkedMapOf<String, BackendMigrationRecord>()

    override suspend fun save(record: BackendMigrationRecord) {
        synchronized(this) { records[record.id] = record }
    }

    override suspend fun find(id: String): BackendMigrationRecord? = synchronized(this) { records[id] }

    override suspend fun listForDownload(downloadId: String): List<BackendMigrationRecord> = synchronized(this) {
        records.values.filter { it.downloadId == downloadId }.sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun listIncomplete(): List<BackendMigrationRecord> = synchronized(this) {
        records.values.filter { it.stage.name !in setOf("Completed", "Failed") }.sortedBy { it.updatedAtEpochMs }
    }
}
