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
import androidx.compose.material3.Button
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
) {
    val executionPlanner = remember { MediaExecutionLibraryPlanner() }
    val consumerPlanner = remember { MediaConsumerWorkspacePlanner() }
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
    var selectedPlayerItem by remember { mutableStateOf<OfflineMediaLibraryItem?>(null) }
    var selectedDetailsItem by remember { mutableStateOf<OfflineMediaLibraryItem?>(null) }
    val visibleItems = remember(allItems, filter) {
        consumerPlanner.filterLibrary(allItems, filter, System.currentTimeMillis())
    }
    val playableCount = allItems.count { it.toPlaybackCandidate() != null }

    Column(Modifier.fillMaxSize().xdmScreen(XdmScreenTags.Library, "Media library")) {
        XdmPageHeader(
            title = "Library",
            subtitle = "Completed video and audio, ready to play or manage.",
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XdmMetricStrip(
                metrics = listOf(
                    XdmMetric("Items", allItems.size.toString()),
                    XdmMetric("Playable", playableCount.toString()),
                ),
            )
            XdmSegmentedControl(
                options = MediaLibraryFilter.entries,
                selected = filter,
                label = MediaLibraryFilter::label,
                onSelected = { filter = it },
            )
        }

        if (visibleItems.isEmpty()) {
            XdmEmptyState(
                title = if (allItems.isEmpty()) "Your library is empty" else "Nothing in this filter",
                description = if (allItems.isEmpty()) {
                    "Completed media appears here automatically after a download finishes."
                } else {
                    "Choose another filter to see your completed media."
                },
                modifier = Modifier.weight(1f),
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
                onRemoveRecord(item)
                selectedDetailsItem = null
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
    onMore: () -> Unit,
) {
    XdmListCard(compact = true) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XdmFileTypeIcon(item.fileName, mimeType = item.sidecar.mimeType)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                XdmCardTitle(item.title, maxLines = 2)
                XdmMetadataText(libraryMetadata(item, consumerPlanner), maxLines = 2)
                XdmSupportingText(consumerPlanner.libraryStateLabel(item), maxLines = 1)
            }
        }
        LibraryPrimaryActions(item, onPlay, onResumeOrRetry, onMore)
    }
}

@Composable
private fun MediaLibraryGridItem(
    item: OfflineMediaLibraryItem,
    consumerPlanner: MediaConsumerWorkspacePlanner,
    onPlay: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onMore: () -> Unit,
) {
    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            XdmFileTypeIcon(item.fileName, mimeType = item.sidecar.mimeType)
            StatusPill(
                if (consumerPlanner.mediaType(item) == "audio") "Audio" else "Video",
                tone = XdmStatusTone.Info,
            )
        }
        XdmCardTitle(item.title, maxLines = 2)
        XdmMetadataText(libraryMetadata(item, consumerPlanner), maxLines = 2)
        XdmSupportingText(consumerPlanner.libraryStateLabel(item), maxLines = 1)
        LibraryPrimaryActions(item, onPlay, onResumeOrRetry, onMore)
    }
}

@Composable
private fun LibraryPrimaryActions(
    item: OfflineMediaLibraryItem,
    onPlay: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onMore: () -> Unit,
) {
    XdmActionFlowRow {
        when {
            item.toPlaybackCandidate() != null -> Button(onClick = onPlay) { Text("Play") }
            item.canResume -> Button(onClick = onResumeOrRetry) { Text("Resume download") }
            item.canRetry -> Button(onClick = onResumeOrRetry) { Text("Retry") }
            else -> StatusPill("Unavailable", tone = XdmStatusTone.Warning)
        }
        TextButton(onClick = onMore) { Text("More") }
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
                TextButton(onClick = { openMediaFile(context, url, item.sidecar.mimeType) }) { Text("Open file") }
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
