package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUiTruthTest {
    @Test
    fun completedIsVerifiedOnlyWithEvidence() {
        val item = download(DownloadState.Completed)

        val unknown = DownloadUiTruthPlanner.truth(item, DownloadActionContext())
        assertEquals("Needs check", unknown.badge)
        assertTrue(unknown.status.contains("health is not confirmed"))

        val present = DownloadActionContext(
            artifact = CompletedArtifactCapabilities(
                health = CompletedArtifactHealth.Present,
            ),
        )
        assertEquals("Complete", DownloadUiTruthPlanner.truth(item, present).badge)
        assertTrue(
            DownloadUiTruthPlanner.truth(item, present)
                .status
                .contains("verification not confirmed"),
        )

        val verified = present.copy(
            latestChecksum = checksum(matches = true),
        )
        assertEquals(
            "Verified",
            DownloadUiTruthPlanner.truth(item, verified).badge,
        )
    }

    @Test
    fun pausedItemNeverReceivesQueuePosition() {
        val paused = download(DownloadState.Paused, id = "paused")
        val queued = download(DownloadState.Queued, id = "queued")
        val context = DownloadUiTruthPlanner.contextFor(paused, listOf(paused, queued))
        assertEquals(null, context.queuePosition)
        assertEquals("Paused by you", DownloadUiTruthPlanner.truth(paused, context).status)
    }

    @Test
    fun queuePositionUsesPriorityAndCreationOrder() {
        val first = download(DownloadState.Queued, id = "first", priority = 20)
        val second = download(DownloadState.Queued, id = "second", priority = 10)
        val context = DownloadUiTruthPlanner.contextFor(second, listOf(second, first))
        assertEquals(2, context.queuePosition)
        assertEquals("Queue position 2 of 2", DownloadUiTruthPlanner.truth(second, context).supportingText)
    }

    @Test
    fun resumeClaimRequiresDurableEvidenceFromPersistence() {
        val paused = download(DownloadState.Paused).copy(bytesReceived = 512)
        val withoutEvidence = DownloadUiTruthPlanner.contextFor(paused, listOf(paused))
        val withEvidence = DownloadUiTruthPlanner.contextFor(
            paused,
            listOf(paused),
            validatedPartialAvailable = true,
        )
        assertFalse(withoutEvidence.validatedPartialAvailable)
        assertTrue(withEvidence.validatedPartialAvailable)
    }

    @Test
    fun staleSpeedIsIgnoredOutsideDownloading() {
        val paused = download(DownloadState.Paused).copy(speedBytesPerSecond = 9999)
        assertEquals("Paused", DownloadUiTruthPlanner.truth(paused, DownloadActionContext()).trailingText)
        assertFalse(DownloadUiTruthPlanner.truth(paused, DownloadActionContext()).trailingText.contains("B/s"))
    }

    @Test
    fun verifyingSeparatesPayloadFromOverallCompletion() {
        val item = download(DownloadState.Verifying).copy(bytesReceived = 1024)
        val truth = DownloadUiTruthPlanner.truth(item, DownloadActionContext())
        assertTrue(truth.byteProgressText.contains("1024"))
        assertTrue(truth.overallProgressText.contains("verification in progress"))
    }

    private fun checksum(matches: Boolean) = ChecksumResult(
        id = "checksum",
        downloadId = "id",
        algorithm = ChecksumAlgorithm.Sha256,
        calculatedHex = "a".repeat(64),
        matchesExpectation = matches,
        verifiedAtEpochMs = 3,
        bytesVerified = 1024,
    )

    private fun download(state: DownloadState, id: String = "id", priority: Int = 0) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://example.test/$id.bin",
        destinationUri = "content://downloads/$id",
        state = state,
        backend = BackendType.Native,
        bytesReceived = 128,
        totalBytes = 1024,
        speedBytesPerSecond = 0,
        queueId = "default",
        priority = priority,
        createdAtEpochMs = if (id == "first") 1 else 2,
        updatedAtEpochMs = 2,
    )
}
