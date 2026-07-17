package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendMigrationInspection
import com.mikeyphw.xdm.android.model.BackendMigrationReuse
import com.mikeyphw.xdm.android.model.BackendBatteryImpact
import com.mikeyphw.xdm.android.model.BackendDiagnosticDetail
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
    val allowBackendFallback: Boolean = true,
    val isExpiringUrl: Boolean = false,
    val isMediaRequest: Boolean = false,
    val networkMetered: Boolean = false,
    val previousNativeThroughputBytesPerSecond: Long? = null,
    val previousAria2ThroughputBytesPerSecond: Long? = null,
)

data class BackendTask(
    val taskId: String,
    val backend: BackendType,
    /** True when the backend created a durable task in a non-writing state that must be activated after ownership attachment. */
    val requiresActivation: Boolean = false,
)

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
    /** Supplies the durable ownership generation before a suspended backend task may write. */
    suspend fun onOwnershipAttached(taskId: String, ownership: BackendOwnership) = Unit
    /** Called only after the backend task and ownership generation are durably attached. */
    suspend fun activate(taskId: String) { if (query(taskId)?.state == DownloadState.Paused) resume(taskId) }
    suspend fun pause(taskId: String)
    suspend fun resume(taskId: String)
    suspend fun cancel(taskId: String)
    suspend fun remove(taskId: String)
    /** Stops and forgets an in-process task while preserving its backend artifacts for recovery. */
    suspend fun detach(taskId: String): Boolean
    /** Permanently retires backend control of a task while preserving its physical artifacts for migration recovery. */
    suspend fun retireForMigration(taskId: String): Boolean = runCatching {
        cancel(taskId)
        detach(taskId)
    }.getOrDefault(false)
    suspend fun query(taskId: String): BackendSnapshot?
    fun observe(taskId: String): Flow<BackendSnapshot>
    suspend fun reconcile(ownership: BackendOwnership): BackendReconciliationResult
    suspend fun inspectForMigration(ownership: BackendOwnership): BackendMigrationInspection = BackendMigrationInspection(
        backend = ownership.backend,
        bytesPresent = 0,
        expectedLength = null,
        reuse = BackendMigrationReuse.Unsafe,
        remoteValidationRequired = true,
        message = "This backend does not expose a safe migration inspection.",
    )
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
    suspend fun transfer(
        downloadId: String,
        expectedGeneration: Long,
        sourceBackend: BackendType,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        targetBackend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult
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

    override suspend fun transfer(
        downloadId: String,
        expectedGeneration: Long,
        sourceBackend: BackendType,
        destinationKey: String,
        artifacts: BackendArtifactIdentity,
        targetBackend: BackendType,
        runtimeIdentity: BackendRuntimeIdentity,
    ): OwnershipClaimResult = synchronized(this) {
        val existing = byDownload[downloadId] ?: error("No ownership exists for $downloadId")
        val transferable = existing.generation == expectedGeneration &&
            existing.backend == sourceBackend &&
            existing.destinationKey == destinationKey &&
            existing.status in setOf(BackendOwnershipStatus.Reconciled, BackendOwnershipStatus.Quarantined)
        if (!transferable) return@synchronized OwnershipClaimResult.Conflict(existing)
        byDestination[destinationKey]?.let { destinationOwner ->
            if (destinationOwner.downloadId != downloadId) return@synchronized OwnershipClaimResult.Conflict(destinationOwner)
        }
        val now = clock()
        val transferred = existing.copy(
            artifacts = artifacts,
            backend = targetBackend,
            generation = nextGeneration++,
            status = BackendOwnershipStatus.Claimed,
            runtimeIdentity = runtimeIdentity,
            backendTaskId = null,
            reconciliation = BackendReconciliationClassification.Pending,
            reconciliationMessage = "Ownership transferred from $sourceBackend to $targetBackend; target task is not attached yet.",
            reconciledAtEpochMs = null,
            claimedAtEpochMs = now,
            synchronizedAtEpochMs = now,
        )
        put(transferred)
        OwnershipClaimResult.Claimed(transferred)
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

    suspend fun capabilitySnapshot(): Map<BackendType, BackendCapabilities> = byType.mapValues { (_, backend) ->
        runCatching { backend.capabilities() }.getOrElse {
            BackendCapabilities(
                protocols = emptySet(),
                supportsSegmentation = false,
                supportsMirrors = false,
                supportsSelectiveRepair = false,
                supportsSafDestination = false,
                supportsAuthentication = false,
                supportsProxy = false,
                maxConnectionsPerDownload = 0,
                diagnosticDetail = BackendDiagnosticDetail.Basic,
            )
        }
    }
}

private fun DownloadBackend.backendType(): BackendType = when (backendId.lowercase(Locale.ROOT)) {
    "native" -> BackendType.Native
    "aria2" -> BackendType.Aria2
    else -> error("Unknown backend id $backendId")
}

class BackendUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
private class BackendPreparationUnavailableException(message: String, cause: Throwable) : IllegalStateException(message, cause)

class BackendSelectionPolicy {
    fun recommend(request: DownloadRequest): BackendRecommendation = recommend(
        request,
        mapOf(
            BackendType.Native to defaultNativeCapabilities(),
            BackendType.Aria2 to defaultAria2Capabilities(),
        ),
    )

    fun recommend(
        request: DownloadRequest,
        capabilities: Map<BackendType, BackendCapabilities>,
    ): BackendRecommendation = rankedRecommendations(request, capabilities).first()

    fun rankedRecommendations(
        request: DownloadRequest,
        capabilities: Map<BackendType, BackendCapabilities>,
    ): List<BackendRecommendation> {
        val requested = request.preferredBackend
        val factors = mutableListOf<String>()
        val scheme = runCatching { URI(request.sourceUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val destinationScheme = runCatching { URI(request.destinationUri).scheme?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val preferred = when {
            requested != BackendType.Automatic -> requested
            destinationScheme in setOf("content", "xdm") -> {
                factors += "Android document destination"
                BackendType.Native
            }
            request.requireSelectiveRepair -> {
                factors += "Selective repair required"
                BackendType.Native
            }
            request.isMediaRequest || scheme in setOf("m3u8", "mpd") || request.sourceUrl.endsWith(".m3u8", true) || request.sourceUrl.endsWith(".mpd", true) -> {
                factors += "Media playlist workflow"
                BackendType.Native
            }
            request.isExpiringUrl -> {
                factors += "Expiring source URL"
                BackendType.Native
            }
            request.headers.isNotEmpty() -> {
                factors += "Authenticated or browser-captured request"
                BackendType.Native
            }
            scheme in setOf("ftp", "sftp", "magnet") || request.sourceUrl.endsWith(".torrent", true) || request.sourceUrl.endsWith(".meta4", true) || request.sourceUrl.endsWith(".metalink", true) -> {
                factors += "aria2-optimized protocol"
                BackendType.Aria2
            }
            request.mirrors.size > 1 -> {
                factors += "Multiple mirrors"
                BackendType.Aria2
            }
            (request.expectedLength ?: 0L) >= LARGE_FILE_THRESHOLD && !request.networkMetered -> {
                factors += "Large unmetered transfer"
                BackendType.Aria2
            }
            hostHistoryPrefersAria2(request) -> {
                factors += "Previous aria2 performance for this host"
                BackendType.Aria2
            }
            hostHistoryPrefersNative(request) -> {
                factors += "Previous native performance for this host"
                BackendType.Native
            }
            else -> BackendType.Native
        }

        val alternate = if (preferred == BackendType.Native) BackendType.Aria2 else BackendType.Native
        val preferredCapability = capabilities[preferred]
        val alternateCapability = capabilities[alternate]
        val preferredIssue = compatibilityIssue(request, preferredCapability)
        val alternateIssue = compatibilityIssue(request, alternateCapability)
        val preferredCompatible = preferredIssue == null
        val alternateCompatible = alternateIssue == null
        val fallbackAllowed = request.allowBackendFallback && alternateCompatible

        val primary = if (preferredCompatible) {
            recommendationFor(request, preferred, factors, fallbackAllowed.then(alternate))
        } else if (fallbackAllowed) {
            BackendRecommendation(
                backend = alternate,
                reason = if (preferredCapability == null || preferredCapability.protocols.isEmpty()) {
                    BackendSelectionReason.BackendUnavailableFallback
                } else {
                    BackendSelectionReason.BackendIncompatibleFallback
                },
                explanation = "${preferred.displayName()} cannot safely start this transfer, so XDM will use ${alternate.displayName()} before any backend task owns the destination.",
                requestedBackend = requested,
                fallbackBackend = null,
                fallbackAllowed = true,
                factors = factors + "Fallback before task creation",
            )
        } else {
            BackendRecommendation(
                backend = preferred,
                reason = if (preferredCapability == null || preferredCapability.protocols.isEmpty()) {
                    BackendSelectionReason.BackendUnavailable
                } else {
                    BackendSelectionReason.BackendIncompatible
                },
                explanation = "${preferred.displayName()} cannot safely start this transfer: ${preferredIssue}.",
                requestedBackend = requested,
                fallbackBackend = null,
                fallbackAllowed = false,
                factors = factors,
                compatible = false,
                compatibilityIssue = preferredIssue,
            )
        }

        val recommendations = mutableListOf(primary)
        if (primary.backend == preferred && fallbackAllowed) {
            recommendations += BackendRecommendation(
                backend = alternate,
                reason = BackendSelectionReason.BackendUnavailableFallback,
                explanation = "Use ${alternate.displayName()} only if ${preferred.displayName()} is unavailable before a task is created.",
                requestedBackend = requested,
                fallbackAllowed = false,
                factors = factors + "Pre-start fallback only",
            )
        }
        return recommendations.distinctBy(BackendRecommendation::backend)
    }

    fun compatibilityIssue(request: DownloadRequest, capability: BackendCapabilities?): String? {
        capability ?: return "Backend is not registered"
        val scheme = runCatching { URI(request.sourceUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
            ?: return "Source URL has no supported scheme"
        if (scheme !in capability.protocols.map { it.lowercase(Locale.ROOT) }) {
            return if (capability.protocols.isEmpty()) "Backend runtime is unavailable" else "Backend does not support $scheme"
        }
        val destinationScheme = runCatching { URI(request.destinationUri).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (destinationScheme in setOf("content", "xdm") && !capability.supportsSafDestination) return "Backend cannot write Android document destinations"
        if (request.requireSelectiveRepair && !capability.supportsSelectiveRepair) return "Backend does not support selective repair"
        if (request.isMediaRequest && !capability.supportsMediaPlaylists) return "Backend does not support this media workflow"
        if (request.headers.isNotEmpty() && !capability.supportsAuthentication) return "Backend cannot preserve authenticated request headers"
        if (request.isExpiringUrl && !capability.supportsExpiringUrls) return "Backend is unsafe for expiring URLs"
        return null
    }

    fun capabilityRows(capabilities: Map<BackendType, BackendCapabilities>): List<BackendCapabilityRow> =
        listOf(BackendType.Native, BackendType.Aria2).map { type ->
            val capability = capabilities[type]
            BackendCapabilityRow(
                backend = type,
                available = capability != null && capability.protocols.isNotEmpty(),
                protocols = capability?.protocols.orEmpty(),
                segmentation = capability?.supportsSegmentation == true,
                mirrors = capability?.supportsMirrors == true,
                metalink = capability?.supportsMetalink == true,
                proxy = capability?.supportsProxy == true,
                authentication = capability?.supportsAuthentication == true,
                saf = capability?.supportsSafDestination == true,
                selectiveRepair = capability?.supportsSelectiveRepair == true,
                media = capability?.supportsMediaPlaylists == true,
                diagnosticDetail = capability?.diagnosticDetail ?: BackendDiagnosticDetail.Basic,
                batteryImpact = capability?.batteryImpact ?: BackendBatteryImpact.Moderate,
                summary = when {
                    capability == null || capability.protocols.isEmpty() -> "Unavailable in this build or runtime."
                    type == BackendType.Native -> "Android-integrated HTTP engine with SAF, strict resume validation and selective repair."
                    else -> "Embedded multi-protocol engine for mirrors, Metalink and aggressive source splitting."
                },
            )
        }

    private fun recommendationFor(
        request: DownloadRequest,
        backend: BackendType,
        factors: List<String>,
        fallback: BackendType?,
    ): BackendRecommendation {
        val reason = when {
            request.preferredBackend != BackendType.Automatic -> BackendSelectionReason.UserForced
            backend == BackendType.Native && request.destinationUri.substringBefore(':').lowercase() in setOf("content", "xdm") -> BackendSelectionReason.SafRequiresNative
            backend == BackendType.Native && request.requireSelectiveRepair -> BackendSelectionReason.SelectiveRepairRequiresNative
            backend == BackendType.Native && request.isMediaRequest -> BackendSelectionReason.MediaWorkflowRequiresNative
            backend == BackendType.Native && request.isExpiringUrl -> BackendSelectionReason.ExpiringRequestPrefersNative
            backend == BackendType.Native && request.headers.isNotEmpty() -> BackendSelectionReason.AuthenticatedRequestPrefersNative
            backend == BackendType.Aria2 && request.mirrors.size > 1 -> BackendSelectionReason.MirrorWorkloadPrefersAria2
            backend == BackendType.Aria2 && (request.expectedLength ?: 0L) >= LARGE_FILE_THRESHOLD -> BackendSelectionReason.LargeFilePrefersAria2
            backend == BackendType.Aria2 && hostHistoryPrefersAria2(request) -> BackendSelectionReason.HostHistoryPrefersAria2
            backend == BackendType.Native && hostHistoryPrefersNative(request) -> BackendSelectionReason.HostHistoryPrefersNative
            backend == BackendType.Aria2 -> BackendSelectionReason.Aria2OptimizedProtocol
            else -> BackendSelectionReason.DefaultNative
        }
        val explanation = when (reason) {
            BackendSelectionReason.UserForced -> "${backend.displayName()} was selected explicitly for this download."
            BackendSelectionReason.SafRequiresNative -> "Android document destinations require XDM Native's storage bridge."
            BackendSelectionReason.SelectiveRepairRequiresNative -> "Selective range repair requires XDM Native checkpoints."
            BackendSelectionReason.MediaWorkflowRequiresNative -> "HLS, DASH and browser media requests stay in the native diagnostic pipeline."
            BackendSelectionReason.ExpiringRequestPrefersNative -> "The native engine is preferred for expiring URLs and strict request replay."
            BackendSelectionReason.AuthenticatedRequestPrefersNative -> "The native engine preserves captured headers and authenticated request details."
            BackendSelectionReason.MirrorWorkloadPrefersAria2 -> "aria2 can schedule multiple mirrors efficiently."
            BackendSelectionReason.LargeFilePrefersAria2 -> "aria2 is preferred for this large unmetered transfer."
            BackendSelectionReason.HostHistoryPrefersAria2 -> "Recent host performance favors aria2."
            BackendSelectionReason.HostHistoryPrefersNative -> "Recent host performance favors XDM Native."
            BackendSelectionReason.Aria2OptimizedProtocol -> "aria2 has the stronger implementation for this protocol."
            else -> "XDM Native is the safest Android-integrated default."
        }
        return BackendRecommendation(
            backend = backend,
            reason = reason,
            explanation = explanation,
            requestedBackend = request.preferredBackend,
            fallbackBackend = fallback,
            fallbackAllowed = request.allowBackendFallback && fallback != null,
            factors = factors,
        )
    }

    private fun hostHistoryPrefersAria2(request: DownloadRequest): Boolean {
        val native = request.previousNativeThroughputBytesPerSecond ?: return false
        val aria2 = request.previousAria2ThroughputBytesPerSecond ?: return false
        return aria2 > native * 12 / 10
    }

    private fun hostHistoryPrefersNative(request: DownloadRequest): Boolean {
        val native = request.previousNativeThroughputBytesPerSecond ?: return false
        val aria2 = request.previousAria2ThroughputBytesPerSecond ?: return false
        return native > aria2 * 12 / 10
    }

    private fun Boolean.then(type: BackendType): BackendType? = if (this) type else null

    private fun BackendType.displayName(): String = when (this) {
        BackendType.Native -> "XDM Native"
        BackendType.Aria2 -> "aria2"
        BackendType.Automatic -> "Automatic"
    }

    private companion object {
        const val LARGE_FILE_THRESHOLD = 512L * 1024L * 1024L
        fun defaultNativeCapabilities() = BackendCapabilities(
            protocols = setOf("http", "https"),
            supportsSegmentation = true,
            supportsMirrors = false,
            supportsSelectiveRepair = true,
            supportsSafDestination = true,
            supportsAuthentication = true,
            supportsProxy = true,
            maxConnectionsPerDownload = 8,
            supportsExpiringUrls = true,
            supportsMediaPlaylists = true,
            supportsMigrationImport = false,
            batteryImpact = BackendBatteryImpact.Low,
            diagnosticDetail = BackendDiagnosticDetail.Forensic,
        )
        fun defaultAria2Capabilities() = BackendCapabilities(
            protocols = setOf("http", "https", "ftp", "sftp", "magnet"),
            supportsSegmentation = true,
            supportsMirrors = true,
            supportsSelectiveRepair = false,
            supportsSafDestination = false,
            supportsAuthentication = true,
            supportsProxy = true,
            maxConnectionsPerDownload = 16,
            supportsMetalink = true,
            supportsExpiringUrls = false,
            supportsMediaPlaylists = false,
            supportsMigrationImport = false,
            batteryImpact = BackendBatteryImpact.High,
            diagnosticDetail = BackendDiagnosticDetail.Detailed,
        )
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
        val capabilities = registry.capabilitySnapshot()
        val recommendations = selectionPolicy.rankedRecommendations(request, capabilities)
        var lastPreStartFailure: Throwable? = null
        recommendations.forEachIndexed { index, recommendation ->
            val backend = registry.find(recommendation.backend)
            if (backend == null) {
                lastPreStartFailure = BackendUnavailableException("${recommendation.backend} backend is not registered")
                return@forEachIndexed
            }
            try {
                validateCapabilities(request, capabilities[recommendation.backend] ?: backend.capabilities())
            } catch (error: BackendUnavailableException) {
                lastPreStartFailure = error
                if (index == recommendations.lastIndex || !request.allowBackendFallback) throw error
                return@forEachIndexed
            } catch (error: BackendCapabilityException) {
                lastPreStartFailure = error
                if (index == recommendations.lastIndex || !request.allowBackendFallback) throw error
                return@forEachIndexed
            }
            try {
                return addWithBackend(request, backend, recommendation)
            } catch (error: BackendPreparationUnavailableException) {
                lastPreStartFailure = error
                if (index == recommendations.lastIndex || !request.allowBackendFallback) throw error
            }
        }
        throw lastPreStartFailure ?: BackendUnavailableException("No compatible backend is available")
    }

    private suspend fun addWithBackend(
        request: DownloadRequest,
        backend: DownloadBackend,
        recommendation: BackendRecommendation,
    ): CoordinatedBackendTask {
        val selectedRequest = request.copy(preferredBackend = recommendation.backend)
        val preparation = try {
            backend.prepare(selectedRequest)
        } catch (error: BackendUnavailableException) {
            throw BackendPreparationUnavailableException(error.message ?: "Backend preparation is unavailable", error)
        }
        var task: BackendTask? = null
        var ownership: BackendOwnership? = null
        var adopted = false
        try {
            validatePreparation(request, backend, recommendation.backend, preparation)
            val existing = ownershipStore.findByDownload(request.id)
            adopted = existing != null
            val claim = if (existing == null) {
                ownershipStore.claim(request.id, preparation.destinationKey, preparation.artifacts, recommendation.backend, preparation.runtimeIdentity)
            } else {
                ownershipStore.adopt(request.id, existing.generation, preparation.destinationKey, preparation.artifacts, recommendation.backend, preparation.runtimeIdentity)
            }
            val claimedOwnership = when (claim) {
                is OwnershipClaimResult.Claimed -> claim.ownership
                is OwnershipClaimResult.Conflict -> throw DestinationOwnershipConflictException(claim.existing)
            }
            ownership = claimedOwnership
            val startedTask = backend.add(selectedRequest, preparation)
            task = startedTask
            val active = ownershipStore.attachTask(request.id, claimedOwnership.generation, startedTask.taskId)
            backend.onOwnershipAttached(startedTask.taskId, active)
            if (startedTask.requiresActivation) backend.activate(startedTask.taskId)
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
                                classification = if (detached) BackendReconciliationClassification.ResumableArtifact else BackendReconciliationClassification.BackendTaskOrphaned,
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
                                BackendReconciliationClassification.ResumableArtifact,
                                "Backend adoption failed before a new task was started: ${error.message ?: error::class.java.simpleName}",
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

    private fun validatePreparation(request: DownloadRequest, backend: DownloadBackend, backendType: BackendType, preparation: BackendPreparation) {
        require(preparation.downloadId == request.id) { "Backend preparation belongs to a different download" }
        require(preparation.backend == backendType) { "Backend preparation type does not match the selected backend" }
        require(preparation.runtimeIdentity == backend.runtimeIdentity) { "Backend preparation runtime identity is stale" }
        require(preparation.destinationKey.isNotBlank()) { "Backend preparation has no destination identity" }
    }

    private fun validateCapabilities(request: DownloadRequest, capabilities: BackendCapabilities) {
        val sourceScheme = runCatching { URI(request.sourceUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
            ?: throw BackendCapabilityException("Source URL has no supported scheme")
        if (sourceScheme !in capabilities.protocols.map { it.lowercase(Locale.ROOT) }) {
            throw if (capabilities.protocols.isEmpty()) BackendUnavailableException("Selected backend is unavailable")
            else BackendCapabilityException("Selected backend does not support $sourceScheme")
        }
        val destinationScheme = runCatching { URI(request.destinationUri).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (destinationScheme in setOf("content", "xdm") && !capabilities.supportsSafDestination) {
            throw BackendCapabilityException("Selected backend cannot write Android document destinations")
        }
        if (request.requireSelectiveRepair && !capabilities.supportsSelectiveRepair) {
            throw BackendCapabilityException("Selected backend does not support selective repair")
        }
        if (request.isMediaRequest && !capabilities.supportsMediaPlaylists) {
            throw BackendCapabilityException("Selected backend does not support this media workflow")
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
