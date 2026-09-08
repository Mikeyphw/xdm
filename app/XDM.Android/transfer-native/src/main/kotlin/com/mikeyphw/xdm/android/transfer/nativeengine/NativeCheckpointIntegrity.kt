package com.mikeyphw.xdm.android.transfer.nativeengine

import java.nio.file.Path
import kotlin.math.min

/**
 * Incremental fixed-block ownership proof for native partial files.
 *
 * Routine checkpoints append only newly completed blocks. A checkpoint may deliberately lag the
 * live writer by less than one block; those uncommitted tail bytes are overwritten after restart.
 * Pause/final-segment flushes include the tail so user-requested durability is exact.
 */
internal class NativeCheckpointIntegrity(
    private val blockBytes: Long,
    previous: List<NativeSegmentCheckpoint> = emptyList(),
    private val hashRange: (Path, Long, Long) -> String,
) {
    init { require(blockBytes > 0L) { "Checkpoint integrity block size must be positive" } }

    private data class CachedDigest(val length: Long, val sha256: String)
    private val digests = mutableMapOf<Int, MutableList<CachedDigest>>()

    init {
        previous.forEach { segment ->
            val parsed = parseProof(segment.integrityProof) ?: return@forEach
            if (parsed.blockBytes != blockBytes) return@forEach
            val fullBlocks = (segment.completedBytes / blockBytes).toInt()
            digests[segment.index] = parsed.digests.take(fullBlocks)
                .map { CachedDigest(blockBytes, it) }
                .toMutableList()
        }
    }

    @Synchronized
    fun reset(segmentIndex: Int) {
        digests.remove(segmentIndex)
    }

    @Synchronized
    fun persistableSegment(path: Path, live: NativeSegmentCheckpoint, includeTail: Boolean): NativeSegmentCheckpoint {
        if (live.completedBytes <= 0L) {
            return live.copy(completedBytes = 0L, complete = false, completedSha256 = null, integrityProof = null)
        }
        val targetBytes = if (includeTail || live.complete) {
            live.completedBytes
        } else {
            (live.completedBytes / blockBytes) * blockBytes
        }
        if (targetBytes <= 0L) {
            return live.copy(completedBytes = 0L, complete = false, completedSha256 = null, integrityProof = null)
        }

        val cache = digests.getOrPut(live.index) { mutableListOf() }
        val neededBlocks = ((targetBytes + blockBytes - 1L) / blockBytes).toInt()
        for (blockIndex in 0 until neededBlocks) {
            val offset = blockIndex * blockBytes
            val length = min(blockBytes, targetBytes - offset)
            val cached = cache.getOrNull(blockIndex)
            if (cached == null) {
                cache += CachedDigest(length, hashRange(path, live.startByte + offset, length))
            } else if (cached.length != length) {
                cache[blockIndex] = CachedDigest(length, hashRange(path, live.startByte + offset, length))
            }
        }
        val proof = encodeProof(blockBytes, cache.take(neededBlocks).map(CachedDigest::sha256))
        return live.copy(
            completedBytes = targetBytes,
            complete = live.complete && targetBytes == live.completedBytes,
            completedSha256 = null,
            integrityProof = proof,
        )
    }

    companion object {
        private const val VERSION = "sha256-blocks-v1"

        data class ParsedProof(val blockBytes: Long, val digests: List<String>)

        fun verify(path: Path, segment: NativeSegmentCheckpoint, hashRange: (Path, Long, Long) -> String): Boolean {
            if (segment.completedBytes <= 0L) return true
            val proof = parseProof(segment.integrityProof) ?: return false
            if (proof.blockBytes <= 0L) return false
            val neededBlocks = ((segment.completedBytes + proof.blockBytes - 1L) / proof.blockBytes).toInt()
            if (proof.digests.size != neededBlocks) return false
            return proof.digests.indices.all { index ->
                val offset = index * proof.blockBytes
                val length = min(proof.blockBytes, segment.completedBytes - offset)
                val actual = hashRange(path, segment.startByte + offset, length)
                proof.digests[index].equals(actual, ignoreCase = true)
            }
        }

        fun parseProof(raw: String?): ParsedProof? {
            val parts = raw?.split('|', limit = 3) ?: return null
            if (parts.size != 3 || parts[0] != VERSION) return null
            val size = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val hashes = parts[2].split(',').filter(String::isNotBlank)
            if (hashes.isEmpty() || hashes.any { it.length != 64 || it.any { ch -> !ch.isDigit() && ch.lowercaseChar() !in 'a'..'f' } }) return null
            return ParsedProof(size, hashes)
        }

        private fun encodeProof(blockBytes: Long, hashes: List<String>): String =
            "$VERSION|$blockBytes|${hashes.joinToString(",")}" 
    }
}
