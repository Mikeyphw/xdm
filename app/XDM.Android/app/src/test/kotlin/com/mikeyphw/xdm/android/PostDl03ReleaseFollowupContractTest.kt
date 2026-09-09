package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDl03ReleaseFollowupContractTest {
    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(current, "PROJECT_MANIFEST.json").isFile && File(current, "app/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate XDM.Android root from ${System.getProperty("user.dir")}")
    }

    @Test
    fun canonicalFinalGateCarriesPostDl03RoadmapSeals() {
        val root = androidRoot()
        val gate = File(root, "tools/run-final-release-gate.sh").readText()
        assertTrue(gate.contains("tools/validate-runtime-foundation-phase59-61.py"))
        assertTrue(gate.contains("tools/validate-dl02-dl03-progress-seal.py"))
        assertTrue(gate.contains("tools/validate-post-dl03-release-followup.py"))
        assertTrue(gate.contains("tools/validate-execution-media-semantics-repair.py"))
        assertTrue(gate.contains("tools/validate-add-media-ux-remodel.py"))
        assertTrue(gate.contains("matrix_owned_validators=("))
        assertTrue(gate.contains("bash tools/run-bug-hunt-phase11-validation-matrix.sh --static-only --ci"))
        assertTrue(gate.contains("Add/Media UX remodel seal is the current final UI/release source of truth"))
        assertTrue(gate.contains("execution/media semantics repair remains its functional baseline"))
        assertTrue(gate.contains("post-DL03 roadmap seal remains its historical baseline"))
        assertFalse(gate.contains("execution/media semantics repair seal is the current final source of truth"))
        assertFalse(gate.contains("post-DL03 roadmap seal is the current final source of truth"))
        assertFalse(gate.contains("Overlay 13 is the current final source of truth"))
    }

    @Test
    fun repositoryCiMatchesCurrentAndroidToolchainAndCanonicalGate() {
        val root = androidRoot()
        val repoRoot = requireNotNull(root.parentFile?.parentFile) { "Could not locate repository root" }
        val workflow = File(repoRoot, ".github/workflows/android.yml").readText()
        assertTrue(Regex("java-version: '21'").findAll(workflow).count() >= 2)
        assertFalse(workflow.contains("java-version: '17'"))
        assertTrue(Regex("gradle-version: '9\\.7\\.1'").findAll(workflow).count() >= 2)
        assertFalse(workflow.contains("gradle-version: '9.7.0'"))
        assertTrue(workflow.contains("bash tools/run-final-release-gate.sh --ci"))
    }

    @Test
    fun currentReleaseTruthIsSchema21AndRoadmapComplete() {
        val root = androidRoot()
        val readme = File(root, "README.md").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue(readme.contains("## XDM Android 0.21.0"))
        assertTrue(readme.contains("Room is schema v21"))
        assertTrue(readme.contains("Phase 17 established the original public-release boundary at Room schema v14; that phase is now historical."))
        assertTrue(manifest.contains("\"roadmap_status\": \"MC01-MC05 and DL01-DL03 sealed\""))
        assertTrue(manifest.contains("\"next_overlay\": null"))
    }
}
