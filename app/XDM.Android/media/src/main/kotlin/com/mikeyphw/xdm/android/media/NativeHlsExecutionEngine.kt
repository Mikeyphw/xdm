package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaNativeCapability
import com.mikeyphw.xdm.android.model.MediaProtectionKind
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Parity03 native HLS execution contract.
 *
 * The previous media stack could identify HLS but routed normal adaptive playlists to yt-dlp.
 * This engine defines the native lane boundary: negotiate support before admission, expand a
 * supported VOD playlist into durable parts, preserve exact execution URLs/headers only in the
 * transient request context, and drive state/progress/finalization so a playlist, one segment,
 * HTML error, or partial tiny artifact can never become Completed.
 */
enum class NativeHlsSupportStatus { Supported, NativeUnsupportedFallback, ProtectedUnsupported }

enum class NativeHlsUnsupportedReason {
    None,
    NotHls,
    LivePlaylist,
    LowLatencyHls,
    SampleAes,
    DrmKeyFormat,
    UnknownEncryption,
    IFrameOnly,
    MasterPlaylist,
    SeparateRenditionMuxRequired,
    EncryptedInitMap,
    MissingSegments,
    MissingKeyUri,
    InvalidAesIv,
    MissingExtinf,
    InvalidPlaylist,
}

enum class NativeHlsExecutionStage(val userLabel: String) {
    Negotiating("Checking stream"),
    Admitted("Preparing download"),
    Downloading("Downloading parts"),
    Paused("Paused"),
    Recovering("Recovering"),
    Finalizing("Finalizing"),
    Publishing("Publishing"),
    Verifying("Verifying"),
    Completed("Completed"),
    Failed("Failed"),
    Cancelled("Cancelled"),
    Fallback("Use fallback"),
    Protected("Protected media"),
}

enum class NativeHlsPartState { Pending, Downloading, Complete, Failed, Skipped }

enum class NativeHlsFinalizationState { None, Prepared, Concatenating, Muxing, Publishing, Verifying, Completed, RecoveryRequired, Cancelled }

data class NativeHlsByteRange(val offset: Long?, val length: Long) {
    init { require(length > 0L) { "HLS byte range length must be positive" } }
    val headerValue: String get() = offset?.let { "bytes=$it-${it + length - 1}" } ?: "bytes=0-${length - 1}"
}

data class NativeHlsKey(
    val method: String,
    val uri: String?,
    val ivHex: String?,
    val keyFormat: String? = null,
) {
    val isAes128: Boolean get() = method.equals("AES-128", ignoreCase = true) && keyFormat.isNullOrBlank()
    val isProtected: Boolean get() = method.equals("SAMPLE-AES", ignoreCase = true) || !keyFormat.isNullOrBlank()
}

data class NativeHlsMap(
    val uri: String,
    val byteRange: NativeHlsByteRange? = null,
)

data class NativeHlsPart(
    val id: String,
    val index: Int,
    val url: String,
    val durationMs: Long,
    val mediaSequence: Long,
    val discontinuitySequence: Int,
    val byteRange: NativeHlsByteRange? = null,
    val initMap: NativeHlsMap? = null,
    val key: NativeHlsKey? = null,
    val expectedBytes: Long? = null,
    val state: NativeHlsPartState = NativeHlsPartState.Pending,
    val bytesReceived: Long = 0L,
    val sha256Hex: String? = null,
    val gap: Boolean = false,
) {
    val effectiveIvHex: String?
        get() = key?.ivHex ?: key?.takeIf { it.isAes128 }?.let { implicitIvHex(mediaSequence) }
    /**
     * XAR12 treats EXT-X-GAP as a successful skipped unit. This keeps progress and resume
     * monotonic without trying to fetch a segment that the playlist explicitly marks absent.
     */
    val complete: Boolean get() = state == NativeHlsPartState.Complete || state == NativeHlsPartState.Skipped
}

data class NativeHlsManifestPlan(
    val captureId: String,
    val manifestUrl: String,
    val canonicalManifestUrl: String,
    val supportStatus: NativeHlsSupportStatus,
    val unsupportedReasons: Set<NativeHlsUnsupportedReason>,
    val parts: List<NativeHlsPart>,
    val selectedVideoVariantId: String?,
    val selectedAudioVariantId: String?,
    val selectedSubtitleVariantId: String?,
    val hasSeparateAudio: Boolean,
    val hasSubtitles: Boolean,
    /** FFprobe expectations for the selected media rendition. Both false means require any media stream. */
    val requireVideoStream: Boolean,
    val requireAudioStream: Boolean,
    val aes128: Boolean,
    val keyRotation: Boolean,
    val mediaSequenceStart: Long,
    val discontinuityCount: Int,
    val estimatedDurationMs: Long,
    val requestHeaders: Map<String, String>,
    val executionUrl: String,
) {
    val nativeExecutable: Boolean get() = supportStatus == NativeHlsSupportStatus.Supported
    val aggregatePartCount: Int get() = parts.size
    val fallbackReason: String get() = unsupportedReasons.joinToString { it.name }.ifBlank { "none" }
}

data class NativeHlsAdmissionKey(
    val logicalMediaId: String,
    val destinationUri: String,
    val selectedTrackFingerprint: String,
) {
    val key: String get() = sha256(listOf(logicalMediaId, destinationUri, selectedTrackFingerprint).joinToString("\u0000"))
}

data class NativeHlsAdmittedJob(
    val jobId: String,
    val downloadId: String,
    val captureId: String,
    val admissionKey: String,
    val attemptGeneration: Long,
    val stage: NativeHlsExecutionStage,
    val plan: NativeHlsManifestPlan,
    val tempDirectoryKey: String,
    val destinationUri: String,
    val fileName: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val resumable: Boolean get() = stage in setOf(NativeHlsExecutionStage.Downloading, NativeHlsExecutionStage.Paused, NativeHlsExecutionStage.Recovering, NativeHlsExecutionStage.Finalizing, NativeHlsExecutionStage.Publishing, NativeHlsExecutionStage.Verifying)
}

data class NativeHlsAdmissionResult(
    val job: NativeHlsAdmittedJob,
    val created: Boolean,
    val reason: String,
)

data class NativeHlsStoragePlan(
    val requiredBytes: Long?,
    val finalizationOverheadBytes: Long?,
    val availableBytes: Long?,
    val okToStart: Boolean,
    val message: String,
) {
    val finalizationSafe: Boolean get() = okToStart && availableBytes?.let { available -> requiredBytes?.let { available >= it } ?: true } != false
}

data class NativeHlsProgressSnapshot(
    val stage: NativeHlsExecutionStage,
    val completedParts: Int,
    val totalParts: Int,
    val downloadedBytes: Long,
    val expectedBytes: Long?,
    val percent: Int,
    val monotonicFloor: Int,
    val userMessage: String,
) {
    val completed: Boolean get() = stage == NativeHlsExecutionStage.Completed && percent == 100
}

data class NativeHlsCompletionEvidence(
    val valid: Boolean,
    val bytes: Long,
    val expectedMinimumBytes: Long,
    val containsManifestText: Boolean,
    val looksLikeHtmlError: Boolean,
    val partCount: Int,
    val verifiedSha256Hex: String?,
    val message: String,
)

class NativeHlsExecutionEngine {
    fun negotiate(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        playlistText: String,
        requestHeaders: Map<String, String> = emptyMap(),
        selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = capture.selectedVariantId),
    ): NativeHlsManifestPlan {
        val canonical = LogicalMediaGraphEngine.identityUrl(capture.canonicalMediaUrl ?: capture.sourceUrl)
        if (capture.kind != MediaSourceKind.HlsPlaylist && !capture.sourceUrl.contains(".m3u8", ignoreCase = true)) {
            return unsupportedPlan(capture, canonical, NativeHlsUnsupportedReason.NotHls, requestHeaders, selection, variants)
        }
        val text = playlistText.trim()
        if (!text.startsWith("#EXTM3U")) {
            return unsupportedPlan(capture, canonical, NativeHlsUnsupportedReason.InvalidPlaylist, requestHeaders, selection, variants)
        }
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val reasons = linkedSetOf<NativeHlsUnsupportedReason>()
        if (lines.none { it.equals("#EXT-X-ENDLIST", ignoreCase = true) }) reasons += NativeHlsUnsupportedReason.LivePlaylist
        if (lines.any { it.startsWith("#EXT-X-PART", true) || it.startsWith("#EXT-X-PRELOAD-HINT", true) || it.startsWith("#EXT-X-SERVER-CONTROL", true) }) reasons += NativeHlsUnsupportedReason.LowLatencyHls
        if (lines.any { it.startsWith("#EXT-X-I-FRAME-STREAM-INF", true) }) reasons += NativeHlsUnsupportedReason.IFrameOnly
        // The segmented executor owns one media playlist only. A master playlist or a graph with
        // separate audio/subtitle renditions must stay on the selected-track embedded FFmpeg lane.
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF", true) }) reasons += NativeHlsUnsupportedReason.MasterPlaylist
        if (variants.any { it.kind == MediaVariantKind.Audio || it.kind == MediaVariantKind.Subtitle }) {
            reasons += NativeHlsUnsupportedReason.SeparateRenditionMuxRequired
        }
        val keyLines = lines.filter { it.startsWith("#EXT-X-KEY", true) }
        val keys = keyLines.map { parseKey(capture.sourceUrl, it) }
        if (keys.any { it.method.equals("SAMPLE-AES", true) }) reasons += NativeHlsUnsupportedReason.SampleAes
        if (keys.any { it.isProtected }) reasons += NativeHlsUnsupportedReason.DrmKeyFormat
        if (keys.any { !it.method.equals("NONE", true) && !it.isAes128 && !it.isProtected }) reasons += NativeHlsUnsupportedReason.UnknownEncryption
        if (keys.any { it.isAes128 && it.uri.isNullOrBlank() }) reasons += NativeHlsUnsupportedReason.MissingKeyUri
        if (keys.any { it.isAes128 && it.ivHex != null && !isValidAes128IvHex(it.ivHex) }) reasons += NativeHlsUnsupportedReason.InvalidAesIv
        if (hasSegmentWithoutExtinf(lines)) reasons += NativeHlsUnsupportedReason.MissingExtinf
        // EXT-X-KEY also applies to EXT-X-MAP. The current executor decrypts media parts but does
        // not persist/decrypt an encrypted init-map key/IV separately, so fail closed to fallback
        // instead of producing a corrupt fMP4 artifact.
        var activeKey: NativeHlsKey? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY", true) -> activeKey = parseKey(capture.sourceUrl, line).takeUnless { it.method.equals("NONE", true) }
                line.startsWith("#EXT-X-MAP", true) && activeKey?.isAes128 == true -> reasons += NativeHlsUnsupportedReason.EncryptedInitMap
            }
        }
        val mediaSequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE", true) }
            ?.substringAfter(':', "0")?.trim()?.toLongOrNull() ?: 0L
        val parts = parseMediaPlaylist(capture, text)
        if (parts.isEmpty()) reasons += NativeHlsUnsupportedReason.MissingSegments
        val protected = capture.protectionKind in setOf(MediaProtectionKind.SampleAes, MediaProtectionKind.Drm, MediaProtectionKind.UnknownEncrypted) ||
            capture.nativeCapability == MediaNativeCapability.ProtectedUnsupported ||
            reasons.any { it in setOf(NativeHlsUnsupportedReason.SampleAes, NativeHlsUnsupportedReason.DrmKeyFormat, NativeHlsUnsupportedReason.UnknownEncryption) }
        val status = when {
            protected -> NativeHlsSupportStatus.ProtectedUnsupported
            reasons.isNotEmpty() -> NativeHlsSupportStatus.NativeUnsupportedFallback
            else -> NativeHlsSupportStatus.Supported
        }
        val selectedVideo = selection.videoVariantId ?: capture.selectedVariantId ?: variants.firstOrNull { it.kind == MediaVariantKind.Video }?.id
        val selectedAudio = selection.audioVariantId ?: variants.firstOrNull { it.kind == MediaVariantKind.Audio && it.isDefault }?.id
        val selectedSubtitle = selection.subtitleVariantId
        val (requireVideoStream, requireAudioStream) = streamRequirements(capture, variants, selection)
        return NativeHlsManifestPlan(
            captureId = capture.id,
            manifestUrl = capture.sourceUrl,
            canonicalManifestUrl = canonical,
            supportStatus = status,
            unsupportedReasons = if (reasons.isEmpty()) setOf(NativeHlsUnsupportedReason.None) else reasons,
            parts = parts,
            selectedVideoVariantId = selectedVideo,
            selectedAudioVariantId = selectedAudio,
            selectedSubtitleVariantId = selectedSubtitle,
            hasSeparateAudio = variants.any { it.kind == MediaVariantKind.Audio },
            hasSubtitles = variants.any { it.kind == MediaVariantKind.Subtitle },
            requireVideoStream = requireVideoStream,
            requireAudioStream = requireAudioStream,
            aes128 = keys.any { it.isAes128 } || capture.protectionKind == MediaProtectionKind.Aes128,
            keyRotation = keys.map { it.uri.orEmpty() + "|" + it.ivHex.orEmpty() }.distinct().size > 1,
            mediaSequenceStart = mediaSequence,
            discontinuityCount = parts.map { it.discontinuitySequence }.distinct().size.coerceAtLeast(1) - 1,
            estimatedDurationMs = parts.sumOf { it.durationMs },
            requestHeaders = requestHeaders,
            executionUrl = capture.sourceUrl,
        )
    }

    fun parseMediaPlaylist(capture: MediaCaptureRecord, playlistText: String): List<NativeHlsPart> {
        val baseUrl = capture.sourceUrl
        val parts = mutableListOf<NativeHlsPart>()
        var pendingDurationMs: Long? = null
        var pendingGap = false
        var currentKey: NativeHlsKey? = null
        var currentMap: NativeHlsMap? = null
        var activeInitMapIdentity: String? = null
        var pendingByteRangeRaw: String? = null
        val byteRangeOffsetsByResource = mutableMapOf<String, Long>()
        var sequence = 0L
        var mediaSequenceBase = 0L
        var discontinuity = 0
        playlistText.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", true) -> {
                    mediaSequenceBase = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                    sequence = 0L
                }
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE", true) -> {
                    discontinuity = line.substringAfter(':', "0").trim().toIntOrNull() ?: 0
                }
                line.startsWith("#EXTINF", true) -> {
                    val seconds = line.substringAfter(':', "0").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    pendingDurationMs = (seconds * 1000.0).roundToInt().toLong().coerceAtLeast(1L)
                }
                line.startsWith("#EXT-X-GAP", true) -> pendingGap = true
                line.startsWith("#EXT-X-KEY", true) -> currentKey = parseKey(baseUrl, line).takeUnless { it.method.equals("NONE", true) }
                line.startsWith("#EXT-X-MAP", true) -> currentMap = parseMap(baseUrl, line)
                line.startsWith("#EXT-X-BYTERANGE", true) -> pendingByteRangeRaw = line.substringAfter(':', "")
                line.startsWith("#EXT-X-DISCONTINUITY", true) -> {
                    discontinuity++
                    activeInitMapIdentity = null
                }
                line.startsWith("#") -> Unit
                else -> {
                    val durationMs = pendingDurationMs ?: run {
                        pendingGap = false
                        pendingByteRangeRaw = null
                        return@forEach
                    }
                    val resolvedUrl = resolveUrl(baseUrl, line)
                    val priorOffset = byteRangeOffsetsByResource[resolvedUrl] ?: 0L
                    val byteRange = pendingByteRangeRaw?.let { parseByteRange(it, priorOffset) }
                    byteRange?.let { range -> byteRangeOffsetsByResource[resolvedUrl] = (range.offset ?: priorOffset) + range.length }
                    val mapIdentity = mapIdentity(currentMap)
                    val mapForPart = currentMap?.takeIf { mapIdentity != null && mapIdentity != activeInitMapIdentity }
                    if (mapForPart != null) activeInitMapIdentity = mapIdentity
                    val mediaSequence = mediaSequenceBase + sequence
                    val gap = pendingGap
                    val part = NativeHlsPart(
                        id = "${capture.id}:hls-part:$discontinuity:$mediaSequence:${parts.size}",
                        index = parts.size,
                        url = resolvedUrl,
                        durationMs = durationMs,
                        mediaSequence = mediaSequence,
                        discontinuitySequence = discontinuity,
                        byteRange = byteRange,
                        initMap = mapForPart,
                        key = currentKey,
                        expectedBytes = if (gap) 0L else byteRange?.length,
                        state = if (gap) NativeHlsPartState.Skipped else NativeHlsPartState.Pending,
                        gap = gap,
                    )
                    parts += part
                    sequence++
                    pendingDurationMs = null
                    pendingByteRangeRaw = null
                    pendingGap = false
                }
            }
        }
        return parts
    }

    fun admissionKey(capture: MediaCaptureRecord, destinationUri: String, selection: MediaTrackSelection): NativeHlsAdmissionKey {
        val logical = capture.logicalMediaId ?: LogicalMediaGraphEngine.identityUrl(capture.canonicalMediaUrl ?: capture.sourceUrl)
        val tracks = selection.selectedIds().sorted().joinToString("|").ifBlank { capture.selectedVariantId.orEmpty() }
        return NativeHlsAdmissionKey(logical, destinationUri, tracks)
    }

    fun admit(
        capture: MediaCaptureRecord,
        destinationUri: String,
        fileName: String,
        plan: NativeHlsManifestPlan,
        existing: NativeHlsAdmittedJob? = null,
        selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = capture.selectedVariantId),
        addAgain: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): NativeHlsAdmissionResult {
        val key = admissionKey(capture, destinationUri, selection).key
        if (!addAgain && existing?.admissionKey == key && existing.stage != NativeHlsExecutionStage.Cancelled) {
            return NativeHlsAdmissionResult(existing, created = false, reason = "existing logical-media HLS job reused")
        }
        val generation = if (addAgain) (existing?.attemptGeneration ?: 0L) + 1L else 1L
        val downloadId = "native-hls-${capture.id}-${key.take(12)}-g$generation"
        return NativeHlsAdmissionResult(
            job = NativeHlsAdmittedJob(
                jobId = "hls-job:$downloadId",
                downloadId = downloadId,
                captureId = capture.id,
                admissionKey = key,
                attemptGeneration = generation,
                stage = if (plan.nativeExecutable) NativeHlsExecutionStage.Admitted else if (plan.supportStatus == NativeHlsSupportStatus.ProtectedUnsupported) NativeHlsExecutionStage.Protected else NativeHlsExecutionStage.Fallback,
                plan = plan,
                tempDirectoryKey = "hls-temp/${capture.id}/${key.take(16)}/g$generation",
                destinationUri = destinationUri,
                fileName = fileName,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
            created = true,
            reason = if (addAgain) "explicit Add again created another HLS generation" else "new native HLS job admitted",
        )
    }

    fun storagePreflight(plan: NativeHlsManifestPlan, availableBytes: Long?, expectedOutputBytes: Long? = null): NativeHlsStoragePlan {
        val knownPartBytes = plan.parts.mapNotNull { part -> part.expectedBytes ?: part.byteRange?.length }.sum().takeIf { it > 0L }
        val knownInitMapBytes = plan.parts.mapNotNull { it.initMap?.byteRange?.length }.sum().takeIf { it > 0L }
        val downloadedEstimate = listOfNotNull(knownPartBytes, knownInitMapBytes).sum().takeIf { it > 0L }
            ?: expectedOutputBytes
        val overhead = downloadedEstimate?.let { (it / 10L).coerceAtLeast(16L * 1024L * 1024L) }
        val required = when {
            downloadedEstimate != null && overhead != null -> downloadedEstimate + overhead
            else -> null
        }
        val ok = when {
            !plan.nativeExecutable -> false
            required == null || availableBytes == null -> true
            else -> availableBytes >= required
        }
        val message = when {
            !plan.nativeExecutable -> "Native HLS cannot start: ${plan.fallbackReason}"
            required == null -> "Storage estimate is unknown; reserve temp + final artifact and fail safely on ENOSPC."
            ok -> "Storage preflight passed with finalization reserve."
            else -> "Not enough free space for HLS parts plus finalization reserve; keep capture reviewable."
        }
        return NativeHlsStoragePlan(required, overhead, availableBytes, ok, message)
    }

    fun progress(parts: List<NativeHlsPart>, stage: NativeHlsExecutionStage, previousPercent: Int = 0, finalizationPercent: Int = 0): NativeHlsProgressSnapshot {
        val totalParts = parts.size.coerceAtLeast(1)
        val completeParts = parts.count { it.complete }
        val countableParts = parts.filterNot { it.gap || it.state == NativeHlsPartState.Skipped }
        val expectedBytes = countableParts
            .takeIf { it.isNotEmpty() && it.all { part -> part.expectedBytes != null } }
            ?.sumOf { requireNotNull(it.expectedBytes) }
            ?.takeIf { it > 0L }
        val downloadedBytes = parts.sumOf { it.bytesReceived.coerceAtLeast(0L) }
        val downloadPercent = expectedBytes?.let { total -> ((downloadedBytes.toDouble() / total).coerceIn(0.0, 1.0) * 85.0).roundToInt() }
            ?: ((completeParts.toDouble() / totalParts) * 85.0).roundToInt()
        val rawPercent = when (stage) {
            NativeHlsExecutionStage.Finalizing -> 85 + finalizationPercent.coerceIn(0, 5)
            NativeHlsExecutionStage.Publishing -> 91 + finalizationPercent.coerceIn(0, 4)
            NativeHlsExecutionStage.Verifying -> 96 + finalizationPercent.coerceIn(0, 3)
            NativeHlsExecutionStage.Completed -> 100
            NativeHlsExecutionStage.Failed, NativeHlsExecutionStage.Cancelled -> previousPercent
            NativeHlsExecutionStage.Paused, NativeHlsExecutionStage.Recovering -> downloadPercent.coerceAtLeast(previousPercent)
            else -> downloadPercent
        }.coerceIn(0, 100)
        val percent = if (stage == NativeHlsExecutionStage.Completed) 100 else rawPercent.coerceAtMost(99).coerceAtLeast(previousPercent.coerceAtMost(99))
        return NativeHlsProgressSnapshot(
            stage = stage,
            completedParts = completeParts,
            totalParts = parts.size,
            downloadedBytes = downloadedBytes,
            expectedBytes = expectedBytes,
            percent = percent,
            monotonicFloor = previousPercent,
            userMessage = "${stage.userLabel}: $completeParts/${parts.size} parts${expectedBytes?.let { ", $downloadedBytes/$it bytes" }.orEmpty()}",
        )
    }

    fun verifyCompletion(artifactBytes: ByteArray, plan: NativeHlsManifestPlan, expectedSha256Hex: String? = null): NativeHlsCompletionEvidence {
        val textPrefix = artifactBytes.copyOfRange(0, artifactBytes.size.coerceAtMost(512)).toString(Charsets.UTF_8)
        val containsManifest = textPrefix.contains("#EXTM3U") || textPrefix.contains("#EXT-X-STREAM-INF") || textPrefix.contains("#EXTINF")
        val html = textPrefix.contains("<html", true) || textPrefix.contains("<!doctype html", true) || textPrefix.contains("access denied", true)
        val executableParts = plan.parts.count { !it.gap }
        val expectedMin = when {
            executableParts > 1 -> executableParts.toLong() * 188L
            executableParts == 1 -> 188L
            else -> 1024L
        }
        val sha = sha256Bytes(artifactBytes)
        val hashMatches = expectedSha256Hex?.equals(sha, ignoreCase = true) ?: true
        val valid = artifactBytes.size >= expectedMin && !containsManifest && !html && plan.nativeExecutable && hashMatches
        val message = when {
            !plan.nativeExecutable -> "Native HLS plan was not executable; completion rejected."
            containsManifest -> "Artifact is playlist text, not finalized media."
            html -> "Artifact looks like an HTML/error response, not media."
            artifactBytes.size < expectedMin -> "Artifact is too small for the planned part count."
            !hashMatches -> "Artifact hash does not match the expected completed generation."
            else -> "Completed artifact passed structural/native-HLS verification."
        }
        return NativeHlsCompletionEvidence(valid, artifactBytes.size.toLong(), expectedMin, containsManifest, html, executableParts, sha, message)
    }

    private fun unsupportedPlan(
        capture: MediaCaptureRecord,
        canonical: String,
        reason: NativeHlsUnsupportedReason,
        requestHeaders: Map<String, String>,
        selection: MediaTrackSelection,
        variants: List<MediaVariant>,
    ): NativeHlsManifestPlan = NativeHlsManifestPlan(
        captureId = capture.id,
        manifestUrl = capture.sourceUrl,
        canonicalManifestUrl = canonical,
        supportStatus = if (reason in setOf(NativeHlsUnsupportedReason.SampleAes, NativeHlsUnsupportedReason.DrmKeyFormat, NativeHlsUnsupportedReason.UnknownEncryption)) NativeHlsSupportStatus.ProtectedUnsupported else NativeHlsSupportStatus.NativeUnsupportedFallback,
        unsupportedReasons = setOf(reason),
        parts = emptyList(),
        selectedVideoVariantId = selection.videoVariantId ?: capture.selectedVariantId ?: variants.firstOrNull { it.kind == MediaVariantKind.Video }?.id,
        selectedAudioVariantId = selection.audioVariantId,
        selectedSubtitleVariantId = selection.subtitleVariantId,
        hasSeparateAudio = variants.any { it.kind == MediaVariantKind.Audio },
        hasSubtitles = variants.any { it.kind == MediaVariantKind.Subtitle },
        requireVideoStream = streamRequirements(capture, variants, selection).first,
        requireAudioStream = streamRequirements(capture, variants, selection).second,
        aes128 = capture.protectionKind == MediaProtectionKind.Aes128,
        keyRotation = false,
        mediaSequenceStart = 0L,
        discontinuityCount = 0,
        estimatedDurationMs = 0L,
        requestHeaders = requestHeaders,
        executionUrl = capture.sourceUrl,
    )

    private fun streamRequirements(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        selection: MediaTrackSelection,
    ): Pair<Boolean, Boolean> {
        val selectedIds = selection.selectedIds()
        val selected = variants.filter { it.id in selectedIds }
        val codecText = buildList {
            capture.codecs?.let(::add)
            selected.mapNotNullTo(this) { it.codecs }
        }.joinToString(",").lowercase(Locale.ROOT)
        val mime = capture.mimeType.orEmpty().lowercase(Locale.ROOT)
        val videoCodecHints = listOf("avc1", "avc3", "h264", "hev1", "hvc1", "hevc", "vp8", "vp9", "vp09", "av01", "mpeg4")
        val audioCodecHints = listOf("mp4a", "aac", "opus", "vorbis", "mp3", "ac-3", "ec-3", "flac")
        val requireVideo = selected.any { it.kind == MediaVariantKind.Video } ||
            mime.startsWith("video/") || videoCodecHints.any(codecText::contains)
        val requireAudio = selected.any { it.kind == MediaVariantKind.Audio } ||
            mime.startsWith("audio/") || audioCodecHints.any(codecText::contains)
        return requireVideo to requireAudio
    }

    private fun hasSegmentWithoutExtinf(lines: List<String>): Boolean {
        var hasDuration = false
        lines.forEach { line ->
            when {
                line.startsWith("#EXTINF", true) -> hasDuration = true
                line.startsWith("#") -> Unit
                hasDuration -> hasDuration = false
                else -> return true
            }
        }
        return false
    }

    fun isValidAes128IvHex(ivHex: String): Boolean = ivHex.removePrefix("0x").removePrefix("0X")
        .matches(Regex("^[0-9a-fA-F]{32}$"))

    private fun mapIdentity(map: NativeHlsMap?): String? = map?.let { value ->
        val range = value.byteRange?.let { byteRange -> "${byteRange.offset ?: 0L}:${byteRange.length}" } ?: "full"
        "${value.uri}#$range"
    }

    private fun parseKey(baseUrl: String, line: String): NativeHlsKey {
        val attrs = attributeList(line.substringAfter(':', ""))
        val uri = attrs["URI"]?.takeIf(String::isNotBlank)?.let { resolveUrl(baseUrl, it) }
        return NativeHlsKey(
            method = attrs["METHOD"] ?: "NONE",
            uri = uri,
            ivHex = attrs["IV"]?.removePrefix("0x")?.removePrefix("0X")?.lowercase(Locale.ROOT),
            keyFormat = attrs["KEYFORMAT"]?.takeUnless { it.equals("identity", true) },
        )
    }

    private fun parseMap(baseUrl: String, line: String): NativeHlsMap? {
        val attrs = attributeList(line.substringAfter(':', ""))
        val uri = attrs["URI"]?.takeIf(String::isNotBlank) ?: return null
        return NativeHlsMap(resolveUrl(baseUrl, uri), attrs["BYTERANGE"]?.let { parseByteRange(it, 0L) })
    }

    private fun parseByteRange(raw: String, priorOffset: Long): NativeHlsByteRange? {
        val value = raw.trim().trim('"')
        val length = value.substringBefore('@').toLongOrNull() ?: return null
        val explicitOffset = value.substringAfter('@', "").takeIf(String::isNotBlank)?.toLongOrNull()
        return NativeHlsByteRange(explicitOffset ?: priorOffset, length)
    }

    private fun resolveUrl(baseUrl: String, value: String): String = runCatching { URI(baseUrl).resolve(value.trim().trim('"')).toString() }.getOrDefault(value.trim().trim('"'))
}

fun implicitIvHex(mediaSequence: Long): String = mediaSequence.toString(16).padStart(32, '0').takeLast(32)

private fun sha256(value: String): String = sha256Bytes(value.toByteArray())
private fun sha256Bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

private fun attributeList(value: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var token = StringBuilder()
    var quoted = false
    val tokens = mutableListOf<String>()
    value.forEach { ch ->
        when {
            ch == '"' -> { quoted = !quoted; token.append(ch) }
            ch == ',' && !quoted -> { tokens += token.toString(); token = StringBuilder() }
            else -> token.append(ch)
        }
    }
    tokens += token.toString()
    tokens.forEach { entry ->
        val key = entry.substringBefore('=', "").trim().uppercase(Locale.ROOT)
        if (key.isBlank()) return@forEach
        val raw = entry.substringAfter('=', "").trim()
        result[key] = raw.trim('"')
    }
    return result
}
