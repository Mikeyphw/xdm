package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase54EngineEscalationPlannerContractTest {
    private val root = androidRoot()

    @Test
    fun addDownloadWiresEngineEscalationPlannerWithoutStartingTransfers() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val addSurface = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/EngineEscalationPlanner.kt")

        assertTrue(app.contains("EngineEscalationPlanner.evaluate"))
        assertTrue(app.contains("externalEngineEscalationPlan = externalEngineEscalation"))
        assertTrue(addSurface.contains("EngineEscalationCard"))
        assertTrue(addSurface.contains("Engine escalation planner"))
        assertTrue(addSurface.contains("This planner chooses only the next review action"))
        assertTrue(planner.contains("review-only"))
        assertFalse(planner.contains("startTransfer("))
        assertFalse(planner.contains("enqueue("))
        assertFalse(planner.contains("Room"))
    }

    @Test
    fun plannerUsesHumanMethodLabelsAndPrivacySafeCopy() {
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/EngineEscalationPlanner.kt")
        val addSurface = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        assertTrue(planner.contains("XDM Native with captured session"))
        assertTrue(planner.contains("aria2 segmented transfer"))
        assertTrue(planner.contains("Media resolver or yt-dlp"))
        assertTrue(planner.contains("Refresh browser capture or inspect with yt-dlp"))
        assertTrue(addSurface.contains("Safe alternatives"))
        assertFalse("Normal UI must not render raw backend enum names", addSurface.contains("recommendedMethodLabel.name"))
        assertFalse("Normal UI must not render raw header dumps", addSurface.contains("requestHeaders"))
        assertFalse("Normal UI must not render raw URL values from the planner", addSurface.contains("plan.url"))
        assertFalse("Planner UI must not expose Cookie values", addSurface.contains("Cookie value"))
        assertFalse("Planner UI must not expose Authorization values", addSurface.contains("Authorization value"))
    }

    @Test
    fun manifestAndDocsKeepOperationalBoundary() {
        val manifest = source("PROJECT_MANIFEST.json")
        val doc = source("docs/architecture/PHASE-54-ENGINE-ESCALATION-PLANNER.md")
        val changelog = source("../../CHANGELOG.md")

        assertTrue(manifest.contains("\"field_bugfix_phase_54\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"debug_workbench_reopened\": false"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(doc.contains("Phase54 is a planner only"))
        assertTrue(doc.contains("does not start transfers"))
        assertTrue(doc.contains("raw URLs, raw header names, Cookie values, Authorization values"))
        assertTrue(changelog.contains("XDM Android Phase 54 Engine Escalation Planner"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
