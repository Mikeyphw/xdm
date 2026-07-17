package com.mikeyphw.xdm.android.transfer.nativeengine

import com.mikeyphw.xdm.android.model.SelectiveRepairPlan
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
        RandomAccessFile(target, "rw").use { file ->
            for (range in plan.ranges) {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .header("Range", "bytes=${range.startByte}-${range.endByteInclusive}")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code !in setOf(200, 206)) {
                        throw IOException("Server rejected repair range ${range.startByte}-${range.endByteInclusive}: HTTP ${response.code}")
                    }
                    val body = requireNotNull(response.body) { "Repair range had no response body" }
                    file.seek(range.startByte)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var remaining = range.endByteInclusive - range.startByte + 1
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            file.write(buffer, 0, read)
                            remaining -= read.toLong()
                        }
                        if (remaining != 0L) throw IOException("Repair range ended early with $remaining bytes missing")
                    }
                }
            }
            file.channel.force(true)
        }
        RepairOutcome(plan.downloadId, repairedRanges = plan.ranges.size, repairedBytes = plan.ranges.sumOf { it.endByteInclusive - it.startByte + 1 })
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
