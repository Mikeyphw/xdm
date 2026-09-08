package com.mikeyphw.xdm.android.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumSource
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadAdmissionDl01InstrumentedTest {
    @Test
    fun concurrentSameUrlAdmissionCreatesExactlyOneDownload() = runBlocking {
        withRepository { repository, database ->
            val first = download("first")
            val second = download("second")
            val results = coroutineScope {
                listOf(first, second).map { candidate ->
                    async {
                        repository.admitDownload(
                            download = candidate,
                            duplicateLookupUrl = candidate.sourceUrl,
                            checksumExpectation = null,
                        )
                    }
                }.awaitAll()
            }

            assertEquals(1, database.downloadDao().count())
            assertEquals(1, results.count { it is DownloadAdmissionResult.Created })
            assertEquals(1, results.count { it is DownloadAdmissionResult.NeedsConfirmation })
        }
    }

    @Test
    fun duplicateActionsHaveDistinctAdmissionOutcomes() = runBlocking {
        withRepository { repository, database ->
            val original = download("original")
            assertTrue(
                repository.admitDownload(original, original.sourceUrl, null) is DownloadAdmissionResult.Created,
            )

            assertTrue(
                repository.admitDownload(
                    download("ask"),
                    original.sourceUrl,
                    null,
                    DuplicateUrlAction.Ask,
                ) is DownloadAdmissionResult.NeedsConfirmation,
            )
            assertTrue(
                repository.admitDownload(
                    download("open"),
                    original.sourceUrl,
                    null,
                    DuplicateUrlAction.OpenExisting,
                ) is DownloadAdmissionResult.OpenExisting,
            )
            assertTrue(
                repository.admitDownload(
                    download("skip"),
                    original.sourceUrl,
                    null,
                    DuplicateUrlAction.Skip,
                ) is DownloadAdmissionResult.Skipped,
            )
            assertTrue(
                repository.admitDownload(
                    download("again"),
                    original.sourceUrl,
                    null,
                    DuplicateUrlAction.AddAgain,
                ) is DownloadAdmissionResult.Created,
            )
            assertEquals(2, database.downloadDao().count())
        }
    }

    @Test
    fun checksumExpectationCommitsWithDownload() = runBlocking {
        withRepository { repository, database ->
            val candidate = download("checksum")
            val expectation = ChecksumExpectation(
                id = "checksum-valid",
                downloadId = candidate.id,
                algorithm = ChecksumAlgorithm.Sha256,
                expectedHex = "b".repeat(64),
                source = ChecksumSource.UserInput,
                createdAtEpochMs = 100L,
            )

            assertTrue(
                repository.admitDownload(
                    download = candidate,
                    duplicateLookupUrl = candidate.sourceUrl,
                    checksumExpectation = expectation,
                ) is DownloadAdmissionResult.Created,
            )
            assertEquals(candidate.id, database.downloadDao().findById(candidate.id)?.id)
            assertEquals("b".repeat(64), database.checksumDao().expectations(candidate.id).single().expectedHex)
        }
    }

    @Test
    fun checksumSidecarFailureRollsBackDownloadInsert() = runBlocking {
        withRepository { repository, database ->
            val candidate = download("rollback")
            val invalidForeignKey = ChecksumExpectation(
                id = "checksum-wrong-owner",
                downloadId = "missing-download",
                algorithm = ChecksumAlgorithm.Sha256,
                expectedHex = "a".repeat(64),
                source = ChecksumSource.UserInput,
                createdAtEpochMs = 100L,
            )

            var rejected = false
            try {
                repository.admitDownload(
                    download = candidate,
                    duplicateLookupUrl = candidate.sourceUrl,
                    checksumExpectation = invalidForeignKey,
                    duplicateActionOverride = DuplicateUrlAction.AddAgain,
                )
            } catch (_: Exception) {
                rejected = true
            }
            if (!rejected) fail("Expected the checksum foreign-key write to fail")

            assertNull(database.downloadDao().findById(candidate.id))
            assertEquals(0, database.downloadDao().count())
        }
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

    private fun download(id: String) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://files.example.test/archive.bin",
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
}
