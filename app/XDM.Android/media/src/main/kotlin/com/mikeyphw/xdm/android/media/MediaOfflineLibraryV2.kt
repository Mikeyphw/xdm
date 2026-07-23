package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaSourceKind
import java.util.Locale

/**
 * Phase 28 Offline Library 2.0.
 *
 * This remains a pure planner: it turns Phase 20 library rows into filterable, sortable,
 * sidecar-aware offline library cards. It never stores cookies, headers, bearer tokens, tokenized
 * source URLs, or proxy credentials in exported metadata.
 */
enum class OfflineLibraryV2Filter(val label: String) {
    Video("video"),
    Audio("audio"),
    Failed("failed"),
    Playable("playable"),
    NeedsCleanup("needs cleanup"),
    MissingFile("missing file"),
}

enum class OfflineLibraryV2SortKey(val label: String) {
    Recent("recent"),
    Title("title"),
    Duration("duration"),
    State("state"),
    SourceHost("source host"),
}

enum class OfflineLibraryV2Health(val label: String) {
    Ready("Ready"),
    Failed("Failed"),
    MissingFile("Missing file"),
    NeedsCleanup("Needs cleanup"),
    NeedsSidecarRepair("Needs sidecar repair"),
    WaitingForDownload("Waiting for download"),
}

enum class OfflineLibraryV2ActionKind(val label: String) {
    OpenPlayer("Open player"),
    OpenFolder("Open containing folder"),
    ShareFile("Share file"),
    RenameSidecar("Rename sidecar"),
    RemoveSidecar("Remove sidecar"),
    RefreshThumbnail("Refresh thumbnail"),
    RetryDownload("Retry download"),
    ResumeDownload("Resume download"),
    LocateMissingFile("Locate missing file"),
}

data class OfflineLibraryV2FilterState(
    val filters: Set<OfflineLibraryV2Filter> = emptySet(),
    val sourceHostQuery: String? = null,
    val sortKey: OfflineLibraryV2SortKey = OfflineLibraryV2SortKey.Recent,
    val descending: Boolean = true,
)

data class OfflineLibraryV2Action(
    val kind: OfflineLibraryV2ActionKind,
    val enabled: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String,
) {
    val summary: String get() = listOf(kind.label, if (enabled) "enabled" else "disabled", if (requiresConfirmation) "confirm" else "safe", reason).joinToString(" • ")
}

data class OfflineLibraryV2Row(
    val captureId: String,
    val title: String,
    val fileName: String,
    val sourceHost: String,
    val mediaTypeLabel: String,
    val durationLabel: String,
    val health: OfflineLibraryV2Health,
    val sortEpochMs: Long,
    val canPlay: Boolean,
    val hasThumbnail: Boolean,
    val sidecarFileName: String,
    val safeExportJson: String,
    val actions: List<OfflineLibraryV2Action>,
) {
    val summary: String get() = listOf(title, mediaTypeLabel, health.label, sourceHost, durationLabel).joinToString(" • ")
}

data class OfflineLibraryV2Dashboard(
    val rows: List<OfflineLibraryV2Row>,
    val filterState: OfflineLibraryV2FilterState,
    val visibleCount: Int,
    val playableCount: Int,
    val audioCount: Int,
    val videoCount: Int,
    val failedCount: Int,
    val missingCount: Int,
    val cleanupCount: Int,
    val sourceHosts: List<String>,
    val secretSafe: Boolean,
) {
    val empty: Boolean get() = rows.isEmpty()
    val summary: String get() = listOf(
        "visible=$visibleCount",
        "playable=$playableCount",
        "video=$videoCount",
        "audio=$audioCount",
        "failed=$failedCount",
        "missing=$missingCount",
        "cleanup=$cleanupCount",
        if (secretSafe) "secret-safe" else "redaction review",
    ).joinToString(" • ")
}

class MediaOfflineLibraryV2Planner {
    fun dashboard(
        items: List<OfflineMediaLibraryItem>,
        filterState: OfflineLibraryV2FilterState = OfflineLibraryV2FilterState(),
        existingFiles: Set<String> = emptySet(),
        cleanupArmedCaptureIds: Set<String> = emptySet(),
    ): OfflineLibraryV2Dashboard {
        val rows = items.map { item -> rowFor(item, existingFiles, cleanupArmedCaptureIds) }
        val filtered = rows.filter { row -> matches(row, filterState) }.let { rowsForSort -> sortRows(rowsForSort, filterState) }
        return OfflineLibraryV2Dashboard(
            rows = filtered,
            filterState = filterState,
            visibleCount = filtered.size,
            playableCount = filtered.count { it.canPlay },
            audioCount = filtered.count { it.mediaTypeLabel == "audio" },
            videoCount = filtered.count { it.mediaTypeLabel == "video" },
            failedCount = filtered.count { it.health == OfflineLibraryV2Health.Failed },
            missingCount = filtered.count { it.health == OfflineLibraryV2Health.MissingFile },
            cleanupCount = filtered.count { it.health == OfflineLibraryV2Health.NeedsCleanup },
            sourceHosts = rows.map { it.sourceHost }.filter { it.isNotBlank() }.distinct().sorted(),
            secretSafe = filtered.all { !containsKnownSecret(it.safeExportJson) && it.actions.none { action -> containsKnownSecret(action.summary) } },
        )
    }

    private fun rowFor(
        item: OfflineMediaLibraryItem,
        existingFiles: Set<String>,
        cleanupArmedCaptureIds: Set<String>,
    ): OfflineLibraryV2Row {
        val pathKnown = existingFiles.isNotEmpty()
        val fileExists = if (pathKnown) existingFiles.contains(item.fileName) || existingFiles.contains(item.captureId) else true
        val health = when {
            item.isCompleted && pathKnown && !fileExists -> OfflineLibraryV2Health.MissingFile
            cleanupArmedCaptureIds.contains(item.captureId) -> OfflineLibraryV2Health.NeedsCleanup
            item.state == DownloadState.Failed || item.canRetry -> OfflineLibraryV2Health.Failed
            item.isCompleted && item.sidecar.fileName.isBlank() -> OfflineLibraryV2Health.NeedsSidecarRepair
            item.isCompleted -> OfflineLibraryV2Health.Ready
            else -> OfflineLibraryV2Health.WaitingForDownload
        }
        val mediaType = mediaTypeFor(item)
        val sidecarName = safeFileName(item.sidecar.fileName.substringBeforeLast('.', item.fileName)) + ".xdm-media.json"
        return OfflineLibraryV2Row(
            captureId = item.captureId,
            title = item.title,
            fileName = safeFileName(item.fileName),
            sourceHost = item.sourceHost,
            mediaTypeLabel = mediaType,
            durationLabel = item.durationLabel,
            health = health,
            sortEpochMs = item.sidecar.completedAtEpochMs ?: 0L,
            canPlay = item.canPlayDirect && health == OfflineLibraryV2Health.Ready,
            hasThumbnail = !item.thumbnailUrl.isNullOrBlank(),
            sidecarFileName = sidecarName,
            safeExportJson = exportJsonFor(item, health, mediaType, sidecarName),
            actions = actionsFor(item, health, fileExists),
        )
    }

    private fun mediaTypeFor(item: OfflineMediaLibraryItem): String = when {
        item.sidecar.kind == MediaSourceKind.AudioStream || item.sidecar.mimeType?.startsWith("audio/", ignoreCase = true) == true || item.fileName.endsWith(".mp3", true) || item.fileName.endsWith(".m4a", true) -> "audio"
        item.sidecar.kind == MediaSourceKind.HlsPlaylist || item.sidecar.kind == MediaSourceKind.DashManifest -> "adaptive"
        else -> "video"
    }

    private fun actionsFor(
        item: OfflineMediaLibraryItem,
        health: OfflineLibraryV2Health,
        fileExists: Boolean,
    ): List<OfflineLibraryV2Action> = listOf(
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.OpenPlayer, item.canPlayDirect && health == OfflineLibraryV2Health.Ready, false, if (item.canPlayDirect) "completed direct media" else "not playable yet"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.OpenFolder, item.isCompleted && fileExists, false, "open containing folder or document provider"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.ShareFile, item.isCompleted && fileExists, false, "share completed local asset"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.RenameSidecar, item.isCompleted, false, "metadata-only rename plan"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.RemoveSidecar, item.isCompleted, true, "confirmation required because metadata is destructive"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.RefreshThumbnail, true, false, if (item.thumbnailUrl.isNullOrBlank()) "thumbnail missing" else "thumbnail refresh available"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.RetryDownload, item.canRetry, false, "retry failed media transfer"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.ResumeDownload, item.canResume, false, "resume partial media transfer"),
        OfflineLibraryV2Action(OfflineLibraryV2ActionKind.LocateMissingFile, health == OfflineLibraryV2Health.MissingFile, false, "recover missing library file"),
    )

    private fun matches(row: OfflineLibraryV2Row, filterState: OfflineLibraryV2FilterState): Boolean {
        val filters = filterState.filters
        if (filters.contains(OfflineLibraryV2Filter.Video) && row.mediaTypeLabel != "video" && row.mediaTypeLabel != "adaptive") return false
        if (filters.contains(OfflineLibraryV2Filter.Audio) && row.mediaTypeLabel != "audio") return false
        if (filters.contains(OfflineLibraryV2Filter.Failed) && row.health != OfflineLibraryV2Health.Failed) return false
        if (filters.contains(OfflineLibraryV2Filter.Playable) && !row.canPlay) return false
        if (filters.contains(OfflineLibraryV2Filter.NeedsCleanup) && row.health != OfflineLibraryV2Health.NeedsCleanup) return false
        if (filters.contains(OfflineLibraryV2Filter.MissingFile) && row.health != OfflineLibraryV2Health.MissingFile) return false
        val hostQuery = filterState.sourceHostQuery?.trim().orEmpty()
        return hostQuery.isBlank() || row.sourceHost.contains(hostQuery, ignoreCase = true)
    }

    private fun sortRows(rows: List<OfflineLibraryV2Row>, filterState: OfflineLibraryV2FilterState): List<OfflineLibraryV2Row> {
        val comparator = when (filterState.sortKey) {
            OfflineLibraryV2SortKey.Recent -> compareBy<OfflineLibraryV2Row> { it.sortEpochMs }
            OfflineLibraryV2SortKey.Title -> compareBy { it.title.lowercase(Locale.US) }
            OfflineLibraryV2SortKey.Duration -> compareBy { parseDurationSortValue(it.durationLabel) }
            OfflineLibraryV2SortKey.State -> compareBy { it.health.label }
            OfflineLibraryV2SortKey.SourceHost -> compareBy { it.sourceHost.lowercase(Locale.US) }
        }
        return if (filterState.descending) rows.sortedWith(comparator.reversed()) else rows.sortedWith(comparator)
    }

    private fun exportJsonFor(
        item: OfflineMediaLibraryItem,
        health: OfflineLibraryV2Health,
        mediaType: String,
        sidecarName: String,
    ): String = buildString {
        append('{')
        appendJson("captureId", item.captureId); append(',')
        appendJson("title", item.title); append(',')
        appendJson("fileName", safeFileName(item.fileName)); append(',')
        appendJson("mediaType", mediaType); append(',')
        appendJson("health", health.name); append(',')
        appendJson("sourceHost", item.sourceHost); append(',')
        appendJson("durationLabel", item.durationLabel); append(',')
        appendJson("sidecarFileName", sidecarName); append(',')
        appendJson("redactedSourceUrl", item.sidecar.redactedSourceUrl)
        append('}')
    }.let(::redactKnownSecrets)

    private fun parseDurationSortValue(label: String): Long = Regex("""(\d+)""").findAll(label).map { it.value.toLongOrNull() ?: 0L }.fold(0L) { acc, value -> acc * 60L + value }

    private fun safeFileName(fileName: String): String = fileName.replace(Regex("[\\r\\n\\t/\\\\]+"), " ").trim().ifBlank { "xdm-media.bin" }.take(120)

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { it.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private fun StringBuilder.appendJson(key: String, value: String) {
        append('"').append(key).append("\":\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
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
