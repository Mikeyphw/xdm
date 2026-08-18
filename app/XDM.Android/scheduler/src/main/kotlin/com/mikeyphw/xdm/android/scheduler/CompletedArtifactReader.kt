package com.mikeyphw.xdm.android.scheduler

import androidx.core.net.toUri
import android.content.ContentResolver
import android.content.Context
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Paths

/** Reads the exact committed artifact URI rather than re-resolving the configured destination. */
interface CompletedArtifactReader {
    suspend fun size(uri: String): Long?
    suspend fun open(uri: String): InputStream?
    fun asFile(uri: String): File?
}

class FileCompletedArtifactReader : CompletedArtifactReader {
    override suspend fun size(uri: String): Long? = asFile(uri)?.takeIf(File::isFile)?.length()
    override suspend fun open(uri: String): InputStream? = asFile(uri)?.takeIf(File::isFile)?.let(::FileInputStream)
    override fun asFile(uri: String): File? = uri.toCompletedFileOrNull()
}

class AndroidCompletedArtifactReader(context: Context) : CompletedArtifactReader {
    private val resolver = context.applicationContext.contentResolver
    private val files = FileCompletedArtifactReader()

    override suspend fun size(uri: String): Long? {
        files.size(uri)?.let { return it }
        val parsed = runCatching { uri.toUri() }.getOrNull() ?: return null
        if (parsed.scheme != ContentResolver.SCHEME_CONTENT) return null
        val queried = resolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        }
        if (queried != null && queried >= 0L) return queried
        return resolver.openFileDescriptor(parsed, "r")?.use { descriptor -> descriptor.statSize.takeIf { it >= 0L } }
    }

    override suspend fun open(uri: String): InputStream? {
        files.open(uri)?.let { return it }
        val parsed = runCatching { uri.toUri() }.getOrNull() ?: return null
        return if (parsed.scheme == ContentResolver.SCHEME_CONTENT) resolver.openInputStream(parsed) else null
    }

    override fun asFile(uri: String): File? = files.asFile(uri)
}

private fun String.toCompletedFileOrNull(): File? = runCatching {
    if (!contains("://") && !startsWith("file:")) return@runCatching File(this)
    val parsed = URI(this)
    if (!parsed.scheme.equals("file", ignoreCase = true)) null else Paths.get(parsed).toFile()
}.getOrNull()
