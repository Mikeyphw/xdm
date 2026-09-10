package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux10DeveloperDiagnosticsPolishContractTest {
    private val root = androidRoot()

    @Test
    fun diagnosticsPrioritizeOutcomeActionAndExpandableTechnicalDetails() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt")
        assertTrue(screen.contains("Run complete • action needed"))
        assertTrue(screen.contains("Recommended action:"))
        assertTrue(screen.contains("Technical details"))
        assertTrue(screen.contains("conciseDebugSummary"))
        assertTrue(screen.contains("if (running)"))
        assertTrue(screen.contains("LinearProgressIndicator"))
        assertFalse(screen.contains("Retest Failed"))
        assertTrue(screen.contains("Retest failed"))
    }

    @Test
    fun developerCenterNoLongerUsesRoadmapPhaseCopyAsRuntimeUi() {
        val developer = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
        assertTrue(developer.contains("Media dispatch control tower"))
        assertTrue(developer.contains("Media final validation gate"))
        assertTrue(developer.contains("Redaction safe"))
        assertFalse(Regex("\\\"Phase \\d+").containsMatchIn(developer))
        assertFalse(developer.contains("secret-safe telemetry"))
        assertFalse(developer.contains("cleanup armed"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
}
