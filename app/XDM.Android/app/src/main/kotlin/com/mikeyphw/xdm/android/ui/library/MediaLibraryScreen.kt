package com.mikeyphw.xdm.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.media.MediaConsumerWorkspacePlanner
import com.mikeyphw.xdm.android.media.MediaExecutionLibraryPlanner
import com.mikeyphw.xdm.android.media.MediaLibraryFilter
import com.mikeyphw.xdm.android.media.MediaExternalJobSnapshot
import com.mikeyphw.xdm.android.media.OfflineMediaLibraryItem
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaOutputOwnerKind
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.util.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class LibrarySort(val label: String) {
    Recent("Recent"),
    Title("Title"),
    Size("Size"),
}

@Composable
@UiSurface(UiAudience.User, "Browse completed playable media")
fun MediaLibraryScreen(
    captures: List<MediaCaptureRecord>,
    variants: List<MediaVariant>,
    downloads: List<Download>,
    outputs: List<MediaOutputRecord>,
    externalJobs: List<MediaExternalJobSnapshot>,
    onResumeOrRetryDownload: (Download) -> Unit,
    onRetryExternalJob: (String) -> Unit,
    onRemoveRecord: (OfflineMediaLibraryItem) -> Unit,
    onFindMedia: () -> Unit,
    onDeleteSavedFile: (OfflineMediaLibraryItem) -> Unit,
) {
    val executionPlanner = remember { MediaExecutionLibraryPlanner() }
    val consumerPlanner = remember { MediaConsumerWorkspacePlanner() }
    val context = LocalContext.current
    val allItems = remember(captures, downloads, variants, outputs, externalJobs) {
        executionPlanner.offlineLibraryItems(
            captures = captures,
            downloads = downloads,
            variants = variants,
            outputs = outputs,
            externalJobs = externalJobs,
            allowLegacyFallback = false,
        )
    }
    var filter by rememberSaveable { mutableStateOf(MediaLibraryFilter.All) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Recent) }
    var selectedPlayerItem by remember { mutableStateOf<OfflineMediaLibraryItem?>(null) }
    var selectedDetailsItem by remember { mutableStateOf<OfflineMediaLibraryItem?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<OfflineMediaLibraryItem?>(null) }
    var pendingRemoveItem by remember { mutableStateOf<OfflineMediaLibraryItem?>(null) }
    val downloadsById = remember(downloads) { downloads.associateBy(Download::id) }
    val visibleItems = remember(allItems, filter, sort, downloadsById) {
        val filtered = consumerPlanner.filterLibrary(allItems, filter, System.currentTimeMillis())
        when (sort) {
            LibrarySort.Recent -> filtered.sortedByDescending { it.sidecar.completedAtEpochMs ?: 0L }
            LibrarySort.Title -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.Size -> filtered.sortedByDescending { libraryItemSize(it, downloadsById) }
        }
    }
    val playableCount = allItems.count { it.toPlaybackCandidate() != null }
    val storedBytes = remember(allItems, downloadsById) {
        allItems.mapNotNull(OfflineMediaLibraryItem::downloadId)
            .distinct()
            .mapNotNull(downloadsById::get)
            .sumOf { it.completedArtifactBytes ?: it.totalBytes ?: it.bytesReceived }
    }

    Column(Modifier.fillMaxSize().xdmScreen(XdmScreenTags.Library, "Media library")) {
        val intro = "Completed video and audio, ready to play or manage."
        if (LocalXdmWindowClass.current == XdmWindowClass.Expanded) {
            XdmPageHeader(title = "Library", subtitle = intro)
        } else {
            XdmPageIntro(intro)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XdmMetricStrip(
                metrics = listOf(
                    XdmMetric("Items", allItems.size.toString()),
                    XdmMetric("Playable", playableCount.toString()),
                    XdmMetric("Stored", storedBytes.formatBytes()),
                ),
            )
            XdmSegmentedControl(
                options = MediaLibraryFilter.entries,
                selected = filter,
                label = MediaLibraryFilter::label,
                onSelected = { filter = it },
            )
            XdmActionFlowRow {
                LibrarySort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = { Text("Sort: ${option.label}") },
                    )
                }
            }
        }

        if (visibleItems.isEmpty()) {
            XdmEmptyState(
                title = if (allItems.isEmpty()) "No media yet" else "Nothing in this filter",
                description = if (allItems.isEmpty()) {
                    "Completed video and audio will appear here automatically."
                } else {
                    "Choose another filter to see your completed media."
                },
                modifier = Modifier.weight(1f),
                actionLabel = if (allItems.isEmpty()) "Find media" else null,
                onAction = if (allItems.isEmpty()) onFindMedia else null,
            )
        } else if (LocalXdmWindowClass.current == XdmWindowClass.Compact) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .xdmScreen(XdmScreenTags.LibraryList, "Media library list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleItems, key = OfflineMediaLibraryItem::outputId) { item ->
                    MediaLibraryListItem(
                        item = item,
                        consumerPlanner = consumerPlanner,
                        onPlay = { selectedPlayerItem = item },
                        onResumeOrRetry = {
                            if (item.ownerKind == MediaOutputOwnerKind.TermuxJob) onRetryExternalJob(item.ownerId)
                            else item.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }?.let(onResumeOrRetryDownload)
                        },
                        onShare = item.playbackUrl?.let { url -> { shareMediaFile(context, url, item.sidecar.mimeType, item.title) } },
                        onRemove = { pendingRemoveItem = item },
                        onMore = { selectedDetailsItem = item },
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 250.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .xdmScreen(XdmScreenTags.LibraryGrid, "Media library grid"),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                gridItems(visibleItems, key = OfflineMediaLibraryItem::outputId) { item ->
                    MediaLibraryGridItem(
                        item = item,
                        consumerPlanner = consumerPlanner,
                        onPlay = { selectedPlayerItem = item },
                        onResumeOrRetry = {
                            if (item.ownerKind == MediaOutputOwnerKind.TermuxJob) onRetryExternalJob(item.ownerId)
                            else item.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }?.let(onResumeOrRetryDownload)
                        },
                        onShare = item.playbackUrl?.let { url -> { shareMediaFile(context, url, item.sidecar.mimeType, item.title) } },
                        onRemove = { pendingRemoveItem = item },
                        onMore = { selectedDetailsItem = item },
                    )
                }
            }
        }
    }

    selectedPlayerItem?.let { item ->
        XdmAdaptiveSheet(
            visible = true,
            windowClass = LocalXdmWindowClass.current,
            onDismissRequest = { selectedPlayerItem = null },
            title = item.title,
        ) {
            item.toPlaybackCandidate()?.let { candidate ->
                Media3DirectPlayerCard(candidate, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    selectedDetailsItem?.let { item ->
        LibraryItemDetailsSheet(
            item = item,
            consumerPlanner = consumerPlanner,
            visible = true,
            onDismiss = { selectedDetailsItem = null },
            onResumeOrRetry = {
                if (item.ownerKind == MediaOutputOwnerKind.TermuxJob) onRetryExternalJob(item.ownerId)
                else item.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }?.let(onResumeOrRetryDownload)
                selectedDetailsItem = null
            },
            onRemoveRecord = {
                pendingRemoveItem = item
                selectedDetailsItem = null
            },
            onDeleteSavedFile = if (item.downloadId != null && item.isCompleted) {
                {
                    pendingDeleteItem = item
                    selectedDetailsItem = null
                }
            } else null,
        )
    }

    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text("Delete saved file?") },
            text = { Text("This permanently deletes the downloaded file and its XDM download entry. This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    onDeleteSavedFile(item)
                    pendingDeleteItem = null
                }) { Text("Delete file") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) { Text("Cancel") }
            },
        )
    }

    pendingRemoveItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoveItem = null },
            title = { Text("Remove from Library?") },
            text = { Text("This removes the Library record only. The downloaded file is kept on your device.") },
            confirmButton = {
                Button(onClick = {
                    onRemoveRecord(item)
                    pendingRemoveItem = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveItem = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun MediaLibraryListItem(
    item: OfflineMediaLibraryItem,
    consumerPlanner: MediaConsumerWorkspacePlanner,
    onPlay: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onShare: (() -> Unit)?,
    onRemove: () -> Unit,
    onMore: () -> Unit,
) {
    XdmListCard(compact = true) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XdmMediaArtwork(
                fileName = item.fileName,
                mimeType = item.sidecar.mimeType,
                thumbnailUrl = item.thumbnailUrl,
                localUri = item.playbackUrl,
                width = 72.dp,
                height = 48.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                XdmCardTitle(item.title, maxLines = 2)
                XdmMetadataText(libraryMetadata(item, consumerPlanner), maxLines = 2)
                XdmSupportingText(consumerPlanner.libraryStateLabel(item), maxLines = 1)
            }
        }
        LibraryPrimaryActions(item, onPlay, onResumeOrRetry, onShare, onRemove, onMore)
    }
}

@Composable
private fun MediaLibraryGridItem(
    item: OfflineMediaLibraryItem,
    consumerPlanner: MediaConsumerWorkspacePlanner,
    onPlay: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onShare: (() -> Unit)?,
    onRemove: () -> Unit,
    onMore: () -> Unit,
) {
    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            XdmMediaArtwork(
                fileName = item.fileName,
                mimeType = item.sidecar.mimeType,
                thumbnailUrl = item.thumbnailUrl,
                localUri = item.playbackUrl,
                width = 96.dp,
                height = 60.dp,
            )
            StatusPill(
                if (consumerPlanner.mediaType(item) == "audio") "Audio" else "Video",
                tone = XdmStatusTone.Info,
            )
        }
        XdmCardTitle(item.title, maxLines = 2)
        XdmMetadataText(libraryMetadata(item, consumerPlanner), maxLines = 2)
        XdmSupportingText(consumerPlanner.libraryStateLabel(item), maxLines = 1)
        LibraryPrimaryActions(item, onPlay, onResumeOrRetry, onShare, onRemove, onMore)
    }
}

@Composable
private fun LibraryPrimaryActions(
    item: OfflineMediaLibraryItem,
    onPlay: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onShare: (() -> Unit)?,
    onRemove: () -> Unit,
    onMore: () -> Unit,
) {
    XdmActionFlowRow {
        when {
            item.toPlaybackCandidate() != null -> Button(onClick = onPlay) { Text("Open") }
            item.canResume -> Button(onClick = onResumeOrRetry) { Text("Resume download") }
            item.canRetry -> Button(onClick = onResumeOrRetry) { Text("Retry") }
            else -> StatusPill("Unavailable", tone = XdmStatusTone.Warning)
        }
        onShare?.let { share -> TextButton(onClick = share) { Text("Share") } }
        TextButton(onClick = onMore) { Text("Manage") }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun LibraryItemDetailsSheet(
    item: OfflineMediaLibraryItem,
    consumerPlanner: MediaConsumerWorkspacePlanner,
    visible: Boolean,
    onDismiss: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onRemoveRecord: (() -> Unit)?,
    onDeleteSavedFile: (() -> Unit)?,
) {
    val context = LocalContext.current
    XdmAdaptiveSheet(
        visible = visible,
        windowClass = LocalXdmWindowClass.current,
        onDismissRequest = onDismiss,
        title = "Media details",
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XdmListCard {
                XdmCardTitle(item.title, maxLines = 2)
                XdmSupportingText(consumerPlanner.libraryStateLabel(item), maxLines = 2)
                XdmMetadataText(libraryMetadata(item, consumerPlanner), maxLines = 3)
            }
            when {
                item.canResume -> Button(onClick = onResumeOrRetry) { Text("Resume download") }
                item.canRetry -> Button(onClick = onResumeOrRetry) { Text("Retry download") }
            }
            item.playbackUrl?.let { url ->
                XdmActionFlowRow {
                    TextButton(onClick = { openMediaFile(context, url, item.sidecar.mimeType) }) { Text("Open file") }
                    TextButton(onClick = { shareMediaFile(context, url, item.sidecar.mimeType, item.title) }) { Text("Share") }
                }
            }
            onDeleteSavedFile?.let { deleteSavedFile ->
                TextButton(onClick = deleteSavedFile) { Text("Delete saved file") }
            }
            onRemoveRecord?.let { removeRecord ->
                TextButton(onClick = removeRecord) { Text("Remove library record") }
                XdmMetadataText("Removing the library record does not delete the downloaded file.", maxLines = 2)
            }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

private fun libraryMetadata(item: OfflineMediaLibraryItem, planner: MediaConsumerWorkspacePlanner): String = buildList {
    add(if (planner.mediaType(item) == "audio") "Audio" else "Video")
    item.durationLabel.takeIf { it.isNotBlank() }?.let(::add)
    item.sidecar.completedAtEpochMs?.let { add("Added ${formatLibraryDate(it)}") }
    if (item.durationLabel.isBlank()) {
        item.downloadId?.let { add(item.fileName) }
    }
}.joinToString(" • ")

private fun formatLibraryDate(epochMs: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}.getOrDefault("recently")

private fun libraryItemSize(item: OfflineMediaLibraryItem, downloadsById: Map<String, Download>): Long =
    item.downloadId?.let(downloadsById::get)?.let { download ->
        download.completedArtifactBytes ?: download.totalBytes ?: download.bytesReceived
    } ?: 0L

private fun shareMediaFile(context: android.content.Context, url: String, mimeType: String?, title: String) {
    val uri = Uri.parse(url)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share media"))
    } catch (_: ActivityNotFoundException) {
        // Keep the library usable even when the device has no compatible share target.
    } catch (_: SecurityException) {
        // Some providers do not grant external share access; embedded playback remains available.
    }
}

private fun openMediaFile(context: android.content.Context, url: String, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The embedded player remains available when no external handler is installed.
    } catch (_: SecurityException) {
        // Some document providers do not grant external read access. Keep playback inside XDM.
    }
}
