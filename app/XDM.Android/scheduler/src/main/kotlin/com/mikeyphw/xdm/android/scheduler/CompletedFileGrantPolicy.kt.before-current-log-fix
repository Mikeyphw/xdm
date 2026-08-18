package com.mikeyphw.xdm.android.scheduler

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import java.io.File

/** Resolves only completed artifacts that are either Android content URIs or canonical files inside
 * the two narrowly exported FileProvider roots. Staging, cache, database, and arbitrary absolute
 * paths are never grantable. */
object CompletedFileGrantPolicy {
    fun resolve(context: Context, download: Download, requireReadable: Boolean = true): Uri? {
        if (download.state != DownloadState.Completed) return null
        if (download.completedArtifactGeneration == null || download.completedArtifactGeneration != download.attemptGeneration) return null
        val raw = download.completedArtifactUri?.trim()?.takeIf(String::isNotBlank) ?: return null
        val parsed = runCatching { raw.toUri() }.getOrNull()
        return when (parsed?.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT -> parsed.takeIf { !requireReadable || canRead(context, it, download.completedArtifactBytes) }
            ContentResolver.SCHEME_FILE -> parsed.path?.let(::File)?.let { fileUri(context, download, it) }
            null, "" -> fileUri(context, download, File(raw))
            else -> null
        }
    }

    private fun fileUri(context: Context, download: Download, candidate: File): Uri? {
        val file = runCatching { candidate.canonicalFile }.getOrNull()?.takeIf(File::isFile) ?: return null
        download.completedArtifactBytes?.let { expected -> if (file.length() != expected) return null }
        val roots = buildList {
            add(File(context.filesDir, "downloads"))
            context.getExternalFilesDir(null)?.let { add(File(it, "Download")) }
        }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        if (roots.none { root -> file.path == root.path || file.path.startsWith(root.path + File.separator) }) return null
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.applicationContext.packageName}.completed-downloads",
                file,
            )
        }.getOrNull()
    }

    private fun canRead(context: Context, uri: Uri, expectedBytes: Long?): Boolean = runCatching {
        val resolver = context.contentResolver
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            if (expectedBytes == null) return@use true
            val descriptorSize = descriptor.statSize.takeIf { it >= 0L }
            val observedSize = descriptorSize ?: resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            }
            observedSize == expectedBytes
        } == true
    }.getOrDefault(false)
}
