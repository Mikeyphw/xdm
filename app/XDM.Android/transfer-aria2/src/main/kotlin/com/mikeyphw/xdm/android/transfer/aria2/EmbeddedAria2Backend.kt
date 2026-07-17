package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.BackendPreparation
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.BackendShutdownResult
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.BackendTask
import com.mikeyphw.xdm.android.transfer.DestinationIdentity
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Embedded aria2 runtime foundation.
 *
 * The managed process and authenticated RPC channel are production code, while task creation remains
 * deliberately gated until GID mapping and event reconciliation are implemented.
 */
class EmbeddedAria2Backend(
    private val processManager: Aria2ProcessManager,
    private val sessionStore: Aria2RuntimeFiles,
    override val runtimeIdentity: BackendRuntimeIdentity,
) : DownloadBackend {
    override val backendId: String = "aria2"

    override suspend fun capabilities(): BackendCapabilities {
        val available = processManager.probe().isAvailable
        return BackendCapabilities(
            protocols = if (available) setOf("http", "https", "ftp", "sftp", "magnet") else emptySet(),
            supportsSegmentation = available,
            supportsMirrors = available,
            supportsSelectiveRepair = false,
            supportsSafDestination = false,
            supportsAuthentication = available,
            supportsProxy = available,
            maxConnectionsPerDownload = if (available) 16 else 0,
        )
    }

    override suspend fun prepare(request: DownloadRequest): BackendPreparation {
        val destinationKey = DestinationIdentity.key(request.destinationUri, request.fileName)
        return BackendPreparation(
            preparationId = UUID.randomUUID().toString(),
            downloadId = request.id,
            backend = BackendType.Aria2,
            destinationKey = destinationKey,
            artifacts = sessionStore.artifactsFor(request.id, request.fileName),
            runtimeIdentity = runtimeIdentity,
        )
    }

    override suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask {
        check(preparation.downloadId == request.id) { "aria2 preparation belongs to a different download" }
        val report = processManager.probe()
        check(report.isAvailable) { report.summary }
        throw UnsupportedOperationException(
            "aria2 task creation is disabled until durable GID mapping and event reconciliation are installed.",
        )
    }

    override suspend fun discardPreparation(preparation: BackendPreparation) = Unit
    override suspend fun pause(taskId: String) = unsupportedTaskOperation()
    override suspend fun resume(taskId: String) = unsupportedTaskOperation()
    override suspend fun cancel(taskId: String) = unsupportedTaskOperation()
    override suspend fun remove(taskId: String) = unsupportedTaskOperation()
    override suspend fun detach(taskId: String): Boolean = false
    override suspend fun query(taskId: String): BackendSnapshot? = null
    override fun observe(taskId: String): Flow<BackendSnapshot> = emptyFlow()

    override suspend fun reconcile(ownership: BackendOwnership): BackendReconciliationResult {
        val report = processManager.probe()
        if (!report.isAvailable) {
            return BackendReconciliationResult(
                classification = BackendReconciliationClassification.BackendUnavailable,
                message = report.summary,
                backendTaskId = ownership.backendTaskId,
            )
        }
        val artifacts = ownership.artifacts.all().mapNotNull(::artifactFile)
        val existing = artifacts.filter(File::exists)
        if (ownership.backendTaskId != null) {
            return BackendReconciliationResult(
                classification = BackendReconciliationClassification.BackendTaskOrphaned,
                message = "The aria2 task mapping requires GID reconciliation before it can be adopted.",
                backendTaskId = ownership.backendTaskId,
            )
        }
        return if (existing.isNotEmpty()) {
            BackendReconciliationResult(
                classification = BackendReconciliationClassification.OrphanedArtifact,
                message = "aria2 artifacts were preserved for controlled GID and session reconciliation.",
            )
        } else {
            BackendReconciliationResult(
                classification = BackendReconciliationClassification.MissingArtifact,
                message = "The aria2 ownership record has no matching session or partial artifacts.",
            )
        }
    }

    override suspend fun shutdown(): BackendShutdownResult {
        val stop = processManager.stop()
        return BackendShutdownResult(clean = stop.clean || stop.exitCode == null, activeTaskIds = emptyList())
    }

    private fun unsupportedTaskOperation(): Nothing = throw UnsupportedOperationException(
        "aria2 task controls are disabled until durable GID mapping is installed.",
    )

    private fun artifactFile(identity: String): File? = runCatching {
        val uri = URI(identity)
        if (uri.scheme.equals("file", ignoreCase = true)) File(uri) else null
    }.getOrNull()
}
