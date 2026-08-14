package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase2DownloadExecutionContractTest {
    // Phase 2: Download Execution Correctness
    private val root = androidRoot()

    @Test
    fun runtimeSerializesExecutionAndPersistsControlIntent() {
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        assertTrue(runtime.contains("commandControls = ConcurrentHashMap<String, DownloadCommandControl>()"))
        assertTrue(runtime.contains("val mutex = Mutex()"))
        assertTrue(runtime.contains("AtomicLong"))
        assertTrue(runtime.contains("DesiredTransferState.PauseRequested"))
        assertTrue(runtime.contains("DesiredTransferState.CancelRequested"))
        assertTrue(runtime.contains("ensureExecutionJob(downloadId)"))
        assertTrue(runtime.contains("current.state in TERMINAL_STATES"))
        assertTrue(runtime.contains("current?.state == DownloadState.Failed"))
        assertTrue(runtime.contains("backend.remove(mapping.second)"))
        assertTrue(runtime.contains("generationBeforeVerification"))
    }

    @Test
    fun androidOwnersPauseInsteadOfOrphaningTransfers() {
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val job = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
        val service = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
        assertTrue(worker.contains("if (isStopped) pauseAndRecordStop()"))
        assertTrue(worker.contains("ownedClaims"))
        assertTrue(worker.contains("runtime.pauseOwned(downloadId, queueClaimToken)"))
        assertTrue(worker.contains("attemptGeneration = durableGeneration"))
        assertFalse(worker.contains("else runtime.pauseAll()"))
        assertTrue(job.contains("runtime.requestPauseOwnedAsync(downloadId, queueClaimToken)"))
        assertTrue(job.contains("attemptGeneration = attemptGeneration"))
        assertTrue(service.contains("runtime.summary.value.activeCount == 0"))
        assertTrue(service.contains("ownedClaims"))
        assertTrue(service.contains("runtime.requestPauseOwnedAsync(downloadId, queueClaimToken)"))
        assertFalse(service.contains("runtime.requestPauseAllAsync()"))
        assertTrue(service.contains("runtime.execute(id, queueClaimToken)"))
    }

    @Test
    fun nativeHttpResumeAndRequestsAreByteAccurate() {
        val native = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
        val models = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeTransferModels.kt")
        listOf(
            "newTransferRequestBuilder",
            "isEngineOwnedHeader",
            "If-Range",
            "Remote ETag validator disappeared",
            "Remote redirect target changed",
            "Server no longer supports byte ranges required by the segmented checkpoint",
            "normalizePreviousSegments",
            "trustedLength",
            "rejectUnexpectedHtmlOrCompressedResponse",
            "Content-Encoding",
            "HostRetryBackoff",
            "Retry-After",
            "control.activeCalls.remove(call)",
            "bytesAtAttemptStart",
        ).forEach { expected -> assertTrue("Missing native Phase 2 contract: $expected", native.contains(expected)) }
        assertTrue(models.contains("retryAfterMillis"))
        assertFalse(native.contains("request.headers.forEach { (name, value) -> builder.header(name, value) }"))
    }

    @Test
    fun aria2ReleasesOwnershipOnlyAfterConfirmedStopAndSessionSave() {
        val aria2 = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")
        assertTrue(aria2.contains("rpc.remove(taskId, force = true)"))
        assertTrue(aria2.contains("rpc.saveSession()"))
        assertFalse("Cancel/remove must not swallow failed aria2 removal before deleting ownership.", aria2.contains("if (status?.status !in TERMINAL_RPC_STATES) runCatching { rpc?.remove(taskId, force = true) }"))
        assertTrue(aria2.contains("Recovered aria2 destination key no longer matches the original ownership claim"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
