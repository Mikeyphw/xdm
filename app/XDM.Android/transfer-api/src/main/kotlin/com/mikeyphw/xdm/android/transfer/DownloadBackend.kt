package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendRecommendation
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.flow.Flow

data class DownloadRequest(
    val id: String,
    val sourceUrl: String,
    val destinationUri: String,
    val fileName: String,
    val preferredBackend: BackendType = BackendType.Automatic,
    val mirrors: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val expectedLength: Long? = null,
    val expectedEtag: String? = null,
    val expectedLastModified: String? = null,
    val maxConnections: Int = 4,
    val requireSelectiveRepair: Boolean = false,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
    val mimeType: String? = null,
)

data class BackendTask(val taskId: String, val backend: BackendType)

data class BackendSnapshot(
    val taskId: String,
    val state: DownloadState,
    val bytesReceived: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val effectiveUrl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val rangeSupported: Boolean? = null,
    val errorMessage: String? = null,
)

data class BackendShutdownResult(val clean: Boolean, val activeTaskIds: List<String>)

interface DownloadBackend {
    val backendId: String
    suspend fun capabilities(): BackendCapabilities
    suspend fun add(request: DownloadRequest): BackendTask
    suspend fun pause(taskId: String)
    suspend fun resume(taskId: String)
    suspend fun cancel(taskId: String)
    suspend fun remove(taskId: String)
    suspend fun query(taskId: String): BackendSnapshot?
    fun observe(taskId: String): Flow<BackendSnapshot>
    suspend fun shutdown(): BackendShutdownResult
}

sealed interface OwnershipClaimResult {
    data class Claimed(val ownership: BackendOwnership) : OwnershipClaimResult
    data class Conflict(val existing: BackendOwnership) : OwnershipClaimResult
}

interface BackendOwnershipStore {
    suspend fun claim(
        downloadId: String,
        destinationKey: String,
        partialIdentity: String,
        backend: BackendType,
    ): OwnershipClaimResult

    suspend fun attachTask(downloadId: String, generation: Long, backendTaskId: String): BackendOwnership
    suspend fun findByDownload(downloadId: String): BackendOwnership?
    suspend fun findByDestination(destinationKey: String): BackendOwnership?
    suspend fun release(downloadId: String, generation: Long): Boolean
}

class InMemoryBackendOwnershipStore(
    private val clock: () -> Long = System::currentTimeMillis,
) : BackendOwnershipStore {
    private val byDownload = linkedMapOf<String, BackendOwnership>()
    private val byDestination = linkedMapOf<String, BackendOwnership>()
    private var nextGeneration = 1L

    override suspend fun claim(
        downloadId: String,
        destinationKey: String,
        partialIdentity: String,
        backend: BackendType,
    ): OwnershipClaimResult = synchronized(this) {
        byDownload[downloadId]?.let { existing ->
            if (existing.destinationKey == destinationKey && existing.backend == backend) {
                return@synchronized OwnershipClaimResult.Claimed(existing)
            }
            return@synchronized OwnershipClaimResult.Conflict(existing)
        }
        byDestination[destinationKey]?.let { return@synchronized OwnershipClaimResult.Conflict(it) }
        val now = clock()
        val ownership = BackendOwnership(
            downloadId = downloadId,
            destinationKey = destinationKey,
            partialIdentity = partialIdentity,
            backend = backend,
            generation = nextGeneration++,
            status = com.mikeyphw.xdm.android.model.BackendOwnershipStatus.Claimed,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        byDownload[downloadId] = ownership
        byDestination[destinationKey] = ownership
        OwnershipClaimResult.Claimed(ownership)
    }

    override suspend fun attachTask(downloadId: String, generation: Long, backendTaskId: String): BackendOwnership = synchronized(this) {
        val existing = requireNotNull(byDownload[downloadId]) { "No ownership for $downloadId" }
        require(existing.generation == generation) { "Stale ownership generation" }
        val updated = existing.copy(
            status = com.mikeyphw.xdm.android.model.BackendOwnershipStatus.Active,
            backendTaskId = backendTaskId,
            synchronizedAtEpochMs = clock(),
        )
        byDownload[downloadId] = updated
        byDestination[updated.destinationKey] = updated
        updated
    }

    override suspend fun findByDownload(downloadId: String): BackendOwnership? = synchronized(this) { byDownload[downloadId] }
    override suspend fun findByDestination(destinationKey: String): BackendOwnership? = synchronized(this) { byDestination[destinationKey] }

    override suspend fun release(downloadId: String, generation: Long): Boolean = synchronized(this) {
        val existing = byDownload[downloadId] ?: return@synchronized false
        if (existing.generation != generation) return@synchronized false
        byDownload.remove(downloadId)
        byDestination.remove(existing.destinationKey)
        true
    }
}

class BackendRegistry(backends: Collection<DownloadBackend>) {
    private val byType = backends.associateBy { backend ->
        when (backend.backendId.lowercase(Locale.ROOT)) {
            "native" -> BackendType.Native
            "aria2" -> BackendType.Aria2
            else -> error("Unknown backend id ${backend.backendId}")
        }
    }

    fun require(type: BackendType): DownloadBackend = requireNotNull(byType[type]) { "Backend $type is unavailable" }
    fun availableTypes(): Set<BackendType> = byType.keys
}

class BackendSelectionPolicy {
    fun recommend(request: DownloadRequest): BackendRecommendation {
        if (request.preferredBackend != BackendType.Automatic) {
            return BackendRecommendation(request.preferredBackend, BackendSelectionReason.UserForced, "Selected explicitly for this download.")
        }
        val scheme = runCatching { URI(request.sourceUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val destinationScheme = runCatching { URI(request.destinationUri).scheme?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        if (destinationScheme in setOf("content", "xdm")) {
            return BackendRecommendation(BackendType.Native, BackendSelectionReason.SafRequiresNative, "Android document destinations require the native storage bridge.")
        }
        if (request.requireSelectiveRepair) {
            return BackendRecommendation(BackendType.Native, BackendSelectionReason.SelectiveRepairRequiresNative, "Selective range repair requires native checkpoints.")
        }
        if (scheme in setOf("ftp", "sftp", "magnet") || request.sourceUrl.endsWith(".torrent", ignoreCase = true) || request.sourceUrl.endsWith(".meta4", ignoreCase = true)) {
            return BackendRecommendation(BackendType.Aria2, BackendSelectionReason.Aria2OptimizedProtocol, "aria2 has the stronger protocol implementation for this source.")
        }
        if (request.mirrors.size > 1) {
            return BackendRecommendation(BackendType.Aria2, BackendSelectionReason.MirrorWorkloadPrefersAria2, "Multiple mirrors can be scheduled efficiently by aria2.")
        }
        return BackendRecommendation(BackendType.Native, BackendSelectionReason.DefaultNative, "Native HTTP is the safest default for Android-integrated downloads.")
    }
}

data class CoordinatedBackendTask(
    val task: BackendTask,
    val ownership: BackendOwnership,
    val recommendation: BackendRecommendation,
)

class DestinationOwnershipConflictException(val existing: BackendOwnership) : IllegalStateException(
    "Destination is already owned by ${existing.backend} download ${existing.downloadId}",
)

class BackendCapabilityException(message: String) : IllegalArgumentException(message)

class BackendCoordinator(
    private val registry: BackendRegistry,
    private val ownershipStore: BackendOwnershipStore,
    private val selectionPolicy: BackendSelectionPolicy = BackendSelectionPolicy(),
) {
    suspend fun add(request: DownloadRequest): CoordinatedBackendTask {
        val recommendation = selectionPolicy.recommend(request)
        val backend = registry.require(recommendation.backend)
        validateCapabilities(request, backend.capabilities())
        val destinationKey = DestinationIdentity.key(request.destinationUri, request.fileName)
        val partialIdentity = "$destinationKey#${recommendation.backend.name.lowercase(Locale.ROOT)}:${request.id}.partial"
        val claim = ownershipStore.claim(request.id, destinationKey, partialIdentity, recommendation.backend)
        val ownership = when (claim) {
            is OwnershipClaimResult.Claimed -> claim.ownership
            is OwnershipClaimResult.Conflict -> throw DestinationOwnershipConflictException(claim.existing)
        }
        return try {
            val task = backend.add(request.copy(preferredBackend = recommendation.backend))
            val active = ownershipStore.attachTask(request.id, ownership.generation, task.taskId)
            CoordinatedBackendTask(task, active, recommendation)
        } catch (error: Throwable) {
            ownershipStore.release(request.id, ownership.generation)
            throw error
        }
    }

    suspend fun release(downloadId: String): Boolean {
        val ownership = ownershipStore.findByDownload(downloadId) ?: return false
        return ownershipStore.release(downloadId, ownership.generation)
    }

    private fun validateCapabilities(request: DownloadRequest, capabilities: BackendCapabilities) {
        val sourceScheme = runCatching { URI(request.sourceUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
            ?: throw BackendCapabilityException("Source URL has no supported scheme")
        if (sourceScheme !in capabilities.protocols.map { it.lowercase(Locale.ROOT) }) {
            throw BackendCapabilityException("Selected backend does not support $sourceScheme")
        }
        val destinationScheme = runCatching { URI(request.destinationUri).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (destinationScheme in setOf("content", "xdm") && !capabilities.supportsSafDestination) {
            throw BackendCapabilityException("Selected backend cannot write Android document destinations yet")
        }
        if (request.requireSelectiveRepair && !capabilities.supportsSelectiveRepair) {
            throw BackendCapabilityException("Selected backend does not support selective repair")
        }
    }
}

object DestinationIdentity {
    fun key(destinationUri: String, fileName: String? = null): String {
        val trimmed = destinationUri.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val base = when {
            uri == null || uri.scheme == null -> "file:${trimmed.replace('\\', '/')}"
            uri.scheme.equals("file", ignoreCase = true) -> "file:${java.io.File(uri).canonicalPath.replace('\\', '/')}"
            else -> uri.normalize().toString().trimEnd('/')
        }
        val directoryLike = uri?.scheme?.lowercase(Locale.ROOT) in setOf("content", "xdm")
        val child = fileName?.trim()?.takeIf { it.isNotEmpty() && directoryLike }
        return if (child == null) base else "$base/${java.net.URLEncoder.encode(child, Charsets.UTF_8.name()).replace("+", "%20")}" 
    }
}
