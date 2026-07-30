package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryStorageDoctorTest {
    @Test
    fun summaryGroupsRecoveryProblemsWithoutRawArtifacts() {
        val records = listOf(
            record("missing", RecoveryClassification.MissingPartialFile, RecoveryAction.RestartFromZero, artifact = "file:///data/user/0/pkg/files/private.bin.xdm.part"),
            record("orphan", RecoveryClassification.OrphanedArtifact, RecoveryAction.AdoptOrphan, artifact = "/data/user/0/pkg/files/orphan.aria2"),
            record("complete", RecoveryClassification.CompletionRecovered, RecoveryAction.Validate, artifact = "content://downloads/public_downloads/42"),
        )

        val summary = RecoveryStorageDoctor.summarize(records)
        val export = summary.exportReport()

        assertEquals(3, summary.totalRecords)
        assertEquals(1, summary.missingPartialRecords)
        assertEquals(1, summary.orphanedArtifactRecords)
        assertEquals(1, summary.completedVisibilityRecords)
        assertTrue(summary.actionItems.any { it.label == "Retry from source" })
        assertTrue(summary.actionItems.any { it.label == "Re-check storage visibility" })
        assertFalse(export.contains("content://"))
        assertFalse(export.contains("/data/user"))
        assertFalse(export.contains("private.bin"))
    }

    @Test
    fun itemGuidanceKeepsDestructiveActionsOutOfDefaultFlow() {
        val orphan = record("orphan", RecoveryClassification.OrphanedArtifact, RecoveryAction.AdoptOrphan)
        val missing = record("missing", RecoveryClassification.MissingPartialFile, RecoveryAction.RestartFromZero)

        assertEquals("Validate untracked artifact", RecoveryStorageDoctor.itemGuidance(orphan).label)
        assertEquals("Retry from source", RecoveryStorageDoctor.itemGuidance(missing).label)
        assertEquals("Untracked app-private artifact", RecoveryStorageDoctor.safeArtifactLabel(orphan))
    }

    private fun record(
        id: String,
        classification: RecoveryClassification,
        action: RecoveryAction,
        artifact: String = "file:///tmp/$id.part",
    ) = RecoveryRecord(
        id = id,
        downloadId = "download-$id",
        artifactPath = artifact,
        classification = classification,
        reason = "Needs review",
        createdAtEpochMs = 1L,
        recommendedAction = action,
    )
}
