package com.mikeyphw.xdm.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.mikeyphw.xdm.android.model.BrowserCaptureSessionSummary
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaOutputAdmissionMode
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaOutputState
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
    outputs: List<MediaOutputRecord>,
    mediaOutputAdmissionsInFlight: Set<String>,
    intakeFeedback: MediaIntakeFeedbackUi,
    browserCaptureSessions: List<BrowserCaptureSessionSummary>,
    onPastePageUrl: (String) -> Unit,
    onBatchInput: (String) -> Unit,
    onDownload: (MediaCaptureRecord, MediaTrackSelection, MediaOutputAdmissionMode) -> Unit,
    onResumeOrRetryDownload: (Download) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onTrackSelectionChanged: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
) {
    val consumerPlanner = remember { MediaConsumerWorkspacePlanner() }
    val context = LocalContext.current
    var batchText by rememberSaveable { mutableStateOf("") }
    var batchFeedback by remember { mutableStateOf<String?>(null) }
    var pageUrlText by rememberSaveable { mutableStateOf("") }
    var mediaToolsExpanded by rememberSaveable { mutableStateOf(false) }
    val downloadsById = remember(downloads) { downloads.associateBy(Download::id) }
    val latestOutputsByCaptureId = remember(outputs) {
        outputs.filter { it.state != MediaOutputState.Hidden }
            .groupBy { it.captureId }
            .mapValues { (_, records) ->
                records.filter { it.state in setOf(MediaOutputState.Active, MediaOutputState.Queued) }
                    .maxByOrNull { it.updatedAtEpochMs }
                    ?: records.filter { it.state == MediaOutputState.Completed && !it.completedArtifactUri.isNullOrBlank() }
                        .maxByOrNull { it.updatedAtEpochMs }
                    ?: records.maxByOrNull { it.updatedAtEpochMs }
            }
    }
    val reviewableCaptures = remember(captures) {
        // DownloadCreated records remain reviewable: one capture may intentionally produce multiple
        // output records/generations with different track selections or destinations.
        captures.filter { it.status != MediaCaptureStatus.Archived }.sortedByDescending(MediaCaptureRecord::updatedAtEpochMs)
    }
    val capturesById = remember(reviewableCaptures) { reviewableCaptures.associateBy(MediaCaptureRecord::id) }
    val activeBrowserSessions = remember(browserCaptureSessions, capturesById) {
        browserCaptureSessions.mapNotNull { session ->
            val candidates = session.candidates.filter { it.captureId in capturesById }
            session.copy(candidates = candidates, importedCandidateCount = candidates.size).takeIf { candidates.isNotEmpty() }
        }
    }
    val browserGroupedCaptureIds = remember(activeBrowserSessions) { activeBrowserSessions.flatMap { it.captureIds }.toSet() }
    val ungroupedCaptures = remember(reviewableCaptures, browserGroupedCaptureIds) {
        reviewableCaptures.filterNot { it.id in browserGroupedCaptureIds }
    }
    val recentlyQueued = remember(captures, downloadsById) {
        captures.mapNotNull { capture ->
            capture.downloadId?.let(downloadsById::get)?.let { capture to it }
        }.sortedByDescending { (_, download) -> download.updatedAtEpochMs }.take(5)
    }

    Column(Modifier.fillMaxSize().xdmScreen(XdmScreenTags.Media, "Media")) {
        val intro = "Direct media downloads in one tap. Playlists show quality and track choices when they exist."
        if (LocalXdmWindowClass.current == XdmWindowClass.Expanded) {
            XdmPageHeader(title = "Media", subtitle = intro)
        } else {
            XdmPageIntro(intro)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                XdmGroupedList {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        XdmSectionLabel("Find media")
                        OutlinedTextField(
                            value = pageUrlText,
                            onValueChange = { pageUrlText = it },
                            label = { Text("Page or media URL") },
                            placeholder = { Text("https://site.example/watch/episode") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        XdmActionFlowRow {
                            Button(onClick = { onPastePageUrl(pageUrlText) }, enabled = pageUrlText.isNotBlank()) { Text("Check URL") }
                            Button(onClick = { context.startActivity(MediaLocatorActivity.intent(context, pageUrlText)) }) { Text("Live locator") }
                            TextButton(onClick = { mediaToolsExpanded = !mediaToolsExpanded }) {
                                Text(if (mediaToolsExpanded) "Hide tools" else "More tools")
                            }
                        }
                        XdmMetadataText("Captured browser context is used when needed and stays private.")
                    }
                }
            }

            if (intakeFeedback.visible) {
                item {
                    XdmGroupedList {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            XdmSectionLabel(intakeFeedback.title.ifBlank { "Media intake" })
                            Text(intakeFeedback.detail)
                            if (intakeFeedback.diagnostics.isNotEmpty()) {
                                XdmTechnicalDetails(label = "Technical details") {
                                    intakeFeedback.diagnostics.take(6).forEach { diagnostic ->
                                        XdmTechnicalText(diagnostic)
                                    }
                                }
                            }
                            when (intakeFeedback.kind) {
                                MediaIntakeFeedbackKind.Working -> XdmStatusBadge("Running", tone = XdmStatusTone.Info)
                                MediaIntakeFeedbackKind.Found -> XdmStatusBadge("Ready", tone = XdmStatusTone.Success)
                                MediaIntakeFeedbackKind.NeedsBrowserCapture,
                                MediaIntakeFeedbackKind.AuthenticationRequired,
                                MediaIntakeFeedbackKind.Unsupported -> XdmStatusBadge("Needs action", tone = XdmStatusTone.Warning)
                                MediaIntakeFeedbackKind.Failed -> XdmStatusBadge("Failed", tone = XdmStatusTone.Error)
                                MediaIntakeFeedbackKind.NoMediaFound -> XdmStatusBadge("Completed", tone = XdmStatusTone.Neutral)
                                MediaIntakeFeedbackKind.Idle -> Unit
                            }
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(mediaToolsExpanded) {
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
                                batchFeedback = "Batch sent for media inspection."
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
                            batchFeedback = "Selected links sent for media inspection."
                        },
                    )
                }
            }

            if (reviewableCaptures.isEmpty()) {
                item {
                    XdmEmptyState(
                        title = if (captures.isEmpty()) "No captured media" else "Everything is already handled",
                        description = if (captures.isEmpty()) {
                            "Open Live locator, paste a media link, or share media from your browser."
                        } else {
                            "New captures will appear here when you inspect or share another media link."
                        },
                        actionLabel = "Open Live locator",
                        onAction = { context.startActivity(MediaLocatorActivity.intent(context, pageUrlText)) },
                    )
                }
            } else {
                if (activeBrowserSessions.isNotEmpty()) {
                    item { XdmSectionLabel("From Firefox") }
                    activeBrowserSessions.forEach { session ->
                        item(key = "browser-session:${session.sessionId}") {
                            BrowserCaptureSessionHeader(session)
                        }
                        val sessionCaptures = session.candidates.mapNotNull { capturesById[it.captureId] }
                        items(sessionCaptures, key = { capture -> "${session.sessionId}:${capture.id}" }) { capture ->
                            val captureVariants = variants.filter { it.captureId == capture.id }.sortedBy { it.position }
                            MediaCaptureCard(
                                capture = capture,
                                captureVariants = captureVariants,
                                persistedSelection = mediaTrackSelections[capture.id]
                                    ?: MediaTrackSelection(videoVariantId = capture.selectedVariantId),
                                consumerPlanner = consumerPlanner,
                                latestOutput = latestOutputsByCaptureId[capture.id],
                                downloadInFlight = capture.id in mediaOutputAdmissionsInFlight,
                                onDownload = onDownload,
                                onOpenOutput = { output -> openMediaOutput(context, output) },
                                onResolve = onResolve,
                                onSelectVariant = onSelectVariant,
                                onTrackSelectionChanged = onTrackSelectionChanged,
                                onRemove = onRemove,
                            )
                        }
                    }
                }
                if (ungroupedCaptures.isNotEmpty()) {
                    item { XdmSectionLabel(if (activeBrowserSessions.isEmpty()) "Captured media" else "Other captured media") }
                    items(ungroupedCaptures, key = MediaCaptureRecord::id) { capture ->
                        val captureVariants = variants.filter { it.captureId == capture.id }.sortedBy { it.position }
                        MediaCaptureCard(
                            capture = capture,
                            captureVariants = captureVariants,
                            persistedSelection = mediaTrackSelections[capture.id]
                                ?: MediaTrackSelection(videoVariantId = capture.selectedVariantId),
                            consumerPlanner = consumerPlanner,
                            latestOutput = latestOutputsByCaptureId[capture.id],
                            downloadInFlight = capture.id in mediaOutputAdmissionsInFlight,
                            onDownload = onDownload,
                            onOpenOutput = { output -> openMediaOutput(context, output) },
                            onResolve = onResolve,
                            onSelectVariant = onSelectVariant,
                            onTrackSelectionChanged = onTrackSelectionChanged,
                            onRemove = onRemove,
                        )
                    }
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
private fun BrowserCaptureSessionHeader(session: BrowserCaptureSessionSummary) {
    XdmGroupedList {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle(session.pageTitle.ifBlank { session.pageHost.ifBlank { "Firefox capture" } }, maxLines = 2)
                    XdmMetadataText(session.pageHost.ifBlank { "Browser capture" })
                }
                XdmStatusBadge("${session.importedCandidateCount} found", tone = XdmStatusTone.Success)
            }
            XdmSupportingText(
                if (session.importedCandidateCount == 1) "1 media item is ready to review." else "${session.importedCandidateCount} media items are ready to review.",
                maxLines = 2,
            )
            XdmMetadataText("Browser session values stay private and are used only when a request needs them.")
            XdmTechnicalDetails(label = "Capture details") {
                XdmMetadataText("Browser observations: ${session.totalCandidateCount}")
                val evidence = session.candidates.flatMap { it.evidence }.distinct().take(5)
                if (evidence.isNotEmpty()) XdmMetadataText("Evidence: ${evidence.joinToString(" • ")}")
                if (session.truncated) {
                    XdmMetadataText("More candidates were observed; XDM kept the highest-confidence results in this handoff.")
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
            XdmSectionLabel("Batch media")
            Text("Paste links or page source. XDM will find supported media links and remove duplicates.")
            XdmTechnicalDetails(label = "How batch inspection works") {
                XdmMetadataText("Static inspection does not run page JavaScript. Live locator can observe media loaded by the page. XDM does not bypass DRM or display private browser values.")
            }
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

private fun openMediaOutput(context: android.content.Context, output: MediaOutputRecord) {
    val uri = output.completedArtifactUri?.takeIf(String::isNotBlank) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), output.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The Library remains the fallback place to open completed media.
    } catch (_: SecurityException) {
        // Some document providers cannot grant an external reader access.
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

