package com.mikeyphw.xdm.android.transfer.nativeengine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCheckpointStoreTest {
    @Test
    fun checkpointRoundTripPreservesValidatorsAndSegments() {
        val directory = Files.createTempDirectory("xdm-checkpoint")
        val path = directory.resolve("file.xdm.part.checkpoint.json")
        val original = NativeCheckpoint(
            downloadId = "download-1",
            sourceUrl = "https://example.test/a?name=\"quoted\"",
            effectiveUrl = "https://cdn.example.test/a",
            destinationPath = directory.resolve("a.bin").toString(),
            partialPath = directory.resolve("a.bin.xdm.part").toString(),
            expectedLength = 4096,
            etag = "\"etag-value\"",
            lastModified = "Mon, 13 Jul 2026 12:00:00 GMT",
            rangeSupported = true,
            segments = listOf(
                NativeSegmentCheckpoint(0, 0, 2047, 2048, true),
                NativeSegmentCheckpoint(1, 2048, 4095, 256, false),
            ),
            persistedAtEpochMs = 42,
        )
        val store = NativeCheckpointStore()
        store.save(path, original)
        assertEquals(original, store.load(path))
    }
}
