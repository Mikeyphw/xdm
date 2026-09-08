package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressDl02Dl03ContractTest {
    @Test
    fun nativeCheckpointAndSpeedPipelineIsScalable() {
        val root = androidRoot()
        val native = File(root, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt").readText()
        val integrity = File(root, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeCheckpointIntegrity.kt").readText()
        assertTrue(native.contains("checkpointSaveMutex = Mutex()"))
        assertTrue(native.contains("stateMutex.withLock { segments.map { it.copy() } }"))
        assertTrue(integrity.contains("sha256-blocks-v1"))
        assertTrue(integrity.contains("CachedDigest"))
        assertFalse(native.contains("bytesAtAttemptStart"))
        assertTrue(native.contains("speedMeter.record(totalReceived, clock())"))
    }

    @Test
    fun liveAndDurableProgressAreSeparatedAcrossRuntimeAndUi() {
        val root = androidRoot()
        val runtime = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        assertTrue(runtime.contains("DURABLE_PROGRESS_INTERVAL_MS = 333L"))
        assertTrue(runtime.contains("val liveProgress:"))
        val publishBody = runtime.substringAfter("    private suspend fun publish(")
            .substringBefore("    private suspend fun quarantineInterruptedFinalization")
        assertFalse(publishBody.contains("ownershipStore.findByDownload"))
        assertTrue(runtime.contains("private data class PublicationFence"))
        assertTrue(runtime.contains("durableOwnershipMismatch(publicationFence)"))
        assertTrue(viewModel.contains("semanticDownloads = repository.downloads.distinctUntilChangedBy"))
        assertTrue(viewModel.contains("private val durableUiState"))
        assertTrue(viewModel.contains("liveTransferUi"))
    }

    @Test
    fun verificationInspectionPollingAndOrderingSealsArePresent() {
        val root = androidRoot()
        val verifier = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletionVerificationCoordinator.kt").readText()
        val poller = File(root, "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2EventPoller.kt").readText()
        val screen = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt").readText()
        val workspace = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt").readText()
        val truth = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt").readText()
        assertTrue(verifier.contains("VerificationProgressThrottle()"))
        assertTrue(poller.contains(".buffer(Channel.CONFLATED)"))
        assertTrue(screen.contains("artifactInspectionKeys"))
        assertTrue(screen.contains("resumeInspectionKeys"))
        assertFalse(workspace.contains("thenByDescending { it.updatedAtEpochMs }"))
        assertTrue(truth.contains("fun phaseProgress"))
        assertTrue(truth.contains("bytes verified"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
