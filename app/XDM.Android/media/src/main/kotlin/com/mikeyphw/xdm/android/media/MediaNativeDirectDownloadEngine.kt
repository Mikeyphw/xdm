package com.mikeyphw.xdm.android.media

import java.util.Locale

/**
 * Phase 27 native direct download engine planner.
 *
 * This plans Android-native direct media transfers. It deliberately excludes adaptive/page-context
 * media, does not write bytes yet, and does not persist request header values. Header names can be
 * carried as transient execution policy, but cookie/authorization/token values stay process-scoped.
 */
enum class NativeDirectDestinationMode(val label: String) {
    AppPrivate("App private"),
    MediaStoreMovies("MediaStore movies"),
    MediaStoreMusic("MediaStore music"),
    SafDocument("SAF document"),
    LegacyFile("Legacy file"),
}

enum class NativeDirectRangeSupport(val label: String) {
    Unknown("Range support unknown"),
    Supported("Range resume supported"),
    NotSupported("Range resume unavailable"),
}

enum class NativeDirectRequestState(val label: String) {
    Ready("Ready"),
    ResumeCandidate("Resume candidate"),
    NeedsDestinationPermission("Needs destination permission"),
    UnsupportedAdaptive("Unsupported adaptive media"),
    BlockedSecretLeak("Blocked secret leak"),
}

data class NativeDirectHeaderPolicy(
    val transientHeaderNames: List<String>,
    val persistHeaderValues: Boolean,
    val redactedPreview: String,
) {
    val summary: String get() = listOf(
        "headers=${transientHeaderNames.joinToString().ifBlank { "none" }}",
        if (persistHeaderValues) "persist-values" else "transient-values-only",
    ).joinToString(" • ")
}

data class NativeDirectResumePlan(
    val rangeSupport: NativeDirectRangeSupport,
    val existingBytes: Long,
    val rangeHeaderPreview: String?,
    val validatorLabel: String,
) {
    val enabled: Boolean get() = rangeSupport == NativeDirectRangeSupport.Supported && existingBytes > 0L
    val summary: String get() = listOfNotNull(rangeSupport.label, existingBytes.takeIf { it > 0L }?.let { "from=$it" }, rangeHeaderPreview).joinToString(" • ")
}

data class NativeDirectFileNamingPlan(
    val fileName: String,
    val conflictPolicy: String,
    val sidecarFileName: String,
) {
    val summary: String get() = "$fileName • conflict=$conflictPolicy • sidecar=$sidecarFileName"
}

data class NativeDirectDownloadRequestPlan(
    val captureId: String,
    val durableJobId: String,
    val state: NativeDirectRequestState,
    val destinationMode: NativeDirectDestinationMode,
    val redactedUrl: String,
    val headerPolicy: NativeDirectHeaderPolicy,
    val resumePlan: NativeDirectResumePlan,
    val fileNaming: NativeDirectFileNamingPlan,
    val notificationTitle: String,
    val redactedDiagnostics: String,
    val launchable: Boolean,
    val secretSafe: Boolean,
) {
    val summary: String get() = listOf(
        state.label,
        destinationMode.label,
        fileNaming.fileName,
        resumePlan.summary,
        if (launchable) "launchable" else "not-ready",
        if (secretSafe) "secret-safe" else "redaction-review",
    ).joinToString(" • ")
}

data class NativeDirectDashboard(
    val plans: List<NativeDirectDownloadRequestPlan>,
    val readyCount: Int,
    val resumeCount: Int,
    val permissionCount: Int,
    val unsupportedCount: Int,
    val secretSafe: Boolean,
) {
    val summary: String get() = listOf(
        "ready=$readyCount",
        "resume=$resumeCount",
        "permission=$permissionCount",
        "unsupported=$unsupportedCount",
        if (secretSafe) "secret-safe" else "redaction review",
    ).joinToString(" • ")
}

class MediaNativeDirectDownloadPlanner {
    fun plan(
        request: MediaWorkerBridgeRequest,
        destinationUri: String,
        existingBytes: Long = 0L,
        hasDestinationPermission: Boolean = true,
    ): NativeDirectDownloadRequestPlan {
        val redactedUrl = extractJsonValue(request.redactedSidecarJson, "redactedSourceUrl").ifBlank { "<redacted-url>" }
        val fileName = extractJsonValue(request.redactedSidecarJson, "fileName").ifBlank { "xdm-media.bin" }
        val destination = destinationModeFor(destinationUri, fileName)
        val headerPolicy = headerPolicyFor(request)
        val resume = resumePlan(existingBytes, request)
        val diagnostics = diagnosticsFor(request, redactedUrl, destination, headerPolicy, resume)
        val secretSafe = request.secretSafe && !containsKnownSecret(diagnostics) && !containsKnownSecret(redactedUrl) && !headerPolicy.persistHeaderValues
        val state = when {
            !secretSafe -> NativeDirectRequestState.BlockedSecretLeak
            request.lane != MediaExecutionLane.DirectNative -> NativeDirectRequestState.UnsupportedAdaptive
            !hasDestinationPermission -> NativeDirectRequestState.NeedsDestinationPermission
            resume.enabled -> NativeDirectRequestState.ResumeCandidate
            else -> NativeDirectRequestState.Ready
        }
        return NativeDirectDownloadRequestPlan(
            captureId = request.captureId,
            durableJobId = request.durableJobId,
            state = state,
            destinationMode = destination,
            redactedUrl = redactedUrl,
            headerPolicy = headerPolicy,
            resumePlan = resume,
            fileNaming = NativeDirectFileNamingPlan(
                fileName = safeFileName(fileName),
                conflictPolicy = if (resume.enabled) "resume-existing-partial" else "rename-on-conflict",
                sidecarFileName = safeFileName(fileName).substringBeforeLast('.', safeFileName(fileName)) + ".xdm-media.json",
            ),
            notificationTitle = request.notification.title.take(80),
            redactedDiagnostics = diagnostics,
            launchable = secretSafe && (state == NativeDirectRequestState.Ready || state == NativeDirectRequestState.ResumeCandidate),
            secretSafe = secretSafe,
        )
    }

    fun dashboard(plans: List<NativeDirectDownloadRequestPlan>): NativeDirectDashboard = NativeDirectDashboard(
        plans = plans,
        readyCount = plans.count { it.state == NativeDirectRequestState.Ready },
        resumeCount = plans.count { it.state == NativeDirectRequestState.ResumeCandidate },
        permissionCount = plans.count { it.state == NativeDirectRequestState.NeedsDestinationPermission },
        unsupportedCount = plans.count { it.state == NativeDirectRequestState.UnsupportedAdaptive },
        secretSafe = plans.all { it.secretSafe },
    )

    private fun destinationModeFor(destinationUri: String, fileName: String): NativeDirectDestinationMode = when {
        destinationUri.startsWith("content://", ignoreCase = true) -> NativeDirectDestinationMode.SafDocument
        destinationUri.startsWith("mediastore://movies", ignoreCase = true) || fileName.endsWith(".mp4", true) || fileName.endsWith(".webm", true) -> NativeDirectDestinationMode.MediaStoreMovies
        destinationUri.startsWith("mediastore://music", ignoreCase = true) || fileName.endsWith(".mp3", true) || fileName.endsWith(".m4a", true) -> NativeDirectDestinationMode.MediaStoreMusic
        destinationUri.startsWith("file://", ignoreCase = true) -> NativeDirectDestinationMode.LegacyFile
        else -> NativeDirectDestinationMode.AppPrivate
    }

    private fun headerPolicyFor(request: MediaWorkerBridgeRequest): NativeDirectHeaderPolicy {
        val preview = request.adapter.redactedPreview
        val names = mutableListOf<String>()
        listOf("Referer", "User-Agent", "Accept", "Range", "Cookie", "Authorization").forEach { name ->
            if (preview.contains(name, ignoreCase = true)) names += name
        }
        return NativeDirectHeaderPolicy(
            transientHeaderNames = names.distinct(),
            persistHeaderValues = false,
            redactedPreview = "transient header names only: ${names.distinct().joinToString().ifBlank { "none" }}",
        )
    }

    private fun resumePlan(existingBytes: Long, request: MediaWorkerBridgeRequest): NativeDirectResumePlan {
        val support = when {
            request.lane != MediaExecutionLane.DirectNative -> NativeDirectRangeSupport.NotSupported
            existingBytes > 0L -> NativeDirectRangeSupport.Supported
            else -> NativeDirectRangeSupport.Unknown
        }
        return NativeDirectResumePlan(
            rangeSupport = support,
            existingBytes = existingBytes.coerceAtLeast(0L),
            rangeHeaderPreview = if (existingBytes > 0L) "Range=bytes=$existingBytes-" else null,
            validatorLabel = if (existingBytes > 0L) "validate-length-and-etag-before-append" else "probe-range-before-resume",
        )
    }

    private fun diagnosticsFor(
        request: MediaWorkerBridgeRequest,
        redactedUrl: String,
        destination: NativeDirectDestinationMode,
        headerPolicy: NativeDirectHeaderPolicy,
        resume: NativeDirectResumePlan,
    ): String = redactKnownSecrets(
        listOf(
            "Native direct download engine",
            "job=${request.durableJobId}",
            "capture=${request.captureId}",
            "lane=${request.lane.label}",
            "destination=${destination.label}",
            "url=$redactedUrl",
            "headers=${headerPolicy.summary}",
            "resume=${resume.summary}",
            "rawShell=false",
            "sidecar=${request.redactedSidecarJson.take(220)}",
        ).joinToString("\n"),
    )

    private fun safeFileName(fileName: String): String = fileName
        .replace(Regex("[\\r\\n\\t/\\\\]+"), " ")
        .trim()
        .ifBlank { "xdm-media.bin" }
        .take(120)

    private fun extractJsonValue(json: String, key: String): String {
        val pattern = Regex("""""" + Regex.escape(key) + """"\s*:\s*"((?:\\.|[^"])*)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\").orEmpty()
    }

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { pattern -> pattern.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private companion object {
        val secretPatterns = listOf(
            Regex("""Bearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=]\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(token|session|sid|sig|signature|auth|key)=((?!<redacted>)[^\s&#;]+)"""),
            Regex("secret-[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
