package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureContractTest {
    @Test
    fun allPhaseZeroModulesAreRegistered() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val settings = File(root, "settings.gradle.kts").readText()
        val modules = listOf(
            "app", "core-model", "core-utils", "persistence", "storage", "transfer-api",
            "transfer-native", "transfer-aria2", "scheduler", "media", "diagnostics",
            "browser-integration", "tasker-plugin", "protocol-test-lab",
        )
        modules.forEach { module -> assertTrue("Missing module $module", settings.contains("\":$module\"")) }
    }

    @Test
    fun allPlannedRoutesArePresent() {
        val labels = AppRoute.entries.map(AppRoute::label)
        assertTrue(labels.containsAll(listOf("Downloads", "Add", "Queues", "Scheduler", "Media", "Recovery", "Diagnostics", "Settings")))
    }

    @Test
    fun phaseTwoAndThreeContractsArePresent() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        assertTrue(File(root, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt").isFile)
        assertTrue(File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt").isFile)
        assertTrue(File(root, "docs/architecture/PHASES-2-3.md").isFile)
    }

    @Test
    fun phaseFourExecutionContractsArePresent() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        assertTrue(File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt").isFile)
        assertTrue(File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt").isFile)
        assertTrue(File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferBootReceiver.kt").isFile)
        assertTrue(File(root, "docs/architecture/PHASE-4.md").isFile)
    }
}
