package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.media.MediaConsumerWorkspacePlanner
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.util.formatBytes
import com.mikeyphw.xdm.android.util.formatSpeed

@Composable
@UiSurface(UiAudience.User, "Review captured media and choose downloadable tracks")
fun MediaInboxScreen(
    captures: List<MediaCaptureRecord>,
    variants: List<MediaVariant>,
    mediaTrackSelections: Map<String, MediaTrackSelection>,
    downloads: List<Download>,
    onPastePageUrl: () -> Unit,
    onDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onResumeOrRetryDownload: (Download) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onTrackSelectionChanged: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
) {
    val consumerPlanner = remember { MediaConsumerWorkspacePlanner() }
    val reviewableCaptures = remember(captures) {
        captures.filterNot { it.status == MediaCaptureStatus.DownloadCreated }
            .sortedByDescending(MediaCaptureRecord::updatedAtEpochMs)
    }
    val downloadsById = remember(downloads) { downloads.associateBy(Download::id) }
    val recentlyQueued = remember(captures, downloadsById) {
        captures.mapNotNull { capture ->
            capture.downloadId?.let(downloadsById::get)?.let { capture to it }
        }.sortedByDescending { (_, download) -> download.updatedAtEpochMs }.take(5)
    }

    Column(Modifier.fillMaxSize().xdmScreen(XdmScreenTags.Media, "Media")) {
        XdmPageHeader(
            title = "Media",
            subtitle = "Choose quality and tracks before anything is added to Downloads.",
            actions = {
                Button(onClick = onPastePageUrl) { Text("Paste page URL") }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                XdmNoticeRow(
                    text = "Page session details stay private. XDM never shows cookies, authorization values, or temporary media links here.",
                    tone = XdmStatusTone.Info,
                )
            }

            if (reviewableCaptures.isEmpty()) {
                item {
                    XdmEmptyState(
                        title = if (captures.isEmpty()) "No media waiting" else "Everything is queued",
                        description = if (captures.isEmpty()) {
                            "Paste a page URL or share a media link to XDM. You will review it before downloading."
                        } else {
                            "New captures will appear here when you share or inspect another media link."
                        },
                        actionLabel = "Paste page URL",
                        onAction = onPastePageUrl,
                    )
                }
            } else {
                item { XdmSectionLabel("Ready to download") }
                items(reviewableCaptures, key = MediaCaptureRecord::id) { capture ->
                    val captureVariants = variants.filter { it.captureId == capture.id }.sortedBy { it.position }
                    MediaCaptureCard(
                        capture = capture,
                        captureVariants = captureVariants,
                        persistedSelection = mediaTrackSelections[capture.id]
                            ?: MediaTrackSelection(videoVariantId = capture.selectedVariantId),
                        consumerPlanner = consumerPlanner,
                        onDownload = onDownload,
                        onResolve = onResolve,
                        onSelectVariant = onSelectVariant,
                        onTrackSelectionChanged = onTrackSelectionChanged,
                        onRemove = onRemove,
                    )
                }
            }

            if (recentlyQueued.isNotEmpty()) {
                item { XdmSectionLabel("Recently queued") }
                item {
                    XdmGroupedList {
                        recentlyQueued.forEachIndexed { index, (capture, download) ->
                            RecentlyQueuedMediaRow(
                                title = capture.title.ifBlank { download.fileName },
                                download = download,
                                onResumeOrRetry = { onResumeOrRetryDownload(download) },
                            )
                            if (index != recentlyQueued.lastIndex) XdmListSeparator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyQueuedMediaRow(
    title: String,
    download: Download,
    onResumeOrRetry: () -> Unit,
) {
    val progress = download.totalBytes?.takeIf { it > 0L }?.let { download.progressFraction }
    Column(Modifier.fillMaxWidth()) {
        XdmListRow(
            headline = title,
            supporting = buildList {
                add(download.state.uiLabel())
                download.totalBytes?.let { add("${download.bytesReceived.formatBytes()} of ${it.formatBytes()}") }
                if (download.speedBytesPerSecond > 0L) add(download.speedBytesPerSecond.formatSpeed())
            }.joinToString(" • "),
            leading = { XdmFileTypeIcon(download.fileName, mimeType = download.mimeType) },
            trailing = {
                val action = when (download.state) {
                    DownloadState.Downloading,
                    DownloadState.Connecting,
                    DownloadState.Queued,
                    DownloadState.Finalizing -> "Pause"
                    DownloadState.Paused,
                    DownloadState.Failed,
                    DownloadState.WaitingForNetwork,
                    DownloadState.WaitingForPower -> "Resume"
                    else -> null
                }
                action?.let { TextButton(onClick = onResumeOrRetry) { Text(it) } }
            },
        )
        if (download.state !in setOf(DownloadState.Completed, DownloadState.Cancelled, DownloadState.Failed)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 68.dp, end = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                XdmProgressLine(progress = progress, stateLabel = download.state.uiLabel())
            }
        }
    }
}
