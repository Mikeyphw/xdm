package com.mikeyphw.xdm.android.storage

import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDestinationWriterTest {
    @Test
    fun renamePolicyPreservesExistingDestination() = runBlocking {
        val root = Files.createTempDirectory("xdm-storage-test").toFile()
        val destination = root.resolve("archive.zip").apply { writeText("old") }
        val writer = FileDestinationWriter(root)
        val prepared = writer.prepare(DestinationRequest("download", destination.toURI().toString(), destination.name, conflictPolicy = FilenameConflictPolicy.Rename))
        prepared.artifacts.stagingFile.writeText("new")
        val result = prepared.promote()
        assertEquals("old", destination.readText())
        assertEquals("archive (1).zip", result.displayName)
        assertEquals("new", root.resolve("archive (1).zip").readText())
    }

    @Test
    fun appPrivateArtifactsUseDurablePartialAndCheckpointNames() {
        val root = Files.createTempDirectory("xdm-private-test").toFile()
        val writer = FileDestinationWriter(root)
        val artifacts = writer.artifactPaths(DestinationRequest("id", DestinationUris.APP_PRIVATE_DOWNLOADS, "video.mp4"))
        assertEquals("video.mp4.xdm.part", artifacts.stagingFile.name)
        assertTrue(artifacts.checkpointFile.name.endsWith(".checkpoint.json"))
        assertFalse(artifacts.stagingFile.exists())
    }
}
