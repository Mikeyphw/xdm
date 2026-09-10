package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux09DeveloperCenterConsolidationContractTest {
    private val root = File(System.getProperty("user.dir") ?: ".").let { cwd ->
        generateSequence(cwd) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    @Test
    fun settingsHasOneSafeDiagnosticsEntryAndOneDeveloperCenterGate() {
        val settings = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt").readText()
        assertTrue(settings.contains("\"Diagnostics & support\""))
        assertTrue(settings.contains("title = \"Developer mode\""))
        assertTrue(settings.contains("\"Developer Center\""))
        assertFalse(settings.contains("title = \"Debug Workbench\""))
        assertFalse(settings.contains("title = \"Developer tools\""))
        assertFalse(settings.contains("title = \"Developer options\""))
    }

    @Test
    fun diagnosticsDoesNotNestAnotherAdvancedWorkbench() {
        val debug = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt").readText()
        assertTrue(debug.contains("SettingsPageHeader(\"Diagnostics & support\""))
        assertTrue(debug.contains("Health(\"Health\")"))
        assertFalse(debug.contains("Advanced Debug Workbench"))
    }

    @Test
    fun releaseReadinessLivesOnlyInValidationSectionBranch() {
        val workspace = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt").readText()
        val callCount = Regex("item \\{ ReleaseReadinessSection\\(state\\) \\}").findAll(workspace).count()
        assertTrue(callCount == 1)
        assertTrue(workspace.contains("DeveloperToolSection.ValidationRelease -> LazyColumn"))
    }
}
