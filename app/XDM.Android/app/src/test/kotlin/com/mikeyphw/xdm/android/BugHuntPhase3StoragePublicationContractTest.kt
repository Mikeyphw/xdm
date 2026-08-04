package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase3StoragePublicationContractTest {
    private val root = androidRoot()

    @Test
    fun publicationJournalsAndArtifactHealthAreExplicit() {
        val models = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt")
        val fsWriter = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriter.kt")
        val androidWriter = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
        listOf(
            "PublicationCommitBoundary",
            "CompletedArtifactHealthStatus",
            "PublicationGeneration",
            "PublicationCommitRecord",
            "committedUri",
            "attemptGeneration",
            "artifactGeneration",
            "expectedDigest",
            "actualDigest",
            "verificationTimestampEpochMs",
            "fsyncParentDirectoryIfSupported",
        ).forEach { assertTrue("Missing Phase 3 publication contract: $it", models.contains(it)) }
        assertTrue(fsWriter.contains("PublicationJournalCodec.write"))
        assertTrue(fsWriter.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(androidWriter.contains("PublicationJournalCodec.write"))
        assertTrue(androidWriter.contains("queryIsPending"))
    }

    @Test
    fun mediaStoreLookupAndDestinationNamesAreNotPrefixOrPathTraversalBased() {
        val androidWriter = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
        val models = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt")
        assertTrue(androidWriter.contains("${'$'}{MediaStore.MediaColumns.RELATIVE_PATH}=?"))
        assertFalse(androidWriter.contains("${'$'}{MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"))
        assertTrue(androidWriter.contains("collisionResistantComponent"))
        assertTrue(models.contains("androidProviderSafeFileName"))
        assertTrue(models.contains("maxUtf8Bytes"))
    }

    @Test
    fun checksumAndSelectiveRepairFailClosed() {
        val checksum = source("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/ChecksumVerification.kt")
        val repair = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeSelectiveRepairService.kt")
        assertTrue(checksum.contains("parseExpectedChecksum"))
        assertTrue(checksum.contains("Checksum must be exactly one hexadecimal digest"))
        assertTrue(checksum.contains("checksum must contain exactly"))
        assertTrue(repair.contains("Selective repair requires HTTP 206"))
        assertTrue(repair.contains("Content-Range mismatch"))
        assertTrue(repair.contains("If-Range"))
        assertTrue(repair.contains(".repair-"))
        assertTrue(repair.contains("trailing bytes"))
        assertFalse(repair.contains("response.code !in setOf(200, 206)"))
    }

    @Test
    fun manifestRecordsPhaseThreeAndCommitMessage() {
        val manifest = source("PROJECT_MANIFEST.json")
        assertTrue(manifest.contains("bug_hunt_remediation_phase_3"))
        assertTrue(manifest.contains("commit_message"))
        assertTrue(manifest.contains("Storage, Publication, Verification, and Repair"))
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
