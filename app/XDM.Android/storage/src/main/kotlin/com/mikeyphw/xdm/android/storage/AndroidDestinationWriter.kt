package com.mikeyphw.xdm.android.storage

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationType
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection

class AndroidDestinationWriter(private val context: Context) : DestinationWriter {
    private val resolver: ContentResolver = context.contentResolver
    private val appPrivateWriter = FileDestinationWriter(File(context.filesDir, "downloads").apply(File::mkdirs))
    override val supportsContentDestinations: Boolean = true

    override fun artifactPaths(request: DestinationRequest): DestinationArtifacts {
        if (isRegularFileDestination(request.destinationUri) || request.destinationUri == DestinationUris.APP_PRIVATE_DOWNLOADS) {
            return appPrivateWriter.artifactPaths(request)
        }
        val directory = File(context.filesDir, "transfer-staging/${safeComponent(request.downloadId)}").apply(File::mkdirs)
        val partial = File(directory, safeFileName(request.fileName) + ".xdm.part")
        return DestinationArtifacts(
            stagingFile = partial,
            checkpointFile = File(directory, partial.name + ".checkpoint.json"),
            journalFile = File(directory, partial.name + ".finalization.json"),
        )
    }

    override suspend fun prepare(request: DestinationRequest): PreparedDestination {
        if (isRegularFileDestination(request.destinationUri) || request.destinationUri == DestinationUris.APP_PRIVATE_DOWNLOADS) {
            return appPrivateWriter.prepare(request)
        }
        val target = resolveTarget(request)
        val artifacts = artifactPaths(request)
        artifacts.stagingFile.parentFile?.mkdirs()
        return object : PreparedDestination {
            override val destinationKey: String = target.destinationKey
            override val displayName: String = target.displayName
            override val artifacts: DestinationArtifacts = artifacts

            override suspend fun availableSpace(): Long? = availableBytesForUri(target.rootUri)

            override suspend fun promote(): DestinationPromotionResult {
                check(artifacts.stagingFile.isFile) { "Staging file is missing" }
                val committed = target.openForCommit()
                try {
                    copyAndSync(artifacts.stagingFile, committed.uri)
                    committed.finish(true)
                } catch (error: Throwable) {
                    committed.finish(false)
                    throw error
                }
                val bytes = querySize(committed.uri) ?: artifacts.stagingFile.length()
                artifacts.stagingFile.delete()
                artifacts.checkpointFile.delete()
                artifacts.journalFile.delete()
                return DestinationPromotionResult(committed.uri.toString(), committed.displayName, bytes, atomic = false)
            }

            override suspend fun deleteArtifacts() {
                artifacts.stagingFile.delete()
                artifacts.checkpointFile.delete()
                artifacts.journalFile.delete()
                artifacts.stagingFile.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
            }
        }
    }

    override suspend fun previewConflict(request: DestinationRequest): DestinationConflict? {
        if (isRegularFileDestination(request.destinationUri) || request.destinationUri == DestinationUris.APP_PRIVATE_DOWNLOADS) {
            return appPrivateWriter.previewConflict(request)
        }
        val root = destinationRoot(request.destinationUri)
        val existing = when (root.type) {
            DestinationType.SafTree -> findTreeChild(root.uri, request.fileName)
            DestinationType.DirectDocument -> root.uri.takeIf { queryDisplayName(it) == request.fileName }
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) findMediaItem(root, request.fileName) else null
        } ?: return null
        return DestinationConflict(
            requestedName = request.fileName,
            existingUri = existing.toString(),
            existingSize = querySize(existing),
            suggestedName = uniqueName(root, request.fileName),
        )
    }

    override suspend fun health(destinationUri: String): DestinationHealth {
        if (isRegularFileDestination(destinationUri) || destinationUri == DestinationUris.APP_PRIVATE_DOWNLOADS) {
            return appPrivateWriter.health(destinationUri)
        }
        return runCatching {
            val root = destinationRoot(destinationUri)
            val permission = if (root.type == DestinationType.SafTree || root.type == DestinationType.DirectDocument) persistedPermission(root.uri) else Pair(true, true)
            val writable = when (root.type) {
                DestinationType.SafTree -> permission.second && canQueryTree(root.uri)
                DestinationType.DirectDocument -> permission.second && queryDisplayName(root.uri) != null
                else -> true
            }
            DestinationHealth(
                uri = destinationUri,
                type = root.type,
                status = when {
                    !permission.second -> DestinationHealthStatus.PermissionMissing
                    !writable -> DestinationHealthStatus.Unavailable
                    else -> DestinationHealthStatus.Healthy
                },
                displayName = root.displayName,
                availableBytes = availableBytesForUri(root.uri),
                message = if (!permission.second) "Write permission is no longer persisted" else null,
            )
        }.getOrElse { error ->
            DestinationHealth(destinationUri, DestinationType.DirectDocument, DestinationHealthStatus.Unavailable, destinationUri, message = error.message)
        }
    }

    fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (error: SecurityException) {
            throw DestinationPermissionException("The selected folder did not grant persistent read/write access", error)
        }
    }

    private suspend fun resolveTarget(request: DestinationRequest): ResolvedTarget {
        val root = destinationRoot(request.destinationUri)
        val conflict = previewConflict(request)
        val displayName = when {
            conflict == null -> safeFileName(request.fileName)
            request.conflictPolicy == FilenameConflictPolicy.Overwrite -> conflict.requestedName
            request.conflictPolicy == FilenameConflictPolicy.Rename -> conflict.suggestedName
            request.conflictPolicy == FilenameConflictPolicy.Resume && artifactPaths(request).stagingFile.exists() -> conflict.requestedName
            else -> throw DestinationConflictException("Destination already exists and cannot be replaced without confirmation", conflict)
        }
        val destinationKey = when (root.type) {
            DestinationType.DirectDocument -> root.uri.normalizeScheme().toString()
            else -> root.uri.normalizeScheme().toString().trimEnd('/') + "/" + Uri.encode(displayName)
        }
        return ResolvedTarget(root, displayName, destinationKey) {
            when (root.type) {
                DestinationType.SafTree -> openTreeDocument(root.uri, displayName, request.mimeType, request.conflictPolicy)
                DestinationType.DirectDocument -> CommitTarget(root.uri, queryDisplayName(root.uri) ?: displayName) { _ -> Unit }
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openMediaItem(root, displayName, request.mimeType, request.conflictPolicy) else error("MediaStore destinations require Android 10")
            }
        }
    }

    private fun destinationRoot(destinationUri: String): DestinationRoot {
        if (destinationUri.startsWith("xdm://mediastore/")) {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "MediaStore destinations require Android 10 or newer; choose a SAF folder on this device" }
            return mediaStoreRoot(destinationUri)
        }
        return when (destinationUri) {
            else -> {
                val uri = Uri.parse(destinationUri)
                require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Unsupported destination $destinationUri" }
                if (DocumentsContract.isTreeUri(uri)) DestinationRoot(uri, DestinationType.SafTree, queryTreeName(uri) ?: "Selected folder", null)
                else DestinationRoot(uri, DestinationType.DirectDocument, queryDisplayName(uri) ?: "Selected document", null)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun mediaStoreRoot(destinationUri: String): DestinationRoot = when (destinationUri) {
        DestinationUris.PUBLIC_DOWNLOADS -> DestinationRoot(MediaStore.Downloads.EXTERNAL_CONTENT_URI, DestinationType.PublicDownloads, "Public Downloads", Environment.DIRECTORY_DOWNLOADS)
        DestinationUris.MEDIA_MOVIES -> DestinationRoot(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, DestinationType.MediaStoreMovies, "Movies", Environment.DIRECTORY_MOVIES)
        DestinationUris.MEDIA_MUSIC -> DestinationRoot(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, DestinationType.MediaStoreMusic, "Music", Environment.DIRECTORY_MUSIC)
        DestinationUris.MEDIA_PICTURES -> DestinationRoot(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, DestinationType.MediaStorePictures, "Pictures", Environment.DIRECTORY_PICTURES)
        DestinationUris.MEDIA_DOCUMENTS -> DestinationRoot(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), DestinationType.MediaStoreDocuments, "Documents", Environment.DIRECTORY_DOCUMENTS)
        else -> error("Unknown MediaStore destination $destinationUri")
    }

    private fun openTreeDocument(treeUri: Uri, name: String, mimeType: String?, policy: FilenameConflictPolicy): CommitTarget {
        val existing = findTreeChild(treeUri, name)
        if (existing != null && policy != FilenameConflictPolicy.Overwrite && policy != FilenameConflictPolicy.Resume) {
            throw DestinationConflictException("A document named $name already exists")
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val uri = existing ?: requireNotNull(DocumentsContract.createDocument(resolver, parent, mimeType ?: guessMimeType(name), name)) {
            "The document provider could not create $name"
        }
        return CommitTarget(uri, name) { success -> if (!success && existing == null) runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
    }

    @SuppressLint("NewApi")
    private fun openMediaItem(root: DestinationRoot, name: String, mimeType: String?, policy: FilenameConflictPolicy): CommitTarget {
        val existing = findMediaItem(root, name)
        if (existing != null && policy != FilenameConflictPolicy.Overwrite && policy != FilenameConflictPolicy.Resume) {
            throw DestinationConflictException("A media item named $name already exists")
        }
        if (existing != null) return CommitTarget(existing, name) { _ -> Unit }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: guessMimeType(name))
            root.relativePath?.let { put(MediaStore.MediaColumns.RELATIVE_PATH, it) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(root.uri, values)) { "MediaStore could not create $name" }
        return CommitTarget(uri, name) { success ->
            if (success) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            else resolver.delete(uri, null, null)
        }
    }

    private fun copyAndSync(source: File, destination: Uri) {
        val descriptor = resolver.openFileDescriptor(destination, "rwt") ?: throw DestinationPermissionException("Unable to open destination for writing")
        descriptor.use { pfd ->
            FileInputStream(source).use { input ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                    pfd.fileDescriptor.sync()
                }
            }
        }
    }

    private fun findTreeChild(treeUri: Uri, name: String): Uri? {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name) return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
            }
            null
        }
    }

    @SuppressLint("NewApi")
    private fun findMediaItem(root: DestinationRoot, name: String): Uri? {
        val selection = if (root.relativePath == null) "${MediaStore.MediaColumns.DISPLAY_NAME}=?" else "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = if (root.relativePath == null) arrayOf(name) else arrayOf(name, root.relativePath.trimEnd('/') + "%")
        return resolver.query(root.uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
            if (!cursor.moveToFirst()) null else ContentUris.withAppendedId(root.uri, cursor.getLong(0))
        }
    }

    private fun uniqueName(root: DestinationRoot, requested: String): String {
        val dot = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
        val stem = requested.substring(0, dot)
        val extension = requested.substring(dot)
        for (index in 1..9999) {
            val candidate = "$stem ($index)$extension"
            val exists = when (root.type) {
                DestinationType.SafTree -> findTreeChild(root.uri, candidate) != null
                DestinationType.DirectDocument -> queryDisplayName(root.uri) == candidate
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && findMediaItem(root, candidate) != null
            }
            if (!exists) return candidate
        }
        return "$stem-${System.currentTimeMillis()}$extension"
    }

    private fun queryDisplayName(uri: Uri): String? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        cursor.singleValue(OpenableColumns.DISPLAY_NAME)
    }

    private fun queryTreeName(uri: Uri): String? {
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        return queryDisplayName(document)
    }

    private fun querySize(uri: Uri): Long? = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
    }

    private fun Cursor.singleValue(column: String): String? = if (!moveToFirst()) null else getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun persistedPermission(uri: Uri): Pair<Boolean, Boolean> {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        return Pair(permission?.isReadPermission == true, permission?.isWritePermission == true)
    }

    private fun canQueryTree(uri: Uri): Boolean = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false
    }.getOrDefault(false)

    private fun availableBytesForUri(uri: Uri): Long? {
        return when (uri.authority) {
            MediaStore.AUTHORITY -> runCatching { StatFs((context.getExternalFilesDir(null) ?: context.filesDir).absolutePath).availableBytes }.getOrNull()
            else -> null
        }
    }

    private fun isRegularFileDestination(uri: String): Boolean = uri.startsWith("file:") || !uri.contains("://")
    private fun safeComponent(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    private fun safeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').ifBlank { "download.bin" }.take(180)
    private fun guessMimeType(name: String): String = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"

    private data class DestinationRoot(
        val uri: Uri,
        val type: DestinationType,
        val displayName: String,
        val relativePath: String?,
    )

    private data class ResolvedTarget(
        val root: DestinationRoot,
        val displayName: String,
        val destinationKey: String,
        val opener: () -> CommitTarget,
    ) {
        val rootUri: Uri get() = root.uri
        fun openForCommit(): CommitTarget = opener()
    }

    private data class CommitTarget(
        val uri: Uri,
        val displayName: String,
        val finisher: (Boolean) -> Unit,
    ) {
        fun finish(success: Boolean) = finisher(success)
    }
}
