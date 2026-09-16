package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPresentationPolicyTest {
    @Test
    fun opaqueUuidNeverBecomesPrimaryDisplayName() {
        val uuid = "e3897970-fe26-460a-8b62-dc2326630204"
        val resolved = DownloadPresentationPolicy.resolvedFileName(
            sourceUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/$uuid",
            requestedName = uuid,
            pageTitle = null,
            mimeType = "application/zip",
        )
        assertEquals("download-release-assets.githubusercontent.com.zip", resolved)
        assertTrue(DownloadPresentationPolicy.isOpaqueIdentifierName(uuid))
        assertFalse(DownloadPresentationPolicy.isOpaqueIdentifierName("xdm-android-v0.21.0.zip"))
    }

    @Test
    fun pageTitleWinsWhenOnlyAvailablePathNameIsOpaque() {
        assertEquals(
            "XDM Android release.apk",
            DownloadPresentationPolicy.resolvedFileName(
                sourceUrl = "https://cdn.example.test/e3897970-fe26-460a-8b62-dc2326630204",
                requestedName = "e3897970-fe26-460a-8b62-dc2326630204",
                pageTitle = "XDM Android release",
                mimeType = "application/vnd.android.package-archive",
            ),
        )
    }

    @Test
    fun publicationFailureIsPresentedAsPostTransferFailure() {
        val item = download(
            state = DownloadState.RecoveryRequired,
            bytesReceived = 128L,
            totalBytes = 128L,
            error = "Final save failed, but the completed staging file is preserved. Retry save after fixing destination access.",
        )
        assertTrue(DownloadPresentationPolicy.isFinalSaveRecovery(item))
        assertTrue(DownloadPresentationPolicy.isFinalizationFailure(item))
        assertEquals("The file was transferred, but XDM couldn't finish saving it.", DownloadPresentationPolicy.finalizationFailureSummary(item))
    }

    @Test
    fun historicalPositiveGenerationFailureIsNotCalledTransferFailure() {
        val item = download(
            state = DownloadState.Failed,
            bytesReceived = 128L,
            totalBytes = 128L,
            error = "Publication generation requires a positive attempt generation",
        )
        assertTrue(DownloadPresentationPolicy.isFinalizationFailure(item))
        assertFalse(DownloadPresentationPolicy.isFinalSaveRecovery(item))
    }

    private fun download(
        state: DownloadState,
        bytesReceived: Long,
        totalBytes: Long?,
        error: String?,
    ) = Download(
        id = "download-id",
        fileName = "file.bin",
        sourceUrl = "https://example.test/file.bin",
        destinationUri = "content://downloads/public_downloads",
        state = state,
        backend = BackendType.Native,
        bytesReceived = bytesReceived,
        totalBytes = totalBytes,
        speedBytesPerSecond = 0L,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        errorMessage = error,
    )
}
