package com.mikeyphw.xdm.android.storage

import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar06PublicationTransactionContractTest {
    @Test
    fun stagingIdentityIncludesAttemptAndArtifactGeneration() {
        val root = Files.createTempDirectory("xdm-xar06-staging").toFile()
        try {
            val writer = FileDestinationWriter(root)
            val attemptOne = writer.artifactPaths(DestinationRequest("same", DestinationUris.APP_PRIVATE_DOWNLOADS, "asset.bin", attemptGeneration = 1L, artifactGeneration = 100L))
            val attemptTwo = writer.artifactPaths(DestinationRequest("same", DestinationUris.APP_PRIVATE_DOWNLOADS, "asset.bin", attemptGeneration = 2L, artifactGeneration = 200L))
            assertNotEquals(attemptOne.stagingFile.name, attemptTwo.stagingFile.name)
            assertTrue(attemptOne.stagingFile.name.contains("attempt-1.artifact-100"))
            assertTrue(attemptTwo.stagingFile.name.contains("attempt-2.artifact-200"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileResumeRefusesToReplaceExistingFinalFromStaleStaging() = runBlocking {
        val root = Files.createTempDirectory("xdm-xar06-resume").toFile()
        try {
            val final = root.resolve("movie.mp4").apply { writeText("existing-final") }
            val writer = FileDestinationWriter(root)
            val request = DestinationRequest("d1", final.toURI().toString(), final.name, conflictPolicy = FilenameConflictPolicy.Resume, attemptGeneration = 3L, artifactGeneration = 300L)
            writer.artifactPaths(request).stagingFile.writeText("stale-partial")
            try {
                writer.prepare(request)
                throw AssertionError("Resume prepared a destructive replacement")
            } catch (expected: DestinationConflictException) {
                assertTrue(expected.message!!.contains("stale staging-file"))
            }
            assertEquals("existing-final", final.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun renameMoveDoesNotOverwriteToctouCreatedTarget() = runBlocking {
        val root = Files.createTempDirectory("xdm-xar06-toctou").toFile()
        try {
            root.resolve("archive.zip").writeText("old")
            val writer = FileDestinationWriter(root)
            val prepared = writer.prepare(DestinationRequest("d1", root.resolve("archive.zip").toURI().toString(), "archive.zip", conflictPolicy = FilenameConflictPolicy.Rename, attemptGeneration = 2L, artifactGeneration = 44L))
            prepared.artifacts.stagingFile.writeText("new")
            root.resolve("archive (1).zip").writeText("racer")
            try {
                prepared.promote()
                throw AssertionError("Rename policy overwrote a TOCTOU-created target")
            } catch (expected: DestinationPublicationException) {
                assertTrue(prepared.artifacts.stagingFile.isFile)
            }
            assertEquals("racer", root.resolve("archive (1).zip").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zeroBytePublicationIsValidWhenExpectedSizeIsZero() {
        assertTrue(CompletedArtifactHealthProbe.sizeMatches(0L, 0L))
        assertFalse(CompletedArtifactHealthProbe.sizeMatches(null, 0L))
    }

    @Test
    fun destinationCapacityAccountsForProviderStagingAndFinalCopy() {
        val required = DestinationCapacityPlanner.requiredBytesForPublication(
            expectedTotalBytes = 10_000L,
            existingBytes = 4_000L,
            resumedBytes = 2_000L,
            contentDestination = true,
        )!!
        assertTrue(required >= 22_000L)
    }
}
