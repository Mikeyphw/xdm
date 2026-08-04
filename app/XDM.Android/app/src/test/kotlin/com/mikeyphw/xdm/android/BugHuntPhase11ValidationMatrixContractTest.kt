package com.mikeyphw.xdm.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BugHuntPhase11ValidationMatrixContractTest {
    private val root = File(".").canonicalFile

    @Test
    fun matrixContainsEveryRoadmapRequirementExactlyOnce() {
        val matrix = File(root, "tools/bug-hunt-phase11-validation-matrix.json").readText()
        assertTrue(matrix.contains("XDM Android Bug Hunt Phase 11 Validation Matrix"))
        assertTrue(matrix.contains("\"total_requirements\": 80"))
        assertTrue(matrix.contains("BH11-001"))
        assertTrue(matrix.contains("BH11-080"))
        assertEquals("each matrix row should have one id", 80, Regex("BH11-\\d{3}").findAll(matrix).count())
        assertTrue(matrix.contains("Untrusted external apps cannot mutate downloads."))
        assertTrue(matrix.contains("Cloud backup and device-to-device transfer rules exclude all sensitive and stale execution state"))
    }

    @Test
    fun everyRowIsRoutedThroughExecutableGates() {
        val matrix = File(root, "tools/bug-hunt-phase11-validation-matrix.json").readText()
        assertFalse("matrix must not use documentation as sole evidence", matrix.contains("doc-only evidence"))
        assertTrue(matrix.contains("tools/run-bug-hunt-phase11-validation-matrix.sh"))
        assertTrue(matrix.contains("/src/test/"))
        assertTrue(matrix.contains("BugHuntPhase11EvidenceClosureContractTest.kt"))
        assertTrue("self-only Phase 11 evidence", matrix.contains("validate-bug-hunt-phase2-download-execution.py"))
        listOf("BH11-003", "BH11-014", "BH11-019", "BH11-032", "BH11-040", "BH11-047").forEach { id ->
            val row = matrix.substringAfter("\"id\": \"$id\"").substringBefore("\n    },")
            assertTrue("$id must no longer be self-only", row.contains("/src/test/") && row.contains("validate-bug-hunt-phase") && !row.endsWith("validate-bug-hunt-phase11-validation-matrix.py"))
        }
        assertTrue(matrix.contains("validate-bug-hunt-phase1-external-control-secrets-privacy.py"))
        assertTrue(matrix.contains("validate-bug-hunt-phase10-release-upgrade-packaging.py"))
        assertTrue(matrix.contains(":app:connectedDebugAndroidTest"))
        assertTrue(matrix.contains(":app:bundleRelease"))
        assertTrue(matrix.contains(":persistence:testDebugUnitTest"))
    }

    @Test
    fun runnerExposesStaticDeviceAndReleaseModes() {
        val runner = File(root, "tools/run-bug-hunt-phase11-validation-matrix.sh").readText()
        assertTrue(runner.contains("--static-only"))
        assertTrue(runner.contains("--device-only"))
        assertTrue(runner.contains("--release-only"))
        assertTrue(runner.contains("COMMON_TASKS"))
        assertTrue(runner.contains("DEVICE_TASKS"))
        assertTrue(runner.contains("RELEASE_TASKS"))
        assertTrue(runner.contains("run-phase10-install-upgrade-matrix.sh"))
    }
}
