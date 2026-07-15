package com.mikeyphw.xdm.android.storage

import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationType
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import java.io.File

object DestinationUris {
    const val APP_PRIVATE_DOWNLOADS = "xdm://private/downloads"
    const val PUBLIC_DOWNLOADS = "xdm://mediastore/downloads"
    const val MEDIA_MOVIES = "xdm://mediastore/movies"
    const val MEDIA_MUSIC = "xdm://mediastore/music"
    const val MEDIA_PICTURES = "xdm://mediastore/pictures"
    const val MEDIA_DOCUMENTS = "xdm://mediastore/documents"
}

data class DestinationRequest(
    val downloadId: String,
    val destinationUri: String,
    val fileName: String,
    val mimeType: String? = null,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
)

data class DestinationArtifacts(
    val stagingFile: File,
    val checkpointFile: File,
    val journalFile: File,
)

data class DestinationPromotionResult(
    val committedUri: String,
    val displayName: String,
    val bytesCommitted: Long,
    val atomic: Boolean,
)

data class DestinationConflict(
    val requestedName: String,
    val existingUri: String,
    val existingSize: Long?,
    val suggestedName: String,
)

data class DestinationHealth(
    val uri: String,
    val type: DestinationType,
    val status: DestinationHealthStatus,
    val displayName: String,
    val availableBytes: Long? = null,
    val message: String? = null,
)

interface PreparedDestination {
    val destinationKey: String
    val displayName: String
    val artifacts: DestinationArtifacts
    suspend fun availableSpace(): Long?
    suspend fun promote(): DestinationPromotionResult
    suspend fun deleteArtifacts()
}

interface DestinationWriter {
    val supportsContentDestinations: Boolean
    fun artifactPaths(request: DestinationRequest): DestinationArtifacts
    suspend fun prepare(request: DestinationRequest): PreparedDestination
    suspend fun previewConflict(request: DestinationRequest): DestinationConflict?
    suspend fun health(destinationUri: String): DestinationHealth
}

interface DestinationProvider {
    val providerId: String
    suspend fun canWrite(destinationUri: String): Boolean
}

class DestinationConflictException(message: String, val conflict: DestinationConflict? = null) : IllegalStateException(message)
class DestinationPermissionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
