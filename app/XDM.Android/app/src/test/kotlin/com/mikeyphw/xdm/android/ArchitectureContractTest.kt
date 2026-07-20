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
        assertTrue("Add button should not require a nonblank filename", screens.contains("val canSubmit = url.isNotBlank() && destinationUri.isNotBlank()"))
        assertTrue("Add button should use the filename-independent submit state", screens.contains("enabled = canSubmit"))
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
        val phaseFourteenVersion = Regex("""versionName = "0\.(\d+)\.0-(?:alpha01|rc01)"""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("Build metadata must be at least 0.14", phaseFourteenVersion != null && phaseFourteenVersion >= 14)
        assertTrue("Diagnostics must expose privacy-safe app integrity summary", screens.contains("App integrity"))
        assertFalse("Diagnostics must not expose release-safety phase copy", screens.contains("Release safety"))
        assertTrue("Diagnostics summary copy must be a real action", screens.contains("copyTextToClipboard"))
    }


    @Test
    fun phaseFifteenUxAccessibilityContractsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val buildGradle = File(root, "app/build.gradle.kts").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        assertTrue("Phase 15 architecture contract is missing", File(root, "docs/architecture/PHASE-15-UX-ACCESSIBILITY-POLISH.md").isFile)
        assertTrue("Phase 15 validator is missing", File(root, "tools/validate-phase-15.py").isFile)
        assertTrue("Manifest must record implemented phase 15", manifest.contains("15"))
        assertTrue("Phase 15 must keep Room schema at v13", manifest.contains("\"schema_version_unchanged\": 13"))
        val phaseFifteenVersion = Regex("""versionName = "0\.(\d+)\.0-(?:alpha01|rc01)"""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("Build metadata must be at least 0.15", phaseFifteenVersion != null && phaseFifteenVersion >= 15)
        assertTrue("Downloads must expose a compact overview card", screens.contains("Download overview"))
        assertTrue("UI must expose accessibility state descriptions", screens.contains("stateDescription") && appShell.contains("stateDescription"))
        assertTrue("Primary actions must keep stable touch targets", screens.contains("sizeIn(minWidth = 48.dp") || screens.contains("sizeIn(minWidth = 96.dp"))
        assertFalse("Settings must not expose implementation phases", screens.contains("Phase 15 polish"))
        assertFalse("Phase 15 must not add a top-level route", AppRoute.entries.any { it.label == "UX" || it.label == "Accessibility" })
        assertTrue("Queues must have a useful empty state", screens.contains("No download queues"))
        assertTrue("Scheduler must have a useful empty state", screens.contains("No schedules"))
        assertFalse("Scheduler must not render raw constraints JSON", screens.contains("Text(rule.constraintsJson"))
    }


    @Test
    fun phaseSixteenPackagingRecoveryReadinessContractsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val buildGradle = File(root, "app/build.gradle.kts").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        assertTrue("Phase 16 architecture contract is missing", File(root, "docs/architecture/PHASE-16-PACKAGING-RECOVERY-READINESS.md").isFile)
        assertTrue("Phase 16 validator is missing", File(root, "tools/validate-phase-16.py").isFile)
        assertTrue("Release readiness model is missing", File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt").isFile)
        assertTrue("Manifest must record implemented phase 16", manifest.contains("16"))
        assertTrue("Phase 16 must keep Room schema at v13", manifest.contains("\"schema_version_unchanged\": 13"))
        assertTrue("Build metadata must be at least 0.16", Regex("""versionName = "0\.(\d+)\.0-(?:alpha01|rc01)"""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 16 } == true)
        assertTrue("Diagnostics must expose user-facing update compatibility", screens.contains("Update compatibility"))
        assertFalse("Diagnostics must not expose install/update implementation wording", screens.contains("Install/update readiness"))
        assertFalse("Settings must not expose packaging milestones", screens.contains("Phase 16 readiness"))
        assertTrue("Clipboard API should use Android ClipboardManager", screens.contains("ClipboardManager") && screens.contains("setPrimaryClip"))
        assertFalse("Deprecated LocalClipboardManager should be removed", screens.contains("LocalClipboardManager"))
        assertTrue("ViewModel must evaluate install/update readiness", viewModel.contains("ReleaseInstallReadinessGate.evaluate"))
        assertFalse("Phase 16 must not add a top-level route", AppRoute.entries.any { it.label == "Packaging" || it.label == "Updates" })
    }



    @Test
    fun phaseSeventeenFinalReleaseGateContractsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val buildGradle = File(root, "app/build.gradle.kts").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val workflow = File(root, ".github/workflows/android.yml").readText()
        assertTrue("Phase 17 architecture contract is missing", File(root, "docs/architecture/PHASE-17-FINAL-RELEASE-GATE.md").isFile)
        assertTrue("Phase 17 validator is missing", File(root, "tools/validate-phase-17.py").isFile)
        assertTrue("Final release model is missing", File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt").isFile)
        assertTrue("Manifest must record implemented phase 17", manifest.contains("17"))
        assertTrue("Phase 17 must keep Room schema at v13", manifest.contains("\"room_schema_locked\": 13"))
        val finalGateVersion = Regex("""versionName\s*=\s*"0\.(\d+)\.0-(?:alpha01|rc\d+)"""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()
        val finalGateVersionCode = Regex("""versionCode\s*(?:=|\.set\()\s*(\d+)""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("Build metadata must stay on a 0.17+ release train", finalGateVersion?.let { it >= 17 } == true)
        assertTrue("Build metadata must advance versionCode", finalGateVersionCode?.let { it >= 18 } == true)
        assertTrue("Diagnostics must expose user-facing release readiness", screens.contains("Release readiness"))
        assertFalse("Settings must not expose release engineering gates", screens.contains("Phase 17 final gate"))
        assertTrue("ViewModel must evaluate final public release readiness", viewModel.contains("FinalPublicReleaseGate.evaluate"))
        assertTrue("CI must run Phase 17 validator", workflow.contains("validate-phase-17.py"))
        assertFalse("Phase 17 must not add a top-level release route", AppRoute.entries.any { it.label == "Release" || it.label == "Final" })
    }


    @Test
    fun postSeventeenDesktopParityContractsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val workflow = File(root, ".github/workflows/android.yml").readText()
        assertTrue("Post-17 parity contract is missing", File(root, "docs/architecture/POST-17-DESKTOP-PARITY.md").isFile)
        assertTrue("Post-17 parity validator is missing", File(root, "tools/validate-post17-desktop-parity.py").isFile)
        assertTrue("Desktop parity model is missing", File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt").isFile)
        assertTrue("Manifest must record desktop parity", manifest.contains("desktop_parity"))
        assertTrue("Desktop parity must keep Room schema at v13", manifest.contains("\"schema_version_unchanged\": 13"))
        assertTrue("Settings must expose import/export", screens.contains("Settings import/export"))
        assertTrue("Downloads must expose history management", screens.contains("History management"))
        assertTrue("Settings must expose proxy credentials", screens.contains("Proxy and credentials"))
        assertTrue("Settings must expose conversion post-processing", screens.contains("Conversion and post-processing"))
        assertTrue("Settings must expose protocol expansion", screens.contains("Protocol expansion"))
        assertFalse("Settings must not expose release packaging internals", screens.contains("Release/non-debug APK packaging"))
        assertFalse("Settings must not expose release packaging sections", screens.contains("Release packaging"))
        assertTrue("ViewModel must import settings snapshots", viewModel.contains("importSettingsSnapshot"))
        assertTrue("CI must run post-17 parity validator", workflow.contains("validate-post17-desktop-parity.py"))
        assertFalse("Post-17 parity must not add a top-level route", AppRoute.entries.any { it.label in setOf("History", "Proxy", "Convert", "Packaging") })
    }


    @Test
    fun uiUxPhaseTwoDesignSystemContractsArePresent() {
        val root = androidRoot()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()
        val mainActivity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val design = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmDesignSystem.kt").readText()
        val labels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmUiLabels.kt").readText()

        assertTrue("UI contract must define visual language rules", contract.contains("Visual Language Rules"))
        assertTrue("Theme must install shared typography", mainActivity.contains("typography = XdmTypography"))
        listOf("XdmTypography", "XdmSpacing", "XdmSectionHeader", "XdmCardTitle", "XdmSupportingText", "XdmMetadataText", "XdmMetricText", "XdmStatusBadge").forEach { primitive ->
            assertTrue("Design system missing $primitive", design.contains(primitive))
        }
        listOf("DownloadState.uiLabel", "BackendType.uiLabel", "ChecksumAlgorithm.uiLabel", "VerificationStatus.uiLabel", "FilenameConflictPolicy.uiLabel", "MediaCaptureStatus.uiLabel", "RecoveryClassification.uiLabel", "BackendMigrationStage.uiLabel").forEach { label ->
            assertTrue("UI label mapping missing $label", labels.contains(label))
        }
        assertTrue("Download cards must use semantic state badges", screens.contains("XdmStatusBadge(download.state.uiLabel(), tone = download.state.statusTone())"))
        assertTrue("Transfer metrics must use the metric text role", screens.contains("XdmMetricText"))
        assertTrue("Filter chips must use readable download state labels", screens.contains("state.uiLabel()"))
        assertTrue("Checksum UI must use readable algorithm labels", screens.contains("checksum.algorithm.uiLabel()"))
        assertTrue("Recovery UI must use readable action labels", screens.contains("record.recommendedAction.uiLabel()"))
        assertFalse("Download filters must not render raw enum names", screens.contains("Text(state.name)"))
        assertFalse("Verification cards must not render raw enum names", screens.contains("Verification: ${'$'}{status.name}"))
        assertFalse("Copied file summaries must not render raw state names", screens.contains("State: ${'$'}{state.name}"))
    }


    @Test
    fun uiUxPhaseThreeAndFourWorkflowContractsArePresent() {
        val root = androidRoot()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()

        assertTrue("UI contract must define Downloads scanability rules", contract.contains("Downloads Scanability Rules"))
        assertTrue("UI contract must define form and settings workflow rules", contract.contains("Form and Settings Workflow Rules"))
        assertTrue("Downloads must keep history tools behind an affordance", screens.contains("History tools"))
        assertTrue("Downloads must support search", screens.contains("Search downloads"))
        assertTrue("Downloads must support sort choices", screens.contains("DownloadSort"))
        assertTrue("Download rows must expose details without always rendering everything", screens.contains("Hide details") && screens.contains("Details"))
        assertTrue("Filtered empty state must explain how to recover", screens.contains("No matching downloads"))
        assertTrue("Add route must fold advanced settings", screens.contains("Advanced download options") && screens.contains("advancedExpanded"))
        assertTrue("Add route must use a persistent bottom action", screens.contains("Ready to add to the default queue") && screens.contains("Start download"))
        assertTrue("Settings must show unsaved draft state", screens.contains("Unsaved") && screens.contains("Saved"))
        assertTrue("Settings must provide reset paths", screens.contains("Reset"))
        assertFalse("Download history must not be a permanent standalone card", screens.contains("private fun HistoryManagementCard"))
        assertFalse("Add route must not use the old queue-specific button copy", screens.contains("Add to Default queue"))
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
