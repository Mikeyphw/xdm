package com.mikeyphw.xdm.android.transfer.nativeengine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProgressDl02Test {
    @Test
    fun fixedBlockCheckpointHashWorkGrowsLinearly() {
        val block = 1024L * 1024L
        val size = 8L * block
        val file = Files.createTempFile("xdm-linear-proof", ".bin")
        Files.newByteChannel(file, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(block.toInt())
            repeat(8) { channel.write(buffer.apply { clear() }) }
        }
        var bytesHashed = 0L
        val integrity = NativeCheckpointIntegrity(block, hashRange = { path, start, length ->
            bytesHashed += length
            java.io.RandomAccessFile(path.toFile(), "r").use { input ->
                input.seek(start)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var remaining = length
                val bytes = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    val read = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                    require(read > 0)
                    digest.update(bytes, 0, read)
                    remaining -= read
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        })
        var persisted: NativeSegmentCheckpoint? = null
        for (step in 1L..8L) {
            persisted = integrity.persistableSegment(
                file,
                NativeSegmentCheckpoint(0, 0, size - 1, step * block, step == 8L),
                includeTail = false,
            )
        }
        assertEquals(size, bytesHashed)
        assertEquals(size, requireNotNull(persisted).completedBytes)
        assertTrue(requireNotNull(persisted).integrityProof.orEmpty().startsWith("sha256-blocks-v1|"))
    }

    @Test
    fun rollingSpeedUsesTaskWideRecentDelta() {
        val meter = NativeRollingSpeedMeter(initialBytes = 4_000L, initialTimeMillis = 1_000L, windowMillis = 1_500L)
        assertEquals(1_000L, meter.record(4_500L, 1_500L))
        assertEquals(1_000L, meter.record(5_000L, 2_000L))
        // A retry/segment boundary does not reset the meter; only task-wide bytes matter.
        assertEquals(1_000L, meter.record(5_500L, 2_500L))
        assertEquals(1_000L, meter.record(6_000L, 3_000L))
    }

    @Test
    fun proofDetectsChangedPartialBytes() {
        val block = 64L * 1024L
        val file = Files.createTempFile("xdm-proof-corruption", ".bin")
        Files.write(file, ByteArray(block.toInt()) { 7 })
        val integrity = NativeCheckpointIntegrity(block, hashRange = ::sha256Range)
        val segment = integrity.persistableSegment(
            file,
            NativeSegmentCheckpoint(0, 0, block - 1, block, complete = true),
            includeTail = true,
        )
        assertTrue(NativeCheckpointIntegrity.verify(file, segment, ::sha256Range))
        java.io.RandomAccessFile(file.toFile(), "rw").use { it.seek(10); it.write(9) }
        assertFalse(NativeCheckpointIntegrity.verify(file, segment, ::sha256Range))
    }

    private fun sha256Range(path: java.nio.file.Path, start: Long, length: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.RandomAccessFile(path.toFile(), "r").use { input ->
            input.seek(start)
            var remaining = length
            val bytes = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val read = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                require(read > 0)
                digest.update(bytes, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
