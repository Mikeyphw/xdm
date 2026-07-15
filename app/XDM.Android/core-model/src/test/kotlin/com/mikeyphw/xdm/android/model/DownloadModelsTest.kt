package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadModelsTest {
    @Test
    fun progressIsBounded() {
        val download = Download(
            id = "1",
            fileName = "file.bin",
            sourceUrl = "https://example.test/file.bin",
            destinationUri = "content://downloads/file.bin",
            state = DownloadState.Downloading,
            backend = BackendType.Native,
            bytesReceived = 150,
            totalBytes = 100,
            speedBytesPerSecond = 10,
            queueId = null,
            priority = 0,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )
        assertEquals(1f, download.progressFraction)
    }
}
