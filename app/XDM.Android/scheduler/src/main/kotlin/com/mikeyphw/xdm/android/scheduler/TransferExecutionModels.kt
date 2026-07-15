package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.DownloadState

data class ActiveTransferSummary(
    val activeCount: Int = 0,
    val bytesReceived: Long = 0,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0,
    val primaryDownloadId: String? = null,
    val primaryFileName: String? = null,
    val primaryState: DownloadState? = null,
    val bandwidthProfile: String = "Unrestricted",
) {
    val progressPercent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { ((bytesReceived * 100L) / it).coerceIn(0, 100).toInt() }
}

enum class TransferLaunchMode { UserInitiatedJob, ForegroundService }

data class TransferLaunchResult(val accepted: Boolean, val mode: TransferLaunchMode, val systemId: Int)


data class TransferTerminalEvent(
    val downloadId: String,
    val fileName: String,
    val state: DownloadState,
    val message: String?,
)

internal fun String.stableSystemId(): Int = (hashCode() and 0x3fffffff).coerceAtLeast(1)
