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


    @Test
    fun uiUxPhaseFiveSecondaryRoutesAreOperational() {
        val root = androidRoot()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val repository = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt").readText()
        val dao = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadDao.kt").readText()

        assertTrue("UI contract must define secondary route operational rules", contract.contains("Secondary Route Operational Rules"))
        assertTrue("Queues route must expose creation", screens.contains("Create queue") && appShell.contains("onCreateQueue = viewModel::createQueue"))
        assertTrue("Queues route must expose edit/save", screens.contains("Save queue") && viewModel.contains("fun updateQueue"))
        assertTrue("Queues route must expose enable toggles", screens.contains("onToggleQueue") && viewModel.contains("fun setQueueEnabled"))
        assertTrue("Queues route must expose deletion", screens.contains("Delete queue") && repository.contains("deleteQueue") && dao.contains("DELETE FROM queues"))
        assertTrue("Scheduler route must expose creation", screens.contains("Create schedule") && appShell.contains("onCreateSchedule = viewModel::createSchedule"))
        assertTrue("Scheduler route must expose edit/save", screens.contains("Save schedule") && viewModel.contains("fun updateSchedule"))
        assertTrue("Scheduler route must expose enable toggles", screens.contains("onToggleSchedule") && viewModel.contains("fun setScheduleEnabled"))
        assertTrue("Scheduler route must expose deletion", screens.contains("Delete schedule") && repository.contains("deleteSchedule") && dao.contains("DELETE FROM schedule_rules"))
        assertTrue("Scheduler must show a next-run summary", screens.contains("Next eligible window"))
        assertTrue("Scheduler must edit human-readable conditions", screens.contains("Unmetered network only") && screens.contains("Charging required") && screens.contains("Minimum battery %"))
        assertTrue("Media route must expose a variant selector", screens.contains("Choose variant") && screens.contains("Selected variant") && screens.contains("VariantSelectorRow"))
        assertTrue("Media cards must emphasize origin instead of raw URL", screens.contains("mediaOriginLabel") && !screens.contains("XdmMetadataText(capture.sourceUrl"))
        assertTrue("Recovery route must clarify safe record-only removal", screens.contains("Remove record only") && screens.contains("Technical details"))
        assertTrue("Recovery route must lead with consequence copy", screens.contains("recoveryProblemTitle") && screens.contains("recoveryRecommendedExplanation"))
        assertFalse("Secondary routes must not contain placeholder actions", screens.contains("onClick = {}"))
        assertFalse("Scheduler must not render raw constraints JSON", screens.contains("Text(rule.constraintsJson"))
    }


    @Test
    fun browserAndShareHandoffsOpenDownloadWorkflows() {
        val root = androidRoot()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()

        assertTrue("UI contract must define browser/share handoff rules", contract.contains("Browser and Share Handoff Rules"))
        assertTrue("Manifest must expose ShareSheet text intake", manifest.contains("android.intent.action.SEND") && manifest.contains("android:mimeType=\"text/*\""))
        assertTrue("Manifest must expose typed browser download-manager VIEW intake", manifest.contains("android:mimeType=\"*/*\"") && manifest.contains("android:scheme=\"http\"") && manifest.contains("android:scheme=\"https\""))
        assertTrue("ShareSheet intake must inspect shared text and clip data", activity.contains("sharedText(incoming)") && activity.contains("Intent.EXTRA_TEXT") && activity.contains("clipData"))
        assertTrue("Browser/share non-media links must open Add instead of being rejected", viewModel.contains("openExternalAddDraft") && viewModel.contains("navigate(AppRoute.Add)"))
        assertTrue("External Add drafts must survive into UI state", viewModel.contains("externalAddDraft = addDraft"))
        assertTrue("Add route must prefill external links", screens.contains("initialUrl") && screens.contains("Link received") && screens.contains("LaunchedEffect(externalDraftId)"))
        assertTrue("App shell must pass external drafts to Add", appShell.contains("initialUrl = state.externalAddDraft?.url"))
        assertFalse("Supported non-media links must not be rejected as missing media", viewModel.contains("No supported media URL detected"))
    }


    @Test
    fun launcherIconAndAria2AlignmentContractsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val appBuild = File(root, "app/build.gradle.kts").readText()
        val aria2Build = File(root, "transfer-aria2/build.gradle.kts").readText()
        val verifier = File(root, "tools/verify-aria2-runtime.py").readText()
        val alignmentDoc = File(root, "docs/architecture/ARIA2_RUNTIME_ALIGNMENT.md").readText()
        val resRoot = File(root, "app/src/main/res")
        val launcherPngs = resRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("ic_launcher") && it.extension == "png" }
            .toList()

        assertTrue("Manifest must wire launcher icon", manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue("Manifest must wire round launcher icon", manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        assertTrue("Adaptive launcher icon must be present", File(root, "app/src/main/res/mipmap-anydpi/ic_launcher.xml").isFile)
        assertTrue("Adaptive round launcher icon must be present", File(root, "app/src/main/res/mipmap-anydpi/ic_launcher_round.xml").isFile)
        assertTrue("Launcher foreground vector must be present", File(root, "app/src/main/res/drawable/ic_launcher_foreground.xml").isFile)
        assertTrue("Launcher monochrome vector must be present", File(root, "app/src/main/res/drawable/ic_launcher_monochrome.xml").isFile)
        assertTrue("Adaptive launcher icon must provide themed-icon monochrome layer", File(root, "app/src/main/res/mipmap-anydpi/ic_launcher.xml").readText().contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome"))
        assertTrue("Adaptive round launcher icon must provide themed-icon monochrome layer", File(root, "app/src/main/res/mipmap-anydpi/ic_launcher_round.xml").readText().contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome"))
        assertTrue("Launcher icons should stay vector/adaptive because minSdk is 26", launcherPngs.isEmpty())
        assertTrue("Default app lint must ignore optional upstream aria2 16 KB alignment", appBuild.contains("disable += \"Aligned16KB\""))
        assertTrue("Aria2 module lint must ignore optional upstream aria2 16 KB alignment", aria2Build.contains("disable += \"Aligned16KB\""))
        assertTrue("Strict aria2 builds must require payload and 16 KB alignment", aria2Build.contains("--require-16kb-alignment"))
        assertTrue("Verifier must implement the 16 KB alignment gate", verifier.contains("assert_16kb_alignment") && verifier.contains("PT_LOAD"))
        assertTrue("Alignment documentation must explain strict builds", alignmentDoc.contains("-Pxdm.requireAria2Runtime=true"))
    }


    @Test
    fun termuxCommandRunnerAndOptionalRootFoundationContractsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val models = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt").readText()
        val runner = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxCommandRunner.kt").readText()
        val resultService = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxResultService.kt").readText()
        val templates = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Termux bridge contract is missing", File(root, "docs/architecture/PHASE-7-TERMUX-COMMAND-RUNNER.md").isFile)
        assertTrue("Termux bridge validator is missing", File(root, "tools/validate-termux-bridge.py").isFile)
        assertTrue("Manifest must declare RUN_COMMAND", manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertTrue("Manifest must query Termux", manifest.contains("com.termux") && manifest.contains("com.termux.api"))
        assertTrue("Manifest must register the result service", manifest.contains(".termux.TermuxResultService"))
        assertTrue("Runner must use Termux RUN_COMMAND service", runner.contains("com.termux.app.RunCommandService") && runner.contains("com.termux.RUN_COMMAND"))
        assertTrue("Runner must return results through PendingIntent", runner.contains("RUN_COMMAND_PENDING_INTENT") && resultService.contains("getBundleExtra(\"result\")"))
        assertTrue("Termux commands must be typed", models.contains("sealed class XdmTermuxCommand") && templates.contains("XdmTermuxCommand.ProbeAllTools"))
        assertTrue("Typed commands must cover media tools", templates.contains("yt-dlp") && templates.contains("ffprobe") && templates.contains("ffmpeg"))
        assertTrue("Optional root mode must default to off", models.contains("TermuxRootMode") && models.contains("Off(\"Off\""))
        assertTrue("Root actions must be typed", models.contains("sealed class XdmRootAction"))
        assertTrue("Diagnostics must expose Termux status", screens.contains("Termux bridge") && appShell.contains("viewModel::runTermuxToolProbe"))
        assertTrue("Settings must expose optional root mode", screens.contains("Termux backend") && screens.contains("Optional root mode"))
        assertTrue("ViewModel must carry Termux state", viewModel.contains("termuxBridgeManager.status") && viewModel.contains("setTermuxRootMode"))
        assertTrue("Project manifest must record Termux bridge", manifestJson.contains("termux_bridge") && manifestJson.contains("\"chroot_support\": false"))
        assertFalse("Termux bridge must not add a top-level route", AppRoute.entries.any { it.label == "Termux" || it.label == "Tools" || it.label == "Root" })
    }


    @Test
    fun termuxAria2CockpitContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val models = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt").readText()
        val cockpitModels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxAria2CockpitModels.kt").readText()
        val cockpitManager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxAria2CockpitManager.kt").readText()
        val templates = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()

        assertTrue("Phase 8 contract doc is missing", File(root, "docs/architecture/PHASE-8-TERMUX-ARIA2-COCKPIT.md").isFile)
        assertTrue("UI contract must define Termux aria2 cockpit rules", contract.contains("Phase 8 Termux aria2 Cockpit Rules"))
        assertTrue("Termux aria2 commands must be typed", models.contains("Aria2StartDaemon") && models.contains("Aria2TellActive") && models.contains("Aria2PauseAll"))
        assertTrue("Termux aria2 models must keep local RPC state", cockpitModels.contains("TermuxAria2CockpitStatus") && cockpitModels.contains("redactedSecret"))
        assertTrue("Manager must generate an app-owned secret", cockpitManager.contains("generateSecret") && cockpitManager.contains("SecretKey"))
        assertTrue("Manager must use a non-default local RPC port", cockpitManager.contains("DefaultPort = 16800"))
        assertTrue("Shell templates must start and control the RPC daemon", templates.contains("--enable-rpc=true") && templates.contains("aria2.tellActive") && templates.contains("aria2.saveSession"))
        assertTrue("Diagnostics must expose cockpit controls", screens.contains("Termux aria2 cockpit") && screens.contains("Start daemon") && screens.contains("Pause all"))
        assertTrue("Settings must expose backend enablement", screens.contains("Termux aria2 backend") && screens.contains("Rotate RPC secret"))
        assertTrue("App shell must wire cockpit actions", appShell.contains("viewModel::startTermuxAria2Daemon") && appShell.contains("viewModel::setTermuxAria2Enabled"))
        assertTrue("ViewModel must carry cockpit state", viewModel.contains("termuxAria2CockpitManager.status") && viewModel.contains("rotateTermuxAria2Secret"))
        assertTrue("Project manifest must record the cockpit", manifestJson.contains("termux_aria2_cockpit") && manifestJson.contains("\"root_required\": false"))
        assertFalse("Termux aria2 cockpit must not add a top-level route", AppRoute.entries.any { it.label == "Termux" || it.label == "aria2" || it.label == "Tools" })
    }


    @Test
    fun termuxMediaPipelineContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val models = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt").readText()
        val mediaModels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineModels.kt").readText()
        val mediaManager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt").readText()
        val templates = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()

        assertTrue("Phase 9 contract doc is missing", File(root, "docs/architecture/PHASE-9-TERMUX-MEDIA-CONVERSION-PIPELINE.md").isFile)
        assertTrue("Phase 9 validator is missing", File(root, "tools/validate-termux-media-pipeline.py").isFile)
        assertTrue("UI contract must define Phase 9 media rules", contract.contains("Phase 9 Termux Media Pipeline Rules"))
        assertTrue("Termux media commands must be typed", models.contains("YtDlpDownload") && models.contains("FfmpegConvert"))
        assertTrue("Media pipeline models must track jobs", mediaModels.contains("TermuxMediaPipelineStatus") && mediaModels.contains("TermuxMediaPipelineJob"))
        assertTrue("Media manager must launch typed media tools", mediaManager.contains("extractMetadata") && mediaManager.contains("downloadWithYtDlp") && mediaManager.contains("inspectWithFfprobe"))
        assertTrue("Shell templates must cover yt-dlp, ffprobe, and ffmpeg", templates.contains("yt-dlp --dump-single-json") && templates.contains("ffprobe -hide_banner") && templates.contains("ffmpeg -hide_banner"))
        assertTrue("Media route must expose the Termux media pipeline", screens.contains("Termux media pipeline") && screens.contains("yt-dlp metadata") && screens.contains("Fast-start MP4"))
        assertTrue("App shell must wire media callbacks", appShell.contains("viewModel::extractMediaMetadataWithTermux") && appShell.contains("viewModel::convertMediaWithTermux"))
        assertTrue("ViewModel must carry media pipeline state", viewModel.contains("termuxMediaPipelineManager.status") && viewModel.contains("downloadMediaWithTermuxYtDlp"))
        assertTrue("Project manifest must record the media pipeline", manifestJson.contains("termux_media_pipeline") && manifestJson.contains("\"root_required\": false"))
        assertFalse("Termux media pipeline must not add a top-level route", AppRoute.entries.any { it.label == "Convert" || it.label == "Tools" || it.label == "Termux" })
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
