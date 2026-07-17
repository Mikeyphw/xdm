package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumSource
import com.mikeyphw.xdm.android.model.RepairBlockStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumVerificationServiceTest {
    @Test
    fun matchingChecksumPassesWithoutNetwork() = runBlocking {
        val file = tempFile("hello")
        val service = ChecksumVerificationService { 1L }
        val hex = service.digestFile(file, ChecksumAlgorithm.Sha256)
        val result = service.verify(
            downloadId = "download",
            file = file,
            expectation = ChecksumExpectation("expectation", "download", ChecksumAlgorithm.Sha256, hex, ChecksumSource.UserInput, 1L),
        )
        assertTrue(result.matchesExpectation == true)
        assertEquals(file.length(), result.bytesVerified)
    }

    @Test
    fun oneBlockCorruptionCreatesSingleRepairRange() = runBlocking {
        val file = tempFile("abcdefgh")
        val service = TrustedBlockManifestService { 1L }
        val manifest = service.create("download", file, blockSize = 4)
        file.writeText("abcdZfgh")
        val plan = service.planRepair(file, manifest)
        assertTrue(plan.requiresNetwork)
        assertEquals(1, plan.ranges.size)
        assertEquals(1, plan.ranges.single().blockIndex)
        assertEquals(RepairBlockStatus.Corrupt, plan.ranges.single().reason)
    }

    @Test
    fun emptyRepairPlanMeansAllTrustedBlocksMatch() = runBlocking {
        val file = tempFile("abcdefgh")
        val service = TrustedBlockManifestService { 1L }
        val manifest = service.create("download", file, blockSize = 4)
        val plan = service.planRepair(file, manifest)
        assertFalse(plan.requiresNetwork)
        assertTrue(plan.ranges.isEmpty())
    }

    private fun tempFile(content: String): File = kotlin.io.path.createTempFile().toFile().apply {
        writeText(content)
        deleteOnExit()
    }
}
