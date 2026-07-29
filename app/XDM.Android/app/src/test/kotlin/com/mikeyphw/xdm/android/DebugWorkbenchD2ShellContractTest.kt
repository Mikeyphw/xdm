package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD2ShellContractTest {
    private val root = androidRoot()

    @Test
    fun settingsRoutesToDebugWorkbenchShellWithoutTopLevelRoute() {
        val panels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/SettingsPanel.kt").readText()
        val settings = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt").readText()
        val appRoute = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()

        assertTrue(panels.contains("""DebugWorkbench("Debug Workbench")"""))
        assertTrue(settings.contains("SettingsPanel.DebugWorkbench -> DebugWorkbenchSettingsScreen(state, viewModel)"))
        assertTrue(settings.contains("""title = "Debug Workbench""""))
        assertTrue(settings.contains("viewModel.selectSettingsPanel(SettingsPanel.DebugWorkbench)"))
        assertFalse("D2 must not add a top-level route", appRoute.contains("DebugWorkbench"))
    }

    @Test
    fun shellShowsRealCopyActionsAndNoPlaceholderButtons() {
        val screen = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt").readText()
        listOf(
            "Live status",
            "Session controls",
            "Support bundle",
            "Runtime self-checks",
            "Copy debug status",
            "Copy support report",
            "report.toClipboardReport()",
            "state.supportReportText",
        ).forEach { assertTrue("Debug Workbench shell missing $it", screen.contains(it)) }
        assertFalse("Debug Workbench shell must not use placeholder click handlers", screen.contains("onClick = {}"))
        assertFalse("D2 shell must not auto-upload support bundles", screen.contains("upload", ignoreCase = true) && screen.contains("http", ignoreCase = true))
    }

    @Test
    fun applicationInstallsRecorderProviderAndViewModelUsesRecorderHooks() {
        val app = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").readText()
        val vm = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()

        assertTrue(app.contains("DebugRecorderProvider"))
        assertTrue(app.contains("RollingJsonlDebugEventRecorder"))
        assertTrue(app.contains("""File(filesDir, "debug-sessions")"""))
        assertTrue(vm.contains("debugEventRecorder: DebugEventRecorder"))
        assertTrue(vm.contains("DownloadIntakePlanner(debugRecorder = debugEventRecorder)"))
        assertTrue(vm.contains("MediaSniffingEngine(mediaCaptureService, debugRecorder = debugEventRecorder)"))
        assertTrue(vm.contains("debugWorkbenchReport = DebugWorkbenchShellPolicy.evaluate"))
    }

    @Test
    fun d2ManifestDocsAndValidatorAreRecorded() {
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val doc = File(root, "docs/architecture/DEBUG-WORKBENCH-D2-SHELL.md").readText()
        val validator = File(root, "tools/validate-debug-workbench-d2-shell.py").readText()

        assertTrue(manifest.contains("debug_workbench_phase_d2_shell"))
        assertTrue(manifest.contains("debug_workbench_phase_d3_media_sniffing_lab"))
        assertTrue(doc.contains("Settings → Debug Workbench"))
        assertTrue(doc.contains("No automatic upload"))
        assertTrue(validator.contains("DebugWorkbenchSettingsScreen.kt"))
    }

    @Test
    fun phase47SharedSnifferContractAcceptsRecorderBackedConstruction() {
        val phase47 = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/MediaSniffingPhase47ContractTest.kt").readText()

        assertTrue(phase47.contains("usesRecorderBackedSharedSniffer"))
        assertTrue(phase47.contains("debugRecorder = debugEventRecorder"))
        assertTrue(phase47.contains("usesOriginalSharedSniffer || usesRecorderBackedSharedSniffer"))
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
