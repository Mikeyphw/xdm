package com.mikeyphw.xdm.android.transfer.nativeengine

import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.storage.DestinationWriter
import com.mikeyphw.xdm.android.storage.PreparedDestination
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.UUID

data class NativeStoragePathProbeResult(
    val successful: Boolean,
    val summary: String,
)

/**
 * Exercises the exact prepared-destination contract used by the native HTTP backend without
 * depending on public networking. The probe writes through the backend staging artifact and then
 * promotes it through DestinationWriter, so direct-file destination and finalization failures are
 * visible before a real download is started.
 */
class NativeStoragePathProbe(
    private val destinationWriter: DestinationWriter,
) {
    suspend fun run(destinationUri: String): NativeStoragePathProbeResult {
        val token = UUID.randomUUID().toString()
        val fileName = ".xdm-native-storage-probe-$token.bin"
        val payload = "XDM native storage probe $token\n".toByteArray(Charsets.UTF_8)
        val request = DestinationRequest(
            downloadId = "storage-doctor-native-$token",
            destinationUri = destinationUri,
            fileName = fileName,
            conflictPolicy = FilenameConflictPolicy.Overwrite,
        )
        var committedFile: File? = null
        var prepared: PreparedDestination? = null
        return try {
            prepared = destinationWriter.prepare(request)
            val staging = requireNotNull(prepared).artifacts.stagingFile
            check(staging.parentFile?.isDirectory == true || staging.parentFile?.mkdirs() == true) {
                "Native staging directory is unavailable."
            }
            FileOutputStream(staging, false).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            check(staging.readBytes().contentEquals(payload)) { "Native staging read-back did not match." }
            val promotion = requireNotNull(prepared).promote()
            val committedUri = URI(promotion.committedUri)
            check(committedUri.scheme.equals("file", ignoreCase = true)) {
                "Direct-storage native probe did not publish to a filesystem URI."
            }
            committedFile = File(committedUri)
            check(committedFile.isFile) { "Native destination probe file was not published." }
            check(committedFile.readBytes().contentEquals(payload)) { "Native destination probe read-back did not match." }
            NativeStoragePathProbeResult(
                successful = true,
                summary = "Native destination staging and promotion wrote ${payload.size} verified bytes.",
            )
        } catch (error: Throwable) {
            NativeStoragePathProbeResult(
                successful = false,
                summary = "Native destination probe failed: ${error.message ?: error::class.java.simpleName}",
            )
        } finally {
            runCatching { prepared?.deleteArtifacts() }
            runCatching { committedFile?.delete() }
        }
    }
}
