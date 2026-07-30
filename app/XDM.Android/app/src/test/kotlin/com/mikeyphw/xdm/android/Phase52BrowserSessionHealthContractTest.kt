package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase52BrowserSessionHealthContractTest {
    private val root = androidRoot()

    @Test
    fun addDownloadShowsSessionHealthWithoutSecretLabels() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        assertTrue(screen.contains("Browser session health"))
        assertTrue(screen.contains("BrowserSessionHealthCard"))
        assertTrue(screen.contains("Private browser values are never shown here"))
        assertTrue(app.contains("BrowserSessionHealthPlanner.evaluate(state.externalAddDraft)"))
        listOf("Cookie", "Authorization", "Bearer", "requestHeaders", "redactedHeaderSummary").forEach { forbidden ->
            assertFalse("Normal Add Download UI must not render $forbidden", screen.contains("Text(\"$forbidden"))
            assertFalse("Normal Add Download UI must not use $forbidden as a visible row", screen.contains("ReviewSummaryRow(\"$forbidden"))
        }
    }

    @Test
    fun sessionHealthPlannerStaysModelOnlyAndSchemaFree() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/BrowserSessionHealth.kt")
        val manifest = source("PROJECT_MANIFEST.json")
        assertTrue(model.contains("object BrowserSessionHealthPlanner"))
        assertTrue(model.contains("Cookie") && model.contains("Authorization"))
        assertTrue(model.contains("Raw Cookie") && model.contains("must remain transient and redacted"))
        assertFalse(model.contains("Room"))
        assertFalse(model.contains("SharedPreferences"))
        assertFalse(model.contains("File("))
        assertTrue(manifest.contains("\"field_bugfix_phase_52\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"debug_workbench_reopened\": false"))
    }

    @Test
    fun phase52DocsDeclarePrivacyAndNoDebugWorkbenchReopen() {
        val doc = source("docs/architecture/PHASE-52-BROWSER-SESSION-HEALTH.md")
        val changelog = File(repoRoot(), "CHANGELOG.md").readText()
        assertTrue(doc.contains("Normal UI must not show"))
        assertTrue(doc.contains("No Room migration"))
        assertTrue(doc.contains("Debug Workbench D-series remains sealed"))
        assertTrue(changelog.contains("XDM Android Phase 52 Browser Session Health"))
        assertTrue(changelog.contains("no D8"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun repoRoot(): File = root.parentFile?.parentFile ?: error("Repository root not found")

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
