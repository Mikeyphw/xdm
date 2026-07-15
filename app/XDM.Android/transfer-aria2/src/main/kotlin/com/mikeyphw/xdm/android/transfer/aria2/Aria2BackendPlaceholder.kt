package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.transfer.BackendShutdownResult
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.BackendTask
import com.mikeyphw.xdm.android.transfer.DownloadBackend
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Capability placeholder until the embedded aria2 process is implemented in Phase 6. */
class Aria2BackendPlaceholder : DownloadBackend {
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

    override suspend fun add(request: DownloadRequest): BackendTask =
        throw UnsupportedOperationException("The embedded aria2 process is implemented in Phase 6")

    override suspend fun pause(taskId: String) = Unit
    override suspend fun resume(taskId: String) = Unit
    override suspend fun cancel(taskId: String) = Unit
    override suspend fun remove(taskId: String) = Unit
    override suspend fun query(taskId: String): BackendSnapshot? = null
    override fun observe(taskId: String): Flow<BackendSnapshot> = emptyFlow()
    override suspend fun shutdown() = BackendShutdownResult(clean = true, activeTaskIds = emptyList())
}
