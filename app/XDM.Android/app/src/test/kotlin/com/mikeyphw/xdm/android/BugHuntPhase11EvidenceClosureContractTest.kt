package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase11EvidenceClosureContractTest {
    private val root = androidRoot()

    @Test
    fun formerlySelfReferentialRowsHavePhaseSpecificEvidence() {
        val matrix = source("tools/bug-hunt-phase11-validation-matrix.json")
        listOf("BH11-003", "BH11-014", "BH11-019", "BH11-032", "BH11-040", "BH11-047").forEach { id ->
            val row = matrix.substringAfter("\"id\": \"$id\"").substringBefore("\n    },")
            assertTrue("$id must include an executable test", row.contains("/src/test/"))
            assertTrue("$id must include a phase-specific validator", row.contains("validate-bug-hunt-phase") && !row.onlyMentionsPhase11Validator())
            assertFalse("$id must not rely only on the matrix runner", row.selfOnlyEvidence())
        }
    }

    @Test
    fun matrixEvidenceClosesPreviouslyWeakRequirements() {
        val matrix = source("tools/bug-hunt-phase11-validation-matrix.json")
        assertTrue(matrix.contains("NativeHttpDownloadBackendTest.kt"))
        assertTrue(matrix.contains("BugHuntPhase3StoragePublicationContractTest.kt"))
        assertTrue(matrix.contains("BugHuntPhase8DownloadActionsUiTruthfulnessContractTest.kt"))
        assertTrue(matrix.contains("BrowserHandoffMediaModelsTest.kt"))
        assertTrue(matrix.contains("EngineEscalationPlannerTest.kt"))
        assertTrue(matrix.contains("BackendCoordinatorTest.kt"))
        assertTrue(matrix.contains("validate-bug-hunt-phase2-download-execution.py"))
        assertTrue(matrix.contains("validate-bug-hunt-phase3-storage-publication-verification-repair.py"))
        assertTrue(matrix.contains("validate-bug-hunt-phase8-download-actions-ui-truthfulness.py"))
    }

    @Test
    fun validatorsRejectSelfOnlyRows() {
        val validator = source("tools/validate-bug-hunt-phase11-validation-matrix.py")
        assertTrue(validator.contains("self-only Phase 11 evidence"))
        assertTrue(validator.contains("phase-specific executable evidence"))
        assertTrue(validator.contains("phase-specific validator beyond Phase 11"))
        assertTrue(validator.contains("tools/bug-hunt-phase11-validation-matrix.json"))
        assertTrue(validator.contains("tools/run-bug-hunt-phase11-validation-matrix.sh"))
    }

    private fun String.onlyMentionsPhase11Validator(): Boolean =
        contains("validate-bug-hunt-phase11-validation-matrix.py") &&
            !contains("validate-bug-hunt-phase2-download-execution.py") &&
            !contains("validate-bug-hunt-phase3-storage-publication-verification-repair.py") &&
            !contains("validate-bug-hunt-phase8-download-actions-ui-truthfulness.py") &&
            !contains("validate-bug-hunt-phase5-browser-handoff-media.py")

    private fun String.selfOnlyEvidence(): Boolean =
        contains("tools/bug-hunt-phase11-validation-matrix.json") &&
            contains("tools/run-bug-hunt-phase11-validation-matrix.sh") &&
            !contains("/src/test/")

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
