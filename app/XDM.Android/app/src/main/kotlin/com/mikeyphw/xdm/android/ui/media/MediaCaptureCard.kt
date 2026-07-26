package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.media.MediaConsumerCaptureSummary
import com.mikeyphw.xdm.android.media.MediaConsumerState
import com.mikeyphw.xdm.android.media.MediaConsumerWorkspacePlanner
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.util.formatBytes

@Composable
@UiSurface(UiAudience.User, "Review one media capture and select tracks")
internal fun MediaCaptureCard(
    capture: MediaCaptureRecord,
    captureVariants: List<MediaVariant>,
    persistedSelection: MediaTrackSelection,
    consumerPlanner: MediaConsumerWorkspacePlanner,
    onDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onTrackSelectionChanged: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
) {
    var detailsVisible by rememberSaveable(capture.id) { mutableStateOf(false) }
    var trackSelection by remember(capture.id) {
        mutableStateOf(persistedSelection.copy(videoVariantId = persistedSelection.videoVariantId ?: capture.selectedVariantId))
    }
    LaunchedEffect(persistedSelection, capture.selectedVariantId) {
        trackSelection = persistedSelection.copy(videoVariantId = persistedSelection.videoVariantId ?: capture.selectedVariantId)
    }

    val summary = remember(capture, captureVariants, trackSelection) {
        consumerPlanner.summarizeCapture(capture, captureVariants, trackSelection)
    }
    val videoVariants = remember(captureVariants) {
        captureVariants.filter { it.kind == MediaVariantKind.Video || it.kind == MediaVariantKind.Primary }
    }

    XdmListCard(
        modifier = Modifier.xdmScreen(XdmScreenTags.MediaCapture, "Media capture ${capture.title.ifBlank { capture.fileName }}"),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            XdmFileTypeIcon(capture.fileName, mimeType = capture.mimeType, contentDescription = capture.kind.uiLabel())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                XdmCardTitle(capture.title.ifBlank { capture.fileName }, maxLines = 2)
                XdmMetadataText(mediaOriginLabel(capture), maxLines = 1)
                XdmSupportingText(
                    listOfNotNull(
                        capture.durationMs?.let(::formatDurationSeconds),
                        capture.container,
                        capture.kind.uiLabel(),
                    ).joinToString(" • ").ifBlank { "Media details will appear after checking this page." },
                    maxLines = 2,
                )
            }
            StatusPill(captureStateLabel(summary.state), toneForConsumerState(summary.state))
        }

        if (videoVariants.isNotEmpty()) {
            XdmMetadataText("Quality")
            XdmActionFlowRow {
                videoVariants.take(4).forEach { variant ->
                    val selected = trackSelection.videoVariantId == variant.id ||
                        (trackSelection.videoVariantId == null && capture.selectedVariantId == variant.id)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = trackSelection.copy(videoVariantId = variant.id)
                            trackSelection = next
                            onTrackSelectionChanged(capture, next)
                            onSelectVariant(capture, variant.id)
                        },
                        label = { Text(variant.qualityLabel) },
                    )
                }
                if (videoVariants.size > 4) {
                    FilterChip(
                        selected = false,
                        onClick = { detailsVisible = true },
                        label = { Text("${videoVariants.size - 4} more") },
                    )
                }
            }
        }

        XdmGroupedList(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            XdmListRow(
                headline = summary.selectedQuality,
                supporting = "Selected quality",
            )
            XdmListSeparator(modifier = Modifier.padding(start = 0.dp))
            XdmListRow(
                headline = summary.trackSummary,
                supporting = "Audio and subtitles",
            )
            summary.estimatedSizeBytes?.let { size ->
                XdmListSeparator(modifier = Modifier.padding(start = 0.dp))
                XdmListRow(
                    headline = "About ${size.formatBytes()}",
                    supporting = "Estimated download size",
                )
            }
        }

        summary.notice?.let { notice ->
            XdmNoticeRow(
                text = notice,
                tone = when (summary.state) {
                    MediaConsumerState.Failed, MediaConsumerState.Protected -> XdmStatusTone.Error
                    MediaConsumerState.NeedsRefresh, MediaConsumerState.NeedsResolution -> XdmStatusTone.Warning
                    MediaConsumerState.Added -> XdmStatusTone.Success
                    MediaConsumerState.Ready -> XdmStatusTone.Info
                },
            )
        }

        XdmActionFlowRow {
            when (summary.state) {
                MediaConsumerState.Ready -> Button(
                    onClick = { onDownload(capture, trackSelection) },
                    enabled = summary.canDownload,
                ) { Text("Download") }
                MediaConsumerState.NeedsRefresh,
                MediaConsumerState.NeedsResolution,
                MediaConsumerState.Failed -> Button(onClick = { onResolve(capture) }) {
                    Text(summary.primaryActionLabel)
                }
                MediaConsumerState.Added -> StatusPill("Added", tone = XdmStatusTone.Success)
                MediaConsumerState.Protected -> Button(onClick = { detailsVisible = true }) { Text("View details") }
            }
            TextButton(onClick = { detailsVisible = true }) { Text("More") }
        }
    }

    MediaTrackPickerSheet(
        visible = detailsVisible,
        capture = capture,
        variants = captureVariants,
        selection = trackSelection,
        summary = summary,
        onDismiss = { detailsVisible = false },
        onSelectionChanged = { next ->
            trackSelection = next
            onTrackSelectionChanged(capture, next)
            next.videoVariantId?.let { onSelectVariant(capture, it) }
        },
        onResolve = {
            detailsVisible = false
            onResolve(capture)
        },
        onRemove = {
            detailsVisible = false
            onRemove(capture)
        },
    )
}

@Composable
private fun MediaTrackPickerSheet(
    visible: Boolean,
    capture: MediaCaptureRecord,
    variants: List<MediaVariant>,
    selection: MediaTrackSelection,
    summary: MediaConsumerCaptureSummary,
    onDismiss: () -> Unit,
    onSelectionChanged: (MediaTrackSelection) -> Unit,
    onResolve: () -> Unit,
    onRemove: () -> Unit,
) {
    XdmAdaptiveSheet(
        visible = visible,
        windowClass = LocalXdmWindowClass.current,
        onDismissRequest = onDismiss,
        title = "Media options",
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().xdmScreen(XdmScreenTags.MediaTrackSheet, "Media track selection"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                XdmListCard(compact = true) {
                    XdmCardTitle(capture.title.ifBlank { capture.fileName }, maxLines = 2)
                    XdmMetadataText(mediaOriginLabel(capture), maxLines = 1)
                    XdmSupportingText(summary.trackSummary, maxLines = 2)
                }
            }
            trackGroup(
                title = "Video quality",
                variants = variants.filter { it.kind == MediaVariantKind.Video || it.kind == MediaVariantKind.Primary },
                selectedId = selection.videoVariantId,
                onSelect = { onSelectionChanged(selection.copy(videoVariantId = it.id)) },
            )
            trackGroup(
                title = "Audio track",
                variants = variants.filter { it.kind == MediaVariantKind.Audio },
                selectedId = selection.audioVariantId,
                onSelect = { onSelectionChanged(selection.copy(audioVariantId = it.id)) },
            )
            val subtitles = variants.filter { it.kind == MediaVariantKind.Subtitle }
            if (subtitles.isNotEmpty()) {
                item {
                    XdmListCard(compact = true) {
                        XdmMetadataText("Subtitle track")
                        FilterChip(
                            selected = selection.subtitleVariantId == null,
                            onClick = { onSelectionChanged(selection.copy(subtitleVariantId = null)) },
                            label = { Text("None") },
                        )
                    }
                }
                items(subtitles, key = MediaVariant::id) { variant ->
                    VariantSelectorRow(
                        variant = variant,
                        selected = selection.subtitleVariantId == variant.id,
                        onSelect = { onSelectionChanged(selection.copy(subtitleVariantId = variant.id)) },
                    )
                }
            }
            item {
                XdmTechnicalDetails(label = "Source details") {
                    XdmMetadataText("Type: ${capture.kind.uiLabel()}")
                    capture.durationMs?.let { XdmMetadataText("Duration: ${formatDurationSeconds(it)}") }
                    capture.mimeType?.let { XdmMetadataText("Format: $it") }
                    XdmMetadataText("Session values and temporary media links remain hidden.")
                }
            }
            item {
                XdmActionFlowRow(Modifier.padding(bottom = 20.dp)) {
                    TextButton(onClick = onResolve) { Text("Check again") }
                    TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackGroup(
    title: String,
    variants: List<MediaVariant>,
    selectedId: String?,
    onSelect: (MediaVariant) -> Unit,
) {
    if (variants.isEmpty()) return
    item { XdmSectionLabel(title) }
    items(variants, key = MediaVariant::id) { variant ->
        VariantSelectorRow(variant, selectedId == variant.id) { onSelect(variant) }
    }
}

@Composable
internal fun VariantSelectorRow(variant: MediaVariant, selected: Boolean, onSelect: () -> Unit) {
    XdmGroupedList {
        XdmListRow(
            headline = variant.qualityLabel,
            supporting = variantDetails(variant),
            trailing = {
                FilterChip(
                    selected = selected,
                    onClick = onSelect,
                    label = { Text(if (selected) "Selected" else "Select") },
                )
            },
            onClick = onSelect,
        )
    }
}

private fun captureStateLabel(state: MediaConsumerState): String = when (state) {
    MediaConsumerState.Ready -> "Ready"
    MediaConsumerState.NeedsResolution -> "Check needed"
    MediaConsumerState.NeedsRefresh -> "Refresh needed"
    MediaConsumerState.Failed -> "Could not read"
    MediaConsumerState.Added -> "Added"
    MediaConsumerState.Protected -> "Protected"
}

private fun toneForConsumerState(state: MediaConsumerState): XdmStatusTone = when (state) {
    MediaConsumerState.Ready, MediaConsumerState.Added -> XdmStatusTone.Success
    MediaConsumerState.NeedsResolution, MediaConsumerState.NeedsRefresh -> XdmStatusTone.Warning
    MediaConsumerState.Failed, MediaConsumerState.Protected -> XdmStatusTone.Error
}
