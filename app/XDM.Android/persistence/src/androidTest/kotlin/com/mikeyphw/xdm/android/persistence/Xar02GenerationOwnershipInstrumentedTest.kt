package com.mikeyphw.xdm.android.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaOutputOwnerKind
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaOutputState
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Xar02GenerationOwnershipInstrumentedTest {
    @Test
    fun staleDownloadSnapshotCannotUndoAtomicQueueClaim() = runBlocking {
        withRepository { repository, _ ->
            assertTrue(repository.save(download("claim")))
            val stale = requireNotNull(repository.findDownload("claim"))
            assertTrue(
                repository.claimQueueSlotAtomically(
                    downloadId = stale.id,
                    queueId = stale.queueId,
                    maxConcurrent = 1,
                    activeStates = setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Finalizing, DownloadState.Verifying),
                    candidateStates = setOf(DownloadState.Queued),
                    nowEpochMs = 200L,
                ),
            )
            assertFalse(repository.save(stale.copy(state = DownloadState.Paused, updatedAtEpochMs = 300L)))
            assertEquals(DownloadState.Connecting, repository.findDownload(stale.id)?.state)
        }
    }

    @Test
    fun reprioritizeRejectsAConcurrentClaimInsteadOfRestoringQueuedState() = runBlocking {
        withRepository { repository, _ ->
            assertTrue(repository.save(download("move-a")))
            assertTrue(repository.save(download("move-b").copy(priority = 10)))
            val stale = listOf(requireNotNull(repository.findDownload("move-a")), requireNotNull(repository.findDownload("move-b")))
            assertTrue(repository.claimQueueSlotAtomically("move-a", "default", 1, setOf(DownloadState.Connecting, DownloadState.Downloading), setOf(DownloadState.Queued), 250L))
            assertFalse(repository.reprioritizeDownloads(stale.mapIndexed { index, item -> item.copy(priority = 100 - index * 10, updatedAtEpochMs = 300L) }))
            assertEquals(DownloadState.Connecting, repository.findDownload("move-a")?.state)
        }
    }

    @Test
    fun staleCaptureRefreshCannotReplaceNewerVariantSet() = runBlocking {
        withRepository { repository, _ ->
            val seed = capture("capture")
            assertTrue(repository.saveMediaCaptureWithVariants(seed, listOf(variant("old", seed.id)), 100L))
            val stale = requireNotNull(repository.findMediaCapture(seed.id))
            assertTrue(repository.saveMediaCaptureWithVariants(stale.copy(updatedAtEpochMs = 300L), listOf(variant("new", seed.id)), 300L))
            assertFalse(repository.saveMediaCaptureWithVariants(stale.copy(updatedAtEpochMs = 400L), listOf(variant("stale", seed.id)), 400L))
            assertEquals(listOf("new"), repository.variantsForMediaCapture(seed.id).map { it.id })
        }
    }

    @Test
    fun terminalMediaOutputCannotBeResurrectedByLateCallback() = runBlocking {
        withRepository { repository, _ ->
            assertTrue(repository.saveMediaCapture(capture("output-capture")))
            val queued = output("output-capture")
            assertTrue(repository.saveMediaOutput(queued))
            val observed = repository.mediaOutputs.first().single { it.id == queued.id }
            assertTrue(repository.saveMediaOutput(observed.copy(state = MediaOutputState.Completed, completedArtifactUri = "content://downloads/final", completedArtifactGeneration = 1L, updatedAtEpochMs = 200L)))
            assertFalse(repository.saveMediaOutput(observed.copy(state = MediaOutputState.Active, updatedAtEpochMs = 300L)))
            assertEquals(MediaOutputState.Completed, repository.mediaOutputs.first().single { it.id == queued.id }.state)
        }
    }

    private suspend fun withRepository(block: suspend (DownloadRepository, AppDatabase) -> Unit) {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { block(DownloadRepository(database), database) } finally { database.close() }
    }

    private fun download(id: String) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://files.example.test/$id.bin",
        destinationUri = "file:/downloads/$id.bin",
        state = DownloadState.Queued,
        backend = BackendType.Native,
        bytesReceived = 0L,
        totalBytes = null,
        speedBytesPerSecond = 0L,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
    )

    private fun capture(id: String) = MediaCaptureRecord(
        id = id,
        sourceUrl = "https://media.example.test/$id/master.m3u8",
        pageUrl = "https://media.example.test/watch",
        title = "Capture",
        status = MediaCaptureStatus.MetadataReady,
        kind = MediaSourceKind.HlsPlaylist,
        mimeType = "application/vnd.apple.mpegurl",
        container = "hls",
        codecs = null,
        durationMs = 60_000L,
        thumbnailUrl = null,
        fileName = "$id.mp4",
        variantCount = 1,
        downloadId = null,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        resolutionStatus = MediaResolutionStatus.Resolved,
    )

    private fun variant(id: String, captureId: String) = MediaVariant(
        id = id,
        captureId = captureId,
        url = "https://media.example.test/$id.m3u8",
        kind = MediaVariantKind.Video,
        mimeType = "application/vnd.apple.mpegurl",
    )

    private fun output(captureId: String) = MediaOutputRecord(
        id = "output-$captureId",
        captureId = captureId,
        ownerKind = MediaOutputOwnerKind.EmbeddedFfmpeg,
        ownerId = "owner-$captureId",
        downloadId = null,
        attemptGeneration = 1L,
        destinationUri = "content://downloads/$captureId",
        fileName = "$captureId.mp4",
        mimeType = "video/mp4",
        selectedTrackIds = setOf("video"),
        state = MediaOutputState.Queued,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
    )
}
