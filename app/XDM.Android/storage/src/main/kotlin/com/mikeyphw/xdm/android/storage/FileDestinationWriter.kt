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
) : DestinationWriter {
    override val supportsContentDestinations: Boolean = false

    override fun artifactPaths(request: DestinationRequest): DestinationArtifacts {
        val destination = resolveDestination(request)
        val partial = destination.resolveSibling(destination.fileName.toString() + ".xdm.part").toFile()
        return DestinationArtifacts(
            stagingFile = partial,
            checkpointFile = File(partial.parentFile, partial.name + ".checkpoint.json"),
            journalFile = File(partial.parentFile, partial.name + ".finalization.json"),
        )
    }

    override suspend fun prepare(request: DestinationRequest): PreparedDestination {
        val destination = resolveDestination(request).toFile()
        destination.parentFile?.mkdirs()
        val conflict = previewConflict(request)
        val resolved = when {
            conflict == null -> destination
            request.conflictPolicy == FilenameConflictPolicy.Overwrite -> destination
            request.conflictPolicy == FilenameConflictPolicy.Rename -> uniqueFile(destination)
            request.conflictPolicy == FilenameConflictPolicy.Resume && artifactPaths(request).stagingFile.exists() -> destination
            else -> throw DestinationConflictException("Destination already exists and requires a conflict decision", conflict)
        }
        val resolvedRequest = request.copy(destinationUri = resolved.toURI().toString(), fileName = resolved.name)
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
                val source = artifacts.stagingFile.toPath()
                val target = resolved.toPath()
                val atomic = runCatching {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    true
                }.getOrElse {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                    false
                }
                artifacts.checkpointFile.delete()
                artifacts.journalFile.delete()
                return DestinationPromotionResult(resolved.toURI().toString(), resolved.name, resolved.length(), atomic)
            }

            override suspend fun deleteArtifacts() {
                artifacts.stagingFile.delete()
                artifacts.checkpointFile.delete()
                artifacts.journalFile.delete()
            }
        }
    }

    override suspend fun previewConflict(request: DestinationRequest): DestinationConflict? {
        val destination = resolveDestination(request).toFile()
        if (!destination.exists()) return null
        return DestinationConflict(destination.name, destination.toURI().toString(), destination.length(), uniqueFile(destination).name)
    }

    override suspend fun health(destinationUri: String): DestinationHealth {
        return runCatching {
            val request = DestinationRequest("health", destinationUri, "probe.bin")
            val destination = resolveDestination(request).toFile()
            val parent = destination.parentFile ?: destination
            val writable = (parent.exists() || parent.mkdirs()) && parent.canWrite()
            DestinationHealth(
                uri = destinationUri,
                type = if (destinationUri == DestinationUris.APP_PRIVATE_DOWNLOADS) DestinationType.AppPrivate else DestinationType.FileSystem,
                status = if (writable) DestinationHealthStatus.Healthy else DestinationHealthStatus.ReadOnly,
                displayName = parent.name.ifBlank { parent.absolutePath },
                availableBytes = usableSpace(parent),
            )
        }.getOrElse {
            DestinationHealth(destinationUri, DestinationType.FileSystem, DestinationHealthStatus.Unavailable, destinationUri, message = it.message)
        }
    }

    @SuppressLint("UsableSpace")
    private fun usableSpace(file: File): Long = file.usableSpace

    private fun resolveDestination(request: DestinationRequest) = when (request.destinationUri) {
        DestinationUris.APP_PRIVATE_DOWNLOADS -> requireNotNull(privateDownloadsDirectory) { "App-private destination requires an application directory" }.resolve(request.fileName).toPath()
        else -> {
            val uri = runCatching { URI(request.destinationUri) }.getOrNull()
            when {
                uri == null || uri.scheme == null -> File(request.destinationUri).toPath()
                uri.scheme.equals("file", ignoreCase = true) -> File(uri).toPath()
                else -> throw UnsupportedOperationException("Unsupported file destination ${request.destinationUri}")
            }
        }
    }.toAbsolutePath().normalize()

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
