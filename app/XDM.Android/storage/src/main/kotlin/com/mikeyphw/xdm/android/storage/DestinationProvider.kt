package com.mikeyphw.xdm.android.storage

import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationType
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import java.io.File

object DestinationUris {
    const val APP_PRIVATE_DOWNLOADS = "xdm://private/downloads"
    const val DIRECT_DOWNLOADS = "xdm://filesystem/downloads"
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
    val stagingSuffix: String = ".xdm.part",
    /** Durable backend-attempt generation owning the staged and committed artifact. */
    val attemptGeneration: Long = 1L,
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
    /** Local crash-recovery journal retained until Room completion metadata is durable. */
    val publicationJournalPath: String? = null,
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
    /** True when final publication needs a second full copy in addition to app-private staging. */
    val requiresPublicationCopy: Boolean get() = false
    suspend fun availableSpace(): Long?
    suspend fun promote(): DestinationPromotionResult
    suspend fun deleteArtifacts()
}

interface DestinationProvider {
    val providerId: String
    suspend fun canWrite(destinationUri: String): Boolean
}

interface DestinationWriter : DestinationProvider {
    override val providerId: String get() = "destination-writer"
    val supportsContentDestinations: Boolean
    fun artifactPaths(request: DestinationRequest): DestinationArtifacts
    suspend fun prepare(request: DestinationRequest): PreparedDestination
    suspend fun previewConflict(request: DestinationRequest): DestinationConflict?
    suspend fun health(destinationUri: String): DestinationHealth
    override suspend fun canWrite(destinationUri: String): Boolean =
        health(destinationUri).status == DestinationHealthStatus.Healthy
}

class DestinationConflictException(message: String, val conflict: DestinationConflict? = null) : IllegalStateException(message)
class DestinationPermissionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class DestinationPublicationException(
    message: String,
    val destinationUri: String,
    val stagingPath: String,
    val stagingPreserved: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    val retryMessage: String
        get() = if (stagingPreserved) {
            "Final save failed, but the completed staging file is preserved. Retry save after fixing destination access."
        } else {
            "Final save failed and XDM could not confirm that the staging file is still present. Open Recovery before retrying."
        }
}
