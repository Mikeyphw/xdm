package com.mikeyphw.xdm.android.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit publication and completed-artifact safety contracts.
 *
 * XAR06 upgrades the Phase-3 primitives into a transaction model that is owned by
 * downloadId + attemptGeneration + artifactGeneration.  The artifact generation is a
 * monotonic token selected before staging/publication begins; it is never inferred from
 * staging file mtime, provider timestamps, or the existence of an old partial file.
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
    init {
        require(downloadId.isNotBlank()) { "Publication generation requires a download id" }
        require(attemptGeneration > 0L) { "Publication generation requires a positive attempt generation" }
        require(artifactGeneration > 0L) { "Publication generation requires a positive artifact generation" }
    }

    val journalIdentity: String = "finalize-$downloadId-attempt-$attemptGeneration-artifact-$artifactGeneration"
}

/** Process-local monotonic token factory. Room/backend attempt generation remains the owner; this
 * token prevents new publication evidence from being confused with stale mtime-derived evidence. */
object PublicationArtifactToken {
    private val counter = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L))

    fun next(downloadId: String, attemptGeneration: Long): Long {
        require(downloadId.isNotBlank()) { "downloadId required for artifact generation" }
        require(attemptGeneration > 0L) { "attemptGeneration required for artifact generation" }
        while (true) {
            val now = System.currentTimeMillis().coerceAtLeast(attemptGeneration)
            val previous = counter.get()
            val candidate = maxOf(previous + 1L, now)
            if (counter.compareAndSet(previous, candidate)) return candidate
        }
    }
}

object PublicationStagingNames {
    fun stagingFileName(finalName: String, attemptGeneration: Long, artifactGeneration: Long, suffix: String): String {
        val safe = androidProviderSafeFileName(finalName)
        return "$safe.attempt-$attemptGeneration.artifact-$artifactGeneration$suffix"
    }

    fun attemptDirectory(downloadId: String, attemptGeneration: Long): String =
        "${collisionResistantComponent(downloadId)}-attempt-$attemptGeneration"
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

data class PublicationTransaction(
    val generation: PublicationGeneration,
    val stagingPath: String,
    val destinationSpec: String,
    val intendedFinalLocator: String,
    val expectedBytes: Long?,
) {
    val ownerKey: String = generation.journalIdentity

    fun beforeCommit(): PublicationCommitRecord = record(
        committedUri = null,
        bytesCommitted = 0L,
        boundary = PublicationCommitBoundary.BeforeDestinationCommit,
        health = CompletedArtifactHealthStatus.PendingPublication,
        message = "Publication transaction prepared before destination commit.",
    )

    fun inProgress(committedUri: String, bytesCommitted: Long = 0L): PublicationCommitRecord = record(
        committedUri = committedUri,
        bytesCommitted = bytesCommitted,
        boundary = PublicationCommitBoundary.DestinationCommitInProgress,
        health = CompletedArtifactHealthStatus.PendingPublication,
        message = "Destination identity recorded before/crossing the physical commit boundary.",
    )

    fun committed(committedUri: String, bytesCommitted: Long, actualDisplayName: String? = null): PublicationCommitRecord = record(
        committedUri = committedUri,
        bytesCommitted = bytesCommitted,
        boundary = PublicationCommitBoundary.DestinationCommitted,
        health = CompletedArtifactHealthStatus.Present,
        message = "Destination committed; staging evidence is retained until Room metadata reconciliation. actualDisplayName=${actualDisplayName.orEmpty()}",
    )

    private fun record(
        committedUri: String?,
        bytesCommitted: Long,
        boundary: PublicationCommitBoundary,
        health: CompletedArtifactHealthStatus,
        message: String,
    ) = PublicationCommitRecord(
        generation = generation,
        sourcePath = stagingPath,
        stagingPath = stagingPath,
        destinationSpec = destinationSpec,
        committedUri = committedUri,
        bytesExpected = expectedBytes,
        bytesCommitted = bytesCommitted,
        checksumAlgorithm = null,
        expectationId = null,
        expectedDigest = null,
        actualDigest = null,
        verificationTimestampEpochMs = if (boundary == PublicationCommitBoundary.DestinationCommitted) System.currentTimeMillis() else null,
        boundary = boundary,
        health = health,
        message = message,
    )
}

object PublicationJournalCodec {
    fun encode(record: PublicationCommitRecord): String = buildString {
        appendLine("phase=xar06-publication-transaction")
        appendLine("contract=attempt-generation-owned-publication")
        appendLine("journalIdentity=${record.generation.journalIdentity}")
        appendLine("artifactOwnerKey=${record.generation.downloadId}:${record.generation.attemptGeneration}:${record.generation.artifactGeneration}")
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

    fun decode(text: String): PublicationCommitRecord {
        val values = text.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        val downloadId = values.getValue("downloadId").trim().also { require(it.isNotBlank()) }
        val attemptGeneration = values.getValue("attemptGeneration").toLong().also { require(it > 0L) }
        val artifactGeneration = values.getValue("artifactGeneration").toLong().also { require(it > 0L) }
        val sourcePath = values.getValue("sourcePath").trim().also { require(it.isNotBlank()) }
        val destinationSpec = values.getValue("destinationSpec").trim().also { require(it.isNotBlank()) }
        val boundary = PublicationCommitBoundary.valueOf(values.getValue("boundary"))
        val health = CompletedArtifactHealthStatus.valueOf(values.getValue("health"))
        return PublicationCommitRecord(
            generation = PublicationGeneration(downloadId, attemptGeneration, artifactGeneration),
            sourcePath = sourcePath,
            stagingPath = values["stagingPath"]?.trim()?.takeIf(String::isNotBlank),
            destinationSpec = destinationSpec,
            committedUri = values["committedUri"]?.trim()?.takeIf(String::isNotBlank),
            bytesExpected = values["bytesExpected"]?.toLongOrNull()?.takeIf { it >= 0L },
            bytesCommitted = values["bytesCommitted"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            checksumAlgorithm = values["checksumAlgorithm"]?.trim()?.takeIf(String::isNotBlank),
            expectationId = values["expectationId"]?.trim()?.takeIf(String::isNotBlank),
            expectedDigest = values["expectedDigest"]?.trim()?.takeIf(String::isNotBlank),
            actualDigest = values["actualDigest"]?.trim()?.takeIf(String::isNotBlank),
            verificationTimestampEpochMs = values["verificationTimestampEpochMs"]?.toLongOrNull()?.takeIf { it >= 0L },
            boundary = boundary,
            health = health,
            message = values["message"].orEmpty(),
        )
    }

    fun read(file: File): PublicationCommitRecord = decode(file.readText(Charsets.UTF_8))

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
        val providerCommitCopy = if (contentDestination) total.coerceAtLeast(0L) else 0L
        val existingSafety = if (contentDestination) existingBytes.coerceAtLeast(0L) else 0L
        return remaining + providerCommitCopy + existingSafety + PUBLICATION_OVERHEAD_BYTES
    }

    fun fits(availableBytes: Long?, requiredBytes: Long?): Boolean =
        availableBytes == null || requiredBytes == null || requiredBytes <= availableBytes
}

object CompletedArtifactHealthProbe {
    fun fileHealth(file: File, expectedBytes: Long?): CompletedArtifactHealthStatus = when {
        !file.exists() -> CompletedArtifactHealthStatus.Missing
        !file.isFile -> CompletedArtifactHealthStatus.ProviderChanged
        expectedBytes != null && file.length() != expectedBytes -> CompletedArtifactHealthStatus.SizeMismatch
        else -> CompletedArtifactHealthStatus.Present
    }

    fun sizeMatches(observedBytes: Long?, expectedBytes: Long?): Boolean = when {
        observedBytes == null -> false
        expectedBytes != null -> observedBytes == expectedBytes
        else -> observedBytes >= 0L
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
