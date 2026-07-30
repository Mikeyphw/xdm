package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase51RecoveryStorageDoctorContractTest {
    private val root = androidRoot()

    @Test
    fun recoveryScreenAddsStorageDoctorWithoutRawPathLeak() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/recovery/RecoveryScreen.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(screen.contains("Recovery + Storage Doctor"))
        assertTrue(screen.contains("Validate all safely"))
        assertTrue(screen.contains("Copy recovery report"))
        assertTrue(screen.contains("Storage Doctor never deletes files automatically"))
        assertTrue(screen.contains("RecoveryStorageDoctor.safeArtifactLabel(record)"))
        assertFalse("Normal or expanded recovery UI must not print raw artifact paths.", screen.contains("Artifact path: ${'$'}{record.artifactPath}"))
        assertFalse("Normal or expanded recovery UI must not print raw download ids.", screen.contains("Download ID: ${'$'}it"))
        assertTrue(app.contains("viewModel::validateAllRecoveryRecords"))
        assertTrue(viewModel.contains("fun validateAllRecoveryRecords"))
    }

    @Test
    fun recoveryDoctorModelKeepsReportsRedactedAndNonDestructive() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RecoveryStorageDoctor.kt")
        assertTrue(model.contains("Privacy: raw paths, source URLs, tokens, cookies, and authorization values are not included."))
        assertTrue(model.contains("safeArtifactLabel"))
        assertTrue(model.contains("Untracked app-private artifact"))
        assertTrue(model.contains("Validate all safely"))
        assertTrue(model.contains("Forget stale record"))
        assertFalse(model.contains("File(") && model.contains("delete()"))
        assertFalse(model.contains("artifactPath)"))
    }

    @Test
    fun phase51IsDocumentedAndValidatedWithoutSchemaOrRouteCreep() {
        val manifest = source("PROJECT_MANIFEST.json")
        val doc = source("docs/architecture/PHASE-51-RECOVERY-STORAGE-DOCTOR.md")
        val validator = source("tools/validate-phase51-recovery-storage-doctor.py")
        assertTrue(manifest.contains("field_bugfix_phase_51"))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(doc.contains("No automatic deletion"))
        assertTrue(doc.contains("No raw paths in normal UI"))
        assertTrue(validator.contains("Recovery + Storage Doctor"))
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
