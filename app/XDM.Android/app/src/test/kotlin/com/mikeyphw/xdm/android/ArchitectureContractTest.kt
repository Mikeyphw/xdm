package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureContractTest {
    @Test
    fun allPhaseZeroModulesAreRegistered() {
        val root = androidRoot()
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
        assertEquals(listOf("Downloads", "Add", "Queues", "Scheduler", "Media", "Recovery", "Diagnostics", "Settings"), labels)
    }

    @Test
    fun uiUxTopographyContractIsAuthoritative() {
        val root = androidRoot()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md")
        assertTrue("Missing UI/UX topography contract", contract.isFile)
        val text = contract.readText()
        listOf(
            "authoritative",
            "Route Topography",
            "Interaction Rules",
            "Content Rules",
            "Future Phase Rules",
            "Downloads",
            "Add",
            "Queues",
            "Scheduler",
            "Media",
            "Recovery",
            "Diagnostics",
            "Settings",
        ).forEach { required -> assertTrue("Contract missing '$required'", text.contains(required)) }
    }

    @Test
    fun uiSourceHasNoPlaceholderActionsOrRoadmapCopy() {
        val root = androidRoot()
        val sourceFiles = listOf(
            File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt"),
            File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt"),
        )
        sourceFiles.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} contains placeholder click handlers", text.contains("onClick = {}"))
            assertFalse("${file.name} contains milestone copy", text.contains("later milestone", ignoreCase = true))
        }
    }

    @Test
    fun addDownloadAllowsFilenameInference() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        assertTrue("Filename field should describe inference", screens.contains("XDM will infer a name from the URL"))
        assertTrue("Add button should not require a nonblank filename", screens.contains("enabled = url.isNotBlank() && destinationUri.isNotBlank()"))
        assertTrue("ViewModel should centralize inferred filename resolution", viewModel.contains("private fun resolveFileName"))
        assertFalse("ViewModel should not reject blank filename", viewModel.contains("fileName.isBlank()"))
    }

    @Test
    fun phaseTwoAndThreeContractsArePresent() {
        val root = androidRoot()
        assertTrue(File(root, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt").isFile)
        assertTrue(File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt").isFile)
        assertTrue(File(root, "docs/architecture/PHASES-2-3.md").isFile)
    }

    @Test
    fun phaseSevenStrategyAndMigrationRemainInsideExistingTopography() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val migration = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt")
        val contract = File(root, "docs/architecture/PHASE-7-BACKEND-STRATEGY-MIGRATION.md")
        assertTrue("Phase 7 migration coordinator is missing", migration.isFile)
        assertTrue("Phase 7 architecture contract is missing", contract.isFile)
        assertTrue("Settings must expose the backend capability matrix", screens.contains("Backend strategy"))
        assertTrue("Downloads must expose real migration actions", screens.contains("onMigrateBackend"))
        assertFalse("Phase 7 must not add a top-level route", AppRoute.entries.any { it.label == "Backends" || it.label == "Migration" })
    }

    @Test
    fun phaseFourExecutionContractsArePresent() {
        val root = androidRoot()
        assertTrue(File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt").isFile)
        assertTrue(File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt").isFile)
        assertTrue(File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferBootReceiver.kt").isFile)
        assertTrue(File(root, "docs/architecture/PHASE-4.md").isFile)
    }


    @Test
    fun phaseFourteenReleaseSafetyContractsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val buildGradle = File(root, "app/build.gradle.kts").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        assertTrue("Phase 14 architecture contract is missing", File(root, "docs/architecture/PHASE-14-RELEASE-SAFETY.md").isFile)
        assertTrue("Phase 14 validator is missing", File(root, "tools/validate-phase-14.py").isFile)
        assertTrue("Release safety model is missing", File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt").isFile)
        assertTrue("Manifest must record implemented phase 14", manifest.contains("14"))
        assertTrue("Manifest must keep Room schema at v13", manifest.contains("\"version\": 13"))
        assertTrue("Build metadata must advance to 0.14", buildGradle.contains("versionName = \"0.14.0-alpha01\""))
        assertTrue("Diagnostics must expose privacy-safe release summary", screens.contains("Release safety"))
        assertTrue("Diagnostics summary copy must be a real action", screens.contains("clipboard.setText"))
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
