package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD5TransferNotificationDebuggerTest {
    @Test
    fun transferDebuggerExplainsActiveTransferWithoutLeakingUrls() {
        val download = sampleDownload(
            state = DownloadState.Downloading,
            sourceUrl = "https://cdn.example.test/file.mp4?token=secret-token",
            bytesReceived = 512L * 1024L,
            totalBytes = 1024L * 1024L,
            backend = BackendType.Aria2,
        )
        val report = TransferNotificationDebugReporter.summarize(
            downloads = listOf(download),
            activeSummary = ActiveTransferSummary(
                activeCount = 1,
                bytesReceived = download.bytesReceived,
                totalBytes = download.totalBytes,
                speedBytesPerSecond = 64L * 1024L,
                primaryDownloadId = download.id,
                primaryFileName = download.fileName,
                primaryState = download.state,
            ),
        )

        assertTrue(report.statusLabel == "Active")
        assertTrue(report.rows.any { it.label == "Backend" && it.value == "aria2 engine" })
        assertTrue(report.notificationPathLabel.contains("Active notification"))
        assertTrue(report.boundaryLabel.contains("does not pause, resume, cancel, retry"))
        assertTrue(report.copyText.contains("token=<redacted>"))
        assertFalse(report.copyText.contains("secret-token"))
    }

    @Test
    fun transferDebuggerExplainsCompletedOpenFileFallbackPath() {
        val download = sampleDownload(
            state = DownloadState.Completed,
            destinationUri = "content://downloads/public_downloads/42",
            mimeType = "video/mp4",
        )
        val report = TransferNotificationDebugReporter.summarize(
            downloads = listOf(download),
            activeSummary = ActiveTransferSummary(),
        )

        assertTrue(report.statusLabel == "Completed")
        assertTrue(report.notificationPathLabel.contains("completed-file tap"))
        assertTrue(report.openFilePathLabel.contains("non-exported open-file trampoline"))
        assertTrue(report.timeline.any { it.step == "Open-file" })
        assertTrue(report.copyText.contains("Destination: selected"))
        assertFalse(report.copyText.contains("content://downloads/public_downloads/42"))
    }

    @Test
    fun transferDebuggerLabelsFailuresForCopyOnlyDiagnosis() {
        val download = sampleDownload(
            state = DownloadState.Failed,
            errorMessage = "Network connection reset while reading Authorization: Bearer abcdefghijklmnopqrstuvwxyz",
        )
        val report = TransferNotificationDebugReporter.summarize(listOf(download), ActiveTransferSummary())

        assertTrue(report.statusLabel == "Failed")
        assertTrue(report.rows.any { it.label == "Failure label" && it.value == "network-or-host-failure" })
        assertTrue(report.copyText.contains("Failure label: network-or-host-failure"))
        assertFalse(report.copyText.contains("abcdefghijklmnopqrstuvwxyz"))
    }

    private fun sampleDownload(
        state: DownloadState,
        sourceUrl: String = "https://example.test/download.bin",
        destinationUri: String = "content://downloads/public_downloads/1",
        bytesReceived: Long = 0L,
        totalBytes: Long? = 1024L,
        speedBytesPerSecond: Long = 0L,
        backend: BackendType = BackendType.Native,
        errorMessage: String? = null,
        mimeType: String? = null,
    ): Download = Download(
        id = "download-d5-test",
        fileName = "example-video.mp4",
        sourceUrl = sourceUrl,
        destinationUri = destinationUri,
        state = state,
        backend = backend,
        bytesReceived = bytesReceived,
        totalBytes = totalBytes,
        speedBytesPerSecond = speedBytesPerSecond,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        errorMessage = errorMessage,
        mimeType = mimeType,
        requestedBackend = BackendType.Automatic,
        backendSelectionReason = BackendSelectionReason.DefaultNative,
        completedArtifactUri = destinationUri.takeIf { state == DownloadState.Completed },
        completedArtifactGeneration = 1L.takeIf { state == DownloadState.Completed },
    )
}
