package com.mikeyphw.xdm.android.termux

import android.net.Uri
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import com.mikeyphw.xdm.android.persistence.PostProcessingJobEntity
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class PostProcessingSubjectType { Download, MediaCapture, Manual }

enum class PostProcessingJobStatus(val label: String, val terminal: Boolean = false) {
    Queued("Queued"),
    WaitingForPrerequisites("Waiting for prerequisites"),
    Preparing("Preparing"),
    Running("Running"),
    Publishing("Publishing"),
    Paused("Paused"),
    Cancelling("Cancelling"),
    Completed("Completed", terminal = true),
    Failed("Failed", terminal = true),
    Cancelled("Cancelled", terminal = true),
    TimedOut("Timed out", terminal = true),
    RecoveryRequired("Recovery required"),
}

enum class PostProcessingConflictPolicy { Fail, Rename, Replace }
enum class PostProcessingResultMode { OutputArtifact, MetadataOnly, SideEffectOnly, InPlace }
enum class PostProcessingPublicationState { None, Prepared, Committed, Reconciled }
enum class TermuxProcessControlAction { Pause, Resume, Cancel, ForceCancel, Probe }

data class PostProcessingOutputSpec(
    val displayName: String,
    val mimeType: String,
    val destinationUri: String? = null,
    val conflictPolicy: PostProcessingConflictPolicy = PostProcessingConflictPolicy.Rename,
    val deleteOriginalAfterPublish: Boolean = false,
)

data class PostProcessingJobSpec(
    val subjectId: String,
    val subjectType: PostProcessingSubjectType,
    val subjectGeneration: Long,
    val downloadId: String? = null,
    val captureId: String? = null,
    val ruleId: String? = null,
    val actionId: String,
    val trigger: PostProcessingAutomationTrigger,
    val kind: PostProcessingActionKind,
    val title: String,
    val inputUri: String,
    val inputMimeType: String? = null,
    val inputContainer: String? = null,
    val inputCodecs: String? = null,
    val output: PostProcessingOutputSpec,
    val expectedSha256: String? = null,
    val requiredTools: Set<ExternalTool> = emptySet(),
    val timeoutSeconds: Long = 30 * 60L,
    val estimatedOutputBytes: Long? = null,
    val resultMode: PostProcessingResultMode = PostProcessingResultMode.OutputArtifact,
    val metadataOnly: Boolean = resultMode == PostProcessingResultMode.MetadataOnly,
    val formatSelector: String? = null,
    val extraArguments: List<String> = emptyList(),
) {
    init {
        require(subjectId.isNotBlank()) { "Post-processing subject ID must not be blank" }
        require(actionId.isNotBlank()) { "Post-processing action ID must not be blank" }
        require(title.isNotBlank()) { "Post-processing title must not be blank" }
        require(inputUri.isNotBlank()) { "Post-processing input must not be blank" }
        require(timeoutSeconds in 10L..86_400L) { "Post-processing timeout must be between 10 seconds and 24 hours" }
        expectedSha256?.let { require(PostProcessingExecutionPolicy.isValidSha256(it)) { "Expected SHA-256 must be exactly 64 hexadecimal characters" } }
        require(PostProcessingExecutionPolicy.sensitiveArgumentReason(extraArguments) == null) {
            PostProcessingExecutionPolicy.sensitiveArgumentReason(extraArguments).orEmpty()
        }
        require(!PostProcessingExecutionPolicy.inputContainsBearerSecret(inputUri)) {
            "Bearer-like or signed remote URLs cannot cross the Termux command-line boundary; refresh the session and use an Android-owned backend."
        }
        if (kind == PostProcessingActionKind.VerifySha256) {
            require(expectedSha256 != null) { "Verify SHA-256 requires an expected digest" }
        }
    }

    fun toJson(): String = JSONObject()
        .put("subjectId", subjectId)
        .put("subjectType", subjectType.name)
        .put("subjectGeneration", subjectGeneration)
        .putNullable("downloadId", downloadId)
        .putNullable("captureId", captureId)
        .putNullable("ruleId", ruleId)
        .put("actionId", actionId)
        .put("trigger", trigger.name)
        .put("kind", kind.name)
        .put("title", title)
        .put("inputUri", inputUri)
        .putNullable("inputMimeType", inputMimeType)
        .putNullable("inputContainer", inputContainer)
        .putNullable("inputCodecs", inputCodecs)
        .put("output", JSONObject()
            .put("displayName", output.displayName)
            .put("mimeType", output.mimeType)
            .putNullable("destinationUri", output.destinationUri)
            .put("conflictPolicy", output.conflictPolicy.name)
            .put("deleteOriginalAfterPublish", output.deleteOriginalAfterPublish))
        .putNullable("expectedSha256", expectedSha256)
        .put("requiredTools", JSONArray(requiredTools.map(ExternalTool::name)))
        .put("timeoutSeconds", timeoutSeconds)
        .putNullable("estimatedOutputBytes", estimatedOutputBytes)
        .put("resultMode", resultMode.name)
        .put("metadataOnly", metadataOnly)
        .putNullable("formatSelector", formatSelector)
        .put("extraArguments", JSONArray(extraArguments))
        .toString()

    companion object {
        fun fromJson(raw: String): PostProcessingJobSpec {
            val json = JSONObject(raw)
            val output = json.getJSONObject("output")
            val tools = json.optJSONArray("requiredTools") ?: JSONArray()
            val arguments = json.optJSONArray("extraArguments") ?: JSONArray()
            return PostProcessingJobSpec(
                subjectId = json.getString("subjectId"),
                subjectType = enumValueOrDefault(json.optString("subjectType"), PostProcessingSubjectType.Manual),
                subjectGeneration = json.optLong("subjectGeneration", 0L),
                downloadId = json.optNullableString("downloadId"),
                captureId = json.optNullableString("captureId"),
                ruleId = json.optNullableString("ruleId"),
                actionId = json.getString("actionId"),
                trigger = enumValueOrDefault(json.optString("trigger"), PostProcessingAutomationTrigger.MediaCaptured),
                kind = enumValueOrDefault(json.optString("kind"), PostProcessingActionKind.FfprobeInspect),
                title = json.getString("title"),
                inputUri = json.getString("inputUri"),
                inputMimeType = json.optNullableString("inputMimeType"),
                inputContainer = json.optNullableString("inputContainer"),
                inputCodecs = json.optNullableString("inputCodecs"),
                output = PostProcessingOutputSpec(
                    displayName = output.optString("displayName", "xdm-output.bin"),
                    mimeType = output.optString("mimeType", "application/octet-stream"),
                    destinationUri = output.optNullableString("destinationUri"),
                    conflictPolicy = enumValueOrDefault(output.optString("conflictPolicy"), PostProcessingConflictPolicy.Rename),
                    deleteOriginalAfterPublish = output.optBoolean("deleteOriginalAfterPublish", false),
                ),
                expectedSha256 = json.optNullableString("expectedSha256"),
                requiredTools = buildSet {
                    repeat(tools.length()) { index -> enumValueOrNull<ExternalTool>(tools.optString(index))?.let(::add) }
                },
                timeoutSeconds = json.optLong("timeoutSeconds", 30 * 60L),
                estimatedOutputBytes = json.optNullableLong("estimatedOutputBytes"),
                resultMode = enumValueOrDefault(
                    json.optString("resultMode"),
                    if (json.optBoolean("metadataOnly", false)) PostProcessingResultMode.MetadataOnly else PostProcessingResultMode.OutputArtifact,
                ),
                metadataOnly = json.optBoolean("metadataOnly", false),
                formatSelector = json.optNullableString("formatSelector"),
                extraArguments = buildList { repeat(arguments.length()) { add(arguments.optString(it)) } },
            )
        }
    }
}

data class TermuxRuntimeArtifacts(
    val ownerShellPath: String,
    val ownerBridgeUri: String,
    val progressShellPath: String,
    val progressBridgeUri: String,
    val metadataShellPath: String,
    val metadataBridgeUri: String,
)

data class TermuxRunOwner(
    val jobId: String,
    val processToken: String,
    val timeoutAtEpochMs: Long?,
    val runtime: TermuxRuntimeArtifacts,
)

data class TermuxOwnerSnapshot(
    val state: String,
    val jobId: String,
    val token: String,
    val pid: Int?,
    val processGroup: Int?,
    val processStartTicks: Long?,
    val setsid: Boolean,
    val exitCode: Int?,
    val payloadPath: String?,
) {
    val finished: Boolean get() = state == "finished"
    val aliveState: Boolean get() = state in setOf("preparing", "running", "paused", "cancelling")

    companion object {
        fun parse(raw: String): TermuxOwnerSnapshot? {
            if (raw.isBlank()) return null
            val values = raw.lineSequence().mapNotNull { line ->
                val key = line.substringBefore('=', missingDelimiterValue = "").trim()
                key.takeIf(String::isNotBlank)?.let { it to line.substringAfter('=', "").trim() }
            }.toMap()
            val jobId = values["jobId"].orEmpty()
            val token = values["token"].orEmpty()
            if (jobId.isBlank() || token.isBlank()) return null
            return TermuxOwnerSnapshot(
                state = values["state"].orEmpty(),
                jobId = jobId,
                token = token,
                pid = values["pid"]?.toIntOrNull(),
                processGroup = values["processGroup"]?.toIntOrNull(),
                processStartTicks = values["processStartTicks"]?.toLongOrNull(),
                setsid = values["setsid"] == "1",
                exitCode = values["exitCode"]?.toIntOrNull(),
                payloadPath = values["payload"]?.takeIf(String::isNotBlank),
            )
        }
    }
}

data class TermuxResultPayload(
    val runId: String,
    val jobId: String?,
    val processToken: String?,
    val operation: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val error: String,
    val stdoutOriginalLength: Int,
    val stderrOriginalLength: Int,
)

interface TermuxResultRouter {
    fun routeTermuxResult(result: TermuxResultPayload)
}

interface TermuxResultRouterProvider {
    val termuxResultRouter: TermuxResultRouter
}

object PostProcessingExecutionPolicy {
    const val MaxOutputNameBytes: Int = 240
    const val DefaultCapacityReserveBytes: Long = 256L * 1024L * 1024L
    const val ToolProbeFreshnessMs: Long = 10L * 60L * 1000L
    private val Sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private val SensitiveHeaderNames = setOf("authorization", "cookie", "proxy-authorization", "x-api-key")
    private val SensitiveQueryKeys = setOf(
        "token", "access_token", "id_token", "signature", "sig", "auth", "authorization", "key", "apikey", "api_key",
        "policy", "credential", "x-amz-signature", "x-amz-credential", "x-amz-security-token", "x-goog-signature",
        "x-goog-credential", "googleaccessid", "key-pair-id", "hdnea", "hdnts", "jwt", "session", "sessionid",
    )

    fun isValidSha256(value: String): Boolean = Sha256Pattern.matches(value.trim())

    fun normalizedSha256(value: String?): String? = value?.trim()?.takeIf(::isValidSha256)?.lowercase(Locale.US)

    fun validateOutputName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return "Output filename is empty."
        if (trimmed == "." || trimmed == ".." || '/' in trimmed || '\\' in trimmed || '\u0000' in trimmed) return "Output filename contains a path separator or reserved value."
        if (trimmed.endsWith('.') || trimmed.endsWith(' ')) return "Output filename cannot end with a dot or space."
        val stem = trimmed.substringBefore('.').uppercase(Locale.US)
        if (stem in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")) return "Output filename uses a reserved provider/device name."
        if (trimmed.toByteArray(Charsets.UTF_8).size > MaxOutputNameBytes) return "Output filename exceeds the $MaxOutputNameBytes-byte provider limit."
        return null
    }

    fun sensitiveArgumentReason(arguments: List<String>): String? {
        var index = 0
        while (index < arguments.size) {
            val flag = arguments[index].lowercase(Locale.US)
            val value = arguments.getOrNull(index + 1).orEmpty()
            if (flag in setOf("--cookies", "--cookies-from-browser", "--username", "--password", "--video-password", "--netrc")) {
                return "$flag cannot be placed in a Termux RUN_COMMAND argument."
            }
            if (flag == "--add-header") {
                val header = value.substringBefore(':').trim().lowercase(Locale.US)
                if (header in SensitiveHeaderNames) return "$header header cannot be placed in a Termux RUN_COMMAND argument."
            }
            if (flag.startsWith("--add-header=")) {
                val header = flag.substringAfter('=').substringBefore(':').trim()
                if (header in SensitiveHeaderNames) return "$header header cannot be placed in a Termux RUN_COMMAND argument."
            }
            index += if (flag.startsWith("--") && '=' !in flag) 2 else 1
        }
        return null
    }

    fun inputContainsBearerSecret(input: String): Boolean {
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https", "ftp")) return false
        if (!uri.userInfo.isNullOrBlank()) return true
        return uri.queryParameterNames.any { it.lowercase(Locale.US) in SensitiveQueryKeys }
    }

    fun preflightIssue(spec: PostProcessingJobSpec, bridge: TermuxBridgeStatus): String? {
        validateOutputName(spec.output.displayName)?.let { return it }
        formatCompatibilityIssue(spec)?.let { return it }
        if (!spec.kind.requiresTermux) return null
        if (!bridge.termuxInstalled) return "Termux is not installed."
        if (!bridge.runCommandPermissionGranted) return "Termux RUN_COMMAND permission is not granted."
        if (spec.kind.requiresRoot && !bridge.canRunRootAction) {
            return "This action requires a successful root probe and Trusted Actions or Ask Each Time root mode."
        }
        if (spec.requiredTools.isNotEmpty() && !bridge.hasFreshSuccessfulToolProbe()) {
            return "Run a successful Termux tool and capability probe before this action can be offered or launched."
        }
        val unavailable = spec.requiredTools.mapNotNull { tool ->
            bridge.toolRows.firstOrNull { it.tool == tool }
                ?.takeUnless { it.available && it.executablePath.isNotBlank() && it.versionLine.isNotBlank() && it.versionLine != "Not probed yet" }
                ?.let { tool.displayName }
                ?: if (bridge.toolRows.none { it.tool == tool }) tool.displayName else null
        }
        if (unavailable.isNotEmpty()) return "Required Termux tools are unavailable or unverified: ${unavailable.joinToString()}."
        capabilityIssue(spec, bridge)?.let { return it }
        return null
    }

    fun formatCompatibilityIssue(spec: PostProcessingJobSpec): String? {
        val outputName = spec.output.displayName.lowercase(Locale.US)
        val outputMime = spec.output.mimeType.lowercase(Locale.US)
        val inputMime = spec.inputMimeType?.lowercase(Locale.US).orEmpty()
        val inputContainer = spec.inputContainer?.lowercase(Locale.US).orEmpty()
        val inputCodecs = spec.inputCodecs?.lowercase(Locale.US).orEmpty()
        if (spec.formatSelector?.any { it.isISOControl() } == true || (spec.formatSelector?.length ?: 0) > 256) {
            return "yt-dlp format selector contains control characters or exceeds 256 characters."
        }
        return when (spec.kind) {
            PostProcessingActionKind.RemuxFastStart -> when {
                !outputName.endsWith(".mp4") || outputMime != "video/mp4" -> "Fast-start remux requires an MP4 output filename and video/mp4 MIME type."
                inputMime.isNotBlank() && !inputMime.startsWith("video/") -> "Fast-start remux requires a video input."
                inputContainer.isNotBlank() && inputContainer.split(',', ' ').none { it in setOf("mp4", "mov", "m4v", "3gp", "3g2", "mj2") } -> "Fast-start remux requires an ISO base media input container; inspect the item with FFprobe first."
                else -> null
            }
            PostProcessingActionKind.ExtractAudio -> when {
                !outputMime.startsWith("audio/") -> "Audio extraction requires an audio output MIME type."
                outputName.substringAfterLast('.', "") !in setOf("m4a", "aac", "mp3", "opus", "ogg", "flac", "wav") -> "Audio extraction output container is unsupported."
                inputMime.isNotBlank() && !inputMime.startsWith("audio/") && !inputMime.startsWith("video/") -> "Audio extraction requires an audio or video input."
                inputCodecs.isNotBlank() && inputCodecs.split(',', ' ').filter(String::isNotBlank).all { it in setOf("h264", "hevc", "av1", "vp8", "vp9", "mpeg4") } -> "No audio codec is present in the known stream metadata; inspect the item with FFprobe first."
                else -> null
            }
            PostProcessingActionKind.FfmpegRemux -> when {
                outputName.substringAfterLast('.', "") !in setOf("mp4", "mkv", "webm", "m4a", "mov", "ts") -> "FFmpeg remux output container is unsupported."
                !outputMime.startsWith("video/") && !outputMime.startsWith("audio/") -> "FFmpeg remux requires an audio or video output MIME type."
                else -> null
            }
            PostProcessingActionKind.YtDlpDownload -> when {
                spec.formatSelector.isNullOrBlank() -> "yt-dlp download requires an explicit format selector."
                outputName.substringAfterLast('.', "") !in setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg") -> "yt-dlp output container is unsupported."
                else -> null
            }
            PostProcessingActionKind.CleanupPartials -> if (Uri.parse(spec.inputUri).scheme == "content") {
                "Cleanup partials requires the exact owned partial artifact path, not a content URI."
            } else null
            else -> null
        }
    }

    fun capabilityIssue(spec: PostProcessingJobSpec, bridge: TermuxBridgeStatus): String? {
        if (ExternalTool.Ffmpeg in spec.requiredTools) {
            val extension = spec.output.displayName.substringAfterLast('.', "").lowercase(Locale.US)
            if (extension.isNotBlank() && bridge.ffmpegMuxers.isNotEmpty() && extension !in bridge.ffmpegMuxers) {
                return "The probed FFmpeg build does not advertise the $extension output muxer."
            }
        }
        return null
    }

    fun sanitizeDurableRemoteUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https", "ftp")) return raw
        if (!uri.userInfo.isNullOrBlank()) return null
        if (uri.queryParameterNames.any { it.lowercase(Locale.US) in SensitiveQueryKeys }) return null
        return raw
    }

    fun sanitizeMetadataJson(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: return PrivacyDiagnosticsRedactor.redactText(raw)?.take(32_768).orEmpty()
        fun scrub(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { key ->
                    val lower = key.lowercase(Locale.US)
                    when {
                        lower in SensitiveHeaderNames || lower in setOf("cookies", "cookie", "headers", "http_headers", "authorization", "password", "username") -> out.put(key, "[REDACTED]")
                        lower in setOf("url", "webpage_url", "original_url", "manifest_url", "fragment_base_url", "thumbnail") -> out.put(key, sanitizeDurableRemoteUrl(value.optString(key)) ?: "[REDACTED_URL]")
                        else -> out.put(key, scrub(value.opt(key)) ?: JSONObject.NULL)
                    }
                }
            }
            is JSONArray -> JSONArray().also { out -> repeat(value.length()) { index -> out.put(scrub(value.opt(index)) ?: JSONObject.NULL) } }
            is String -> when {
                inputContainsBearerSecret(value) -> "[REDACTED_URL]"
                else -> PrivacyDiagnosticsRedactor.redactText(value)?.take(16_384).orEmpty()
            }
            else -> value
        }
        return (scrub(parsed) as JSONObject).toString()
    }

    fun sanitizeDurableMetadata(raw: String): String = sanitizeMetadataJson(raw)

    fun claimKey(spec: PostProcessingJobSpec): String = sha256(
        listOf(spec.subjectId, spec.subjectGeneration, spec.trigger.name, spec.ruleId.orEmpty(), spec.actionId).joinToString("\u0000"),
    )

    fun processToken(jobId: String, generation: Int, entropy: String): String = sha256("$jobId\u0000$generation\u0000$entropy").take(32)

    fun fallbackSubjectGeneration(subjectId: String, createdAtEpochMs: Long): Long =
        createdAtEpochMs.takeIf { it > 0L } ?: stablePositiveLong("fallback-attempt\u0000$subjectId")

    fun mediaSubjectGeneration(
        captureId: String,
        linkedDownloadId: String?,
        resolvedAtEpochMs: Long?,
        createdAtEpochMs: Long,
    ): Long = resolvedAtEpochMs?.takeIf { it > 0L }
        ?: stablePositiveLong("media-attempt\u0000$captureId\u0000${linkedDownloadId.orEmpty()}\u0000$createdAtEpochMs")

    private fun stablePositiveLong(value: String): Long =
        sha256(value).take(15).toLong(16).coerceAtLeast(1L)


    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun PostProcessingJobEntity.toPipelineJob(): TermuxMediaPipelineJob = TermuxMediaPipelineJob(
    id = id,
    rootJobId = rootJobId,
    parentJobId = parentJobId,
    attemptGeneration = attemptGeneration,
    captureId = captureId,
    downloadId = downloadId,
    title = title,
    kind = TermuxMediaJobKind.fromAction(kind),
    status = TermuxMediaJobStatus.fromPersistent(status),
    input = inputUri,
    output = finalOutputUri ?: outputDestinationUri ?: outputDisplayName,
    runId = runId.orEmpty(),
    processId = processId,
    processToken = processToken.orEmpty(),
    progressPercent = progressPercent,
    progressBytes = progressBytes,
    progressTotalBytes = progressTotalBytes,
    timeoutAtEpochMs = timeoutAtEpochMs,
    message = message,
    redactedSession = "credentials are never placed in command arguments, logs, or process listings",
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
private fun JSONObject.optNullableString(name: String): String? = if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)
private fun JSONObject.optNullableLong(name: String): Long? = if (!has(name) || isNull(name)) null else optLong(name)
private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? = enumValues<T>().firstOrNull { it.name == value }
private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T = enumValueOrNull<T>(value) ?: default
