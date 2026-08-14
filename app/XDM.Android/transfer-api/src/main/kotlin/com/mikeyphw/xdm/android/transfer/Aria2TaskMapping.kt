package com.mikeyphw.xdm.android.transfer

/** Durable application-owned mapping between an XDM download and an aria2 GID. */
data class Aria2TaskMapping(
    val downloadId: String,
    val gid: String,
    val sourceUrl: String,
    val mirrorUrls: List<String>,
    val destinationUri: String,
    val destinationKey: String,
    val fileName: String,
    val conflictPolicy: String,
    val mimeType: String?,
    val outputPath: String,
    val controlPath: String,
    val ownershipMetadataPath: String,
    val sessionFilePath: String,
    val expectedLength: Long?,
    val ownershipGeneration: Long,
    val backendInstanceId: String,
    val backendSessionId: String,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastSynchronizedAtEpochMs: Long,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
)

interface Aria2TaskMappingStore {
    suspend fun upsert(mapping: Aria2TaskMapping)
    suspend fun findByDownload(downloadId: String): Aria2TaskMapping?
    suspend fun findByGid(gid: String): Aria2TaskMapping?
    suspend fun listAll(): List<Aria2TaskMapping>
    suspend fun deleteByDownload(downloadId: String)
    suspend fun deleteByGid(gid: String)
}

class InMemoryAria2TaskMappingStore : Aria2TaskMappingStore {
    private val mappings = linkedMapOf<String, Aria2TaskMapping>()

    override suspend fun upsert(mapping: Aria2TaskMapping) = synchronized(this) {
        mappings.values.firstOrNull { it.gid == mapping.gid && it.downloadId != mapping.downloadId }?.let {
            error("aria2 GID ${mapping.gid} is already mapped to ${it.downloadId}")
        }
        mappings[mapping.downloadId]?.let { current ->
            require(mapping.ownershipGeneration >= current.ownershipGeneration) { "aria2 mapping cannot regress ownership generation" }
            if (mapping.ownershipGeneration == current.ownershipGeneration) {
                require(mapping.updatedAtEpochMs > current.updatedAtEpochMs || mapping == current) { "aria2 mapping writes must advance monotonically within a generation" }
                require(current.status !in TERMINAL_MAPPING_STATES || mapping.status == current.status) { "aria2 mapping cannot leave a terminal state in the same generation" }
            }
        }
        mappings[mapping.downloadId] = mapping
    }

    override suspend fun findByDownload(downloadId: String): Aria2TaskMapping? = synchronized(this) { mappings[downloadId] }
    override suspend fun findByGid(gid: String): Aria2TaskMapping? = synchronized(this) { mappings.values.firstOrNull { it.gid == gid } }
    override suspend fun listAll(): List<Aria2TaskMapping> = synchronized(this) { mappings.values.toList() }
    override suspend fun deleteByDownload(downloadId: String) { synchronized(this) { mappings.remove(downloadId) } }
    override suspend fun deleteByGid(gid: String) { synchronized(this) { mappings.entries.removeIf { it.value.gid == gid } } }
}


private val TERMINAL_MAPPING_STATES = setOf("Completed", "Removed", "Error", "FinalizationFailed")
