package com.mikeyphw.xdm.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.mikeyphw.xdm.android.model.CompletedArtifactCapabilities
import com.mikeyphw.xdm.android.model.CompletedArtifactHealth
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.scheduler.CompletedFileGrantPolicy
import com.mikeyphw.xdm.android.util.sanitizeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadArtifactOutcome(
    val success: Boolean,
    val message: String,
    val canonicalUri: String? = null,
    val displayName: String? = null,
)

/** Performs completed-artifact inspection and mutation away from Compose and the main thread. */
class DownloadArtifactActionManager(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun inspect(download: Download): CompletedArtifactCapabilities = withContext(Dispatchers.IO) {
        if (download.state != DownloadState.Completed) {
            return@withContext CompletedArtifactCapabilities(
                friendlyLocation = destinationUiLabel(download.destinationUri),
                detail = "Completed-file actions are available only after destination commit.",
            )
        }
        val sourceUri = rawCanonicalUri(download)
        if (sourceUri?.scheme == ContentResolver.SCHEME_CONTENT && sourceUri.authority?.let { authority ->
                context.packageManager.resolveContentProvider(authority, 0) == null
            } == true
        ) {
            return@withContext CompletedArtifactCapabilities(
                health = CompletedArtifactHealth.ProviderChanged,
                friendlyLocation = destinationUiLabel(download.destinationUri),
                androidUri = sourceUri.toString(),
                providerLabel = sourceUri.authority.orEmpty(),
                detail = "The Android provider that owns this completed artifact is no longer installed or available.",
            )
        }
        val grantUri = CompletedFileGrantPolicy.resolve(context, download, requireReadable = false)
            ?: return@withContext CompletedArtifactCapabilities(
                health = CompletedArtifactHealth.Missing,
                friendlyLocation = destinationUiLabel(download.destinationUri),
                detail = "The completed artifact could not be resolved from its canonical saved URI.",
            )
        val readable = canRead(grantUri)
        val row = queryMetadata(grantUri)
        val expected = download.completedArtifactBytes?.takeIf { it >= 0L }
        val actual = row.size
        val generationMatches = download.completedArtifactGeneration == download.attemptGeneration
        val health = when {
            !generationMatches -> CompletedArtifactHealth.ProviderChanged
            !readable -> CompletedArtifactHealth.PermissionLost
            expected != null && actual != null && expected != actual -> CompletedArtifactHealth.SizeMismatch
            else -> CompletedArtifactHealth.Present
        }
        val documentFlags = sourceUri?.takeIf(::isDocumentUri)?.let(::documentFlags) ?: 0L
        val mediaStore = sourceUri?.authority?.startsWith("media", ignoreCase = true) == true
        val safeFile = sourceUri?.let { safeOwnedFile(download, it) }
        val renameable = health == CompletedArtifactHealth.Present && (
            documentFlags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L ||
                mediaStore || safeFile != null
            )
        val deletable = health in setOf(CompletedArtifactHealth.Present, CompletedArtifactHealth.SizeMismatch) && (
            documentFlags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() != 0L ||
                mediaStore || safeFile != null
            )
        val provider = providerLabel(sourceUri ?: grantUri)
        val friendly = listOf(provider, destinationUiLabel(download.destinationUri)).distinct().joinToString(" · ")
        CompletedArtifactCapabilities(
            health = health,
            readable = readable,
            shareable = readable,
            renameable = renameable,
            deletable = deletable,
            // A direct document or MediaStore item URI identifies the file, not its containing
            // folder. Keep location hidden until XDM persists a real tree/collection identity.
            locationBrowsable = false,
            friendlyLocation = friendly,
            androidUri = sourceUri?.toString() ?: grantUri.toString(),
            providerLabel = provider,
            sizeBytes = actual,
            detail = when (health) {
                CompletedArtifactHealth.Present -> "Readable ${actual?.let { "$it-byte " }.orEmpty()}artifact from $provider."
                CompletedArtifactHealth.SizeMismatch -> "The provider reports ${actual ?: "unknown"} bytes; XDM expected ${expected ?: "unknown"}."
                CompletedArtifactHealth.PermissionLost -> "Android no longer grants read access to this saved artifact."
                CompletedArtifactHealth.ProviderChanged -> if (!generationMatches) {
                    "The saved artifact identity belongs to another download attempt and is quarantined."
                } else {
                    "The original provider is no longer available."
                }
                CompletedArtifactHealth.Missing -> "The saved artifact is missing."
                CompletedArtifactHealth.Unknown -> "Artifact capability has not been checked yet."
            },
        )
    }

    suspend fun rename(download: Download, requestedName: String): DownloadArtifactOutcome = withContext(Dispatchers.IO) {
        val capability = inspect(download)
        if (!capability.renameable) return@withContext DownloadArtifactOutcome(false, capability.detail)
        val newName = sanitizeFileName(requestedName).takeIf { it.isNotBlank() }
            ?: return@withContext DownloadArtifactOutcome(false, "Enter a valid file name.")
        val sourceUri = rawCanonicalUri(download)
            ?: return@withContext DownloadArtifactOutcome(false, "The saved artifact has no canonical URI.")
        if (isDocumentUri(sourceUri)) {
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, sourceUri, newName) }.getOrNull()
            if (renamed != null) return@withContext DownloadArtifactOutcome(true, "Renamed saved file.", renamed.toString(), newName)
        }
        if (sourceUri.scheme == ContentResolver.SCHEME_CONTENT && sourceUri.authority?.startsWith("media", true) == true) {
            val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            val updated = runCatching { resolver.update(sourceUri, values, null, null) }.getOrDefault(0)
            if (updated == 1) return@withContext DownloadArtifactOutcome(true, "Renamed saved file.", sourceUri.toString(), newName)
        }
        val file = safeOwnedFile(download, sourceUri)
            ?: return@withContext DownloadArtifactOutcome(false, "This provider does not support safe rename for the selected artifact.")
        val target = File(file.parentFile, newName).canonicalFile
        if (target.parentFile != file.parentFile || target.exists()) {
            return@withContext DownloadArtifactOutcome(false, "The new name conflicts with an existing file or leaves the approved folder.")
        }
        if (!file.renameTo(target)) return@withContext DownloadArtifactOutcome(false, "Android could not rename the saved file.")
        DownloadArtifactOutcome(true, "Renamed saved file.", Uri.fromFile(target).toString(), newName)
    }

    suspend fun delete(download: Download): DownloadArtifactOutcome = withContext(Dispatchers.IO) {
        val capability = inspect(download)
        if (!capability.deletable) return@withContext DownloadArtifactOutcome(false, capability.detail)
        val sourceUri = rawCanonicalUri(download)
            ?: return@withContext DownloadArtifactOutcome(false, "The saved artifact has no canonical URI.")
        val deleted = when {
            isDocumentUri(sourceUri) -> runCatching { DocumentsContract.deleteDocument(resolver, sourceUri) }.getOrDefault(false)
            sourceUri.scheme == ContentResolver.SCHEME_CONTENT -> runCatching { resolver.delete(sourceUri, null, null) == 1 }.getOrDefault(false)
            else -> safeOwnedFile(download, sourceUri)?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
        }
        if (deleted) DownloadArtifactOutcome(true, "Deleted the saved file.")
        else DownloadArtifactOutcome(false, "Android did not confirm deletion of the exact saved artifact.")
    }

    fun providerLocationIntent(download: Download): Intent? = null

    private fun rawCanonicalUri(download: Download): Uri? {
        if (download.completedArtifactGeneration == null || download.completedArtifactGeneration != download.attemptGeneration) return null
        val raw = download.completedArtifactUri?.trim()?.takeIf(String::isNotBlank) ?: return null
        val parsed = runCatching { raw.toUri() }.getOrNull()
        return when (parsed?.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE -> parsed
            null, "" -> Uri.fromFile(File(raw))
            else -> null
        }
    }

    private fun safeOwnedFile(download: Download, uri: Uri): File? {
        val candidate = when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)
            null, "" -> File(uri.toString())
            else -> null
        } ?: return null
        val file = runCatching { candidate.canonicalFile }.getOrNull()?.takeIf(File::isFile) ?: return null
        download.completedArtifactBytes?.let { expected -> if (file.length() != expected) return null }
        val roots = buildList {
            add(File(context.filesDir, "downloads"))
            context.getExternalFilesDir(null)?.let { add(File(it, "Download")) }
        }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return file.takeIf { candidateFile -> roots.any { root -> candidateFile.path == root.path || candidateFile.path.startsWith(root.path + File.separator) } }
    }

    private fun canRead(uri: Uri): Boolean = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    private data class Metadata(val name: String?, val size: Long?)

    private fun queryMetadata(uri: Uri): Metadata = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use Metadata(null, null)
            Metadata(cursor.stringOrNull(OpenableColumns.DISPLAY_NAME), cursor.longOrNull(OpenableColumns.SIZE))
        } ?: Metadata(null, null)
    }.getOrDefault(Metadata(null, null))

    private fun documentFlags(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun isDocumentUri(uri: Uri): Boolean = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)

    private fun providerLabel(uri: Uri): String = when {
        uri.authority?.startsWith("media", true) == true -> "Android MediaStore"
        uri.authority?.contains("downloads", true) == true -> "Android Downloads provider"
        uri.scheme == ContentResolver.SCHEME_CONTENT -> uri.authority ?: "Android document provider"
        uri.scheme == ContentResolver.SCHEME_FILE -> "XDM private storage"
        else -> "Saved provider"
    }

    private fun Cursor.stringOrNull(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.longOrNull(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
}
