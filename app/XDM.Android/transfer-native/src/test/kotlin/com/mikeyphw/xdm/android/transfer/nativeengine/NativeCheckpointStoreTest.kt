package com.mikeyphw.xdm.android.transfer.nativeengine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCheckpointStoreTest {
    @Test
    fun segmentObjectRegexEscapesClosingBraceForAndroidRuntime() {
        val root = androidRoot()
        val source = File(root, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeCheckpointStore.kt").readText()
        assertTrue(source.contains("Regex(\"\\\\{([^{}]+)\\\\}\")"))
        assertTrue(!source.contains("Regex(\"\\\\{([^{}]+)}\")"))
    }

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
                NativeSegmentCheckpoint(0, 0, 2047, 2048, true, completedSha256 = "a".repeat(64)),
                NativeSegmentCheckpoint(1, 2048, 4095, 256, false, completedSha256 = "b".repeat(64)),
            ),
            persistedAtEpochMs = 42,
            attemptGeneration = 9L,
            backendInstanceId = "install-1",
            backendSessionId = "session-1",
            sourceIdentitySha256 = "c".repeat(64),
            effectiveIdentitySha256 = "d".repeat(64),
            resumeValidatorKind = ResumeValidatorKind.StrongEtag.name,
            resumeValidatorValue = "\"etag-value\"",
        )
        val store = NativeCheckpointStore()
        store.save(path, original)
        assertEquals(original, store.load(path))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "transfer-native/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
