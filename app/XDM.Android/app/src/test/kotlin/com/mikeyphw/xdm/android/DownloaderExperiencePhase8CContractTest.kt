package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.QueueExecutionPolicy
import com.mikeyphw.xdm.android.model.QueueHoldReason
import com.mikeyphw.xdm.android.model.QueueIntelligencePlanner
import com.mikeyphw.xdm.android.model.QueueNetworkRequirement
import com.mikeyphw.xdm.android.model.QueueRuntimeConditions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderExperiencePhase8CContractTest {
    @Test
    fun automaticQueueExecutionUsesAForegroundWorkerOwnedLifetime() {
        val root = androidRoot()
        val worker = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt").readText()
        val coordinator = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt").readText()
        listOf(
            "coordinator.evaluateAndClaim()",
            "setForeground(createForegroundInfo",
            "runtime.execute(download.id)",
            "ExistingWorkPolicy.KEEP",
        ).forEach { assertTrue("Foreground queue worker missing $it", worker.contains(it)) }
        assertTrue(coordinator.contains("suspend fun evaluateAndClaim(): QueueReconcileOutcome"))
        assertTrue(coordinator.contains("The caller owns execution"))
        assertFalse("Policy evaluation must not depend on WebView", worker.contains("android.webkit"))
    }

    @Test
    fun queuePolicyIsExplainableAndSupportsExplicitOverride() {
        val conditions = QueueRuntimeConditions(
            connected = true,
            validated = true,
            unmetered = false,
            wifi = false,
            charging = false,
            batteryPercent = 15,
            availableStorageBytes = 128,
            nowEpochMs = 1_000,
        )
        val policy = QueueExecutionPolicy(
            networkRequirement = QueueNetworkRequirement.Wifi,
            chargingRequired = true,
            minimumBatteryPercent = 40,
            minimumFreeStorageBytes = 1024,
        )
        val held = QueueIntelligencePlanner.decision(policy, conditions)
        assertEquals(QueueHoldReason.WifiRequired, held.reason)
        assertFalse(held.canStart)
        val overridden = QueueIntelligencePlanner.decision(policy, conditions, policyOverride = true)
        assertTrue(overridden.canStart)
        assertTrue(overridden.policyOverridden)
    }

    @Test
    fun appSurfacesPolicyEditingDecisionHistoryAndStartAnyway() {
        val root = androidRoot()
        val screens = UiSourceTree.readAll(root)
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val application = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").readText()
        listOf(
            "QueueNetworkRequirement.entries",
            "QueueRetryStrategy.entries",
            "Maximum automatic retries",
            "Storage reserve (MB)",
            "Start anyway",
            "recentDecisions.take(4)",
        ).forEach { assertTrue("Phase 8C UI missing $it", screens.contains(it)) }
        assertTrue(viewModel.contains("fun startIgnoringQueuePolicy(download: Download)"))
        assertTrue(viewModel.contains("policyOverride = true"))
        assertTrue(application.contains("QueueConditionMonitor"))
        assertTrue(application.contains("QueueIntelligenceWorker.enqueueImmediate"))
    }

    @Test
    fun browserFreeDownloaderBoundaryAndStableStorageRemainSealed() {
        val root = androidRoot()
        val production = sequenceOf(
            File(root, "app/src/main"),
            File(root, "scheduler/src/main"),
            File(root, "core-model/src/main"),
        ).flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .joinToString("\n") { it.readText() }
        assertFalse(production.contains("android.webkit"))
        assertFalse(production.contains("BrowserActivity"))
        val database = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").readText()
        val build = File(root, "app/build.gradle.kts").readText()
        assertTrue(database.contains("version = 14"))
        assertTrue(build.contains("versionCode = 21"))
        assertTrue(build.contains("versionName = \"0.20.0-rc08\""))
        listOf(
            "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
            "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
        ).forEach { assertTrue("Missing preserved downloader component $it", File(root, it).isFile) }
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
