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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.media.MediaBatchInputParser
import com.mikeyphw.xdm.android.media.MediaBatchUrlDisposition
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
    intakeFeedback: MediaIntakeFeedbackUi,
    onPastePageUrl: (String) -> Unit,
    onBatchInput: (String) -> Unit,
    onDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onResumeOrRetryDownload: (Download) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onTrackSelectionChanged: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
) {
    val consumerPlanner = remember { MediaConsumerWorkspacePlanner() }
    val context = LocalContext.current
    var batchText by remember { mutableStateOf("") }
    var batchFeedback by remember { mutableStateOf<String?>(null) }
    var pageUrlText by remember { mutableStateOf("") }
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
                Button(onClick = { if (pageUrlText.isNotBlank()) onPastePageUrl(pageUrlText) }, enabled = pageUrlText.isNotBlank()) { Text("Sniff page URL") }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                XdmGroupedList {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        XdmSectionLabel("Paste page URL")
                        Text("Paste a watch page, iframe page, HLS playlist, DASH manifest, or direct media URL. XDM fetches a bounded prefix with preserved session headers when available and creates review records only from real probe results.")
                        OutlinedTextField(
                            value = pageUrlText,
                            onValueChange = { pageUrlText = it },
                            label = { Text("Page or media URL") },
                            placeholder = { Text("https://site.example/watch/episode") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        XdmActionFlowRow {
                            Button(onClick = { onPastePageUrl(pageUrlText) }, enabled = pageUrlText.isNotBlank()) { Text("Sniff page URL") }
                            TextButton(onClick = { pageUrlText = "" }, enabled = pageUrlText.isNotBlank()) { Text("Clear") }
                        }
                    }
                }
            }

            if (intakeFeedback.visible) {
                item {
                    XdmGroupedList {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            XdmSectionLabel(intakeFeedback.title.ifBlank { "Media intake" })
                            Text(intakeFeedback.detail)
                            intakeFeedback.diagnostics.take(3).forEach { diagnostic ->
                                XdmMetadataText(diagnostic)
                            }
                            when (intakeFeedback.kind) {
                                MediaIntakeFeedbackKind.Working -> XdmStatusBadge("Working", tone = XdmStatusTone.Info)
                                MediaIntakeFeedbackKind.Found -> XdmStatusBadge("Found", tone = XdmStatusTone.Success)
                                MediaIntakeFeedbackKind.NeedsBrowserCapture,
                                MediaIntakeFeedbackKind.AuthenticationRequired -> XdmStatusBadge("Firefox capture recommended", tone = XdmStatusTone.Warning)
                                MediaIntakeFeedbackKind.Unsupported,
                                MediaIntakeFeedbackKind.Failed -> XdmStatusBadge("Needs attention", tone = XdmStatusTone.Error)
                                MediaIntakeFeedbackKind.NoMediaFound -> XdmStatusBadge("No media found", tone = XdmStatusTone.Neutral)
                                MediaIntakeFeedbackKind.Idle -> Unit
                            }
                        }
                    }
                }
            }

            item {
                XdmNoticeRow(
                    text = "Page session details stay private. XDM never shows cookies, authorization values, or temporary media links here.",
                    tone = XdmStatusTone.Info,
                )
            }

            item {
                MediaBatchInputPanel(
                    text = batchText,
                    feedback = batchFeedback,
                    onTextChanged = {
                        batchText = it
                        batchFeedback = null
                    },
                    onInspectAll = {
                        val trimmed = batchText.trim()
                        if (trimmed.isNotEmpty()) {
                            onBatchInput(trimmed)
                            batchFeedback = "Batch sent to the shared app-side media sniffing engine for review."
                        }
                    },
                    onClearInvalid = {
                        batchText = batchText.lines()
                            .filter { line -> line.contains("http://", ignoreCase = true) || line.contains("https://", ignoreCase = true) }
                            .joinToString("\n")
                        batchFeedback = "Removed lines without supported HTTP(S) URLs."
                    },
                    onCopyRejectedLines = {
                        val rejected = MediaBatchInputParser().parse(batchText).rejectedLinesText
                        if (rejected.isNotBlank()) {
                            copyTextToClipboard(context, "XDM rejected media batch lines", rejected)
                            batchFeedback = "Rejected lines copied."
                        } else {
                            batchFeedback = "No rejected lines to copy."
                        }
                    },
                    onAddSelected = { selectedText ->
                        onBatchInput(selectedText)
                        batchFeedback = "Selected links sent to the shared app-side media sniffing engine."
                    },
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
                        onAction = { if (pageUrlText.isNotBlank()) onPastePageUrl(pageUrlText) },
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
private fun MediaBatchInputPanel(
    text: String,
    feedback: String?,
    onTextChanged: (String) -> Unit,
    onInspectAll: () -> Unit,
    onClearInvalid: () -> Unit,
    onCopyRejectedLines: () -> Unit,
    onAddSelected: (String) -> Unit,
) {
    val parser = remember { MediaBatchInputParser() }
    val parsed = remember(text) { parser.parse(text) }
    val mediaReadyUrls = remember(parsed) {
        parsed.accepted
            .filter { it.disposition == MediaBatchUrlDisposition.MediaReady }
            .map { it.normalizedUrl }
    }
    var selectedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(text) { selectedUrls = mediaReadyUrls.toSet() }

    XdmGroupedList {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            XdmSectionLabel("Batch media intake")
            Text(
                "Paste URLs or page text. XDM extracts HTTP(S) media links, dedupes them, and sends reviewable media through the shared app-side media sniffing engine.",
            )
            XdmMetadataText("Static sniffing only: no arbitrary JavaScript execution, no DRM bypass, and no raw cookie or token diagnostics.")
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                label = { Text("Paste URLs or page text") },
                placeholder = { Text("https://site/video1.m3u8\nhttps://site/watch/episode\nhttps://cdn/file.mp4") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 7,
                supportingText = { Text(feedback ?: "One URL per line, or paste HTML/JSON/text containing media URLs.") },
            )
            if (text.isNotBlank()) {
                XdmMetadataText(parsed.summaryLabel)
                parsed.accepted.take(6).forEach { accepted ->
                    val canSelect = accepted.disposition == MediaBatchUrlDisposition.MediaReady
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = accepted.normalizedUrl in selectedUrls,
                            onCheckedChange = { checked ->
                                selectedUrls = if (checked) {
                                    selectedUrls + accepted.normalizedUrl
                                } else {
                                    selectedUrls - accepted.normalizedUrl
                                }
                            },
                            enabled = canSelect,
                        )
                        Text(
                            text = if (canSelect) accepted.normalizedUrl else "Needs page inspection: ${accepted.normalizedUrl}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (parsed.accepted.size > 6) {
                    XdmMetadataText("${parsed.accepted.size - 6} more accepted URL(s) will be inspected by Inspect all.")
                }
            }
            XdmActionFlowRow {
                Button(onClick = onInspectAll, enabled = text.isNotBlank()) { Text("Inspect all") }
                TextButton(onClick = onClearInvalid, enabled = text.isNotBlank()) { Text("Clear invalid") }
                TextButton(onClick = onCopyRejectedLines, enabled = text.isNotBlank()) { Text("Copy rejected lines") }
                TextButton(
                    onClick = { onAddSelected(mediaReadyUrls.filter { it in selectedUrls }.joinToString("\n")) },
                    enabled = selectedUrls.isNotEmpty(),
                ) { Text("Add selected") }
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
                    DownloadState.RecoveryRequired -> if (download.errorMessage.orEmpty().startsWith("Final save failed")) "Retry save" else null
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

