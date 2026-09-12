package com.mikeyphw.xdm.android.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationJournalCodecTest {
    @Test
    fun committedJournalRoundTripsForCrashRecovery() {
        val record = PublicationCommitRecord(
            generation = PublicationGeneration("embedded-owner=7", 3L, 11L),
            sourcePath = "/tmp/stage=one.mkv",
            stagingPath = "/tmp/stage=one.mkv",
            destinationSpec = "content://downloads/tree?id=7",
            committedUri = "content://media/external/video/media/42?source=xdm",
            bytesExpected = 12_345L,
            bytesCommitted = 12_345L,
            checksumAlgorithm = "SHA-256",
            expectationId = null,
            expectedDigest = null,
            actualDigest = null,
            verificationTimestampEpochMs = 123L,
            boundary = PublicationCommitBoundary.DestinationCommitted,
            health = CompletedArtifactHealthStatus.Present,
            message = "committed = yes",
        )
        val decoded = PublicationJournalCodec.decode(PublicationJournalCodec.encode(record))
        assertEquals(record, decoded)
    }

    @Test
    fun fileReadUsesTheSameDurableCodec() {
        val file = File.createTempFile("xdm-publication-", ".finalization.json")
        try {
            val record = PublicationCommitRecord(
                generation = PublicationGeneration("embedded-owner", 1L, 9L),
                sourcePath = "/tmp/source.mka",
                stagingPath = null,
                destinationSpec = "file:///tmp/final.mka",
                committedUri = "file:///tmp/final.mka",
                bytesExpected = 2048L,
                bytesCommitted = 2048L,
                checksumAlgorithm = null,
                expectationId = null,
                expectedDigest = null,
                actualDigest = null,
                verificationTimestampEpochMs = 9L,
                boundary = PublicationCommitBoundary.DestinationCommitted,
                health = CompletedArtifactHealthStatus.Present,
                message = "ready",
            )
            PublicationJournalCodec.write(file, record)
            assertTrue(file.length() > 0L)
            assertEquals(record, PublicationJournalCodec.read(file))
        } finally {
            file.delete()
        }
    }
}
