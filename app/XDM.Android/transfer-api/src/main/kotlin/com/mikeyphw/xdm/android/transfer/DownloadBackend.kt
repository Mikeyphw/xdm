package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendRecommendation
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendSelectionReason
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

data class BackendPreparation(
    val preparationId: String,
    val downloadId: String,
    val backend: BackendType,
    val destinationKey: String,
    val artifacts: BackendArtifactIdentity,
    val runtimeIdentity: BackendRuntimeIdentity,
)

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

data class BackendReconciliationResult(
    val classification: BackendReconciliationClassification,
    val message: String,
    val safeToResume: Boolean = false,
    val backendTaskId: String? = null,
)

interface DownloadBackend {
    val backendId: String
    val runtimeIdentity: BackendRuntimeIdentity
    suspend fun capabilities(): BackendCapabilities
    suspend fun prepare(request: DownloadRequest): BackendPreparation
    suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask
    suspend fun add(request: DownloadRequest): BackendTask {
        val preparation = prepare(request)
        return try {
            add(request, preparation)
        } catch (error: Throwable) {
            discardPreparation(preparation)
            throw error
        }
    }
    suspend fun discardPreparation(preparation: BackendPreparation)
    suspend fun pause(taskId: String)
    suspend fun resume(taskId: String)
    suspend fun cancel(taskId: String)
    suspend fun remove(taskId: String)
    /** Stops and forgets an in-process task while preserving its backend artifacts for recovery. */
    suspend fun detach(taskId: String): Boolean
    suspend fun query(taskId: String): BackendSnapshot?
    fun observe(taskId: String): Flow<BackendSnapshot>
    suspend fun reconcile(ownership: BackendOwnership): BackendReconciliationResult
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
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult

    suspend fun adopt(
        downloadId: String,
        expectedGeneration: Long,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult

    suspend fun attachTask(downloadId: String, generation: Long, backendTaskId: String): BackendOwnership
    suspend fun markReconciling(downloadId: String, generation: Long): BackendOwnership
    suspend fun recordReconciliation(
        downloadId: String,
        generation: Long,
        result: BackendReconciliationResult,
    ): BackendOwnership
    suspend fun findByDownload(downloadId: String): BackendOwnership?
    suspend fun findByDestination(destinationKey: String): BackendOwnership?
    suspend fun listAll(): List<BackendOwnership>
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
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult = synchronized(this) {
        byDownload[downloadId]?.let { existing ->
            val idempotent = existing.destinationKey == destinationKey &&
                existing.backend == backend &&
                existing.artifacts == artifacts &&
                existing.runtimeIdentity == runtimeIdentity &&
                existing.status == BackendOwnershipStatus.Claimed
            return@synchronized if (idempotent) OwnershipClaimResult.Claimed(existing) else OwnershipClaimResult.Conflict(existing)
        }
        byDestination[destinationKey]?.let { return@synchronized OwnershipClaimResult.Conflict(it) }
        val now = clock()
        val ownership = BackendOwnership(
            downloadId = downloadId,
            destinationKey = destinationKey,
            artifacts = artifacts,
            backend = backend,
            generation = nextGeneration++,
            status = BackendOwnershipStatus.Claimed,
            runtimeIdentity = runtimeIdentity,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        put(ownership)
        OwnershipClaimResult.Claimed(ownership)
    }

    override suspend fun adopt(
        downloadId: String,
        expectedGeneration: Long,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult = synchronized(this) {
        val existing = byDownload[downloadId] ?: return@synchronized claimSynchronized(
            downloadId,
            destinationKey,
            artifacts,
            backend,
            runtimeIdentity,
        )
        val adoptable = existing.generation == expectedGeneration &&
            existing.destinationKey == destinationKey &&
            existing.backend == backend &&
            existing.artifacts == artifacts &&
            existing.status == BackendOwnershipStatus.Reconciled &&
            existing.reconciliation == BackendReconciliationClassification.ResumableArtifact
        if (!adoptable) return@synchronized OwnershipClaimResult.Conflict(existing)
        val now = clock()
        val adopted = existing.copy(
            generation = nextGeneration++,
            status = BackendOwnershipStatus.Claimed,
            runtimeIdentity = runtimeIdentity,
            backendTaskId = null,
            reconciliation = BackendReconciliationClassification.Pending,
            reconciliationMessage = null,
            reconciledAtEpochMs = null,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        put(adopted)
        OwnershipClaimResult.Claimed(adopted)
    }

    override suspend fun attachTask(downloadId: String, generation: Long, backendTaskId: String): BackendOwnership = synchronized(this) {
        val existing = requireOwnership(downloadId, generation)
        val updated = existing.copy(
            status = BackendOwnershipStatus.Active,
            backendTaskId = backendTaskId,
            reconciliation = BackendReconciliationClassification.ActiveTaskVerified,
            reconciliationMessage = "Backend task attached to the current ownership generation.",
            reconciledAtEpochMs = clock(),
            synchronizedAtEpochMs = clock(),
        )
        put(updated)
        updated
    }

    override suspend fun markReconciling(downloadId: String, generation: Long): BackendOwnership = synchronized(this) {
        val existing = requireOwnership(downloadId, generation)
        val updated = existing.copy(status = BackendOwnershipStatus.Reconciling, synchronizedAtEpochMs = clock())
        put(updated)
        updated
    }

    override suspend fun recordReconciliation(
        downloadId: String,
        generation: Long,
        result: BackendReconciliationResult,
    ): BackendOwnership = synchronized(this) {
        val existing = requireOwnership(downloadId, generation)
        val now = clock()
        val updated = existing.copy(
            status = statusFor(result),
            backendTaskId = result.backendTaskId ?: existing.backendTaskId,
            reconciliation = result.classification,
            reconciliationMessage = result.message,
            reconciledAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        put(updated)
        updated
    }

    override suspend fun findByDownload(downloadId: String): BackendOwnership? = synchronized(this) { byDownload[downloadId] }
    override suspend fun findByDestination(destinationKey: String): BackendOwnership? = synchronized(this) { byDestination[destinationKey] }
    override suspend fun listAll(): List<BackendOwnership> = synchronized(this) { byDownload.values.toList() }

    override suspend fun release(downloadId: String, generation: Long): Boolean = synchronized(this) {
        val existing = byDownload[downloadId] ?: return@synchronized false
        if (existing.generation != generation) return@synchronized false
        byDownload.remove(downloadId)
        byDestination.remove(existing.destinationKey)
        true
    }

    private fun claimSynchronized(
        downloadId: String,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        backend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult {
        byDestination[destinationKey]?.let { return OwnershipClaimResult.Conflict(it) }
        val now = clock()
        val ownership = BackendOwnership(
            downloadId = downloadId,
            destinationKey = destinationKey,
            artifacts = artifacts,
            backend = backend,
            generation = nextGeneration++,
            status = BackendOwnershipStatus.Claimed,
            runtimeIdentity = runtimeIdentity,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        put(ownership)
        return OwnershipClaimResult.Claimed(ownership)
    }

    private fun requireOwnership(downloadId: String, generation: Long): BackendOwnership {
        val existing = requireNotNull(byDownload[downloadId]) { "No ownership for $downloadId" }
        require(existing.generation == generation) { "Stale ownership generation" }
        return existing
    }

    private fun put(ownership: BackendOwnership) {
        byDownload[ownership.downloadId] = ownership
        byDestination[ownership.destinationKey] = ownership
    }
}

class BackendRegistry(backends: Collection<DownloadBackend>) {
    private val byType = backends.associateBy { backend -> backend.backendType() }

    fun require(type: BackendType): DownloadBackend = requireNotNull(byType[type]) { "Backend $type is unavailable" }
    fun find(type: BackendType): DownloadBackend? = byType[type]
    fun availableTypes(): Set<BackendType> = byType.keys
}

private fun DownloadBackend.backendType(): BackendType = when (backendId.lowercase(Locale.ROOT)) {
    "native" -> BackendType.Native
    "aria2" -> BackendType.Aria2
    else -> error("Unknown backend id $backendId")
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
        val preparation = backend.prepare(request.copy(preferredBackend = recommendation.backend))
        var task: BackendTask? = null
        var ownership: BackendOwnership? = null
        var adopted = false
        try {
            validatePreparation(request, backend, recommendation.backend, preparation)
            val existing = ownershipStore.findByDownload(request.id)
            adopted = existing != null
            val claim = if (existing == null) {
                ownershipStore.claim(
                    request.id,
                    preparation.destinationKey,
                    preparation.artifacts,
                    recommendation.backend,
                    preparation.runtimeIdentity,
                )
            } else {
                ownershipStore.adopt(
                    request.id,
                    existing.generation,
                    preparation.destinationKey,
                    preparation.artifacts,
                    recommendation.backend,
                    preparation.runtimeIdentity,
                )
            }
            val claimedOwnership = when (claim) {
                is OwnershipClaimResult.Claimed -> claim.ownership
                is OwnershipClaimResult.Conflict -> throw DestinationOwnershipConflictException(claim.existing)
            }
            ownership = claimedOwnership
            val startedTask = backend.add(request.copy(preferredBackend = recommendation.backend), preparation)
            task = startedTask
            val active = ownershipStore.attachTask(request.id, claimedOwnership.generation, startedTask.taskId)
            return CoordinatedBackendTask(startedTask, active, recommendation)
        } catch (error: Throwable) {
            val startedTask = task
            val claimedOwnership = ownership
            if (startedTask != null) {
                val detached = runCatching { backend.detach(startedTask.taskId) }.getOrDefault(false)
                if (claimedOwnership != null) {
                    runCatching {
                        ownershipStore.recordReconciliation(
                            request.id,
                            claimedOwnership.generation,
                            BackendReconciliationResult(
                                classification = if (detached) {
                                    BackendReconciliationClassification.ResumableArtifact
                                } else {
                                    BackendReconciliationClassification.BackendTaskOrphaned
                                },
                                message = if (detached) {
                                    "Backend task startup was interrupted before durable attachment; the task was stopped and its artifacts were preserved."
                                } else {
                                    "Backend task startup was interrupted before durable attachment and the task could not be safely detached."
                                },
                                safeToResume = detached,
                                backendTaskId = if (detached) null else startedTask.taskId,
                            ),
                        )
                    }
                }
            } else if (claimedOwnership != null) {
                if (adopted) {
                    runCatching {
                        ownershipStore.recordReconciliation(
                            request.id,
                            claimedOwnership.generation,
                            BackendReconciliationResult(
                                classification = BackendReconciliationClassification.ResumableArtifact,
                                message = "Backend adoption failed before a new task was started: ${error.message ?: error::class.java.simpleName}",
                                safeToResume = true,
                            ),
                        )
                    }
                } else {
                    runCatching { ownershipStore.release(request.id, claimedOwnership.generation) }
                }
            }
            throw error
        } finally {
            runCatching { backend.discardPreparation(preparation) }
        }
    }

    suspend fun release(downloadId: String): Boolean {
        val ownership = ownershipStore.findByDownload(downloadId) ?: return false
        return ownershipStore.release(downloadId, ownership.generation)
    }

    private fun validatePreparation(
        request: DownloadRequest,
        backend: DownloadBackend,
        backendType: BackendType,
        preparation: BackendPreparation,
    ) {
        require(preparation.downloadId == request.id) { "Backend preparation belongs to a different download" }
        require(preparation.backend == backendType) { "Backend preparation type does not match the selected backend" }
        require(preparation.runtimeIdentity == backend.runtimeIdentity) { "Backend preparation runtime identity is stale" }
        require(preparation.destinationKey.isNotBlank()) { "Backend preparation has no destination identity" }
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

class BackendOwnershipReconciler(
    private val registry: BackendRegistry,
    private val ownershipStore: BackendOwnershipStore,
) {
    suspend fun reconcile(downloadId: String): BackendReconciliationResult? {
        val ownership = ownershipStore.findByDownload(downloadId) ?: return null
        ownershipStore.markReconciling(downloadId, ownership.generation)
        val backend = registry.find(ownership.backend)
        val result = if (backend == null) {
            BackendReconciliationResult(
                classification = BackendReconciliationClassification.BackendUnavailable,
                message = "The ${ownership.backend} backend is unavailable; ownership remains quarantined.",
            )
        } else {
            runCatching { backend.reconcile(ownership) }.getOrElse { error ->
                BackendReconciliationResult(
                    classification = BackendReconciliationClassification.BackendUnavailable,
                    message = "Backend reconciliation failed: ${error.message ?: error::class.java.simpleName}",
                )
            }
        }
        ownershipStore.recordReconciliation(downloadId, ownership.generation, result)
        return result
    }

    suspend fun reconcileAll(): List<Pair<BackendOwnership, BackendReconciliationResult>> =
        ownershipStore.listAll().mapNotNull { ownership ->
            reconcile(ownership.downloadId)?.let { result -> ownership to result }
        }
}

private fun statusFor(result: BackendReconciliationResult): BackendOwnershipStatus = when (result.classification) {
    BackendReconciliationClassification.ActiveTaskVerified -> BackendOwnershipStatus.Active
    BackendReconciliationClassification.ResumableArtifact -> BackendOwnershipStatus.Reconciled
    BackendReconciliationClassification.Pending -> BackendOwnershipStatus.Claimed
    else -> BackendOwnershipStatus.Quarantined
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
