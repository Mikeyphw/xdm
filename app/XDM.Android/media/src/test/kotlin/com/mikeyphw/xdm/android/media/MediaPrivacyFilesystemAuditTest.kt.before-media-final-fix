package com.mikeyphw.xdm.android.media

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPrivacyFilesystemAuditTest {
    @Test
    fun durableFilesystemSecretIsBlockedAndRedacted() {
        val root = Files.createTempDirectory("browser-capture-import-journal").toFile()
        try {
            root.resolve("leak.journal").writeText("Authorization: Bearer secret-browser-token")
            val audit = MediaSessionPrivacyAuditPlanner().audit(
                captures = emptyList(),
                variants = emptyList(),
                libraryItems = emptyList(),
                executionJobs = emptyList(),
                filesystemRoots = listOf(root),
            )
            assertEquals(1, audit.blockerCount)
            assertFalse(audit.durableSecretSafe)
            assertFalse(audit.findings.joinToString("\n") { it.summary }.contains("secret-browser-token"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedAtomicAndSidecarReplacementFilesAreScanned() {
        val root = Files.createTempDirectory("secure-request-envelopes-v1").toFile()
        try {
            root.resolve("capture.xdm-secure.bak").writeText("Authorization: Bearer secret-bak-token")
            root.resolve("session.properties.new").writeText("Cookie: secret-new-cookie")
            root.resolve("variant.xdm-secure.tmp-deadbeef").writeText("access_token=secret-temp-token")
            val audit = MediaSessionPrivacyAuditPlanner().audit(
                captures = emptyList(),
                variants = emptyList(),
                libraryItems = emptyList(),
                executionJobs = emptyList(),
                filesystemRoots = listOf(root),
            )
            assertEquals(3, audit.blockerCount)
            assertFalse(audit.durableSecretSafe)
            val summary = audit.findings.joinToString("\n") { it.summary }
            assertFalse(summary.contains("secret-bak-token"))
            assertFalse(summary.contains("secret-new-cookie"))
            assertFalse(summary.contains("secret-temp-token"))
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun oversizedRelevantFileMakesCoverageIncompleteEvenWhenPrefixIsClean() {
        val root = Files.createTempDirectory("media-privacy-oversized").toFile()
        try {
            root.resolve("large.sidecar").writeText("A".repeat(300 * 1024))
            val audit = MediaSessionPrivacyAuditPlanner().audit(
                captures = emptyList(),
                variants = emptyList(),
                libraryItems = emptyList(),
                executionJobs = emptyList(),
                filesystemRoots = listOf(root),
            )
            assertFalse(audit.filesystemCoverageComplete)
            assertTrue(audit.filesystemCoverageIssueCount >= 1)
            assertFalse(audit.durableSecretSafe)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun depthTruncationMakesCoverageIncomplete() {
        val root = Files.createTempDirectory("media-privacy-depth").toFile()
        try {
            var current = root
            repeat(7) { depth -> current = current.resolve("level-$depth").apply { mkdir() } }
            current.resolve("deep.journal").writeText("ciphertext=ABCDEF")
            val audit = MediaSessionPrivacyAuditPlanner().audit(
                captures = emptyList(),
                variants = emptyList(),
                libraryItems = emptyList(),
                executionJobs = emptyList(),
                filesystemRoots = listOf(root),
            )
            assertFalse(audit.filesystemCoverageComplete)
            assertTrue(audit.filesystemCoverageIssueCount >= 1)
            assertFalse(audit.durableSecretSafe)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ciphertextOnlyJournalAndAbsentSurfaceAreSafe() {
        val root = Files.createTempDirectory("secure-request-envelopes-v1").toFile()
        val absent = root.parentFile.resolve("browser-capture-import-journal-absent-${System.nanoTime()}")
        try {
            root.resolve("capture.xdm-secure").writeText("ciphertext=A1B2C3D4\niv=0123456789")
            val audit = MediaSessionPrivacyAuditPlanner().audit(
                captures = emptyList(),
                variants = emptyList(),
                libraryItems = emptyList(),
                executionJobs = emptyList(),
                filesystemRoots = listOf(root, absent),
            )
            assertEquals(0, audit.blockerCount)
            assertTrue(audit.durableSecretSafe)
            assertTrue(audit.findings.any { it.redactedPreview.contains("surface absent") })
        } finally {
            root.deleteRecursively()
        }
    }
}
