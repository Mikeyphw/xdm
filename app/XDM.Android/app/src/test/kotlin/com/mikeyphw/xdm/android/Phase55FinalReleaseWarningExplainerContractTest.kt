package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase55FinalReleaseWarningExplainerContractTest {
    private val root = androidRoot()

    @Test
    fun releaseReadinessShowsWarningExplainerInsteadOfBareCounts() {
        val developerTools = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(developerTools.contains("Release warning explainer"))
        assertTrue(developerTools.contains("Impact: ${'$'}{explanation.impact}"))
        assertTrue(developerTools.contains("Safe to ignore: ${'$'}{explanation.safeToIgnore}"))
        assertTrue(developerTools.contains("Fix action: ${'$'}{explanation.fixAction}"))
        assertTrue(developerTools.contains("Owning check: ${'$'}{explanation.owner.validator}"))
        assertTrue(model.contains("FinalReleaseGateExplanation"))
        assertTrue(model.contains("redactedExplanationSummary"))
        assertTrue(viewModel.contains("finalReleaseGateReport.redactedExplanationSummary()"))
        assertFalse("Normal UI must not render raw final gate ids", developerTools.contains("check.id"))
        assertFalse("Normal UI must not render raw enum names", developerTools.contains("FinalReleaseGateSeverity"))
    }

    @Test
    fun explainerMapsWarningsToOwningValidatorsAndSafeActions() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt")
        val tests = source("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModelsTest.kt")

        assertTrue(model.contains("tools/verify-aria2-runtime.py"))
        assertTrue(model.contains("tools/run-final-release-gate.sh"))
        assertTrue(model.contains("Yes for Native-only debug testing"))
        assertTrue(model.contains("Yes in debug diagnostics before a full gate run"))
        assertTrue(model.contains("Run the full Devtool selected-task validation"))
        assertTrue(tests.contains("debugWarningsIncludeActionableReleaseExplanations"))
        assertTrue(tests.contains("assertFalse(redacted.contains(\"aria2.payload\"))"))
        assertTrue(tests.contains("assertFalse(redacted.contains(\"full.validation\"))"))
    }

    @Test
    fun manifestDocsAndValidatorKeepReleaseBoundary() {
        val manifest = source("PROJECT_MANIFEST.json")
        val doc = source("docs/architecture/PHASE-55-FINAL-RELEASE-WARNING-EXPLAINER.md")
        val validator = source("tools/validate-phase55-final-release-warning-explainer.py")
        val phase48Gate = source("tools/run-phase-48-final-release-gate.sh")
        val finalGate = source("tools/run-final-release-gate.sh")
        val changelog = source("../../CHANGELOG.md")

        assertTrue(manifest.contains("\"field_bugfix_phase_55\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"debug_workbench_reopened\": false"))
        assertTrue(doc.contains("Phase55 explains final-release warnings"))
        assertTrue(doc.contains("does not change release criteria"))
        assertTrue(validator.contains("Release warning explainer"))
        assertTrue(phase48Gate.contains("validate-phase55-final-release-warning-explainer.py"))
        assertTrue(finalGate.contains("tools/validate-phase55-final-release-warning-explainer.py"))
        assertTrue(changelog.contains("XDM Android Phase 55 Final Release Warning Explainer"))
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
