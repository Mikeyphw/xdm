package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import java.net.URI
import java.util.Locale

/**
 * Phase 20 execution/library coordinator.
 *
 * This is deliberately a planning layer: it converts the resolver's selected tracks into safe
 * queue specs, derives visible job states, and writes only redacted sidecar metadata. Raw cookies,
 * authorization values, and tokenized URLs are allowed only in the short-lived request handoff map
 * consumed by native/aria2/yt-dlp execution.
 */
enum class MediaExecutionStage(val label: String) {
    Probing("Probing"),
    Queued("Queued"),
    Downloading("Downloading"),
    Completed("Completed"),
    Failed("Failed"),
    Blocked("Blocked"),
}

enum class MediaExecutionLane(val label: String) {
    DirectNative("Direct native"),
    Aria2Segmented("aria2 segmented"),
    YtDlpAdaptive("yt-dlp adaptive"),
    LiveRecording("yt-dlp/FFmpeg live recording"),
    ProtectedBlocked("Protected diagnostic only"),
}

enum class AndroidMediaWorkKind(val label: String) {
    UserInitiatedDataTransfer("User-initiated data transfer"),
    WorkManagerForeground("WorkManager foreground fallback"),
    ForegroundServiceFallback("Foreground service fallback"),
    TermuxExternalJob("Termux external job"),
    BlockedDiagnostic("Blocked diagnostic"),
}

data class MediaBackgroundExecutionPolicy(
    val sdkInt: Int,
    val workKind: AndroidMediaWorkKind,
    val foregroundServiceType: String?,
    val reason: String,
) {
    val summary: String get() = listOfNotNull(
        workKind.label,
        "sdk=$sdkInt",
        foregroundServiceType?.let { "fgs=$it" },
        reason,
    ).joinToString(" • ")
}

data class MediaTempCookieFilePlan(
    val fileName: String,
    val netscapeHeader: String,
    val redactedCookieLines: Int,
    val deleteAfterTerminalState: Boolean,
    val verifierLabel: String,
    val redactedPreview: String,
)

data class Aria2TransientInputPlan(
    val inputFileName: String,
    val sessionFileName: String,
    val redactedOptions: Map<String, String>,
    val deleteAfterTerminalState: Boolean,
) {
    val redactedPreview: String get() = redactedOptions.entries.joinToString("\n") { (key, value) -> "$key=$value" }
}

data class MediaSecretLeakReport(
    val safe: Boolean,
    val scannedSurfaces: List<String>,
    val findingLabels: List<String>,
) {
    val summary: String get() = if (safe) {
        "No raw cookies, authorization headers, or token values detected across ${scannedSurfaces.size} media surfaces."
    } else {
        "Potential secret surfaces: ${findingLabels.joinToString()}"
    }
}

data class MediaExecutionEnginePlan(
    val lane: MediaExecutionLane,
    val backgroundPolicy: MediaBackgroundExecutionPolicy,
    val typedExecutor: String,
    val typedArguments: List<String>,
    val tempCookieFile: MediaTempCookieFilePlan?,
    val aria2Input: Aria2TransientInputPlan?,
    val cleanupActions: List<String>,
    val leakReport: MediaSecretLeakReport,
) {
    val safeSummary: String get() = listOf(
        "lane=${lane.label}",
        "executor=$typedExecutor",
        "policy=${backgroundPolicy.summary}",
        "cleanup=${cleanupActions.joinToString()}",
        "leaks=${leakReport.summary}",
    ).joinToString("; ")
}

data class MediaQueuedDownloadSpec(
    val captureId: String,
    val sourceUrl: String,
    val fileName: String,
    val requestedBackend: BackendType,
    val userLabel: String,
    val safeExplanation: String,
    val selectedTrackIds: Set<String>,
    val redactedSessionSummary: String,
    val requestHeaders: Map<String, String>,
    val isExpiringUrl: Boolean,
    val canUseAppQueue: Boolean,
    val requiresTermuxYtDlp: Boolean,
    val strategy: MediaDownloadStrategy,
    val intent: MediaDownloadIntent,
    val sidecar: OfflineMediaSidecarMetadata,
) {
    val safeQueuedJobSummary: String
        get() = listOf(
            "capture=$captureId",
            "backend=${requestedBackend.name}",
            "tracks=${selectedTrackIds.size}",
            "source=${sidecar.redactedSourceUrl}",
            "session=$redactedSessionSummary",
        ).joinToString("; ")
}

data class MediaExternalJobSnapshot(
    val id: String,
    val captureId: String?,
    val kindLabel: String,
    val statusLabel: String,
    val running: Boolean,
    val completed: Boolean,
    val failed: Boolean,
    val output: String,
    val message: String,
)

data class MediaExecutionJob(
    val captureId: String,
    val title: String,
    val stage: MediaExecutionStage,
    val engine: String,
    val detail: String,
    val downloadId: String? = null,
    val canResume: Boolean = false,
    val canRetry: Boolean = false,
)

data class OfflineMediaSidecarMetadata(
    val captureId: String,
    val downloadId: String?,
    val title: String,
    val fileName: String,
    val sourceHost: String,
    val pageHost: String?,
    val redactedSourceUrl: String,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    val kind: MediaSourceKind,
    val mimeType: String?,
    val selectedTrackIds: Set<String>,
    val completedAtEpochMs: Long? = null,
) {
    fun toRedactedJson(): String = buildString {
        append('{')
        appendJson("captureId", captureId); append(',')
        appendJson("downloadId", downloadId.orEmpty()); append(',')
        appendJson("title", title); append(',')
        appendJson("fileName", fileName); append(',')
        appendJson("sourceHost", sourceHost); append(',')
        appendJson("pageHost", pageHost.orEmpty()); append(',')
        appendJson("redactedSourceUrl", redactedSourceUrl); append(',')
        appendJson("durationMs", durationMs?.toString().orEmpty()); append(',')
        appendJson("thumbnailUrl", thumbnailUrl.orEmpty()); append(',')
        appendJson("kind", kind.name); append(',')
        appendJson("mimeType", mimeType.orEmpty()); append(',')
        appendJson("selectedTrackIds", selectedTrackIds.sorted().joinToString(",")); append(',')
        appendJson("completedAtEpochMs", completedAtEpochMs?.toString().orEmpty())
        append('}')
    }
}

data class OfflineMediaLibraryItem(
    val captureId: String,
    val downloadId: String?,
    val title: String,
    val fileName: String,
    val sourceHost: String,
    val pageHost: String?,
    val durationLabel: String,
    val thumbnailUrl: String?,
    val state: DownloadState?,
    val detail: String,
    val playbackUrl: String?,
    val isCompleted: Boolean,
    val canPlayDirect: Boolean,
    val canResume: Boolean,
    val canRetry: Boolean,
    val sidecar: OfflineMediaSidecarMetadata,
) {
    fun toPlaybackCandidate(): MediaPlaybackCandidate? = playbackUrl?.takeIf { canPlayDirect }?.let { url ->
        MediaPlaybackCandidate(
            captureId = captureId,
            title = title,
            playbackUrl = url,
            isAdaptive = false,
            needsExternalResolver = false,
            subtitleCount = sidecar.selectedTrackIds.count { it.contains(":sub", ignoreCase = true) || it.contains("subtitle", ignoreCase = true) },
            audioTrackCount = sidecar.selectedTrackIds.count { it.contains(":audio", ignoreCase = true) },
        )
    }
}

class MediaExecutionLibraryPlanner(
    private val resolver: MediaDownloadPlanner = MediaDownloadPlanner(),
) {
    fun queueSpec(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = capture.selectedVariantId),
        destinationUri: String,
        sessionHeaders: List<MediaSessionHeader> = emptyList(),
    ): MediaQueuedDownloadSpec {
        val plan = resolver.plan(capture, variants, selection = selection, sessionHeaders = sessionHeaders)
        val backend = when (plan.strategy) {
            MediaDownloadStrategy.Native -> BackendType.Native
            MediaDownloadStrategy.Aria2 -> BackendType.Aria2
            MediaDownloadStrategy.YtDlp,
            MediaDownloadStrategy.FfmpegLive,
            MediaDownloadStrategy.UnsupportedProtected -> BackendType.Automatic
        }
        val selectedTrackIds = plan.trackSelection.selectedIds()
        val sidecar = sidecar(capture, null, selectedTrackIds, null)
        val blocked = plan.strategy == MediaDownloadStrategy.UnsupportedProtected
        val needsTermux = plan.strategy == MediaDownloadStrategy.YtDlp || plan.strategy == MediaDownloadStrategy.FfmpegLive
        return MediaQueuedDownloadSpec(
            captureId = capture.id,
            sourceUrl = plan.primaryUrl,
            fileName = safeMediaFileName(capture, plan),
            requestedBackend = backend,
            userLabel = "Media: ${capture.title.ifBlank { capture.fileName }}",
            safeExplanation = listOf(
                plan.explanation,
                failureReason(capture, plan, null),
                "destination=${destinationUri.take(160)}",
                "sidecar=${sidecar.toRedactedJson()}",
            ).filter(String::isNotBlank).joinToString(" ").take(900),
            selectedTrackIds = selectedTrackIds,
            redactedSessionSummary = plan.sessionHandoff.redactedSummary,
            requestHeaders = plan.sessionHandoff.requestHeaders(),
            isExpiringUrl = plan.needsCookieContext || capture.needsManifestRefresh(System.currentTimeMillis()),
            canUseAppQueue = plan.canQueueDirectly && !needsTermux && !blocked,
            requiresTermuxYtDlp = needsTermux && !blocked,
            strategy = plan.strategy,
            intent = plan.intent,
            sidecar = sidecar,
        )
    }

    fun enginePlan(spec: MediaQueuedDownloadSpec, androidSdkInt: Int, userInitiated: Boolean = true): MediaExecutionEnginePlan {
        val lane = laneFor(spec)
        val policy = backgroundPolicyFor(lane, androidSdkInt, userInitiated)
        val tempCookie = tempCookieFilePlan(spec)
        val aria2 = aria2TransientInputPlan(spec, lane)
        val typedArgs = typedExecutorArguments(spec, lane, tempCookie, aria2)
        val surfaces = mutableListOf(
            spec.safeQueuedJobSummary,
            spec.safeExplanation,
            spec.sidecar.toRedactedJson(),
            tempCookie?.redactedPreview.orEmpty(),
            aria2?.redactedPreview.orEmpty(),
            typedArgs.joinToString(" "),
        )
        val leakReport = secretLeakReport(surfaces)
        val cleanup = mutableListOf("forget process-local media handoff")
        if (tempCookie != null) cleanup += "delete temporary Netscape cookie file"
        if (aria2 != null) cleanup += "delete aria2 transient input/session files"
        cleanup += "verify no cookie/header/token text entered persistent metadata"
        return MediaExecutionEnginePlan(
            lane = lane,
            backgroundPolicy = policy,
            typedExecutor = when (lane) {
                MediaExecutionLane.DirectNative -> "native-request"
                MediaExecutionLane.Aria2Segmented -> "aria2c"
                MediaExecutionLane.YtDlpAdaptive, MediaExecutionLane.LiveRecording -> "yt-dlp"
                MediaExecutionLane.ProtectedBlocked -> "diagnostics-only"
            },
            typedArguments = typedArgs,
            tempCookieFile = tempCookie,
            aria2Input = aria2,
            cleanupActions = cleanup,
            leakReport = leakReport,
        )
    }

    fun executionJobs(
        captures: List<MediaCaptureRecord>,
        downloads: List<Download>,
        variants: List<MediaVariant>,
        externalJobs: List<MediaExternalJobSnapshot> = emptyList(),
    ): List<MediaExecutionJob> = captures.map { capture ->
        val download = capture.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }
        val external = externalJobs.firstOrNull { it.captureId == capture.id || it.id == capture.downloadId }
        val plan = resolver.plan(capture, variants.filter { it.captureId == capture.id })
        when {
            download != null -> executionJobForDownload(capture, download, plan)
            external != null -> executionJobForExternal(capture, external)
            plan.protectedDiagnostic.protected -> MediaExecutionJob(capture.id, capture.title.ifBlank { capture.fileName }, MediaExecutionStage.Blocked, "resolver", failureReason(capture, plan, null))
            capture.resolutionStatus.name == "Unresolved" -> MediaExecutionJob(capture.id, capture.title.ifBlank { capture.fileName }, MediaExecutionStage.Probing, "resolver", "Metadata probe is ready before queueing.")
            else -> MediaExecutionJob(capture.id, capture.title.ifBlank { capture.fileName }, MediaExecutionStage.Queued, "resolver", "Ready to queue selected media tracks.")
        }
    }

    fun offlineLibraryItems(captures: List<MediaCaptureRecord>, downloads: List<Download>, variants: List<MediaVariant>): List<OfflineMediaLibraryItem> = captures.mapNotNull { capture ->
        val download = capture.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }
        val selectedIds = selectedTrackIds(capture, variants.filter { it.captureId == capture.id })
        val sidecar = sidecar(capture, download?.id, selectedIds, download?.takeIf { it.state == DownloadState.Completed }?.updatedAtEpochMs)
        val playback = download?.takeIf { it.state == DownloadState.Completed }?.let { completedPlaybackUrl(it) }
        OfflineMediaLibraryItem(
            captureId = capture.id,
            downloadId = download?.id ?: capture.downloadId,
            title = capture.title.ifBlank { capture.fileName },
            fileName = download?.fileName ?: capture.fileName,
            sourceHost = sidecar.sourceHost,
            pageHost = sidecar.pageHost,
            durationLabel = capture.durationMs?.let(::formatDurationForUi) ?: "duration unknown",
            thumbnailUrl = capture.thumbnailUrl,
            state = download?.state,
            detail = libraryDetail(capture, download),
            playbackUrl = playback,
            isCompleted = download?.state == DownloadState.Completed,
            canPlayDirect = playback != null && !capture.isPlaylist,
            canResume = download?.state in resumableStates,
            canRetry = download?.state in retryableStates,
            sidecar = sidecar,
        )
    }.sortedWith(compareByDescending<OfflineMediaLibraryItem> { it.isCompleted }.thenBy { it.title.lowercase(Locale.US) })

    private fun laneFor(spec: MediaQueuedDownloadSpec): MediaExecutionLane = when {
        spec.strategy == MediaDownloadStrategy.UnsupportedProtected || !spec.canUseAppQueue && !spec.requiresTermuxYtDlp -> MediaExecutionLane.ProtectedBlocked
        spec.strategy == MediaDownloadStrategy.FfmpegLive -> MediaExecutionLane.LiveRecording
        spec.requiresTermuxYtDlp -> MediaExecutionLane.YtDlpAdaptive
        spec.requestedBackend == BackendType.Aria2 -> MediaExecutionLane.Aria2Segmented
        else -> MediaExecutionLane.DirectNative
    }

    private fun backgroundPolicyFor(lane: MediaExecutionLane, sdkInt: Int, userInitiated: Boolean): MediaBackgroundExecutionPolicy = when (lane) {
        MediaExecutionLane.ProtectedBlocked -> MediaBackgroundExecutionPolicy(sdkInt, AndroidMediaWorkKind.BlockedDiagnostic, null, "Protected or unsupported media never enters background execution.")
        MediaExecutionLane.YtDlpAdaptive,
        MediaExecutionLane.LiveRecording -> MediaBackgroundExecutionPolicy(sdkInt, AndroidMediaWorkKind.TermuxExternalJob, null, "yt-dlp/FFmpeg execution stays in the typed Termux media pipeline.")
        MediaExecutionLane.DirectNative,
        MediaExecutionLane.Aria2Segmented -> when {
            sdkInt >= 34 && userInitiated -> MediaBackgroundExecutionPolicy(sdkInt, AndroidMediaWorkKind.UserInitiatedDataTransfer, "dataSync", "Large visible download is UIDT-ready on Android 14+.")
            sdkInt >= 23 -> MediaBackgroundExecutionPolicy(sdkInt, AndroidMediaWorkKind.WorkManagerForeground, "dataSync", "Foreground WorkManager remains the fallback for visible transfer progress.")
            else -> MediaBackgroundExecutionPolicy(sdkInt, AndroidMediaWorkKind.ForegroundServiceFallback, "dataSync", "Legacy devices use an explicit foreground service fallback.")
        }
    }

    private fun tempCookieFilePlan(spec: MediaQueuedDownloadSpec): MediaTempCookieFilePlan? {
        val cookieHeader = spec.requestHeaders.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value
        val cookieCount = cookieHeader
            ?.split(';')
            ?.map(String::trim)
            ?.count { it.contains('=') }
            ?: if (spec.redactedSessionSummary.contains("cookies=available", ignoreCase = true)) 1 else 0
        if (cookieCount <= 0) return null
        return MediaTempCookieFilePlan(
            fileName = "xdm-media-${spec.captureId.take(12)}.cookies.txt",
            netscapeHeader = "# Netscape HTTP Cookie File",
            redactedCookieLines = cookieCount,
            deleteAfterTerminalState = true,
            verifierLabel = "delete-after-terminal-and-before-log-copy",
            redactedPreview = "# Netscape HTTP Cookie File\n# $cookieCount redacted cookie line(s) for ${spec.sidecar.sourceHost}\n# deleted after terminal state",
        )
    }

    private fun aria2TransientInputPlan(spec: MediaQueuedDownloadSpec, lane: MediaExecutionLane): Aria2TransientInputPlan? {
        if (lane != MediaExecutionLane.Aria2Segmented) return null
        val options = linkedMapOf(
            "continue" to "true",
            "allow-overwrite" to "false",
            "auto-file-renaming" to "true",
            "save-session" to "xdm-media-${spec.captureId.take(12)}.aria2.session",
            "out" to spec.fileName,
        )
        spec.requestHeaders.keys.sorted().forEach { headerName ->
            options["header:${headerName}"] = when {
                headerName.equals("Cookie", ignoreCase = true) -> "<redacted-cookie>"
                headerName.equals("Authorization", ignoreCase = true) -> "<redacted-auth>"
                else -> "<redacted-or-nonsecret>"
            }
        }
        return Aria2TransientInputPlan(
            inputFileName = "xdm-media-${spec.captureId.take(12)}.aria2.input",
            sessionFileName = "xdm-media-${spec.captureId.take(12)}.aria2.session",
            redactedOptions = options,
            deleteAfterTerminalState = true,
        )
    }

    private fun typedExecutorArguments(
        spec: MediaQueuedDownloadSpec,
        lane: MediaExecutionLane,
        tempCookie: MediaTempCookieFilePlan?,
        aria2: Aria2TransientInputPlan?,
    ): List<String> {
        val args = mutableListOf<String>()
        when (lane) {
            MediaExecutionLane.DirectNative -> {
                args += listOf("--url", spec.sidecar.redactedSourceUrl, "--output", spec.fileName)
            }
            MediaExecutionLane.Aria2Segmented -> {
                args += listOf("--input-file", aria2?.inputFileName ?: "<transient-aria2-input>")
                args += listOf("--save-session", aria2?.sessionFileName ?: "<transient-aria2-session>")
            }
            MediaExecutionLane.YtDlpAdaptive,
            MediaExecutionLane.LiveRecording -> {
                args += listOf("--no-progress", "--newline")
                tempCookie?.let { args += listOf("--cookies", it.fileName) }
                spec.selectedTrackIds.takeIf { it.isNotEmpty() }?.let { ids -> args += listOf("--format", ids.sorted().joinToString("+")) }
                if (lane == MediaExecutionLane.LiveRecording) args += "--live-from-start"
                args += listOf("--output", spec.fileName, spec.sidecar.redactedSourceUrl)
            }
            MediaExecutionLane.ProtectedBlocked -> {
                args += listOf("--diagnostics-only", spec.captureId)
            }
        }
        return args
    }

    private fun secretLeakReport(surfaces: List<String>): MediaSecretLeakReport {
        val findings = mutableListOf<String>()
        val patterns = listOf(
            Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE) to "authorization bearer",
            Regex("Cookie\\s*:", RegexOption.IGNORE_CASE) to "raw cookie header",
            Regex("(?:token|session|sid|sig|signature|auth|key)=((?!<redacted>)[^\\s&#;]+)", RegexOption.IGNORE_CASE) to "unredacted token parameter",
            Regex("secret-[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE) to "test secret literal",
        )
        surfaces.forEachIndexed { index, surface ->
            patterns.forEach { (pattern, label) ->
                if (pattern.containsMatchIn(surface)) findings += "surface-$index:$label"
            }
        }
        return MediaSecretLeakReport(
            safe = findings.isEmpty(),
            scannedSurfaces = surfaces.mapIndexed { index, _ -> "surface-$index" },
            findingLabels = findings.distinct(),
        )
    }

    fun failureReason(capture: MediaCaptureRecord, plan: MediaDownloadPlan, download: Download?): String {
        val errorMessage = download?.errorMessage.orEmpty()
        return when {
            plan.protectedDiagnostic.protected -> "Unsupported DRM/protected media. Diagnostics only; no bypass or queue action."
            plan.strategy == MediaDownloadStrategy.FfmpegLive -> "Live stream requires an explicit yt-dlp/FFmpeg recording job instead of a normal finite download."
            capture.needsManifestRefresh(System.currentTimeMillis()) -> "Manifest/session may be expired; refresh metadata before retrying."
            errorMessage.contains("aria2", ignoreCase = true) -> "aria2 failure: ${errorMessage.take(180)}"
            download?.state == DownloadState.Failed -> errorMessage.take(180).ifBlank { "Download failed; retry will requeue with the saved media plan." }
            plan.strategy == MediaDownloadStrategy.YtDlp -> "yt-dlp extractor is required. Check Termux tool probe if the job fails."
            else -> ""
        }
    }

    private fun executionJobForDownload(capture: MediaCaptureRecord, download: Download, plan: MediaDownloadPlan): MediaExecutionJob {
        val stage = when (download.state) {
            DownloadState.Created -> MediaExecutionStage.Probing
            DownloadState.Queued, DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower -> MediaExecutionStage.Queued
            DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing -> MediaExecutionStage.Downloading
            DownloadState.Completed -> MediaExecutionStage.Completed
            DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired -> MediaExecutionStage.Failed
        }
        return MediaExecutionJob(
            captureId = capture.id,
            title = capture.title.ifBlank { download.fileName },
            stage = stage,
            engine = download.backend.name,
            detail = failureReason(capture, plan, download).ifBlank { download.backendSelectionExplanation.ifBlank { "Queued through XDM media execution." } },
            downloadId = download.id,
            canResume = download.state in resumableStates,
            canRetry = download.state in retryableStates,
        )
    }

    private fun executionJobForExternal(capture: MediaCaptureRecord, external: MediaExternalJobSnapshot): MediaExecutionJob {
        val stage = when {
            external.kindLabel.contains("metadata", ignoreCase = true) && external.running -> MediaExecutionStage.Probing
            external.running -> MediaExecutionStage.Downloading
            external.completed -> MediaExecutionStage.Completed
            external.failed -> MediaExecutionStage.Failed
            else -> MediaExecutionStage.Queued
        }
        return MediaExecutionJob(
            captureId = capture.id,
            title = capture.title.ifBlank { capture.fileName },
            stage = stage,
            engine = "Termux ${external.kindLabel}",
            detail = external.message.ifBlank { external.output.ifBlank { external.statusLabel } },
            downloadId = external.id,
            canRetry = external.failed,
        )
    }

    private fun sidecar(capture: MediaCaptureRecord, downloadId: String?, selectedTrackIds: Set<String>, completedAt: Long?): OfflineMediaSidecarMetadata = OfflineMediaSidecarMetadata(
        captureId = capture.id,
        downloadId = downloadId,
        title = capture.title.ifBlank { capture.fileName },
        fileName = capture.fileName,
        sourceHost = hostFor(capture.sourceUrl),
        pageHost = capture.pageUrl?.let(::hostFor),
        redactedSourceUrl = redactMediaUrl(capture.selectedVariantUrl ?: capture.sourceUrl),
        durationMs = capture.durationMs,
        thumbnailUrl = capture.thumbnailUrl,
        kind = capture.kind,
        mimeType = capture.mimeType,
        selectedTrackIds = selectedTrackIds,
        completedAtEpochMs = completedAt,
    )

    private fun selectedTrackIds(capture: MediaCaptureRecord, variants: List<MediaVariant>): Set<String> = buildSet {
        capture.selectedVariantId?.let(::add)
        variants.firstOrNull { it.id == capture.selectedVariantId }?.id?.let(::add)
    }

    private fun safeMediaFileName(capture: MediaCaptureRecord, plan: MediaDownloadPlan): String {
        val raw = capture.fileName.ifBlank { capture.title.ifBlank { "xdm-media" } }
        val hasExtension = raw.substringAfterLast('/', raw).substringAfterLast('.', "").length in 2..5
        val extension = when {
            hasExtension -> ""
            capture.mimeType?.contains("audio", ignoreCase = true) == true -> ".m4a"
            plan.strategy == MediaDownloadStrategy.Native || plan.strategy == MediaDownloadStrategy.Aria2 -> ".mp4"
            else -> ".media"
        }
        return (raw + extension).replace(Regex("[\\r\\n\\t]"), " ").take(120)
    }

    private fun libraryDetail(capture: MediaCaptureRecord, download: Download?): String = when {
        download == null && capture.downloadId != null -> "External media job ${capture.downloadId}; check Termux media pipeline for output."
        download == null -> "Captured from ${hostFor(capture.pageUrl ?: capture.sourceUrl)}; not queued yet."
        download.state == DownloadState.Completed -> "Completed in ${download.destinationUri}; local sidecar metadata is redacted."
        download.state == DownloadState.Failed -> download.errorMessage?.take(180) ?: "Failed; retry from the media library."
        else -> "${download.state.name} through ${download.backend.name}."
    }

    private fun completedPlaybackUrl(download: Download): String? = if (download.destinationUri.startsWith("content://") || download.destinationUri.startsWith("file://")) {
        download.destinationUri.trimEnd('/') + "/" + download.fileName
    } else null

    companion object {
        private val resumableStates = setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)
        private val retryableStates = setOf(DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)
    }
}


private fun formatDurationForUi(durationMs: Long): String {
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining) else "%d:%02d".format(minutes, remaining)
}

private fun hostFor(url: String): String = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }
    .getOrDefault("")
    .ifBlank { "unknown host" }

private fun redactMediaUrl(url: String): String = url
    .replace(Regex("""([?&](?:token|auth|session|sid|sig|signature|key|cookie|expires)=)[^&#]+""", RegexOption.IGNORE_CASE), "$1<redacted>")
    .take(220)

private fun StringBuilder.appendJson(name: String, value: String) {
    append('"').append(name).append("\":")
    append('"').append(value.jsonEscaped()).append('"')
}

private fun String.jsonEscaped(): String = buildString(length) {
    this@jsonEscaped.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}
