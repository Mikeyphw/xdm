package com.mikeyphw.xdm.android.transfer.nativeengine

import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.SelectiveRepairPlan
import com.mikeyphw.xdm.android.model.TrustedBlockManifest
import com.mikeyphw.xdm.android.storage.fsyncParentDirectoryIfSupported
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class NativeSelectiveRepairService(
    private val client: OkHttpClient = OkHttpClient(),
    /** Same request-security boundary used by normal transfer execution. Default is deliberately fail-closed. */
    private val requestSecurityValidator: suspend (DownloadRequest) -> Unit = {
        throw IllegalStateException("Selective repair requires the app request-security validator")
    },
) {
    /**
     * Legacy callers do not carry enough representation or trusted-block proof for safe repair.
     * They must re-enter through the generation-bound overload below.
     */
    suspend fun repair(sourceUrl: String, target: File, plan: SelectiveRepairPlan): RepairOutcome {
        throw IllegalStateException("Selective repair requires the exact request envelope, a strong remote validator, and a generation-bound trusted-block manifest")
    }

    suspend fun repair(
        request: DownloadRequest,
        target: File,
        plan: SelectiveRepairPlan,
        manifest: TrustedBlockManifest,
        validator: ResumeValidator,
    ): RepairOutcome = withContext(Dispatchers.IO) {
        require(plan.requiresNetwork) { "Repair plan contains no corrupt or missing ranges" }
        require(plan.downloadId == request.id && manifest.downloadId == request.id) { "Repair evidence belongs to another download" }
        require(manifest.attemptGeneration == request.attemptGeneration) { "Trusted blocks belong to a stale attempt generation" }
        require(manifest.fileLength == plan.fileLength && manifest.blockSize == plan.blockSize) { "Repair plan does not match the trusted manifest" }
        require(target.parentFile?.let { it.exists() || it.mkdirs() } != false) { "Repair target parent is unavailable" }
        require(target.isFile) { "Selective repair requires the existing verified artifact" }
        requestSecurityValidator(request)

        val originalLength = target.length()
        require(originalLength == plan.fileLength) { "Repair target length does not match trusted manifest" }
        val originalDigest = fileSha256(target)
        val suffix = System.currentTimeMillis()
        val temp = File(target.parentFile, target.name + ".repair-$suffix.tmp")
        val backup = File(target.parentFile, target.name + ".repair-backup-$suffix.bak")
        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        RandomAccessFile(backup, "rw").use { it.channel.force(true) }
        backup.fsyncParentDirectoryIfSupported()
        check(backup.length() == originalLength && fileSha256(backup) == originalDigest) {
            "Selective repair could not create a durable verified backup of the original artifact"
        }
        var targetReplaced = false
        var repairCommitted = false
        try {
            Files.copy(target.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            RandomAccessFile(temp, "rw").use { file ->
                for (range in plan.ranges) {
                    val requestBuilder = Request.Builder()
                        .url(request.sourceUrl)
                        .header("Range", "bytes=${range.startByte}-${range.endByteInclusive}")
                        .header("If-Range", validator.value)
                    request.headers.forEach { (name, value) ->
                        if (!name.equals("Range", true) && !name.equals("If-Range", true)) requestBuilder.header(name, value)
                    }
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        validateRepairResponse(response.code, response.header("Content-Range"), range.startByte, range.endByteInclusive, plan.fileLength)
                        val observed = when (validator.kind) {
                            ResumeValidatorKind.StrongEtag -> response.header("ETag")
                            ResumeValidatorKind.StrongLastModified -> response.header("Last-Modified")
                        }
                        if (observed != validator.value) throw IOException("Repair response validator no longer matches the trusted representation")
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
            verifyRepairedBlocks(temp, plan, manifest)
            check(temp.length() == plan.fileLength) { "Repaired temporary artifact length changed before commit" }
            try {
                // Same-filesystem atomic replacement is required. The verified backup remains
                // durable until the repaired target has been promoted and checked. A crash during
                // commit therefore leaves either the old target, or the new target plus its backup.
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                targetReplaced = true
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IOException("Selective repair requires atomic replacement; the original artifact was preserved", unsupported)
            }
            target.fsyncParentDirectoryIfSupported()
            check(target.isFile && target.length() == plan.fileLength) { "Repaired artifact length changed during atomic commit" }
            verifyRepairedBlocks(target, plan, manifest)
            repairCommitted = true
            backup.delete()
            target.fsyncParentDirectoryIfSupported()
        } catch (error: Throwable) {
            temp.delete()
            if (targetReplaced && backup.isFile) {
                val rollbackSucceeded = runCatching {
                    Files.move(
                        backup.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    target.fsyncParentDirectoryIfSupported()
                    target.isFile && target.length() == originalLength && fileSha256(target) == originalDigest
                }.getOrDefault(false)
                if (!rollbackSucceeded) {
                    throw IOException(
                        "Selective repair failed after replacement; the verified original backup remains at ${backup.absolutePath}",
                        error,
                    )
                }
            }
            check(target.isFile && target.length() == originalLength && fileSha256(target) == originalDigest) {
                "Selective repair could not preserve or restore the original artifact"
            }
            if (!targetReplaced) backup.delete()
            throw error
        } finally {
            temp.delete()
            if (repairCommitted) backup.delete()
        }
        RepairOutcome(plan.downloadId, repairedRanges = plan.ranges.size, repairedBytes = plan.ranges.sumOf { it.endByteInclusive - it.startByte + 1 })
    }

    private fun verifyRepairedBlocks(file: File, plan: SelectiveRepairPlan, manifest: TrustedBlockManifest) {
        val byIndex = manifest.blocks.associateBy { it.index }
        RandomAccessFile(file, "r").use { input ->
            for (range in plan.ranges) {
                val block = requireNotNull(byIndex[range.blockIndex]) { "Trusted manifest is missing repair block ${range.blockIndex}" }
                require(block.startByte == range.startByte && block.endByteInclusive == range.endByteInclusive) { "Repair range does not match trusted block boundaries" }
                val digest = MessageDigest.getInstance(if (manifest.algorithm == ChecksumAlgorithm.Sha512) "SHA-512" else "SHA-256")
                input.seek(block.startByte)
                var remaining = block.endByteInclusive - block.startByte + 1
                val buffer = ByteArray(BUFFER_SIZE)
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Repaired block ended before its trusted boundary")
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(block.checksumHex, ignoreCase = true)) throw IOException("Repaired block ${block.index} failed trusted checksum verification")
            }
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
