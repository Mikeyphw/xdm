package com.mikeyphw.xdm.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugRecorderProvider
import com.mikeyphw.xdm.android.model.DebugSeverity
import com.mikeyphw.xdm.android.model.MimePresentationKind
import com.mikeyphw.xdm.android.model.MimePresentationResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal data class XdmArtworkRequest(
    val fileName: String,
    val mimeType: String?,
    val thumbnailUrl: String?,
    val sourceUrl: String?,
    val localUri: String?,
) {
    val presentation = MimePresentationResolver.resolve(fileName, mimeType)
    val remoteUrl: String? = thumbnailUrl.validHttpUrl()
        ?: sourceUrl.validHttpUrl()?.takeIf { presentation.kind == MimePresentationKind.Image }
    val effectiveLocalUri: String? = localUri.validLocalUri()
    val cacheKey: String = sha256(
        listOf(fileName, mimeType.orEmpty(), remoteUrl.orEmpty(), effectiveLocalUri.orEmpty(), presentation.kind.name).joinToString("\u0000"),
    )

    private fun String?.validHttpUrl(): String? = this?.trim()?.takeIf { raw ->
        runCatching { URI(raw).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)
    }

    private fun String?.validLocalUri(): String? = this?.trim()?.takeIf { raw ->
        runCatching {
            val scheme = Uri.parse(raw).scheme?.lowercase()
            scheme == null || scheme in setOf("content", "file", "android.resource")
        }.getOrDefault(false)
    }
}

@Composable
fun XdmMediaArtwork(
    fileName: String,
    mimeType: String?,
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
    sourceUrl: String? = null,
    localUri: String? = null,
    width: Dp = 72.dp,
    height: Dp = 48.dp,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val request = remember(fileName, mimeType, thumbnailUrl, sourceUrl, localUri) {
        XdmArtworkRequest(fileName, mimeType, thumbnailUrl, sourceUrl, localUri)
    }
    var bitmap by remember(request.cacheKey) { mutableStateOf(XdmArtworkLoader.peek(request.cacheKey)) }

    LaunchedEffect(request.cacheKey) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { XdmArtworkLoader.load(context.applicationContext, request) }
        }
    }

    Surface(
        modifier = modifier.size(width, height),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = contentDescription ?: MimePresentationResolver.contentDescription(fileName, mimeType),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                XdmFileTypeIcon(
                    fileName = fileName,
                    mimeType = mimeType,
                    contentDescription = contentDescription,
                    containerSize = minOf(width, height).coerceAtMost(48.dp),
                )
            }
        }
    }
}

private object XdmArtworkLoader {
    private const val MAX_REMOTE_BYTES = 6 * 1024 * 1024
    private const val MAX_DISK_BYTES = 32L * 1024L * 1024L
    private const val TARGET_EDGE_PX = 320

    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun peek(key: String): Bitmap? = synchronized(memory) { memory.get(key) }

    fun load(context: Context, request: XdmArtworkRequest): Bitmap? {
        peek(request.cacheKey)?.let { return it }
        val diskFile = cacheFile(context, request.cacheKey)
        decodeFile(diskFile)?.let { return remember(request.cacheKey, it) }

        val local = request.effectiveLocalUri?.let { uri ->
            when (request.presentation.kind) {
                MimePresentationKind.Image -> decodeLocalImage(context, uri)
                MimePresentationKind.Video, MimePresentationKind.AdaptiveMedia -> extractLocalVideoFrame(context, uri)
                else -> null
            }
        }
        if (local != null) {
            persistBitmap(diskFile, local)
            prune(context)
            return remember(request.cacheKey, local)
        }

        val remote = request.remoteUrl?.let { loadRemoteImage(it) }
        if (remote != null) {
            persistBitmap(diskFile, remote)
            prune(context)
            return remember(request.cacheKey, remote)
        }

        traceFailure(context, request)
        return null
    }

    private fun remember(key: String, bitmap: Bitmap): Bitmap {
        synchronized(memory) { memory.put(key, bitmap) }
        return bitmap
    }

    private fun decodeLocalImage(context: Context, rawUri: String): Bitmap? = runCatching {
        val uri = Uri.parse(rawUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openLocalDescriptor(context, uri) { descriptor -> BitmapFactory.decodeFileDescriptor(descriptor, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > TARGET_EDGE_PX * 2 || bounds.outHeight / sample > TARGET_EDGE_PX * 2) sample *= 2
        openLocalDescriptor(context, uri) { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })?.scaledForCache()
        }
    }.getOrNull()

    private fun extractLocalVideoFrame(context: Context, rawUri: String): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(rawUri))
            retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.scaledForCache()
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun loadRemoteImage(rawUrl: String): Bitmap? = runCatching {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.2")
            setRequestProperty("User-Agent", "XDM-Android-Thumbnail/1")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@runCatching null
            val declared = connection.contentLengthLong
            if (declared > MAX_REMOTE_BYTES) return@runCatching null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(MAX_REMOTE_BYTES, declared.takeIf { it > 0 }?.toInt() ?: 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_REMOTE_BYTES) return@runCatching null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            decodeSampled(bytes)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > TARGET_EDGE_PX * 2 || bounds.outHeight / sample > TARGET_EDGE_PX * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.scaledForCache()
    }

    private fun Bitmap.scaledForCache(): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= TARGET_EDGE_PX || largest <= 0) return this
        val scale = TARGET_EDGE_PX.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun cacheDirectory(context: Context): File = File(context.cacheDir, "media-artwork-v1").apply { mkdirs() }
    private fun cacheFile(context: Context, key: String): File = File(cacheDirectory(context), "$key.jpg")

    private fun decodeFile(file: File): Bitmap? = if (file.isFile) runCatching {
        BitmapFactory.decodeFile(file.absolutePath)?.also { file.setLastModified(System.currentTimeMillis()) }
    }.getOrNull() else null

    private fun persistBitmap(file: File, bitmap: Bitmap) = runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out) }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }.getOrNull()

    private fun prune(context: Context) {
        val files = cacheDirectory(context).listFiles { f -> f.isFile && f.extension == "jpg" }.orEmpty()
        var total = files.sumOf(File::length)
        if (total <= MAX_DISK_BYTES) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (total <= MAX_DISK_BYTES) return
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun traceFailure(context: Context, request: XdmArtworkRequest) {
        val recorder = (context.applicationContext as? DebugRecorderProvider)?.debugEventRecorder ?: return
        recorder.record(
            area = DebugArea.Thumbnail,
            severity = DebugSeverity.Trace,
            action = "thumbnail-load",
            result = "fallback-icon",
            safeDetails = mapOf(
                "fileName" to request.fileName.take(160),
                "mimeType" to request.mimeType.orEmpty(),
                "thumbnailUrl" to request.remoteUrl.orEmpty(),
                "hasLocalUri" to (request.effectiveLocalUri != null).toString(),
                "presentation" to request.presentation.kind.name,
            ),
        )
    }

    private fun <T> openLocalDescriptor(context: Context, uri: Uri, block: (java.io.FileDescriptor) -> T?): T? {
        if (uri.scheme.isNullOrBlank()) {
            val file = File(uri.toString())
            if (!file.isFile) return null
            return java.io.FileInputStream(file).use { input -> block(input.fd) }
        }
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor -> block(descriptor.fileDescriptor) }
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
