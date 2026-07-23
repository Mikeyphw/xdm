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
        assertTrue("Manifest must record Room schema v14", manifest.contains("\"version\": 14"))
        val phaseFourteenVersion = Regex("""versionName = "0\.(\d+)\.0-(?:alpha01|rc\d+)"""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()
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
        assertTrue("Phase 15 baseline must be carried forward on Room schema v14", manifest.contains("\"schema_version_unchanged\": 14"))
        val phaseFifteenVersion = Regex("""versionName = "0\.(\d+)\.0-(?:alpha01|rc\d+)"""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()
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
        assertTrue("Phase 16 baseline must be carried forward on Room schema v14", manifest.contains("\"schema_version_unchanged\": 14"))
        assertTrue("Build metadata must be at least 0.16", Regex("""versionName = "0\.(\d+)\.0-(?:alpha01|rc\d+)"""").find(buildGradle)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 16 } == true)
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
        assertTrue("Phase 17 baseline must be carried forward on Room schema v14", manifest.contains("\"room_schema_locked\": 14"))
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
        assertTrue("Desktop parity must be carried forward on Room schema v14", manifest.contains("\"schema_version_unchanged\": 14"))
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
    fun phasesTwelveToFourteenPowerToolsArePresent() {
        val root = androidRoot()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val models = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt").readText()
        val desktopModels = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt").readText()
        val database = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").readText()
        val migrations = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt").readText()
        val repository = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()

        assertTrue("Manifest must record organization power tools", manifest.contains("organization_history_power_tools"))
        assertTrue("Manifest must record browser clipboard inbox", manifest.contains("browser_clipboard_inbox"))
        assertTrue("Manifest must record backup restore hardening", manifest.contains("backup_restore_hardening"))
        assertTrue("Room schema must advance to v14", database.contains("version = 14") && migrations.contains("Migration13To14"))
        listOf("SavedSearch", "DuplicateUrlRule", "DestinationRule", "ClipboardInboxItem", "archived").forEach { symbol ->
            assertTrue("Download model missing $symbol", models.contains(symbol) || desktopModels.contains(symbol))
        }
        listOf("saved_searches", "duplicate_url_rules", "destination_rules", "clipboard_inbox").forEach { table ->
            assertTrue("Repository persistence missing $table", repository.contains(table) || migrations.contains(table))
        }
        assertTrue("Downloads must expose organization tools", screens.contains("Organization and history tools") && screens.contains("Archive selected") && screens.contains("Save search"))
        assertTrue("Diagnostics must expose browser integration and clipboard inbox", screens.contains("Browser integration and clipboard inbox") && screens.contains("Scan clipboard"))
        assertTrue("Settings must expose destination and duplicate rules", screens.contains("Destination rules") && screens.contains("Duplicate URL rules"))
        assertTrue("Settings must expose backup hardening", screens.contains("Backup ready") || screens.contains("backupRestoreReport"))
        assertTrue("App shell must wire Phase 12-14 actions", appShell.contains("viewModel::archiveDownloads") && appShell.contains("viewModel::scanClipboardText") && appShell.contains("viewModel::saveDestinationRule"))
        assertTrue("ViewModel must evaluate organization, browser, and backup reports", viewModel.contains("OrganizationPowerTools.summarize") && viewModel.contains("BrowserIntegrationStatus") && viewModel.contains("BackupRestorePolicy.evaluate"))
        assertFalse("Phases 12-14 must not add new top-level routes", AppRoute.entries.any { it.label in setOf("History", "Browser", "Clipboard", "Backup", "Updater") })
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


    @Test
    fun termuxOptionalRootModeContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val models = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt").readText()
        val manager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeManager.kt").readText()
        val runStore = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxRunStore.kt").readText()
        val templates = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()

        assertTrue("Phase 10 contract doc is missing", File(root, "docs/architecture/PHASE-10-OPTIONAL-ROOT-MODE.md").isFile)
        assertTrue("Phase 10 validator is missing", File(root, "tools/validate-termux-root-mode.py").isFile)
        assertTrue("UI contract must define Phase 10 root rules", contract.contains("Phase 10 Optional Root Mode Rules"))
        assertTrue("Root actions must remain typed", models.contains("XdmRootAction") && models.contains("RootActionAuditRecord") && models.contains("RootProbe"))
        assertTrue("Root manager must expose guarded actions", manager.contains("collectRootProcessDiagnostics") && manager.contains("killStuckTermuxAria2Daemon") && manager.contains("rootMode == TermuxRootMode.Off"))
        assertTrue("Root actions must be audited", runStore.contains("recordRootActionLaunch") && runStore.contains("rootAudit") && runStore.contains("lastRootMessage"))
        assertTrue("Shell templates must use su only behind typed actions", templates.contains("su -c") && templates.contains("XDM_ROOT_ACTION") && templates.contains("process is not XDM-owned"))
        assertTrue("Diagnostics/settings must expose root controls", screens.contains("Optional root actions") && screens.contains("Root audit") && screens.contains("Kill stuck aria2"))
        assertTrue("App shell must wire root actions", appShell.contains("viewModel::runTermuxRootProbe") && appShell.contains("viewModel::killStuckTermuxAria2WithRoot"))
        assertTrue("ViewModel must carry root actions", viewModel.contains("collectTermuxRootProcessDiagnostics") && viewModel.contains("fixTermuxDownloadPermissionsWithRoot"))
        assertTrue("Project manifest must record optional root", manifestJson.contains("termux_optional_root") && manifestJson.contains("\"raw_shell_exposed\": false") && manifestJson.contains("\"root_required\": false"))
        assertFalse("Optional root mode must not add a top-level route", AppRoute.entries.any { it.label == "Root" || it.label == "Termux" || it.label == "Tools" })
    }



    @Test
    fun termuxPostProcessingAutomationContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val app = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").readText()
        val models = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationModels.kt").readText()
        val manager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt").readText()
        val bridgeModels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt").readText()
        val templates = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()
        val contract = File(root, "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md").readText()

        assertTrue("Phase 11 contract doc is missing", File(root, "docs/architecture/PHASE-11-POST-PROCESSING-AUTOMATION.md").isFile)
        assertTrue("Phase 11 validator is missing", File(root, "tools/validate-termux-post-processing-automation.py").isFile)
        assertTrue("UI contract must define Phase 11 automation rules", contract.contains("Phase 11 Post-processing Automation Rules"))
        assertTrue("Post-processing models must define typed rules", models.contains("PostProcessingAutomationRule") && models.contains("PostProcessingAutomationTrigger") && models.contains("TermuxPostProcessingPlan"))
        assertTrue("Post-processing manager must preview and run rules", manager.contains("preview(download") && manager.contains("runForDownload") && manager.contains("runForMedia"))
        assertTrue("Termux commands must include a typed post-processing plan", bridgeModels.contains("PostProcess") && bridgeModels.contains("TermuxPostProcessingPlan"))
        assertTrue("Shell templates must include typed post-processing actions", templates.contains("XDM_POST_PROCESS") && templates.contains("PostProcessingActionKind.CleanupPartials") && templates.contains("-movflags +faststart"))
        assertTrue("Settings and Diagnostics must expose post-processing automation", screens.contains("Post-processing automation") && screens.contains("Retry failed") && screens.contains("Copy diagnostics"))
        assertTrue("Media route must expose preview and run actions", screens.contains("Preview rules") && screens.contains("Run rules"))
        assertTrue("App shell must wire automation callbacks", appShell.contains("viewModel::previewPostProcessingForMedia") && appShell.contains("viewModel::setPostProcessingAutomationEnabled"))
        assertTrue("ViewModel must carry automation state", viewModel.contains("postProcessingAutomationManager.status") && viewModel.contains("runPostProcessingForMedia"))
        assertTrue("Application must create the automation manager", app.contains("PostProcessingAutomationManager") && app.contains("postProcessingAutomationManager"))
        assertTrue("Project manifest must record post-processing automation", manifestJson.contains("termux_post_processing_automation") && manifestJson.contains("\"raw_shell_exposed\": false") && manifestJson.contains("\"root_required\": false"))
        assertFalse("Post-processing automation must not add a top-level route", AppRoute.entries.any { it.label == "Automation" || it.label == "Post" || it.label == "Tools" })
    }


    @Test
    fun mediaResolverPlayerOverlayContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt").readText()
        val player = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt").readText()
        val bridgeModels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt").readText()
        val mediaModels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineModels.kt").readText()
        val mediaManager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt").readText()
        val templates = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt").readText()
        val libs = File(root, "gradle/libs.versions.toml").readText()
        val appGradle = File(root, "app/build.gradle.kts").readText()

        assertTrue("Resolver/player contract doc is missing", File(root, "docs/architecture/PHASE-19-MEDIA-RESOLVER-PLAYER.md").isFile)
        assertTrue("Resolver/player validator is missing", File(root, "tools/validate-media-resolver-player.py").isFile)
        listOf("MediaTrackSelection", "MediaVariantPickerGroup", "MediaSessionHandoff", "YtDlpMetadataProbeResult", "ProtectedMediaDiagnostic").forEach { token ->
            assertTrue("Planner missing $token", planner.contains(token))
        }
        assertTrue("Screens must expose track picking", screens.contains("Choose tracks") && screens.contains("Audio track") && screens.contains("Subtitle track"))
        assertTrue("Screens must expose yt-dlp preview and protected diagnostics", screens.contains("yt-dlp metadata preview") && screens.contains("Protected media diagnostics"))
        assertTrue("Screens must expose redacted session handoff", screens.contains("Cookie/header session handoff") && screens.contains("Resolver will forward referer/header context"))
        assertTrue("Media3 player must use ExoPlayer and PlayerView", player.contains("ExoPlayer.Builder") && player.contains("PlayerView"))
        assertTrue("Media3 dependency must be wired", libs.contains("androidx.media3:media3-exoplayer") && appGradle.contains("libs.androidx.media3.exoplayer"))
        assertTrue("Termux commands must carry extra yt-dlp args", bridgeModels.contains("extraArguments") && templates.contains("appendYtDlpExtraArguments"))
        assertTrue("Termux manager must fetch variants for resolver-selected tracks", viewModel.contains("variantsForMediaCapture(record.id)") && mediaManager.contains("MediaTrackSelection"))
        assertTrue("Diagnostics must carry redacted session only", mediaModels.contains("redactedSession") && !mediaModels.contains("Cookie:"))
        assertFalse("Resolver/player must not add top-level routes", AppRoute.entries.any { it.label == "Player" || it.label == "Resolver" || it.label == "Browser" })
    }


    @Test
    fun mediaExecutionLibraryOverlayContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val execution = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt").readText()
        val runtime = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt").readText()
        val handoff = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()

        assertTrue("Phase 20 contract doc is missing", File(root, "docs/architecture/PHASE-20-MEDIA-EXECUTION-LIBRARY.md").isFile)
        assertTrue("Phase 20 validator is missing", File(root, "tools/validate-media-execution-library.py").isFile)
        listOf("MediaExecutionStage", "MediaQueuedDownloadSpec", "OfflineMediaSidecarMetadata", "OfflineMediaLibraryItem").forEach { token ->
            assertTrue("Execution/library planner missing $token", execution.contains(token))
        }
        assertTrue("Screens must expose execution states", screens.contains("Media download execution") && screens.contains("Probing, Queued, Downloading, Completed, Failed, or Blocked"))
        assertTrue("Offline library must expose resume/retry/player actions", screens.contains("Retry media") && screens.contains("Resume media") && screens.contains("Open player"))
        assertTrue("Download selected must carry track selection", screens.contains("onDownload(capture, mediaPlan.trackSelection)"))
        assertTrue("App shell must pass downloads and retry callback", appShell.contains("state.downloads") && appShell.contains("viewModel::togglePause"))
        assertTrue("ViewModel must build media execution specs", viewModel.contains("mediaExecutionPlanner.queueSpec") && viewModel.contains("MediaRequestHandoffStore.remember"))
        assertTrue("Runtime must consume short-lived media handoff headers", runtime.contains("MediaRequestHandoffStore.forDownload") && runtime.contains("headers = mediaHandoff?.headers.orEmpty()") && runtime.contains("MediaRequestHandoffStore.forget(downloadId)"))
        assertTrue("Handoff store must not persist raw cookies to Room", handoff.contains("process-local handoff") && !handoff.contains("Room"))
        assertTrue("Project manifest must record media execution library", manifestJson.contains("media_execution_library") && manifestJson.contains("raw_shell_exposed"))
        assertFalse("Media execution library must not add top-level routes", AppRoute.entries.any { it.label == "Library" || it.label == "Player" || it.label == "Execution" })
    }


    @Test
    fun mediaDownloadEngineHardeningContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val execution = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt").readText()
        val player = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt").readText()
        val handoff = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt").readText()
        val runtime = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()

        assertTrue("Phase 21 contract doc is missing", File(root, "docs/architecture/PHASE-21-MEDIA-DOWNLOAD-ENGINE-HARDENING.md").isFile)
        assertTrue("Phase 21 validator is missing", File(root, "tools/validate-media-download-engine-hardening.py").isFile)
        listOf("MediaExecutionLane", "MediaBackgroundExecutionPolicy", "MediaTempCookieFilePlan", "Aria2TransientInputPlan", "MediaSecretLeakReport", "MediaExecutionEnginePlan").forEach { token ->
            assertTrue("Media engine hardening missing $token", execution.contains(token))
        }
        assertTrue("Screens must expose engine hardening", screens.contains("Download engine hardening") && screens.contains("UIDT / WorkManager fallback / foreground service policy"))
        assertTrue("ViewModel must attach engine cleanup handoff", viewModel.contains("enginePlan.safeSummary") && viewModel.contains("cleanupActions = enginePlan.cleanupActions"))
        assertTrue("Handoff store must track transient cleanup only", handoff.contains("cleanupActions") && handoff.contains("tempCookieFileName") && handoff.contains("verifyForgotten"))
        assertTrue("Runtime must keep process-local handoff cleanup", runtime.contains("MediaRequestHandoffStore.forget(downloadId)"))
        assertTrue("Player must expose Media3 error diagnostics and retry prepare", player.contains("onPlayerError") && player.contains("Retry player prepare"))
        assertTrue("Project manifest must record media download engine hardening", manifestJson.contains("media_download_engine_hardening") && manifestJson.contains("no_validation_until_final_phase"))
        assertFalse("Media engine hardening must not add top-level routes", AppRoute.entries.any { it.label == "Engine" || it.label == "Hardening" || it.label == "Library" })
    }



    @Test
    fun mediaDispatchControlTowerContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val dispatcher = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt").readText()
        val tests = File(root, "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()

        assertTrue("Phase 22 contract doc is missing", File(root, "docs/architecture/PHASE-22-MEDIA-DISPATCH-CONTROL-TOWER.md").isFile)
        assertTrue("Phase 22 validator is missing", File(root, "tools/validate-media-dispatch-control-tower.py").isFile)
        listOf("MediaDispatchReadiness", "MediaDispatchStepKind", "MediaDispatchPlan", "MediaRetryPolicy", "MediaDispatchDashboard").forEach { token ->
            assertTrue("Dispatch planner missing $token", dispatcher.contains(token))
        }
        assertTrue("Dispatch planner must model safe terminal cleanup", dispatcher.contains("Register terminal cleanup") && dispatcher.contains("Verify no durable secrets"))
        assertTrue("Screens must expose dispatch control tower", screens.contains("Media dispatch control tower") && screens.contains("Dispatch runbook"))
        assertTrue("Screens must expose readiness and action labels", screens.contains("toneForDispatchReadiness") && screens.contains("primaryActionLabel"))
        assertTrue("Tests must cover dispatch redaction and dashboard counts", tests.contains("mediaDispatchRunbookKeepsSecretsOut") && tests.contains("mediaDispatchDashboardCounts"))
        assertTrue("Project manifest must record dispatch control tower", manifestJson.contains("media_dispatch_control_tower") && manifestJson.contains("no_validation_until_final_phase"))
        assertFalse("Media dispatch control tower must not add top-level routes", AppRoute.entries.any { it.label == "Dispatch" || it.label == "Control" || it.label == "Tower" })
    }


    @Test
    fun mediaQueueTelemetryContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val telemetry = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueTelemetry.kt").readText()
        val tests = File(root, "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()

        assertTrue("Phase 23 contract doc is missing", File(root, "docs/architecture/PHASE-23-MEDIA-QUEUE-TELEMETRY.md").isFile)
        assertTrue("Phase 23 validator is missing", File(root, "tools/validate-media-queue-telemetry.py").isFile)
        listOf("MediaQueueTelemetryTone", "MediaQueueTelemetryRow", "MediaQueueTelemetryDeck", "MediaQueueTelemetryPlanner").forEach { token ->
            assertTrue("Queue telemetry missing $token", telemetry.contains(token))
        }
        assertTrue("Queue telemetry must model cleanup and next action", telemetry.contains("cleanupArmed") && telemetry.contains("nextActionLabel") && telemetry.contains("safeDiagnostic"))
        assertTrue("Screens must expose queue telemetry", screens.contains("Media queue telemetry") && screens.contains("Phase 23 turns dispatch runbooks"))
        assertTrue("Screens must keep telemetry inside Media route", screens.contains("MediaQueueTelemetryCard(queueTelemetry)"))
        assertTrue("Tests must cover telemetry redaction and retry action", tests.contains("mediaQueueTelemetryDeckShowsReadyCleanup") && tests.contains("mediaQueueTelemetryRedactsFailedJobDetails"))
        assertTrue("Project manifest must record queue telemetry", manifestJson.contains("media_queue_telemetry") && manifestJson.contains("no_validation_until_final_phase"))
        assertFalse("Media queue telemetry must not add top-level routes", AppRoute.entries.any { it.label == "Telemetry" || it.label == "Queue telemetry" || it.label == "Pulse" })
    }

    @Test
    fun mediaQueueActionsContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val actions = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueActions.kt").readText()
        val tests = File(root, "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()

        assertTrue("Phase 24 contract doc is missing", File(root, "docs/architecture/PHASE-24-MEDIA-QUEUE-ACTIONS.md").isFile)
        assertTrue("Phase 24 validator is missing", File(root, "tools/validate-media-queue-actions.py").isFile)
        listOf("MediaQueueActionKind", "MediaQueueActionAvailability", "MediaQueueActionPlan", "MediaQueueActionDashboard", "MediaQueueActionPlanner").forEach { token ->
            assertTrue("Queue actions missing $token", actions.contains(token))
        }
        assertTrue("Queue actions must model pause resume retry cancel cleanup", actions.contains("Pause media") && actions.contains("Resume media") && actions.contains("Retry media") && actions.contains("Cancel media") && actions.contains("Cleanup finished"))
        assertTrue("Screens must expose queue actions inside Media", screens.contains("Media queue actions") && screens.contains("Phase 24 turns telemetry"))
        assertTrue("Screens must keep queue actions inside the Media route", screens.contains("MediaQueueActionsCard(queueActions)"))
        assertTrue("Tests must cover queue actions and redaction", tests.contains("mediaQueueActionsExposeLaunchRetryCancelAndCleanupWithoutSecrets") && tests.contains("mediaQueueActionsExplainBlockedPreQueueStates"))
        assertTrue("Project manifest must record queue actions", manifestJson.contains("media_queue_actions") && manifestJson.contains("no_validation_until_final_phase"))
        assertFalse("Media queue actions must not add top-level routes", AppRoute.entries.any { it.label == "Actions" || it.label == "Queue actions" || it.label == "Control" })
    }


    @Test
    fun mediaWorkerBridgeContractsArePresent() {
        val root = androidRoot()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val bridge = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt").readText()
        val tests = File(root, "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt").readText()
        val manifestJson = File(root, "PROJECT_MANIFEST.json").readText()

        assertTrue("Phase 25 contract doc is missing", File(root, "docs/architecture/PHASE-25-MEDIA-WORKER-BRIDGE.md").isFile)
        assertTrue("Phase 25 validator is missing", File(root, "tools/validate-media-worker-bridge.py").isFile)
        listOf("MediaWorkerBridgeKind", "MediaWorkerBridgeReadiness", "MediaWorkerAdapterContract", "MediaWorkerForegroundNotificationPlan", "MediaWorkerBridgeRequest", "MediaWorkerBridgePlanner").forEach { token ->
            assertTrue("Worker bridge missing $token", bridge.contains(token))
        }
        assertTrue("Worker bridge must model UIDT WorkManager Termux aria2 yt-dlp", bridge.contains("Android UIDT worker") && bridge.contains("WorkManager foreground worker") && bridge.contains("aria2 launch adapter") && bridge.contains("Termux yt-dlp adapter"))
        assertTrue("Worker bridge must keep typed adapters", bridge.contains("rawShellExposed") && bridge.contains("typedArguments") && bridge.contains("redactedPreview"))
        assertTrue("Screens must expose worker bridge inside Media", screens.contains("Media worker bridge") && screens.contains("Phase 25 converts ready media actions"))
        assertTrue("Screens must keep worker bridge inside the Media route", screens.contains("MediaWorkerBridgeCard(workerBridge)"))
        assertTrue("Tests must cover worker bridge redaction", tests.contains("mediaWorkerBridgeBuildsUidtRequestForDirectMediaWithoutSecrets") && tests.contains("mediaWorkerBridgeBuildsTypedTermuxYtDlpRequestWithCleanupOwnedSecrets"))
        assertTrue("Project manifest must record worker bridge", manifestJson.contains("media_worker_bridge") && manifestJson.contains("no_validation_until_final_phase"))
        assertFalse("Media worker bridge must not add top-level routes", AppRoute.entries.any { it.label == "Worker" || it.label == "Bridge" || it.label == "Workers" })
    }


    @Test
    fun mediaTermuxRuntimeAdapterContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 26 contract doc is missing", File(root, "docs/architecture/PHASE-26-MEDIA-TERMUX-RUNTIME-ADAPTER.md").isFile)
        assertTrue("Phase 26 validator is missing", File(root, "tools/validate-media-termux-runtime-adapter.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val adapter = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Runtime adapter must expose typed launch plans", adapter.contains("TermuxRuntimeLaunchPlan") && adapter.contains("typedArguments"))
        assertTrue("Runtime adapter must expose capability probes", adapter.contains("TermuxMediaRuntimeCapabilityReport") && adapter.contains("Install yt-dlp"))
        assertTrue("Runtime adapter must model transient cleanup", adapter.contains("Netscape cookie file") && adapter.contains("delete after terminal state"))
        assertTrue("Screens must expose Termux runtime adapter inside Media", screens.contains("Media Termux runtime adapter") && screens.contains("Phase 26 turns worker bridge requests"))
        assertTrue("Manifest must record Phase 26", manifest.contains("media_termux_runtime_adapter"))
        assertFalse("Termux runtime adapter must not add top-level routes", AppRoute.entries.any { it.label == "Runtime" || it.label == "Termux runtime" || it.label == "yt-dlp" })
    }


    @Test
    fun mediaNativeDirectDownloadEngineContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 27 contract doc is missing", File(root, "docs/architecture/PHASE-27-MEDIA-NATIVE-DIRECT-DOWNLOAD-ENGINE.md").isFile)
        assertTrue("Phase 27 validator is missing", File(root, "tools/validate-media-native-direct-download-engine.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaNativeDirectDownloadEngine.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Native engine must expose direct request plans", planner.contains("NativeDirectDownloadRequestPlan") && planner.contains("NativeDirectHeaderPolicy"))
        assertTrue("Native engine must expose resume planning", planner.contains("NativeDirectResumePlan") && planner.contains("Range=bytes="))
        assertTrue("Screens must expose native direct engine inside Media", screens.contains("Native direct download engine") && screens.contains("Phase 27 plans Android-native direct media transfers"))
        assertTrue("Manifest must record Phase 27", manifest.contains("media_native_direct_download_engine"))
        assertFalse("Native direct engine must not add top-level routes", AppRoute.entries.any { it.label == "Native" || it.label == "Direct engine" || it.label == "Media engine" })
    }


    @Test
    fun mediaOfflineLibraryV2ContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 28 contract doc is missing", File(root, "docs/architecture/PHASE-28-MEDIA-OFFLINE-LIBRARY-V2.md").isFile)
        assertTrue("Phase 28 validator is missing", File(root, "tools/validate-media-offline-library-v2.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaOfflineLibraryV2.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Offline Library 2.0 must expose filter/sort dashboard", planner.contains("OfflineLibraryV2Filter") && planner.contains("OfflineLibraryV2SortKey") && planner.contains("OfflineLibraryV2Dashboard"))
        assertTrue("Offline Library 2.0 must keep safe exports and sidecar actions", planner.contains("safeExportJson") && planner.contains("RemoveSidecar") && planner.contains("RenameSidecar"))
        assertTrue("Screens must expose Offline Library 2.0 inside Media", screens.contains("Offline Library 2.0") && screens.contains("Phase 28 makes completed media filterable"))
        assertTrue("Manifest must record Phase 28", manifest.contains("media_offline_library_v2"))
        assertFalse("Offline Library 2.0 must not add top-level routes", AppRoute.entries.any { it.label == "Library" || it.label == "Offline" || it.label == "Player" })
    }

    @Test
    fun mediaPlayerDiagnosticsContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 29 contract doc is missing", File(root, "docs/architecture/PHASE-29-MEDIA-PLAYER-DIAGNOSTICS.md").isFile)
        assertTrue("Phase 29 validator is missing", File(root, "tools/validate-media-player-diagnostics.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val player = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Player diagnostics must expose buckets and retry prepare", planner.contains("MediaPlayerDiagnosticBucket") && planner.contains("RetryPrepare") && planner.contains("Protected media diagnostics only"))
        assertTrue("Media3 card must expose Player 2.0 diagnostics", player.contains("Player 2.0 diagnostics") && player.contains("Track availability") && player.contains("Playback position"))
        assertTrue("Screens must expose player diagnostics inside Media", screens.contains("Player diagnostics deck") && screens.contains("Phase 29 makes Media3 playback failures"))
        assertTrue("Manifest must record Phase 29", manifest.contains("media_player_diagnostics"))
        assertFalse("Player diagnostics must not add top-level routes", AppRoute.entries.any { it.label == "Player" || it.label == "Playback" })
    }


    @Test
    fun mediaBrowserCaptureQualityContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 30 contract doc is missing", File(root, "docs/architecture/PHASE-30-BROWSER-CAPTURE-QUALITY.md").isFile)
        assertTrue("Phase 30 validator is missing", File(root, "tools/validate-media-browser-capture-quality.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBrowserCaptureQuality.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Capture quality must expose dispositions and signals", planner.contains("CaptureQualityDisposition") && planner.contains("AnalyticsBeacon") && planner.contains("GroupWithExisting"))
        assertTrue("Capture quality must score and redact diagnostics", planner.contains("confidenceScore") && planner.contains("secret-safe capture quality") && planner.contains("redactKnownSecrets"))
        assertTrue("Screens must expose Browser capture quality inside Media", screens.contains("Browser capture quality") && screens.contains("Phase 30 improves sniffing quality"))
        assertTrue("Manifest must record Phase 30", manifest.contains("media_browser_capture_quality"))
        assertFalse("Browser capture quality must not add top-level routes", AppRoute.entries.any { it.label == "Capture" || it.label == "Quality" || it.label == "Sniffer" })
    }

    @Test
    fun mediaSessionPrivacyAuditContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 31 contract doc is missing", File(root, "docs/architecture/PHASE-31-SESSION-PRIVACY-CLEANUP-AUDIT.md").isFile)
        assertTrue("Phase 31 validator is missing", File(root, "tools/validate-media-session-privacy-audit.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Privacy audit must scan expected surfaces", planner.contains("MediaPrivacySurface") && planner.contains("TermuxCommandPreview") && planner.contains("TempFiles"))
        assertTrue("Privacy audit must classify cleanup and blockers", planner.contains("MediaCleanupState") && planner.contains("durable secret-safe") && planner.contains("transientCleanupHealthy"))
        assertTrue("Screens must expose Session privacy audit inside Media", screens.contains("Session privacy audit") && screens.contains("Phase 31 audits browser sessions"))
        assertTrue("Manifest must record Phase 31", manifest.contains("media_session_privacy_audit"))
        assertFalse("Session privacy audit must not add top-level routes", AppRoute.entries.any { it.label == "Privacy" || it.label == "Audit" || it.label == "Cleanup" })
    }


    @Test
    fun mediaMobilePolishContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 32 contract doc is missing", File(root, "docs/architecture/PHASE-32-MEDIA-MOBILE-POLISH.md").isFile)
        assertTrue("Phase 32 validator is missing", File(root, "tools/validate-media-mobile-polish.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue("Mobile polish must expose phone-first dashboard concepts", planner.contains("MediaMobilePolishDashboard") && planner.contains("StickyCurrentJob") && planner.contains("NoTinyScrollIslands"))
        assertTrue("Mobile polish must include accessibility and foldable rules", planner.contains("AccessibilityLabels") && planner.contains("FoldableReady") && planner.contains("TouchTargetSafe"))
        assertTrue("Screens must expose Media mobile polish inside Media", screens.contains("Media mobile polish") && screens.contains("Phase 32 makes the Media stack phone-friendly"))
        assertTrue("Manifest must record Phase 32", manifest.contains("media_mobile_polish"))
        assertFalse("Media mobile polish must not add top-level routes", AppRoute.entries.any { it.label == "Mobile" || it.label == "Polish" || it.label == "Media UX" })
    }


    @Test
    fun mediaFinalValidationGateContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 33 contract doc is missing", File(root, "docs/architecture/PHASE-33-MEDIA-FINAL-VALIDATION-GATE.md").isFile)
        assertTrue("Phase 33 validator is missing", File(root, "tools/validate-media-final-validation-gate.py").isFile)
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGate.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val runGate = File(root, "tools/run-final-release-gate.sh").readText()
        val workflow = File(root, ".github/workflows/android.yml").readText()
        assertTrue("Final gate planner must expose checks and commands", planner.contains("MediaFinalValidationGatePlanner") && planner.contains("DefaultGradleCommand") && planner.contains("warning-zero gate"))
        assertTrue("Final gate must scan secrets and known Kotlin traps", planner.contains("PrivacyLeakScan") && planner.contains("KotlinTrapScan") && planner.contains("TermuxChrootSafety"))
        assertTrue("Screens must expose Phase 33 inside Media", screens.contains("Media final validation gate") && screens.contains("Phase 33 re-enables validation"))
        assertTrue("Manifest must record Phase 33", manifest.contains("media_final_validation_gate") && manifest.contains("\"next_phase\": \"complete\""))
        assertTrue("Final gate script must include media validators", runGate.contains("validate-media-final-validation-gate.py") && runGate.contains("validate-media-mobile-polish.py"))
        assertTrue("CI must include final media validator", workflow.contains("validate-media-final-validation-gate.py"))
        assertFalse("Final media gate must not add top-level routes", AppRoute.entries.any { it.label == "Validation" || it.label == "Final" || it.label == "Release" || it.label == "Media Gate" })
    }


    @Test
    fun phaseThirtyFourReleaseHandoffContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 34 handoff doc is missing", File(root, "docs/architecture/PHASE-34-STABILIZATION-RELEASE-HANDOFF.md").isFile)
        assertTrue("Phase 34 validator is missing", File(root, "tools/validate-phase-34-release-handoff.py").isFile)
        val handoff = File(root, "docs/architecture/PHASE-34-STABILIZATION-RELEASE-HANDOFF.md").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val runGate = File(root, "tools/run-final-release-gate.sh").readText()
        val workflow = File(root, ".github/workflows/android.yml").readText()
        assertTrue("Handoff doc must record the landed Phase 33 result", handoff.contains("149 passed, 0 failed, 0 skipped") && handoff.contains("Phase 33 is landed"))
        assertTrue("Manifest must record Phase 34", manifest.contains("phase34_release_handoff") && manifest.contains("\"next_phase\": \"complete\""))
        assertTrue("Manifest must keep the Phase 33 success ledger", manifest.contains("\"tests_passed\": 149") && manifest.contains("\"diagnostic_errors\": 0"))
        assertTrue("Final release gate must include the Phase 34 handoff validator", runGate.contains("validate-phase-34-release-handoff.py"))
        assertTrue("CI must include the Phase 34 handoff validator", workflow.contains("validate-phase-34-release-handoff.py"))
        assertFalse("Phase 34 must not add top-level routes", AppRoute.entries.any { it.label == "Handoff" || it.label == "Release Handoff" || it.label == "Stabilization" })
    }


    @Test
    fun phaseThirtyFiveReleaseCandidatePolishContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 35 release-candidate doc is missing", File(root, "docs/architecture/PHASE-35-RELEASE-CANDIDATE-POLISH.md").isFile)
        assertTrue("Phase 35 validator is missing", File(root, "tools/validate-phase-35-release-candidate-polish.py").isFile)
        val polish = File(root, "docs/architecture/PHASE-35-RELEASE-CANDIDATE-POLISH.md").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val buildGradle = File(root, "app/build.gradle.kts").readText()
        val releaseHelper = File(root, "tools/build-release-artifacts.sh").readText()
        val runGate = File(root, "tools/run-final-release-gate.sh").readText()
        val workflow = File(root, ".github/workflows/android.yml").readText()
        assertTrue("Phase 35 doc must define the ship/no-ship gate", polish.contains("Phase 35: Release Candidate Polish") && polish.contains("Ship/no-ship gate") && polish.contains("No-ship is required"))
        assertTrue("Manifest must record Phase 35", manifest.contains("phase35_release_candidate_polish") && (manifest.contains("\"current_overlay\": \"xdm_android_phase35_release_candidate_polish_overlay.zip\"") || manifest.contains("\"current_overlay\": \"xdm_android_phase36_external_download_handoff_overlay.zip\"")))
        assertTrue("Phase 35 must keep version metadata stable", buildGradle.contains("versionName = \"0.20.0-rc08\"") && buildGradle.contains("versionCode = 21"))
        assertTrue("Release helper must keep artifact checksums", releaseHelper.contains("sha256sum") && releaseHelper.contains("assembleBeta") && releaseHelper.contains("assembleRelease"))
        assertTrue("Final release gate must include the Phase 35 validator", runGate.contains("validate-phase-35-release-candidate-polish.py"))
        assertTrue("CI must include the Phase 35 validator", workflow.contains("validate-phase-35-release-candidate-polish.py"))
        assertFalse("Phase 35 must not add top-level routes", AppRoute.entries.any { it.label == "Release Candidate" || it.label == "Ship" || it.label == "No Ship" || it.label == "Checklist" })
    }


    @Test
    fun phaseThirtySixExternalDownloadHandoffContractsArePresent() {
        val root = androidRoot()
        assertTrue("Phase 36 external handoff doc is missing", File(root, "docs/architecture/PHASE-36-EXTERNAL-DOWNLOAD-HANDOFF.md").isFile)
        assertTrue("Phase 36 validator is missing", File(root, "tools/validate-phase-36-external-download-handoff.py").isFile)
        val manifestXml = File(root, "app/src/main/AndroidManifest.xml").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val mainActivity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val externalActivity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val models = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()
        val appShell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val runGate = File(root, "tools/run-final-release-gate.sh").readText()
        val workflow = File(root, ".github/workflows/android.yml").readText()

        assertTrue("Manifest must expose a dedicated external Add activity", manifestXml.contains(".ExternalAddDownloadActivity") && manifestXml.contains("@string/external_add_download_label"))
        assertTrue("External Add must receive Android share text and wildcard handoffs", manifestXml.contains("android.intent.action.SEND") && manifestXml.contains("android:mimeType=\"text/plain\"") && manifestXml.contains("android:mimeType=\"*/*\""))
        assertTrue("External Add must receive browser VIEW and download actions", manifestXml.contains("android.intent.action.VIEW") && manifestXml.contains("com.android.browser.action.DOWNLOAD") && manifestXml.contains("android:scheme=\"ftp\""))
        assertTrue("External Add must expose lint-safe downloadable path patterns", manifestXml.contains("android:host=\"*\" android:pathPattern=\".*\\\\.zip\"") && manifestXml.contains("android:host=\"*\" android:pathPattern=\".*\\\\.apk\"") && manifestXml.contains("android:host=\"*\" android:pathPattern=\".*\\\\.mp4\""))
        assertTrue("Browser-download web filters must opt out of verified App Links", manifestXml.contains("<intent-filter android:autoVerify=\"false\">"))
        assertTrue("External activity must reuse the Compose shell without a new route", externalActivity.contains("class ExternalAddDownloadActivity : MainActivity()"))
        assertTrue("MainActivity must route external receiver handoffs to Add prompt", mainActivity.contains("shouldOpenExternalAddPrompt") && mainActivity.contains("AutomationCommandAction.PromptAddDownload"))
        assertTrue("PromptAddDownload must open Add instead of auto-queuing", viewModel.contains("AutomationCommandAction.PromptAddDownload -> openExternalAddDraft") && viewModel.contains("External download opened Add Download prompt"))
        assertTrue("URL normalization must support ftp for download-manager handoff", models.contains("(?:https?|ftp)://") && models.contains("scheme != \"http\" && scheme != \"https\" && scheme != \"ftp\""))
        assertTrue("Add screen must show external source and no-auto-queue safety copy", screens.contains("externalSourceLabel") && screens.contains("XDM never auto-queues external handoffs"))
        assertTrue("App shell must pass the external source label", appShell.contains("externalSourceLabel = state.externalAddDraft?.sourceLabel"))
        assertTrue("Manifest must record Phase 36", manifest.contains("phase36_external_download_handoff") && manifest.contains("\"current_overlay\": \"xdm_android_phase36_external_download_handoff_overlay.zip\""))
        assertTrue("Final release gate must include the Phase 36 validator", runGate.contains("validate-phase-36-external-download-handoff.py"))
        assertTrue("CI must include the Phase 36 validator", workflow.contains("validate-phase-36-external-download-handoff.py"))
        assertFalse("Phase 36 must not add top-level routes", AppRoute.entries.any { it.label == "External" || it.label == "Handoff" || it.label == "IronFox" || it.label == "Browser Download" })
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
