package com.mikeyphw.xdm.android.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Phase 3: explicit publication and completed-artifact safety contracts.
 *
 * These types keep storage commits from being a smoky back alley where bytes
 * disappear between staging, publication, verification, and recovery. They are
 * intentionally platform-light so file, SAF, and MediaStore writers can share
 * one contract and tests can inspect the same invariants on the JVM.
 */
enum class PublicationCommitBoundary {
    BeforeDestinationCommit,
    DestinationCommitInProgress,
    DestinationCommitted,
    MetadataReconciled,
}

enum class CompletedArtifactHealthStatus {
    Present,
    Missing,
    PermissionLost,
    ProviderChanged,
    SizeMismatch,
    PendingPublication,
    Unknown,
}

data class PublicationGeneration(
    val downloadId: String,
    val attemptGeneration: Long,
    val artifactGeneration: Long,
) {
    val journalIdentity: String = "finalize-$downloadId-attempt-$attemptGeneration-artifact-$artifactGeneration"
}

data class PublicationCommitRecord(
    val generation: PublicationGeneration,
    val sourcePath: String,
    val stagingPath: String?,
    val destinationSpec: String,
    val committedUri: String?,
    val bytesExpected: Long?,
    val bytesCommitted: Long,
    val checksumAlgorithm: String?,
    val expectationId: String?,
    val expectedDigest: String?,
    val actualDigest: String?,
    val verificationTimestampEpochMs: Long?,
    val boundary: PublicationCommitBoundary,
    val health: CompletedArtifactHealthStatus,
    val message: String,
)

object PublicationJournalCodec {
    fun encode(record: PublicationCommitRecord): String = buildString {
        appendLine("phase=bug-hunt-phase-3")
        appendLine("journalIdentity=${record.generation.journalIdentity}")
        appendLine("downloadId=${record.generation.downloadId}")
        appendLine("attemptGeneration=${record.generation.attemptGeneration}")
        appendLine("artifactGeneration=${record.generation.artifactGeneration}")
        appendLine("sourcePath=${record.sourcePath}")
        appendLine("stagingPath=${record.stagingPath.orEmpty()}")
        appendLine("destinationSpec=${record.destinationSpec}")
        appendLine("committedUri=${record.committedUri.orEmpty()}")
        appendLine("bytesExpected=${record.bytesExpected ?: -1}")
        appendLine("bytesCommitted=${record.bytesCommitted}")
        appendLine("checksumAlgorithm=${record.checksumAlgorithm.orEmpty()}")
        appendLine("expectationId=${record.expectationId.orEmpty()}")
        appendLine("expectedDigest=${record.expectedDigest.orEmpty()}")
        appendLine("actualDigest=${record.actualDigest.orEmpty()}")
        appendLine("verificationTimestampEpochMs=${record.verificationTimestampEpochMs ?: -1}")
        appendLine("boundary=${record.boundary.name}")
        appendLine("health=${record.health.name}")
        appendLine("message=${record.message.replace('\n', ' ')}")
    }

    fun write(file: File, record: PublicationCommitRecord) {
        file.parentFile?.mkdirs()
        val bytes = encode(record).toByteArray(Charsets.UTF_8)
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        file.fsyncParentDirectoryIfSupported()
    }
}

object DestinationCapacityPlanner {
    const val PUBLICATION_OVERHEAD_BYTES: Long = 1024L * 1024L

    fun requiredBytesForPublication(
        expectedTotalBytes: Long?,
        existingBytes: Long,
        resumedBytes: Long,
        contentDestination: Boolean,
    ): Long? {
        val total = expectedTotalBytes ?: return null
        val remaining = (total - resumedBytes.coerceAtLeast(0)).coerceAtLeast(0)
        val publicationCopy = if (contentDestination) total.coerceAtLeast(existingBytes) else 0L
        return remaining + publicationCopy + PUBLICATION_OVERHEAD_BYTES
    }
}

object CompletedArtifactHealthProbe {
    fun fileHealth(file: File, expectedBytes: Long?): CompletedArtifactHealthStatus = when {
        !file.exists() -> CompletedArtifactHealthStatus.Missing
        !file.isFile -> CompletedArtifactHealthStatus.ProviderChanged
        expectedBytes != null && file.length() != expectedBytes -> CompletedArtifactHealthStatus.SizeMismatch
        else -> CompletedArtifactHealthStatus.Present
    }
}

fun File.fsyncParentDirectoryIfSupported() {
    val parent = parentFile ?: return
    runCatching {
        FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
    }
}

fun collisionResistantComponent(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
    val prefix = value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(48).ifBlank { "download" }
    return "$prefix-$digest"
}

fun androidProviderSafeFileName(value: String, maxUtf8Bytes: Int = 180): String {
    val sanitized = value
        .replace(Regex("[\\p{Cntrl}/\\\\:]+"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "download.bin" }
    val builder = StringBuilder()
    var bytes = 0
    for (char in sanitized) {
        val size = char.toString().toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxUtf8Bytes) break
        builder.append(char)
        bytes += size
    }
    return builder.toString().trim().ifBlank { "download.bin" }
}
