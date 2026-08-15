package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaOutputOwnerKind
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaOutputState
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

enum class MediaExecutionFailureKind(val label: String) {
    None("No failure"),
    Protected("Protected media"),
    LiveRequiresExternalJob("Live recording requires external job"),
    MetadataRefreshRequired("Metadata refresh required"),
    AppDownloadFailed("App download failed"),
    Aria2DownloadFailed("aria2 download failed"),
    YtDlpRequired("yt-dlp resolver required"),
}

data class MediaExecutionFailure(
    val kind: MediaExecutionFailureKind,
    val message: String,
    val retryable: Boolean,
)

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
    val destinationUri: String,
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
    val ytDlpFormatSelector: String?,
    val sidecar: OfflineMediaSidecarMetadata,
) {
    val safeQueuedJobSummary: String
        get() = listOf(
            "capture=$captureId",
            "backend=${requestedBackend.name}",
            "destination=${destinationUri.take(96)}",
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
    val metadataOnly: Boolean = false,
    val attemptGeneration: Long = 1L,
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
    val outputId: String,
    val captureId: String,
    val ownerKind: MediaOutputOwnerKind,
    val ownerId: String,
    val attemptGeneration: Long,
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
        variantSessionHeaders: Map<String, List<MediaSessionHeader>> = emptyMap(),
    ): MediaQueuedDownloadSpec {
        val plan = resolver.plan(
            capture = capture,
            variants = variants,
            selection = selection,
            sessionHeaders = sessionHeaders,
            variantSessionHeaders = variantSessionHeaders,
        )
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
            destinationUri = destinationUri,
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
            ytDlpFormatSelector = plan.ytDlpFormatSelector,
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
        outputs: List<MediaOutputRecord> = emptyList(),
    ): List<MediaExecutionJob> {
        if (outputs.isEmpty()) {
            return captures.map { capture ->
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
        }
        val captureById = captures.associateBy(MediaCaptureRecord::id)
        return outputs.asSequence()
            .filterNot { it.state == MediaOutputState.Hidden }
            .mapNotNull { output ->
                val capture = captureById[output.captureId] ?: return@mapNotNull null
                val plan = resolver.plan(capture, variants.filter { it.captureId == capture.id })
                when (output.ownerKind) {
                    MediaOutputOwnerKind.AppDownload -> {
                        val download = output.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }
                        if (download != null && download.attemptGeneration == output.attemptGeneration) {
                            executionJobForDownload(capture, download, plan)
                        } else {
                            historicalAppExecutionJob(capture, output)
                        }
                    }
                    MediaOutputOwnerKind.TermuxJob -> externalJobs.firstOrNull { it.id == output.ownerId }
                        ?.let { executionJobForExternal(capture, it) }
                        ?: MediaExecutionJob(
                            captureId = capture.id,
                            title = capture.title.ifBlank { output.fileName },
                            stage = when (output.state) {
                                MediaOutputState.Completed -> MediaExecutionStage.Completed
                                MediaOutputState.Failed, MediaOutputState.Cancelled, MediaOutputState.RecoveryRequired -> MediaExecutionStage.Failed
                                MediaOutputState.Active -> MediaExecutionStage.Downloading
                                MediaOutputState.Queued -> MediaExecutionStage.Queued
                                MediaOutputState.Hidden -> return@mapNotNull null
                            },
                            engine = "Termux external job",
                            detail = "Durable external output generation ${output.attemptGeneration} is recorded; runtime status will reconnect when available.",
                            downloadId = output.ownerId,
                            canRetry = output.state in setOf(MediaOutputState.Failed, MediaOutputState.Cancelled, MediaOutputState.RecoveryRequired),
                        )
                }
            }
            .toList()
    }

    fun offlineLibraryItems(
        captures: List<MediaCaptureRecord>,
        downloads: List<Download>,
        variants: List<MediaVariant>,
        outputs: List<MediaOutputRecord> = emptyList(),
        externalJobs: List<MediaExternalJobSnapshot> = emptyList(),
        allowLegacyFallback: Boolean = true,
    ): List<OfflineMediaLibraryItem> {
        val captureById = captures.associateBy(MediaCaptureRecord::id)
        val rows = if (outputs.isEmpty() && allowLegacyFallback) {
            // Compatibility path for pre-v20 in-memory fixtures only. Production disables this
            // fallback so deleting the final output generation cannot resurrect capture.downloadId.
            captures.mapNotNull { capture ->
                val download = capture.downloadId?.let { id -> downloads.firstOrNull { it.id == id } } ?: return@mapNotNull null
                legacyLibraryItem(capture, download, variants.filter { it.captureId == capture.id })
            }
        } else {
            outputs.asSequence()
                .filterNot { it.state == MediaOutputState.Hidden }
                .mapNotNull { output ->
                    val capture = captureById[output.captureId] ?: return@mapNotNull null
                    when (output.ownerKind) {
                        MediaOutputOwnerKind.AppDownload -> {
                            val download = output.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }
                            if (download != null && download.attemptGeneration == output.attemptGeneration) {
                                outputLibraryItem(capture, output, download, variants.filter { it.captureId == capture.id })
                            } else {
                                historicalAppOutputLibraryItem(capture, output)
                            }
                        }
                        MediaOutputOwnerKind.TermuxJob -> externalOutputLibraryItem(
                            capture = capture,
                            output = output,
                            external = externalJobs.firstOrNull { it.id == output.ownerId },
                        )
                    }
                }
                .toList()
        }
        return rows.sortedWith(
            compareByDescending<OfflineMediaLibraryItem> { it.isCompleted }
                .thenByDescending { it.sidecar.completedAtEpochMs ?: 0L }
                .thenByDescending { it.attemptGeneration }
                .thenBy { it.title.lowercase(Locale.US) },
        )
    }

    private fun legacyLibraryItem(
        capture: MediaCaptureRecord,
        download: Download,
        captureVariants: List<MediaVariant>,
    ): OfflineMediaLibraryItem? {
        val completed = download.state == DownloadState.Completed
        if (!completed && download.state !in retryableStates) return null
        val playback = download.takeIf { completed }?.let(::completedPlaybackUrl)
        val selectedIds = selectedTrackIds(capture, captureVariants)
        val sidecar = sidecar(capture, download.id, selectedIds, download.updatedAtEpochMs.takeIf { completed })
        return OfflineMediaLibraryItem(
            outputId = "legacy:${capture.id}:${download.id}:${download.attemptGeneration}",
            captureId = capture.id,
            ownerKind = MediaOutputOwnerKind.AppDownload,
            ownerId = download.id,
            attemptGeneration = download.attemptGeneration,
            downloadId = download.id,
            title = capture.title.ifBlank { capture.fileName },
            fileName = download.fileName,
            sourceHost = sidecar.sourceHost,
            pageHost = sidecar.pageHost,
            durationLabel = capture.durationMs?.let(::formatDurationForUi) ?: "duration unknown",
            thumbnailUrl = capture.thumbnailUrl,
            state = download.state,
            detail = libraryDetail(capture, download),
            playbackUrl = playback,
            isCompleted = completed,
            canPlayDirect = completed && playback != null,
            canResume = download.state in resumableStates,
            canRetry = download.state in retryableStates,
            sidecar = sidecar,
        )
    }

    private fun outputLibraryItem(
        capture: MediaCaptureRecord,
        output: MediaOutputRecord,
        download: Download,
        captureVariants: List<MediaVariant>,
    ): OfflineMediaLibraryItem? {
        // App-owned execution state belongs to the Download row. media_outputs is the durable
        // one-to-many capture/output identity and may intentionally retain the enqueue-time state
        // snapshot while transfer state advances through compare-and-swap paths outside this DAO.
        val completed = download.state == DownloadState.Completed
        val retryable = download.state in retryableStates
        if (!completed && !retryable) return null
        val playback = download.takeIf { completed }?.let(::completedPlaybackUrl)
        val selectedIds = output.selectedTrackIds.ifEmpty { selectedTrackIds(capture, captureVariants) }
        val sidecar = sidecar(capture, download.id, selectedIds, output.updatedAtEpochMs.takeIf { completed })
        return OfflineMediaLibraryItem(
            outputId = output.id,
            captureId = capture.id,
            ownerKind = output.ownerKind,
            ownerId = output.ownerId,
            attemptGeneration = output.attemptGeneration,
            downloadId = download.id,
            title = capture.title.ifBlank { capture.fileName },
            fileName = output.fileName,
            sourceHost = sidecar.sourceHost,
            pageHost = sidecar.pageHost,
            durationLabel = capture.durationMs?.let(::formatDurationForUi) ?: "duration unknown",
            thumbnailUrl = capture.thumbnailUrl,
            state = download.state,
            detail = "Generation ${output.attemptGeneration} • ${libraryDetail(capture, download)}",
            playbackUrl = playback,
            isCompleted = completed,
            canPlayDirect = completed && playback != null,
            canResume = download.state in resumableStates,
            canRetry = retryable,
            sidecar = sidecar,
        )
    }

    private fun historicalAppExecutionJob(
        capture: MediaCaptureRecord,
        output: MediaOutputRecord,
    ): MediaExecutionJob? {
        val stage = when (output.state) {
            MediaOutputState.Completed -> MediaExecutionStage.Completed
            MediaOutputState.Failed, MediaOutputState.Cancelled, MediaOutputState.RecoveryRequired -> MediaExecutionStage.Failed
            MediaOutputState.Queued, MediaOutputState.Active, MediaOutputState.Hidden -> return null
        }
        return MediaExecutionJob(
            captureId = capture.id,
            title = capture.title.ifBlank { output.fileName },
            stage = stage,
            engine = "App download history",
            detail = "Historical app output generation ${output.attemptGeneration}; the owning Download has advanced or left active history.",
            downloadId = output.downloadId,
            canRetry = false,
        )
    }

    private fun historicalAppOutputLibraryItem(
        capture: MediaCaptureRecord,
        output: MediaOutputRecord,
    ): OfflineMediaLibraryItem? {
        val state = when (output.state) {
            MediaOutputState.Completed -> DownloadState.Completed
            MediaOutputState.Failed -> DownloadState.Failed
            MediaOutputState.Cancelled -> DownloadState.Cancelled
            MediaOutputState.RecoveryRequired -> DownloadState.RecoveryRequired
            MediaOutputState.Queued, MediaOutputState.Active, MediaOutputState.Hidden -> return null
        }
        val completed = state == DownloadState.Completed
        val sidecar = sidecar(capture, output.downloadId, output.selectedTrackIds, output.updatedAtEpochMs.takeIf { completed })
        return OfflineMediaLibraryItem(
            outputId = output.id,
            captureId = capture.id,
            ownerKind = MediaOutputOwnerKind.AppDownload,
            ownerId = output.ownerId,
            attemptGeneration = output.attemptGeneration,
            downloadId = output.downloadId,
            title = capture.title.ifBlank { output.fileName },
            fileName = output.fileName,
            sourceHost = sidecar.sourceHost,
            pageHost = sidecar.pageHost,
            durationLabel = capture.durationMs?.let(::formatDurationForUi) ?: "duration unknown",
            thumbnailUrl = capture.thumbnailUrl,
            state = state,
            detail = "Historical app output generation ${output.attemptGeneration}; playback is intentionally unavailable without a current validated Download artifact.",
            playbackUrl = null,
            isCompleted = completed,
            canPlayDirect = false,
            canResume = false,
            canRetry = false,
            sidecar = sidecar,
        )
    }

    private fun externalOutputLibraryItem(
        capture: MediaCaptureRecord,
        output: MediaOutputRecord,
        external: MediaExternalJobSnapshot?,
    ): OfflineMediaLibraryItem? {
        val completed = output.state == MediaOutputState.Completed || external?.completed == true
        val retryable = output.state in setOf(MediaOutputState.Failed, MediaOutputState.Cancelled, MediaOutputState.RecoveryRequired) || external?.failed == true
        if (!completed && !retryable) return null
        val playback = listOfNotNull(output.completedArtifactUri, external?.output)
            .firstOrNull { it.startsWith("content://") || it.startsWith("file://") }
        val sidecar = sidecar(capture, null, output.selectedTrackIds, output.updatedAtEpochMs.takeIf { completed })
        return OfflineMediaLibraryItem(
            outputId = output.id,
            captureId = capture.id,
            ownerKind = MediaOutputOwnerKind.TermuxJob,
            ownerId = output.ownerId,
            attemptGeneration = output.attemptGeneration,
            downloadId = null,
            title = capture.title.ifBlank { output.fileName },
            fileName = output.fileName,
            sourceHost = sidecar.sourceHost,
            pageHost = sidecar.pageHost,
            durationLabel = capture.durationMs?.let(::formatDurationForUi) ?: "duration unknown",
            thumbnailUrl = capture.thumbnailUrl,
            state = null,
            detail = external?.message?.ifBlank { null }
                ?: "External generation ${output.attemptGeneration} • ${output.state.name}",
            playbackUrl = playback,
            isCompleted = completed,
            canPlayDirect = completed && playback != null,
            canResume = false,
            canRetry = retryable,
            sidecar = sidecar,
        )
    }

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
                spec.ytDlpFormatSelector?.takeIf(String::isNotBlank)?.let { selector -> args += listOf("--format", selector) }
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
            Regex("Bearer\\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})", RegexOption.IGNORE_CASE) to "authorization bearer",
            Regex("Cookie\\s*[:=](?!\\s*<redacted(?:-[A-Za-z]+)?>)\\s*[^\\n;]+", RegexOption.IGNORE_CASE) to "raw cookie header",
            Regex("(?<![-A-Za-z])(?:token|session|sid|sig|signature|auth|key)=((?!<redacted>|referer=|none\\b|available\\b|redacted\\b)[^\\s&#;]+)", RegexOption.IGNORE_CASE) to "unredacted token parameter",
            Regex("\\b(?:super-)?secret-(?!(?:safe|bearing|free)\\b)[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE) to "test secret literal",
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

    fun classifyFailure(capture: MediaCaptureRecord, plan: MediaDownloadPlan, download: Download?): MediaExecutionFailure = when {
        plan.protectedDiagnostic.protected -> MediaExecutionFailure(
            MediaExecutionFailureKind.Protected,
            "Unsupported DRM/protected media. Diagnostics only; no bypass or queue action.",
            retryable = false,
        )
        plan.strategy == MediaDownloadStrategy.FfmpegLive -> MediaExecutionFailure(
            MediaExecutionFailureKind.LiveRequiresExternalJob,
            "Live stream requires an explicit yt-dlp/FFmpeg recording job instead of a normal finite download.",
            retryable = false,
        )
        capture.needsManifestRefresh(System.currentTimeMillis()) -> MediaExecutionFailure(
            MediaExecutionFailureKind.MetadataRefreshRequired,
            "Manifest/session may be expired; refresh metadata before retrying.",
            retryable = true,
        )
        download?.state == DownloadState.Failed && download.backend == BackendType.Aria2 -> MediaExecutionFailure(
            MediaExecutionFailureKind.Aria2DownloadFailed,
            download.errorMessage?.take(180).orEmpty().ifBlank { "aria2 transfer failed; retry uses the saved media request plan." },
            retryable = true,
        )
        download?.state == DownloadState.Failed -> MediaExecutionFailure(
            MediaExecutionFailureKind.AppDownloadFailed,
            download.errorMessage?.take(180).orEmpty().ifBlank { "Download failed; retry will requeue with the saved media plan." },
            retryable = true,
        )
        plan.strategy == MediaDownloadStrategy.YtDlp -> MediaExecutionFailure(
            MediaExecutionFailureKind.YtDlpRequired,
            "yt-dlp extractor is required. Check the verified Termux tool probe before launch.",
            retryable = true,
        )
        else -> MediaExecutionFailure(MediaExecutionFailureKind.None, "", retryable = false)
    }

    fun failureReason(capture: MediaCaptureRecord, plan: MediaDownloadPlan, download: Download?): String =
        classifyFailure(capture, plan, download).message

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
            external.metadataOnly && external.running -> MediaExecutionStage.Probing
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
        download.state == DownloadState.Completed -> {
            val artifact = download.completedArtifactUri
                ?.takeIf { download.completedArtifactGeneration == download.attemptGeneration }
                ?.let(::mediaLocationLabel)
                ?: "committed artifact unavailable"
            "Completed artifact: $artifact; local sidecar metadata is redacted."
        }
        download.state == DownloadState.Failed -> download.errorMessage?.take(180) ?: "Failed; retry from the media library."
        else -> "${download.state.name} through ${download.backend.name}."
    }

    private fun completedPlaybackUrl(download: Download): String? {
        if (download.completedArtifactGeneration != download.attemptGeneration) return null
        return download.completedArtifactUri
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
    }

    private fun mediaLocationLabel(uri: String): String = when {
        uri.startsWith("content://") -> "Android document"
        uri.startsWith("file://") -> "XDM-managed file"
        else -> "committed artifact"
    }

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
