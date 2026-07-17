package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumSource
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.transfer.BackendSnapshot
import com.mikeyphw.xdm.android.transfer.InMemoryBackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.InMemoryChecksumWorkflowStore
import com.mikeyphw.xdm.android.transfer.ChecksumVerificationService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionVerificationCoordinatorTest {
    @Test
    fun checksumMismatchMovesCompletionToRecovery() = runBlocking {
        val file = kotlin.io.path.createTempFile().toFile().apply {
            writeText("actual")
            deleteOnExit()
        }
        val checksumStore = InMemoryChecksumWorkflowStore()
        checksumStore.saveExpectation(
            ChecksumExpectation(
                id = "expectation",
                downloadId = "download",
                algorithm = ChecksumAlgorithm.Sha256,
                expectedHex = "0000",
                source = ChecksumSource.UserInput,
                createdAtEpochMs = 1L,
            ),
        )
        val coordinator = CompletionVerificationCoordinator(checksumStore, InMemoryBackendOwnershipStore())
        val completed = coordinator.complete(download(file), BackendSnapshot("task", DownloadState.Completed, file.length(), file.length(), 0, completedUri = file.toURI().toString()))
        assertEquals(DownloadState.RecoveryRequired, completed.state)
        assertTrue(checksumStore.results("download").single().matchesExpectation == false)
    }

    @Test
    fun matchingChecksumAllowsCompletionAndRecordsTrustedBlocks() = runBlocking {
        val file = kotlin.io.path.createTempFile().toFile().apply {
            writeText("actual")
            deleteOnExit()
        }
        val checksumStore = InMemoryChecksumWorkflowStore()
        val verifier = ChecksumVerificationService { 1L }
        val hex = verifier.digestFile(file, ChecksumAlgorithm.Sha256)
        checksumStore.saveExpectation(
            ChecksumExpectation("expectation", "download", ChecksumAlgorithm.Sha256, hex, ChecksumSource.UserInput, 1L),
        )
        val coordinator = CompletionVerificationCoordinator(checksumStore, InMemoryBackendOwnershipStore())
        val completed = coordinator.complete(download(file), BackendSnapshot("task", DownloadState.Completed, file.length(), file.length(), 0, completedUri = file.toURI().toString()))
        assertEquals(DownloadState.Completed, completed.state)
        assertTrue(checksumStore.trustedManifest("download") != null)
    }

    private fun download(file: java.io.File) = Download(
        id = "download",
        fileName = file.name,
        sourceUrl = "https://example.test/file",
        destinationUri = file.toURI().toString(),
        state = DownloadState.Downloading,
        backend = BackendType.Native,
        bytesReceived = 0,
        totalBytes = file.length(),
        speedBytesPerSecond = 0,
        queueId = null,
        priority = 0,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        conflictPolicy = FilenameConflictPolicy.Rename,
    )
}
