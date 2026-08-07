package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadActionPlannerTest {
    @Test
    fun verifyingAndRepairingNeverAdvertisePauseButAlwaysOfferCancel() {
        listOf(DownloadState.Verifying, DownloadState.Repairing).forEach { state ->
            val actions = DownloadActionPlanner.actionsFor(download(state))
            assertFalse(actions.any { it.kind == DownloadActionKind.Pause })
            assertTrue(actions.any { it.kind == DownloadActionKind.Cancel })
            assertEquals(DownloadActionKind.OpenDetails, actions.first { it.primary }.kind)
        }
    }

    @Test
    fun queuedStartNowIsDirectPrimaryAndMovementReflectsRealPosition() {
        val context = DownloadActionContext(queuePosition = 2, queueSize = 3, publicSourceUrl = "https://example.test/file.bin")
        val actions = DownloadActionPlanner.actionsFor(download(DownloadState.Queued), context)
        assertEquals(DownloadActionKind.StartNow, actions.first { it.primary }.kind)
        assertTrue(actions.first { it.kind == DownloadActionKind.MoveUp }.enabled)
        assertTrue(actions.first { it.kind == DownloadActionKind.MoveDown }.enabled)
        assertTrue(actions.first { it.kind == DownloadActionKind.StartNow }.supportingText.contains("never routes through Pause"))
    }

    @Test
    fun completedActionsAreCapabilityAwareAndUseTruthfulLabels() {
        val artifact = CompletedArtifactCapabilities(
            health = CompletedArtifactHealth.Present,
            readable = true,
            shareable = true,
            renameable = true,
            deletable = true,
            locationBrowsable = false,
            friendlyLocation = "Android Downloads provider · Downloads folder",
            androidUri = "content://downloads/public_downloads/42",
        )
        val actions = DownloadActionPlanner.actionsFor(download(DownloadState.Completed), DownloadActionContext(artifact = artifact))
        assertEquals(DownloadActionKind.OpenFile, actions.first { it.primary }.kind)
        assertTrue(actions.any { it.kind == DownloadActionKind.CopyFriendlyLocation })
        assertTrue(actions.any { it.kind == DownloadActionKind.CopyDestination && it.label == "Copy Android URI" })
        assertTrue(actions.any { it.kind == DownloadActionKind.DeleteFile })
        assertTrue(actions.any { it.kind == DownloadActionKind.DeleteFileAndRecord })
        assertFalse(actions.any { it.kind == DownloadActionKind.OpenFolder })
        assertFalse(actions.any { it.label.contains("record", ignoreCase = true) })
    }

    @Test
    fun completedUnsupportedProviderDisablesMutatingActions() {
        val actions = DownloadActionPlanner.actionsFor(
            download(DownloadState.Completed),
            DownloadActionContext(artifact = CompletedArtifactCapabilities(health = CompletedArtifactHealth.PermissionLost)),
        )
        assertFalse(actions.first { it.kind == DownloadActionKind.OpenFile }.enabled)
        assertFalse(actions.first { it.kind == DownloadActionKind.Rename }.enabled)
        assertFalse(actions.first { it.kind == DownloadActionKind.DeleteFile }.enabled)
    }


    @Test
    fun finalSaveRecoveryOffersRetrySaveBeforeGenericRecovery() {
        val actions = DownloadActionPlanner.actionsFor(
            download(
                DownloadState.RecoveryRequired,
                errorMessage = "Final save failed, but the completed staging file is preserved. Retry save after fixing destination access.",
            ),
        )
        assertEquals(DownloadActionKind.Retry, actions.first { it.primary }.kind)
        assertEquals("Retry save", actions.first { it.primary }.label)
        assertTrue(actions.first { it.primary }.supportingText.contains("preserved completed staging file"))
        assertTrue(actions.any { it.kind == DownloadActionKind.ReviewRecovery })
    }

    @Test
    fun recoveryActionsPreserveExactItemContext() {
        val actions = DownloadActionPlanner.actionsFor(download(DownloadState.RecoveryRequired))
        assertEquals(DownloadActionKind.ReviewRecovery, actions.first { it.primary }.kind)
        assertTrue(actions.any { it.kind == DownloadActionKind.LocateFile })
        assertTrue(actions.any { it.kind == DownloadActionKind.RestartFromZero })
    }

    private fun download(state: DownloadState, errorMessage: String? = null) = Download(
        id = "download-id",
        fileName = "file.bin",
        sourceUrl = "https://example.test/file.bin",
        destinationUri = "content://downloads/public_downloads/42",
        state = state,
        backend = BackendType.Native,
        bytesReceived = if (state == DownloadState.Completed) 1024 else 128,
        totalBytes = 1024,
        speedBytesPerSecond = if (state == DownloadState.Downloading) 2048 else 0,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
        errorMessage = errorMessage,
    )
}
