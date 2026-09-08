package com.mikeyphw.xdm.android.persistence

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaOutputAdmissionMode
import com.mikeyphw.xdm.android.model.MediaOutputOwnerKind
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaOutputState
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaOutputAdmissionMc02InstrumentedTest {
    @Test
    fun concurrentPrimaryAdmissionsCreateExactlyOneOutputAndOneDownload() = runBlocking {
        withRepository { repository, database ->
            repository.saveMediaCapture(capture("capture-primary"))
            val results = coroutineScope {
                listOf(download("one"), download("two")).map { candidate ->
                    async {
                        repository.createDownloadFromMediaCapture(
                            captureId = "capture-primary",
                            download = candidate,
                            selectedTrackIds = setOf("video-720", "audio-pt"),
                            admissionMode = MediaOutputAdmissionMode.Primary,
                        ).getOrThrow()
                    }
                }.awaitAll()
            }

            assertEquals(1, results.count { it is MediaDownloadAdmissionResult.Created })
            assertEquals(1, results.count { it is MediaDownloadAdmissionResult.Existing })
            assertEquals(1, database.downloadDao().count())
            assertEquals(1, database.mediaCaptureDao().outputsForCapture("capture-primary").size)
        }
    }

    @Test
    fun appAndTermuxPrimaryAdmissionsShareOneCrossPathClaim() = runBlocking {
        withRepository { repository, database ->
            repository.saveMediaCapture(capture("capture-cross-path"))
            val results = coroutineScope {
                listOf(
                    async {
                        val result = repository.createDownloadFromMediaCapture(
                            "capture-cross-path",
                            download("app-primary"),
                            admissionMode = MediaOutputAdmissionMode.Primary,
                        ).getOrThrow()
                        result is MediaDownloadAdmissionResult.Created
                    },
                    async { termuxLikePrimaryAdmission(repository, database, "capture-cross-path") },
                ).awaitAll()
            }

            assertEquals(1, results.count { it })
            assertEquals(1, database.mediaCaptureDao().outputsForCapture("capture-cross-path").size)
            assertTrue(database.downloadDao().count() in 0..1)
        }
    }

    @Test
    fun explicitAdditionalGenerationCreatesAnotherOutput() = runBlocking {
        withRepository { repository, database ->
            repository.saveMediaCapture(capture("capture-repeat"))
            val first = repository.createDownloadFromMediaCapture(
                "capture-repeat",
                download("first"),
                selectedTrackIds = setOf("video-720"),
                admissionMode = MediaOutputAdmissionMode.Primary,
            ).getOrThrow()
            val second = repository.createDownloadFromMediaCapture(
                "capture-repeat",
                download("second"),
                selectedTrackIds = setOf("video-1080", "audio-en"),
                admissionMode = MediaOutputAdmissionMode.AdditionalGeneration,
            ).getOrThrow()

            assertTrue(first is MediaDownloadAdmissionResult.Created)
            assertTrue(second is MediaDownloadAdmissionResult.Created)
            assertEquals(2, database.downloadDao().count())
            assertEquals(2, database.mediaCaptureDao().outputsForCapture("capture-repeat").size)
        }
    }

    @Test
    fun removalArchivesCaptureWithOutputsButDeletesUnusedCapture() = runBlocking {
        withRepository { repository, database ->
            repository.saveMediaCapture(capture("capture-used"))
            repository.createDownloadFromMediaCapture(
                "capture-used",
                download("linked"),
                admissionMode = MediaOutputAdmissionMode.Primary,
            ).getOrThrow()
            assertEquals(MediaCaptureRemovalResult.Archived, repository.archiveOrDeleteMediaCapture("capture-used"))
            val archived = database.mediaCaptureDao().findById("capture-used")
            assertNotNull(archived)
            assertEquals(MediaCaptureStatus.Archived.name, archived?.status)
            assertEquals(1, database.mediaCaptureDao().outputsForCapture("capture-used").size)

            repository.saveMediaCapture(capture("capture-unused"))
            assertEquals(MediaCaptureRemovalResult.Deleted, repository.archiveOrDeleteMediaCapture("capture-unused"))
            assertNull(database.mediaCaptureDao().findById("capture-unused"))
        }
    }

    private suspend fun termuxLikePrimaryAdmission(
        repository: DownloadRepository,
        database: AppDatabase,
        captureId: String,
    ): Boolean = database.withTransaction {
        val existing = database.mediaCaptureDao().outputsForCapture(captureId)
            .firstOrNull { it.state != MediaOutputState.Hidden.name }
        if (existing != null) return@withTransaction false
        val now = 200L
        repository.saveMediaOutput(
            MediaOutputRecord(
                id = "output-termux",
                captureId = captureId,
                ownerKind = MediaOutputOwnerKind.TermuxJob,
                ownerId = "job-termux",
                downloadId = null,
                attemptGeneration = 1L,
                destinationUri = "xdm://post-processing",
                fileName = "termux.mp4",
                mimeType = "video/mp4",
                selectedTrackIds = setOf("video-720", "audio-pt"),
                state = MediaOutputState.Queued,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        database.mediaCaptureDao().markOutputCreated(captureId, MediaCaptureStatus.DownloadCreated.name, now)
        true
    }

    private suspend fun withRepository(block: suspend (DownloadRepository, AppDatabase) -> Unit) {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            block(DownloadRepository(database), database)
        } finally {
            database.close()
        }
    }

    private fun capture(id: String) = MediaCaptureRecord(
        id = id,
        sourceUrl = "https://media.example.test/master.m3u8",
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
        variantCount = 2,
        downloadId = null,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        resolutionStatus = MediaResolutionStatus.Resolved,
    )

    private fun download(id: String) = Download(
        id = id,
        fileName = "$id.mp4",
        sourceUrl = "https://media.example.test/master.m3u8",
        destinationUri = "file:/downloads/$id.mp4",
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
}
