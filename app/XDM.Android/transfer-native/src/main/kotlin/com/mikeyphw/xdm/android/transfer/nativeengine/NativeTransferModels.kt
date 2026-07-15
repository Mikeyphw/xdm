package com.mikeyphw.xdm.android.transfer.nativeengine

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

data class NativeTransferConfig(
    val defaultConnections: Int = 4,
    val segmentThresholdBytes: Long = 4L * 1024 * 1024,
    val checkpointIntervalBytes: Long = 1024L * 1024,
    val bufferBytes: Int = 64 * 1024,
    val maximumRetries: Int = 4,
    val baseRetryDelayMillis: Long = 250,
    val maximumGlobalConnections: Int = 8,
    val maximumConnectionsPerHost: Int = 4,
)

data class NativeSegmentCheckpoint(
    val index: Int,
    val startByte: Long,
    val endByteInclusive: Long?,
    val completedBytes: Long,
    val complete: Boolean,
)

data class NativeCheckpoint(
    val downloadId: String,
    val sourceUrl: String,
    val effectiveUrl: String,
    val destinationPath: String,
    val partialPath: String,
    val expectedLength: Long?,
    val etag: String?,
    val lastModified: String?,
    val rangeSupported: Boolean,
    val segments: List<NativeSegmentCheckpoint>,
    val persistedAtEpochMs: Long,
)

data class RemoteMetadata(
    val effectiveUrl: String,
    val totalLength: Long?,
    val etag: String?,
    val lastModified: String?,
    val rangeSupported: Boolean,
)

data class NativeArtifactPaths(val destinationIdentity: String, val partial: Path, val checkpoint: Path) {
    companion object {
        fun fromDestinationUri(destinationUri: String): NativeArtifactPaths {
            val uri = runCatching { URI(destinationUri) }.getOrNull()
            val destination = when {
                uri == null || uri.scheme == null -> Paths.get(destinationUri)
                uri.scheme.equals("file", ignoreCase = true) -> Paths.get(uri)
                else -> throw UnsupportedOperationException("Phase 3 native engine supports file destinations; SAF arrives in Phase 5")
            }.toAbsolutePath().normalize()
            val partial = destination.resolveSibling(destination.fileName.toString() + ".xdm.part")
            return NativeArtifactPaths(destination.toString(), partial, partial.resolveSibling(partial.fileName.toString() + ".checkpoint.json"))
        }
    }
}

class RemoteObjectChangedException(message: String) : IllegalStateException(message)
class InvalidRangeResponseException(message: String) : IllegalStateException(message)
class HttpTransferException(val statusCode: Int, message: String) : java.io.IOException(message)
