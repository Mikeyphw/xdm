package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Capability placeholder until the embedded aria2 process is implemented in Phase 6. */
class Aria2BackendPlaceholder(
    override val runtimeIdentity: BackendRuntimeIdentity = BackendRuntimeIdentity("aria2-placeholder", UUID.randomUUID().toString()),
) : DownloadBackend {
    override val backendId: String = "aria2"

    override suspend fun capabilities() = BackendCapabilities(
        protocols = setOf("http", "https", "ftp", "sftp", "magnet"),
        supportsSegmentation = true,
        supportsMirrors = true,
        supportsSelectiveRepair = false,
        supportsSafDestination = false,
        supportsAuthentication = true,
        supportsProxy = true,
        maxConnectionsPerDownload = 16,
    )

    override suspend fun prepare(request: DownloadRequest): BackendPreparation {
        val destinationKey = DestinationIdentity.key(request.destinationUri, request.fileName)
        return BackendPreparation(
            preparationId = UUID.randomUUID().toString(),
            downloadId = request.id,
            backend = BackendType.Aria2,
            destinationKey = destinationKey,
            artifacts = BackendArtifactIdentity(
                format = "aria2-planned-v1",
                primary = destinationKey,
                companions = listOf("aria2-control:$destinationKey"),
            ),
            runtimeIdentity = runtimeIdentity,
        )
    }

    override suspend fun add(request: DownloadRequest, preparation: BackendPreparation): BackendTask =
        throw UnsupportedOperationException("The embedded aria2 process is implemented in Phase 6")

    override suspend fun discardPreparation(preparation: BackendPreparation) = Unit
    override suspend fun pause(taskId: String) = Unit
    override suspend fun resume(taskId: String) = Unit
    override suspend fun cancel(taskId: String) = Unit
    override suspend fun remove(taskId: String) = Unit
    override suspend fun detach(taskId: String) = true
    override suspend fun query(taskId: String): BackendSnapshot? = null
    override fun observe(taskId: String): Flow<BackendSnapshot> = emptyFlow()
    override suspend fun reconcile(ownership: BackendOwnership) = BackendReconciliationResult(
        classification = BackendReconciliationClassification.BackendUnavailable,
        message = "The embedded aria2 runtime is not installed yet; the ownership record was preserved.",
    )
    override suspend fun shutdown() = BackendShutdownResult(clean = true, activeTaskIds = emptyList())
}
