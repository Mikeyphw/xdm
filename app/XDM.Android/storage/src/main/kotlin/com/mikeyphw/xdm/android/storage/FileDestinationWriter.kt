package com.mikeyphw.xdm.android.storage

import android.annotation.SuppressLint
import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationType
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileDestinationWriter(
    private val privateDownloadsDirectory: File? = null,
    private val directDownloadsDirectory: File? = null,
) : DestinationWriter {
    override val supportsContentDestinations: Boolean = false

    override fun artifactPaths(request: DestinationRequest): DestinationArtifacts {
        val destination = resolveDestination(request)
        val partial = destination.resolveSibling(
            PublicationStagingNames.stagingFileName(destination.fileName.toString(), request.attemptGeneration, request.artifactGeneration, request.stagingSuffix),
        ).toFile()
        return DestinationArtifacts(
            stagingFile = partial,
            checkpointFile = File(partial.parentFile, partial.name + ".checkpoint.json"),
            journalFile = File(partial.parentFile, partial.name + ".finalization.json"),
        )
    }

    override suspend fun prepare(request: DestinationRequest): PreparedDestination {
        val safeRequest = request.copy(fileName = androidProviderSafeFileName(request.fileName))
        val destination = resolveDestination(safeRequest).toFile()
        destination.parentFile?.mkdirs()
        val conflict = previewConflict(safeRequest)
        val resolved = when {
            conflict == null -> destination
            safeRequest.conflictPolicy == FilenameConflictPolicy.Overwrite -> destination
            safeRequest.conflictPolicy == FilenameConflictPolicy.Rename -> uniqueFile(destination)
            safeRequest.conflictPolicy == FilenameConflictPolicy.Resume -> throw DestinationConflictException(
                "Resume cannot replace an existing final file based only on stale staging-file existence; choose Rename or explicit Overwrite after review",
                conflict,
            )
            else -> throw DestinationConflictException("Destination already exists and requires a conflict decision", conflict)
        }
        val resolvedRequest = safeRequest.copy(destinationUri = resolved.toURI().toString(), fileName = resolved.name)
        val artifacts = artifactPaths(resolvedRequest)
        return object : PreparedDestination {
            override val destinationKey: String = resolved.canonicalFile.toURI().normalize().toString()
            override val displayName: String = resolved.name
            override val artifacts: DestinationArtifacts = artifacts
            @SuppressLint("UsableSpace")
            override suspend fun availableSpace(): Long? = resolved.parentFile?.usableSpace
            override suspend fun promote(): DestinationPromotionResult {
                check(artifacts.stagingFile.isFile) { "Staging file is missing" }
                resolved.parentFile?.mkdirs()
                val generation = PublicationGeneration(request.downloadId, attemptGeneration = request.attemptGeneration, artifactGeneration = request.artifactGeneration)
                val transaction = PublicationTransaction(
                    generation = generation,
                    stagingPath = artifacts.stagingFile.absolutePath,
                    destinationSpec = request.destinationUri,
                    intendedFinalLocator = resolved.toURI().toString(),
                    expectedBytes = request.expectedTotalBytes ?: artifacts.stagingFile.length(),
                )
                try {
                    PublicationJournalCodec.write(
                        artifacts.journalFile,
                        transaction.beforeCommit(),
                    )
                } catch (error: Throwable) {
                    throw DestinationPublicationException(
                        message = "Could not prepare filesystem final save for ${resolved.name}: ${error.message ?: error::class.java.simpleName}",
                        destinationUri = request.destinationUri,
                        stagingPath = artifacts.stagingFile.absolutePath,
                        stagingPreserved = artifacts.stagingFile.isFile,
                        cause = error,
                    )
                }
                val source = artifacts.stagingFile.toPath()
                val target = resolved.toPath()
                val expectedBytes = request.expectedTotalBytes ?: artifacts.stagingFile.length()
                val targetExistedBeforePromotion = resolved.exists()
                val atomic = try {
                    PublicationJournalCodec.write(
                        artifacts.journalFile,
                        transaction.inProgress(resolved.toURI().toString()),
                    )
                    try {
                        if (targetExistedBeforePromotion && safeRequest.conflictPolicy != FilenameConflictPolicy.Overwrite) {
                            throw java.nio.file.FileAlreadyExistsException(target.toString())
                        }
                        val moveOptions = if (targetExistedBeforePromotion && safeRequest.conflictPolicy == FilenameConflictPolicy.Overwrite) {
                            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } else {
                            arrayOf(StandardCopyOption.ATOMIC_MOVE)
                        }
                        Files.move(source, target, *moveOptions)
                    } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                        // Never move an existing destination aside as a fallback. Without an atomic
                        // replace guarantee, preserve both the old target and completed staging data.
                        throw IllegalStateException(
                            "Filesystem does not support atomic final replacement; existing destination and staging bytes were preserved",
                            unsupported,
                        )
                    }
                    resolved.fsyncParentDirectoryIfSupported()
                    val committedHealth = CompletedArtifactHealthProbe.fileHealth(resolved, expectedBytes)
                    check(committedHealth == CompletedArtifactHealthStatus.Present) { "Completed file health is $committedHealth after publication" }
                    true
                } catch (error: Throwable) {
                    val stagingPreserved = artifacts.stagingFile.isFile
                    runCatching {
                        PublicationJournalCodec.write(
                            artifacts.journalFile,
                            PublicationCommitRecord(
                                generation = generation,
                                sourcePath = artifacts.stagingFile.absolutePath,
                                stagingPath = artifacts.stagingFile.absolutePath.takeIf { stagingPreserved },
                                destinationSpec = request.destinationUri,
                                committedUri = resolved.takeIf(File::isFile)?.toURI()?.toString(),
                                bytesExpected = expectedBytes,
                                bytesCommitted = resolved.takeIf(File::isFile)?.length() ?: 0L,
                                checksumAlgorithm = null,
                                expectationId = null,
                                expectedDigest = null,
                                actualDigest = null,
                                verificationTimestampEpochMs = null,
                                boundary = PublicationCommitBoundary.DestinationCommitInProgress,
                                health = CompletedArtifactHealthStatus.PendingPublication,
                                message = "Filesystem publication failed; staging preservation was checked before recovery. targetExistedBeforePromotion=$targetExistedBeforePromotion",
                            ),
                        )
                    }
                    throw DestinationPublicationException(
                        message = "Could not finalize ${resolved.name}: ${error.message ?: error::class.java.simpleName}",
                        destinationUri = request.destinationUri,
                        stagingPath = artifacts.stagingFile.absolutePath,
                        stagingPreserved = stagingPreserved,
                        cause = error,
                    )
                }
                runCatching {
                    PublicationJournalCodec.write(
                        artifacts.journalFile,
                        transaction.committed(resolved.toURI().toString(), resolved.length(), resolved.name),
                    )
                }
                artifacts.checkpointFile.delete()
                return DestinationPromotionResult(
                    committedUri = resolved.toURI().toString(),
                    displayName = resolved.name,
                    bytesCommitted = resolved.length(),
                    atomic = atomic,
                    attemptGeneration = request.attemptGeneration,
                    artifactGeneration = request.artifactGeneration,
                    publicationJournalPath = artifacts.journalFile.absolutePath,
                    stagingPathRetained = null,
                )
            }

            override suspend fun deleteArtifacts() {
                artifacts.stagingFile.delete()
                artifacts.checkpointFile.delete()
                artifacts.journalFile.delete()
            }
        }
    }

    override suspend fun previewConflict(request: DestinationRequest): DestinationConflict? {
        val destination = resolveDestination(request.copy(fileName = androidProviderSafeFileName(request.fileName))).toFile()
        if (!destination.exists()) return null
        return DestinationConflict(destination.name, destination.toURI().toString(), destination.length(), uniqueFile(destination).name)
    }

    override suspend fun health(destinationUri: String): DestinationHealth {
        return runCatching {
            val request = DestinationRequest("health", destinationUri, "probe.bin")
            val destination = resolveDestination(request).toFile()
            val parent = destination.parentFile ?: destination
            val existing = firstExistingAncestor(parent)
            val writable = parent.exists() && parent.isDirectory && parent.canWrite()
            DestinationHealth(
                uri = destinationUri,
                type = if (destinationUri == DestinationUris.APP_PRIVATE_DOWNLOADS) DestinationType.AppPrivate else DestinationType.FileSystem,
                status = if (writable) DestinationHealthStatus.Healthy else DestinationHealthStatus.ReadOnly,
                displayName = parent.name.ifBlank { parent.absolutePath },
                availableBytes = existing?.let(::usableSpace),
                message = if (!parent.exists()) "Destination parent does not exist; health probe is side-effect-free and did not create it." else null,
            )
        }.getOrElse {
            DestinationHealth(destinationUri, DestinationType.FileSystem, DestinationHealthStatus.Unavailable, destinationUri, message = it.message)
        }
    }

    @SuppressLint("UsableSpace")
    private fun usableSpace(file: File): Long = file.usableSpace

    private fun resolveDestination(request: DestinationRequest) = when (request.destinationUri) {
        DestinationUris.APP_PRIVATE_DOWNLOADS -> requireNotNull(privateDownloadsDirectory) { "App-private destination requires an application directory" }.resolve(request.fileName).toPath()
        DestinationUris.DIRECT_DOWNLOADS -> requireNotNull(directDownloadsDirectory) { "Direct Downloads requires a shared-storage directory" }.resolve(request.fileName).toPath()
        else -> {
            val uri = runCatching { URI(request.destinationUri) }.getOrNull()
            when {
                uri == null || uri.scheme == null -> File(request.destinationUri).toPath()
                uri.scheme.equals("file", ignoreCase = true) -> {
                    val file = File(uri)
                    if (request.destinationUri.endsWith('/')) file.resolve(request.fileName).toPath() else file.toPath()
                }
                else -> throw UnsupportedOperationException("Unsupported file destination ${request.destinationUri}")
            }
        }
    }.toAbsolutePath().normalize()

    private fun firstExistingAncestor(file: File): File? {
        var cursor: File? = file
        while (cursor != null) {
            if (cursor.exists()) return cursor
            cursor = cursor.parentFile
        }
        return null
    }

    private fun uniqueFile(file: File): File {
        val dot = file.name.lastIndexOf('.').takeIf { it > 0 } ?: file.name.length
        val stem = file.name.substring(0, dot)
        val extension = file.name.substring(dot)
        var index = 1
        var candidate: File
        do candidate = File(file.parentFile, "$stem ($index)$extension") while (candidate.exists().also { index++ })
        return candidate
    }
}
