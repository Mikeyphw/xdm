package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR5ActivitySettingsContractTest {
    @Test
    fun normalActivityHasTwoPrimaryViewsAndMovesManagementIntoASecondarySheet() {
        val root = androidRoot()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt").readText()
        val app = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val panels = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ActivityPanel.kt").readText()

        listOf("Needs attention", "Recent", "Manage", "ActivityPanel.primaryPanels").forEach {
            assertTrue("Activity R5 missing $it", activity.contains(it) || panels.contains(it))
        }
        assertTrue("Management must use an adaptive secondary surface", app.contains("XdmAdaptiveSheet(") && app.contains("ActivityPanel.managePanels"))
        assertFalse("Normal Activity must not render every panel as peer navigation", activity.contains("ActivityPanel.entries"))
        assertTrue("Legacy Diagnostics must redirect to gated tools", app.contains("viewModel.openDeveloperTools()"))
    }

    @Test
    fun settingsStartsWithEverydayChoicesAndKeepsDeveloperToolsGated() {
        val root = androidRoot()
        val settings = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt").readText()
        val developer = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperSettingsScreen.kt").readText()
        val preferences = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt").readText()

        val save = settings.indexOf("Save location")
        val queue = settings.indexOf("Smart queue")
        val advanced = settings.indexOf("Advanced download rules")
        val support = settings.indexOf("Copy support report")
        assertTrue("Everyday settings must lead", save >= 0 && queue > save && advanced > queue && support > advanced)
        assertTrue("Developer options must default off", preferences.contains("developerOptionsEnabled: Boolean = false"))
        assertTrue("Developer preference must persist", preferences.contains("DeveloperOptionsEnabled") && preferences.contains("setDeveloperOptionsEnabled"))
        assertTrue("Developer workspace must refuse access while disabled", developer.contains("DeveloperWorkspacePolicy.shouldCompose(state.developerOptionsEnabled, state.settingsPanel)"))
        assertTrue("Support report must remain outside the developer-only branch", settings.contains("state.supportReportText"))
    }

    @Test
    fun developerWorkspaceIsGroupedRedactedAndHasNoRawShellSurface() {
        val root = androidRoot()
        val workspace = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt").readText()
        val settings = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt").readText()

        listOf(
            "Runtime and engines",
            "Termux and aria2",
            "Media pipeline",
            "Dispatch and workers",
            "Privacy and cleanup",
            "Validation and release",
            "Intake and clipboard",
            "Redacted logs and exports",
            "ReleaseReadinessSection",
        ).forEach { assertTrue("Developer workspace missing $it", workspace.contains(it)) }
        assertTrue("Clipboard URLs must be redacted before rendering", workspace.contains("PrivacyDiagnosticsRedactor.redactUrl(item.url)"))
        assertFalse("Developer workspace must not expose an arbitrary shell input", workspace.contains("Raw shell") || workspace.contains("Shell command") || workspace.contains("execute arbitrary", ignoreCase = true))
        assertFalse("Normal Settings must not inline technical dashboards", settings.contains("MediaDispatchDashboardCard") || settings.contains("MediaQueueTelemetryCard") || settings.contains("MediaWorkerBridgeCard"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
