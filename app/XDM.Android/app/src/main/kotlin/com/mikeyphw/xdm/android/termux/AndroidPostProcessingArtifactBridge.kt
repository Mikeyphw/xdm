package com.mikeyphw.xdm.android.termux

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.storage.DestinationUris
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provider-aware bridge between Android-owned artifacts and Termux's filesystem-only process model.
 * Every bridge row is owned by XDM, recorded in Room, and removed after terminal reconciliation.
 */
class AndroidPostProcessingArtifactBridge(
    context: Context,
    private val destinationWriter: AndroidDestinationWriter,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    data class BridgeArtifact(val contentUri: Uri, val shellPath: String, val bytes: Long)

    data class PreparedExecution(
        val inputPath: String,
        val inputBridgeUri: String?,
        val outputPath: String?,
        val outputBridgeUri: String?,
        val runtime: TermuxRuntimeArtifacts,
        val availableBytes: Long,
    )

    data class ImportedOutput(
        val finalUri: String,
        val displayName: String,
        val bytes: Long,
        val sha256: String,
    )

    data class PublicationPlan(
        val displayName: String,
        val expectedBytes: Long,
        val expectedSha256: String,
        val outputBridgeUri: String,
    )

    data class SideEffectOutcome(
        val code: String,
        val message: String,
        val affectedArtifacts: List<String>,
    )

    suspend fun prepare(spec: PostProcessingJobSpec, jobId: String): PreparedExecution = withContext(Dispatchers.IO) {
        PostProcessingExecutionPolicy.validateOutputName(spec.output.displayName)?.let(::error)
        val sourceUri = Uri.parse(spec.inputUri)
        val sourceScheme = sourceUri.scheme?.lowercase()
        val sourceFile = when (sourceScheme) {
            ContentResolver.SCHEME_FILE -> File(requireNotNull(sourceUri.path)).canonicalFile
            null, "" -> File(spec.inputUri).canonicalFile
            else -> null
        }
        sourceFile?.let { require(it.isFile) { "Post-processing input does not exist: ${it.path}" } }
        val inputBytes = when (sourceScheme) {
            ContentResolver.SCHEME_CONTENT -> querySize(sourceUri)
            ContentResolver.SCHEME_FILE, null, "" -> sourceFile?.length()
            "http", "https", "ftp" -> null
            else -> error("Unsupported post-processing input scheme: $sourceScheme")
        }
        val inputNeedsBridge = when (sourceScheme) {
            ContentResolver.SCHEME_CONTENT -> true
            ContentResolver.SCHEME_FILE, null, "" -> sourceFile?.let { !isDirectTermuxFile(it) } ?: false
            else -> false
        }
        val available = StatFs(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath).availableBytes
        val estimatedOutput = if (spec.resultMode == PostProcessingResultMode.OutputArtifact) {
            spec.estimatedOutputBytes ?: estimateOutput(spec.kind, inputBytes ?: 0L)
        } else {
            0L
        }
        val inputStaging = if (inputNeedsBridge) inputBytes ?: 0L else 0L
        val toolScratch = estimateToolScratch(spec.kind, inputBytes ?: 0L, estimatedOutput)
        val bridgePeak = inputStaging + estimatedOutput + toolScratch + PostProcessingExecutionPolicy.DefaultCapacityReserveBytes
        require(available >= bridgePeak) {
            "Insufficient shared storage for input staging, output staging, tool scratch, and reserve: need $bridgePeak bytes, have $available bytes."
        }
        preflightFinalDestination(spec, jobId, estimatedOutput)

        var input: BridgeArtifact? = null
        var output: BridgeArtifact? = null
        var owner: BridgeArtifact? = null
        var progress: BridgeArtifact? = null
        var metadata: BridgeArtifact? = null
        try {
            input = when (sourceScheme) {
                ContentResolver.SCHEME_CONTENT -> createInputBridge(
                    source = sourceUri,
                    jobId = jobId,
                    maximumBytes = available - estimatedOutput - toolScratch - PostProcessingExecutionPolicy.DefaultCapacityReserveBytes,
                )
                ContentResolver.SCHEME_FILE, null, "" -> directFileInput(
                    file = requireNotNull(sourceFile),
                    jobId = jobId,
                    maximumBridgeBytes = available - estimatedOutput - toolScratch - PostProcessingExecutionPolicy.DefaultCapacityReserveBytes,
                )
                "http", "https", "ftp" -> null
                else -> error("Unsupported post-processing input scheme: $sourceScheme")
            }
            output = if (spec.resultMode != PostProcessingResultMode.OutputArtifact || spec.kind in setOf(PostProcessingActionKind.VerifySha256, PostProcessingActionKind.FfprobeInspect, PostProcessingActionKind.YtDlpMetadata)) {
                null
            } else {
                createEmptyBridgeRow(jobId, "output", safeBridgeName(spec.output.displayName), spec.output.mimeType)
            }
            owner = createEmptyBridgeRow(jobId, "owner", "owner.txt", "text/plain")
            progress = createEmptyBridgeRow(jobId, "progress", "progress.txt", "text/plain")
            metadata = createEmptyBridgeRow(jobId, "metadata", "metadata.json", "application/json")
            val runtime = TermuxRuntimeArtifacts(
                ownerShellPath = owner.shellPath,
                ownerBridgeUri = owner.contentUri.toString(),
                progressShellPath = progress.shellPath,
                progressBridgeUri = progress.contentUri.toString(),
                metadataShellPath = metadata.shellPath,
                metadataBridgeUri = metadata.contentUri.toString(),
            )
            PreparedExecution(
                inputPath = input?.shellPath ?: spec.inputUri,
                inputBridgeUri = input?.contentUri?.toString(),
                outputPath = output?.shellPath,
                outputBridgeUri = output?.contentUri?.toString(),
                runtime = runtime,
                availableBytes = available,
            )
        } catch (error: Throwable) {
            cleanupUris(listOf(input?.contentUri?.toString(), output?.contentUri?.toString(), owner?.contentUri?.toString(), progress?.contentUri?.toString(), metadata?.contentUri?.toString()))
            throw error
        }
    }

    suspend fun preparePublication(spec: PostProcessingJobSpec, jobId: String, outputBridgeUri: String): PublicationPlan = withContext(Dispatchers.IO) {
        val sourceUri = Uri.parse(outputBridgeUri)
        val size = querySize(sourceUri) ?: error("Termux output bridge is missing or has no size")
        require(size > 0L) { "Termux produced an empty output" }
        val digest = sha256(sourceUri)
        PostProcessingExecutionPolicy.normalizedSha256(spec.expectedSha256)?.let { expected ->
            require(digest == expected) { "Output SHA-256 mismatch" }
        }
        val baseRequest = destinationRequest(spec, jobId)
        val conflict = destinationWriter.previewConflict(baseRequest)
        val effectiveName = when {
            conflict == null -> spec.output.displayName
            spec.output.conflictPolicy == PostProcessingConflictPolicy.Rename -> conflict.suggestedName
            spec.output.conflictPolicy == PostProcessingConflictPolicy.Replace -> conflict.requestedName
            else -> error("Destination already exists and the saved conflict policy is Fail")
        }
        PostProcessingExecutionPolicy.validateOutputName(effectiveName)?.let(::error)
        PublicationPlan(effectiveName, size, digest, outputBridgeUri)
    }

    suspend fun publishPrepared(spec: PostProcessingJobSpec, jobId: String, plan: PublicationPlan): ImportedOutput = withContext(Dispatchers.IO) {
        recoverPublished(spec, jobId, plan)?.let { return@withContext it }
        val sourceUri = Uri.parse(plan.outputBridgeUri)
        require(querySize(sourceUri) == plan.expectedBytes) { "Output bridge changed after publication preparation" }
        require(sha256(sourceUri) == plan.expectedSha256) { "Output bridge digest changed after publication preparation" }
        val effectiveSpec = spec.copy(
            output = spec.output.copy(
                displayName = plan.displayName,
                conflictPolicy = if (spec.output.conflictPolicy == PostProcessingConflictPolicy.Replace) PostProcessingConflictPolicy.Replace else PostProcessingConflictPolicy.Fail,
            ),
        )
        val prepared = destinationWriter.prepare(destinationRequest(effectiveSpec, jobId))
        try {
            prepared.availableSpace()?.let { available ->
                require(available >= plan.expectedBytes + PostProcessingExecutionPolicy.DefaultCapacityReserveBytes) {
                    "Final destination does not have enough free space for transactional publication."
                }
            }
            openInput(sourceUri).use { input ->
                FileOutputStream(prepared.artifacts.stagingFile).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                    output.fd.sync()
                }
            }
            require(prepared.artifacts.stagingFile.length() == plan.expectedBytes) { "Staging size changed during publication" }
            require(sha256(prepared.artifacts.stagingFile) == plan.expectedSha256) { "Staging digest changed during publication" }
            val promoted = prepared.promote()
            require(promoted.bytesCommitted == plan.expectedBytes) { "Committed output size mismatch" }
            ImportedOutput(promoted.committedUri, promoted.displayName, promoted.bytesCommitted, plan.expectedSha256)
        } catch (error: Throwable) {
            prepared.deleteArtifacts()
            throw error
        }
    }

    suspend fun recoverPublished(spec: PostProcessingJobSpec, jobId: String, plan: PublicationPlan): ImportedOutput? = withContext(Dispatchers.IO) {
        val effectiveSpec = spec.copy(output = spec.output.copy(displayName = plan.displayName, conflictPolicy = PostProcessingConflictPolicy.Fail))
        val conflict = destinationWriter.previewConflict(destinationRequest(effectiveSpec, jobId)) ?: return@withContext null
        val existingUri = Uri.parse(conflict.existingUri)
        if (querySize(existingUri) != plan.expectedBytes) return@withContext null
        if (sha256(existingUri) != plan.expectedSha256) return@withContext null
        ImportedOutput(conflict.existingUri, plan.displayName, plan.expectedBytes, plan.expectedSha256)
    }


    suspend fun renameOriginal(spec: PostProcessingJobSpec): ImportedOutput = withContext(Dispatchers.IO) {
        require(spec.kind == PostProcessingActionKind.RenameByPattern) { "renameOriginal requires a RenameByPattern specification" }
        val requestedName = spec.output.displayName
        PostProcessingExecutionPolicy.validateOutputName(requestedName)?.let(::error)
        val source = Uri.parse(spec.inputUri)
        val beforeSize = querySize(source) ?: error("Unable to determine the original artifact size before rename")
        require(beforeSize > 0L) { "Cannot rename an empty or missing artifact" }
        val beforeDigest = sha256(source)
        val renamedUri = when (source.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT -> renameContentUri(source, requestedName)
            ContentResolver.SCHEME_FILE, null, "" -> renameDirectFile(source, spec.inputUri, requestedName, spec.output.conflictPolicy)
            else -> error("Rename requires a local file or content URI")
        }
        val afterSize = querySize(renamedUri) ?: error("Renamed artifact is no longer readable")
        require(afterSize == beforeSize) { "Renamed artifact size changed" }
        val afterDigest = sha256(renamedUri)
        require(afterDigest == beforeDigest) { "Renamed artifact digest changed" }
        val actualName = queryDisplayName(renamedUri) ?: renamedUri.path?.let(::File)?.name ?: requestedName
        ImportedOutput(renamedUri.toString(), actualName, afterSize, afterDigest)
    }

    private fun renameContentUri(source: Uri, requestedName: String): Uri {
        if (android.provider.DocumentsContract.isDocumentUri(appContext, source)) {
            return requireNotNull(android.provider.DocumentsContract.renameDocument(resolver, source, requestedName)) {
                "The document provider refused the rename"
            }
        }
        val updated = resolver.update(
            source,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName) },
            null,
            null,
        )
        require(updated == 1) { "The content provider did not rename exactly one artifact" }
        return source
    }

    private fun renameDirectFile(sourceUri: Uri, rawInput: String, requestedName: String, conflictPolicy: PostProcessingConflictPolicy): Uri {
        val source = when (sourceUri.scheme?.lowercase()) {
            ContentResolver.SCHEME_FILE -> File(requireNotNull(sourceUri.path))
            else -> File(rawInput)
        }.canonicalFile
        require(source.isFile) { "Original artifact does not exist" }
        val parent = requireNotNull(source.parentFile?.canonicalFile) { "Original artifact has no parent directory" }
        val requested = File(parent, requestedName).canonicalFile
        require(requested.parentFile == parent) { "Rename target escaped the original directory" }
        val target = when {
            requested.path == source.path -> return Uri.fromFile(source)
            !requested.exists() -> requested
            conflictPolicy == PostProcessingConflictPolicy.Fail -> error("Rename target already exists")
            conflictPolicy == PostProcessingConflictPolicy.Rename -> uniqueSibling(parent, requestedName)
            else -> requested
        }
        if (target.exists() && conflictPolicy == PostProcessingConflictPolicy.Replace) {
            val backup = File(parent, ".${target.name}.xdm-rename-backup-${System.nanoTime()}")
            require(target.renameTo(backup)) { "Unable to preserve the existing rename target" }
            try {
                require(source.renameTo(target)) { "Unable to rename the original artifact" }
                require(!backup.exists() || backup.delete()) { "Rename succeeded, but the temporary replacement backup could not be removed" }
            } catch (error: Throwable) {
                if (target.exists() && !source.exists()) target.renameTo(source)
                if (backup.exists()) backup.renameTo(target)
                throw error
            }
        } else {
            require(source.renameTo(target)) { "Unable to rename the original artifact" }
        }
        return Uri.fromFile(target)
    }

    private fun uniqueSibling(parent: File, requestedName: String): File {
        val stem = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        for (index in 1..10_000) {
            val candidate = File(parent, "$stem ($index)$extension").canonicalFile
            require(candidate.parentFile == parent) { "Generated rename target escaped the original directory" }
            if (!candidate.exists()) return candidate
        }
        error("Unable to allocate a collision-free rename target")
    }

    suspend fun copyInputToOutput(
        prepared: PreparedExecution,
        onProgress: suspend (bytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        shouldCancel: suspend () -> Boolean = { false },
    ): Unit = withContext(Dispatchers.IO) {
        val output = prepared.outputPath ?: error("This action has no output bridge")
        val source = File(prepared.inputPath)
        val totalBytes = source.length().coerceAtLeast(0L)
        source.inputStream().use { input ->
            FileOutputStream(File(output), false).use { out ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                var lastReported = 0L
                while (true) {
                    if (shouldCancel()) throw LocalOperationCancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    copied += read
                    if (copied - lastReported >= 1024L * 1024L) {
                        onProgress(copied, totalBytes)
                        lastReported = copied
                    }
                }
                out.flush()
                out.fd.sync()
                onProgress(copied, totalBytes)
            }
        }
    }

    suspend fun cleanupOwnedPartials(inputUri: String): SideEffectOutcome = withContext(Dispatchers.IO) {
        val target = canonicalDirectFile(inputUri)
        require(isApprovedOwnedArtifact(target)) { "Cleanup refused: artifact is not inside an XDM-owned root or has an unsupported artifact type" }
        val candidates = linkedSetOf(target)
        if (!target.name.endsWith(".part") && !target.name.endsWith(".aria2") && !target.name.endsWith(".tmp")) {
            candidates += File(target.path + ".part")
            candidates += File(target.path + ".aria2")
            candidates += File(target.path + ".tmp")
        }
        val removed = candidates.filter { it.exists() && it.isFile && it.delete() }.map(File::getCanonicalPath)
        SideEffectOutcome("cleanup_completed", "Removed ${removed.size} verified XDM-owned partial artifact(s).", removed)
    }

    fun verifiedOriginalPath(inputUri: String): String {
        val target = canonicalDirectFile(inputUri)
        require(isApprovedOwnedRoot(target)) { "Original artifact is outside approved XDM/download roots" }
        require(target.isFile) { "Original artifact does not exist" }
        return target.path
    }

    fun deleteBridgeAfterReconciliation(contentUri: String?) {
        contentUri?.takeIf(String::isNotBlank)?.let { deleteBridge(Uri.parse(it)) }
    }

    class LocalOperationCancelledException : java.util.concurrent.CancellationException("Local post-processing operation cancelled")
    class ChecksumCancelledException : java.util.concurrent.CancellationException("SHA-256 verification cancelled")

    suspend fun checksumInput(
        inputUri: String,
        onProgress: suspend (bytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        shouldCancel: suspend () -> Boolean = { false },
    ): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputUri)
        val totalBytes = querySize(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        val input = when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT -> requireNotNull(resolver.openInputStream(uri)) { "Unable to open content input for SHA-256" }
            ContentResolver.SCHEME_FILE -> FileInputStream(File(requireNotNull(uri.path)))
            null, "" -> FileInputStream(File(inputUri))
            else -> error("SHA-256 verification requires a local file or content URI")
        }
        input.use { stream ->
            val buffer = ByteArray(256 * 1024)
            var bytes = 0L
            var lastReportedBytes = 0L
            var lastReportAt = 0L
            while (true) {
                if (shouldCancel()) throw ChecksumCancelledException()
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                bytes += read
                val now = android.os.SystemClock.elapsedRealtime()
                if (bytes - lastReportedBytes >= 1024L * 1024L || now - lastReportAt >= 250L) {
                    onProgress(bytes, totalBytes)
                    lastReportedBytes = bytes
                    lastReportAt = now
                }
            }
            onProgress(bytes, totalBytes)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun readText(uri: String?): String = withContext(Dispatchers.IO) {
        uri?.takeIf(String::isNotBlank)?.let(Uri::parse)?.let { target ->
            runCatching { openInput(target).bufferedReader().use { it.readText() } }.getOrDefault("")
        }.orEmpty()
    }

    suspend fun deleteOriginalAfterPublish(spec: PostProcessingJobSpec) = withContext(Dispatchers.IO) {
        if (!spec.output.deleteOriginalAfterPublish) return@withContext
        val uri = Uri.parse(spec.inputUri)
        when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT -> {
                val deleted = resolver.delete(uri, null, null)
                if (deleted <= 0) {
                    val stillExists = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
                    check(!stillExists) { "Published replacement, but the original provider item could not be removed" }
                }
            }
            ContentResolver.SCHEME_FILE -> {
                val file = File(requireNotNull(uri.path))
                check(!file.exists() || file.delete()) { "Published replacement, but the original file could not be removed" }
            }
            null, "" -> {
                val file = File(spec.inputUri)
                check(!file.exists() || file.delete()) { "Published replacement, but the original file could not be removed" }
            }
            else -> error("Deleting a remote input after publication is not supported")
        }
    }

    fun cleanupBridge(uri: String?) {
        uri?.takeIf(String::isNotBlank)?.let { value ->
            runCatching {
                val target = Uri.parse(value)
                if (target.scheme == ContentResolver.SCHEME_FILE) {
                    target.path?.let(::File)?.delete()
                } else {
                    resolver.delete(target, null, null)
                }
            }
        }
    }

    fun cleanupUris(uris: Iterable<String?>) = uris.forEach(::cleanupBridge)

    private suspend fun preflightFinalDestination(spec: PostProcessingJobSpec, jobId: String, estimatedBytes: Long) {
        if (spec.resultMode != PostProcessingResultMode.OutputArtifact) return
        val prepared = destinationWriter.prepare(destinationRequest(spec, jobId))
        try {
            prepared.availableSpace()?.let { available ->
                require(available >= estimatedBytes + PostProcessingExecutionPolicy.DefaultCapacityReserveBytes) {
                    "Final destination preflight failed: need ${estimatedBytes + PostProcessingExecutionPolicy.DefaultCapacityReserveBytes} bytes, have $available bytes."
                }
            }
        } finally {
            prepared.deleteArtifacts()
        }
    }

    private fun destinationRequest(spec: PostProcessingJobSpec, jobId: String) = DestinationRequest(
        downloadId = "post-processing-$jobId",
        destinationUri = spec.output.destinationUri ?: destinationForMime(spec.output.mimeType),
        fileName = spec.output.displayName,
        mimeType = spec.output.mimeType,
        conflictPolicy = when (spec.output.conflictPolicy) {
            PostProcessingConflictPolicy.Fail -> FilenameConflictPolicy.Skip
            PostProcessingConflictPolicy.Rename -> FilenameConflictPolicy.Rename
            PostProcessingConflictPolicy.Replace -> FilenameConflictPolicy.Overwrite
        },
        stagingSuffix = ".xdm-post-processing",
    )

    private fun createInputBridge(source: Uri, jobId: String, maximumBytes: Long): BridgeArtifact {
        val display = queryDisplayName(source) ?: "input.bin"
        val mime = resolver.getType(source) ?: "application/octet-stream"
        val bridge = createEmptyBridgeRow(jobId, "input", safeBridgeName(display), mime)
        resolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open selected content input" }
            openOutput(bridge.contentUri).use { output ->
                copyWithLimit(input, output, maximumBytes)
                output.flush()
            }
        }
        return bridge.copy(bytes = querySize(bridge.contentUri) ?: 0L)
    }

    private fun directFileInput(file: File, jobId: String, maximumBridgeBytes: Long): BridgeArtifact {
        val canonical = file.canonicalFile
        require(canonical.isFile) { "Post-processing input does not exist: ${canonical.path}" }
        return if (isDirectTermuxFile(canonical)) {
            BridgeArtifact(Uri.fromFile(canonical), canonical.path, canonical.length())
        } else {
            val bridge = createEmptyBridgeRow(jobId, "input", safeBridgeName(canonical.name), "application/octet-stream")
            FileInputStream(canonical).use { input ->
                openOutput(bridge.contentUri).use { output ->
                    copyWithLimit(input, output, maximumBridgeBytes)
                    output.flush()
                }
            }
            bridge.copy(bytes = canonical.length())
        }
    }

    private fun isDirectTermuxFile(file: File): Boolean {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        return file.canonicalPath.startsWith(publicDownloads.path + File.separator)
    }

    private fun canonicalDirectFile(inputUri: String): File {
        val uri = Uri.parse(inputUri)
        val file = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(requireNotNull(uri.path))
            null, "" -> File(inputUri)
            else -> error("This side effect requires an exact direct-file artifact, not ${uri.scheme}:// content")
        }.canonicalFile
        return file
    }

    private fun isApprovedOwnedRoot(file: File): Boolean {
        val roots = listOf(
            appContext.filesDir,
            appContext.cacheDir,
            appContext.noBackupFilesDir,
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "XDM"),
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return roots.any { root -> file.path == root.path || file.path.startsWith(root.path + File.separator) }
    }

    private fun isApprovedOwnedArtifact(file: File): Boolean {
        val name = file.name.lowercase()
        val typed = name.endsWith(".part") || name.endsWith(".aria2") || name.endsWith(".tmp") || name.endsWith(".checkpoint.json") || name.endsWith(".finalization.json")
        return typed && isApprovedOwnedRoot(file)
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openInput(uri).use { updateDigest(it, digest) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyWithLimit(input: java.io.InputStream, output: java.io.OutputStream, maximumBytes: Long): Long {
        require(maximumBytes >= 0L) { "No capacity remains for the Termux input bridge" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return copied
            if (read == 0) continue
            copied += read
            require(copied <= maximumBytes) {
                "Input staging exceeded the preflight capacity limit; copied $copied bytes with a $maximumBytes-byte allowance."
            }
            output.write(buffer, 0, read)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("InlinedApi")
    private fun createEmptyBridgeRow(jobId: String, role: String, displayName: String, mimeType: String): BridgeArtifact {
        val ownedName = ".xdm-$role-${jobId.takeLast(12)}-$displayName"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "XDM/PostProcessing/Staging",
            ).canonicalFile
            require(directory.exists() || directory.mkdirs()) { "Unable to create the legacy Termux bridge directory" }
            val file = File(directory, ownedName).canonicalFile
            require(file.parentFile == directory) { "Legacy bridge name escaped the owned staging directory" }
            require(!file.exists() || file.delete()) { "Unable to replace a stale XDM-owned bridge file" }
            require(file.createNewFile()) { "Unable to allocate the legacy Termux bridge file" }
            return BridgeArtifact(Uri.fromFile(file), file.path, 0L)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ownedName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/XDM/PostProcessing/Staging/")
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Unable to allocate Termux bridge row" }
        val path = queryDataPath(uri)
        if (path.isNullOrBlank()) {
            resolver.delete(uri, null, null)
            error("The MediaStore provider did not expose the owned filesystem path required by Termux")
        }
        return BridgeArtifact(uri, path, querySize(uri) ?: 0L)
    }

    @Suppress("DEPRECATION")
    private fun queryDataPath(uri: Uri): String? = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getColumnIndex(MediaStore.MediaColumns.DATA).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
    }

    private fun queryDisplayName(uri: Uri): String? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
    }

    private fun querySize(uri: Uri): Long? = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.takeIf(File::isFile)?.length()
        else -> resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        }?.takeIf { it >= 0L } ?: runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length.takeIf { it >= 0L } }
        }.getOrNull()
    }

    private fun deleteBridge(uri: Uri) {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let(::File)?.delete()
        } else {
            resolver.delete(uri, null, null)
        }
    }

    private fun openInput(uri: Uri): java.io.InputStream = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> FileInputStream(File(requireNotNull(uri.path)))
        else -> requireNotNull(resolver.openInputStream(uri)) { "Unable to open bridge input: $uri" }
    }

    private fun openOutput(uri: Uri): java.io.OutputStream = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> FileOutputStream(File(requireNotNull(uri.path)), false)
        else -> requireNotNull(resolver.openOutputStream(uri, "rwt")) { "Unable to open bridge output: $uri" }
    }

    private fun destinationForMime(mimeType: String): String = when {
        mimeType.startsWith("video/") -> DestinationUris.MEDIA_MOVIES
        mimeType.startsWith("audio/") -> DestinationUris.MEDIA_MUSIC
        mimeType.startsWith("image/") -> DestinationUris.MEDIA_PICTURES
        else -> DestinationUris.PUBLIC_DOWNLOADS
    }

    private fun estimateOutput(kind: PostProcessingActionKind, inputBytes: Long): Long = when (kind) {
        PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.FfmpegRemux,
        PostProcessingActionKind.MoveToFolder, PostProcessingActionKind.RenameByPattern -> inputBytes.coerceAtLeast(64L * 1024L * 1024L)
        PostProcessingActionKind.ExtractAudio -> (inputBytes / 3L).coerceAtLeast(32L * 1024L * 1024L)
        PostProcessingActionKind.YtDlpDownload -> inputBytes.coerceAtLeast(512L * 1024L * 1024L)
        else -> 64L * 1024L * 1024L
    }

    private fun estimateToolScratch(kind: PostProcessingActionKind, inputBytes: Long, outputBytes: Long): Long = when (kind) {
        PostProcessingActionKind.YtDlpDownload -> outputBytes.coerceAtLeast(512L * 1024L * 1024L)
        PostProcessingActionKind.RemuxFastStart,
        PostProcessingActionKind.ExtractAudio,
        PostProcessingActionKind.FfmpegRemux -> maxOf(128L * 1024L * 1024L, minOf(inputBytes, outputBytes) / 4L)
        else -> 0L
    }

    private fun safeBridgeName(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "artifact.bin" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { updateDigest(it, digest) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigest(input: java.io.InputStream, digest: MessageDigest) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
}
