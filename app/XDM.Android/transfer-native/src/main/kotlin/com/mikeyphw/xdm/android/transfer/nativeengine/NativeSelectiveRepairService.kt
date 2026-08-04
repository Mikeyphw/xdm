package com.mikeyphw.xdm.android.transfer.nativeengine

import com.mikeyphw.xdm.android.model.SelectiveRepairPlan
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class NativeSelectiveRepairService(
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun repair(sourceUrl: String, target: File, plan: SelectiveRepairPlan): RepairOutcome = withContext(Dispatchers.IO) {
        require(plan.requiresNetwork) { "Repair plan contains no corrupt or missing ranges" }
        require(target.parentFile?.let { it.exists() || it.mkdirs() } != false) { "Repair target parent is unavailable" }
        val originalLength = target.takeIf(File::isFile)?.length() ?: 0L
        val temp = File(target.parentFile, target.name + ".repair-${System.currentTimeMillis()}.tmp")
        if (target.isFile) {
            Files.copy(target.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            temp.createNewFile()
            RandomAccessFile(temp, "rw").use { it.setLength(plan.fileLength) }
        }
        try {
            RandomAccessFile(temp, "rw").use { file ->
                if (file.length() > plan.fileLength) throw IOException("Repair target has trailing data beyond trusted manifest length")
                file.setLength(plan.fileLength)
                for (range in plan.ranges) {
                    val request = Request.Builder()
                        .url(sourceUrl)
                        .header("Range", "bytes=${range.startByte}-${range.endByteInclusive}")
                        .header("If-Range", "trusted-block-manifest-${plan.downloadId}")
                        .build()
                    client.newCall(request).execute().use { response ->
                        validateRepairResponse(response.code, response.header("Content-Range"), range.startByte, range.endByteInclusive, plan.fileLength)
                        val body = requireNotNull(response.body) { "Repair range had no response body" }
                        val expectedBytes = range.endByteInclusive - range.startByte + 1
                        val declaredLength = body.contentLength().takeIf { it >= 0 }
                        if (declaredLength != null && declaredLength != expectedBytes) {
                            throw IOException("Repair range declared $declaredLength bytes, expected $expectedBytes")
                        }
                        file.seek(range.startByte)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var remaining = expectedBytes
                            while (remaining > 0) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read < 0) break
                                file.write(buffer, 0, read)
                                remaining -= read.toLong()
                            }
                            if (remaining != 0L) throw IOException("Repair range ended early with $remaining bytes missing")
                            if (input.read() >= 0) throw IOException("Repair range contained trailing bytes beyond Content-Range")
                        }
                    }
                }
                file.channel.force(true)
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            temp.delete()
            if (target.isFile && originalLength > 0L) {
                check(target.length() == originalLength) { "Selective repair modified the original artifact before validation" }
            }
            throw error
        }
        RepairOutcome(plan.downloadId, repairedRanges = plan.ranges.size, repairedBytes = plan.ranges.sumOf { it.endByteInclusive - it.startByte + 1 })
    }


    private fun validateRepairResponse(code: Int, contentRange: String?, start: Long, endInclusive: Long, totalLength: Long) {
        if (start == 0L && endInclusive + 1 == totalLength && code == 200) return
        if (code != 206) throw IOException("Selective repair requires HTTP 206 for partial range $start-$endInclusive; server returned $code")
        val expected = "bytes $start-$endInclusive/$totalLength"
        if (contentRange != expected) throw IOException("Repair Content-Range mismatch: expected '$expected' but was '${contentRange.orEmpty()}'")
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}

data class RepairOutcome(
    val downloadId: String,
    val repairedRanges: Int,
    val repairedBytes: Long,
)
