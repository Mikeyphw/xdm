package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.persistence.DownloadRepository

interface TransferDownloadStore {
    suspend fun find(downloadId: String): Download?
    suspend fun findByStates(states: Set<DownloadState>): List<Download>
    suspend fun save(download: Download)
    suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership)
    suspend fun deleteBackendTask(downloadId: String)
}

class RepositoryTransferDownloadStore(private val repository: DownloadRepository) : TransferDownloadStore {
    override suspend fun find(downloadId: String): Download? = repository.findDownload(downloadId)
    override suspend fun findByStates(states: Set<DownloadState>): List<Download> = repository.findDownloadsByStates(states)
    override suspend fun save(download: Download) = repository.save(download)
    override suspend fun saveBackendTask(downloadId: String, backend: BackendType, backendTaskId: String, ownership: BackendOwnership) =
        repository.saveBackendTask(downloadId, backend, backendTaskId, ownership)
    override suspend fun deleteBackendTask(downloadId: String) = repository.deleteBackendTask(downloadId)
}
