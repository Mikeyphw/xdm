package com.mikeyphw.xdm.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import com.mikeyphw.xdm.android.model.BackendRecommendation
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.HistoryManagementPolicy
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProtocolExpansionReport
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.ReleasePackagingReport
import com.mikeyphw.xdm.android.model.displayName
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import com.mikeyphw.xdm.android.model.redactedDiagnosticLine
import com.mikeyphw.xdm.android.model.ReleaseReadinessSeverity
import com.mikeyphw.xdm.android.model.FinalReleaseGateSeverity
import com.mikeyphw.xdm.android.model.ReleaseSecuritySeverity
import com.mikeyphw.xdm.android.util.formatBytes
import com.mikeyphw.xdm.android.util.formatSpeed

@Composable
fun DownloadsScreen(
    downloads: List<Download>,
    compact: Boolean,
    active: ActiveTransferSummary,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    historyReport: HistoryManagementReport,
    onTogglePause: (Download) -> Unit,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
    onClearFinishedHistory: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf<DownloadState?>(null) }
    Column(Modifier.fillMaxSize()) {
        DownloadListSummary(downloads = downloads, active = active)
        HistoryManagementCard(
            report = historyReport,
            historyText = HistoryManagementPolicy.exportIndex(downloads),
            onCopyHistory = { copyTextToClipboard(context, "XDM history index", HistoryManagementPolicy.exportIndex(downloads)) },
            onClearFinished = onClearFinishedHistory,
        )
        if (active.activeCount > 0) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("${active.activeCount} active download${if (active.activeCount == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                        Text(active.speedBytesPerSecond.formatSpeed(), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onPauseAll) { Text("Pause all") }
                }
            }
        } else if (downloads.any { it.state == DownloadState.Paused }) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Paused downloads are ready to continue")
                    Button(onClick = onResumeAll) { Text("Resume all") }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("All") },
                modifier = Modifier.semantics { stateDescription = if (filter == null) "All downloads selected" else "All downloads not selected" },
            )
            listOf(DownloadState.Downloading, DownloadState.Queued, DownloadState.Completed, DownloadState.Failed).forEach { state ->
                FilterChip(
                    selected = filter == state,
                    onClick = { filter = state },
                    label = { Text(state.name) },
                    modifier = Modifier.semantics { stateDescription = if (filter == state) "${state.name} downloads selected" else "${state.name} downloads not selected" },
                )
            }
        }
        val visible = downloads.filter { filter == null || it.state == filter }
        if (visible.isEmpty()) {
            EmptyFeatureScreen("No downloads", "Add a URL to create the first download.")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
                items(visible, key = Download::id) { download ->
                    DownloadCard(download, compact, capabilities, checksumResults, verificationRecords, onTogglePause, onMigrateBackend, onRemoveHistory)
                }
            }
        }
    }
}


@Composable
private fun HistoryManagementCard(
    report: HistoryManagementReport,
    historyText: String,
    onCopyHistory: () -> Unit,
    onClearFinished: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("History management", modifier = Modifier.semantics { heading() }, fontWeight = FontWeight.SemiBold)
            Text(report.summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onCopyHistory, enabled = historyText.isNotBlank()) { Text("Copy history index") }
                TextButton(onClick = onClearFinished, enabled = report.removableHistory > 0) { Text("Clear finished history") }
            }
            Text("History actions only remove app records; downloaded files stay in their destination.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DownloadListSummary(downloads: List<Download>, active: ActiveTransferSummary) {
    val failed = downloads.count { it.state == DownloadState.Failed || it.state == DownloadState.RecoveryRequired }
    val completed = downloads.count { it.state == DownloadState.Completed }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Download overview", modifier = Modifier.semantics { heading() }, fontWeight = FontWeight.SemiBold)
            Text(
                "${downloads.size} total • ${active.activeCount} active • $completed complete • $failed need attention",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DownloadCard(
    download: Download,
    compact: Boolean,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    onTogglePause: (Download) -> Unit,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
) {
    val context = LocalContext.current
    Card(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = download.accessibilitySummary() },
    ) {
        Column(Modifier.padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(download.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${download.state.name} • ${download.backend.name}", style = MaterialTheme.typography.bodySmall)
                    if (download.backendSelectionExplanation.isNotBlank()) {
                        Text(download.backendSelectionExplanation, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    download.mimeType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") || it.contains("mpegurl") || it.contains("dash") }?.let {
                        Text("Media type: $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (download.state in setOf(DownloadState.Downloading, DownloadState.Connecting, DownloadState.Paused, DownloadState.Failed)) {
                    IconButton(
                        onClick = { onTogglePause(download) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        val paused = download.state == DownloadState.Paused || download.state == DownloadState.Failed
                        val action = if (paused) "Resume" else "Pause"
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "$action ${download.fileName}")
                    }
                }
            }
            val totalBytes = download.totalBytes
            if (totalBytes != null) {
                LinearProgressIndicator(
                    progress = { download.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = download.progressAccessibilitySummary() },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${download.bytesReceived.formatBytes()} / ${totalBytes.formatBytes()}", style = MaterialTheme.typography.bodySmall)
                    if (download.speedBytesPerSecond > 0) Text(download.speedBytesPerSecond.formatSpeed(), style = MaterialTheme.typography.bodySmall)
                }
            }
            val latestVerification = verificationRecords.firstOrNull { it.downloadId == download.id }
            val latestChecksum = checksumResults.firstOrNull { it.downloadId == download.id }
            if (download.state == DownloadState.Verifying || download.state == DownloadState.Repairing || latestVerification != null || latestChecksum != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val status = latestVerification?.status ?: if (download.state == DownloadState.Verifying) VerificationStatus.Running else VerificationStatus.Pending
                        Text("Verification: ${status.name}", fontWeight = FontWeight.Medium)
                        latestChecksum?.let { checksum ->
                            val result = when (checksum.matchesExpectation) {
                                true -> "match"
                                false -> "mismatch"
                                null -> "recorded"
                            }
                            Text("${checksum.algorithm.name}: $result", style = MaterialTheme.typography.bodySmall)
                        }
                        latestVerification?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            val targetBackend = when (download.backend) {
                BackendType.Native -> BackendType.Aria2
                BackendType.Aria2 -> BackendType.Native
                BackendType.Automatic -> null
            }
            val targetCapability = capabilities.firstOrNull { it.backend == targetBackend }
            val destinationScheme = download.destinationUri.substringBefore(':').lowercase()
            val documentDestination = destinationScheme in setOf("content", "xdm")
            val targetCompatible = targetCapability?.available == true &&
                (!documentDestination || targetCapability.saf)
            if (
                download.state in setOf(DownloadState.Paused, DownloadState.Failed, DownloadState.RecoveryRequired) &&
                targetBackend != null &&
                targetCompatible
            ) {
                val target = if (targetBackend == BackendType.Aria2) "aria2" else "XDM Native"
                Button(
                    onClick = { onMigrateBackend(download) },
                    modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
                ) {
                    Text(if (download.bytesReceived > 0) "Restart with $target" else "Switch to $target")
                }
                if (download.bytesReceived > 0) {
                    Text("Existing partial bytes are preserved for recovery and are not reused silently.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (download.state in setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { copyTextToClipboard(context, "XDM source URL", download.sourceUrl) }) { Text("Copy URL") }
                    TextButton(onClick = { copyTextToClipboard(context, "XDM file info", download.fileManagementSummary()) }) { Text("Copy file info") }
                    TextButton(onClick = { onRemoveHistory(download) }) { Text("Remove history") }
                }
            }
            download.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun AddDownloadScreen(
    destinationUri: String,
    conflictPolicy: FilenameConflictPolicy,
    savedDestinations: List<DestinationPermission>,
    onDestinationChanged: (String) -> Unit,
    onSafDestinationSelected: (String) -> Unit,
    onConflictPolicyChanged: (FilenameConflictPolicy) -> Unit,
    onAdd: (String, String, BackendType, String, FilenameConflictPolicy, Boolean, String, ChecksumAlgorithm) -> Unit,
    recommend: (String, String, BackendType, String, FilenameConflictPolicy, Boolean) -> BackendRecommendation,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf(BackendType.Automatic) }
    var allowFallback by remember { mutableStateOf(true) }
    var expectedChecksum by remember { mutableStateOf("") }
    var checksumAlgorithm by remember { mutableStateOf(ChecksumAlgorithm.Sha256) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onSafDestinationSelected(it.toString()) }
    }
    val recommendation = url.takeIf(String::isNotBlank)?.let {
        recommend(url, name, backend, destinationUri, conflictPolicy, allowFallback)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("New download", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(
            name,
            { name = it },
            label = { Text("Filename") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Optional. XDM will infer a name from the URL when this is empty.") },
        )
        Text("Destination", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DestinationCatalog.available(Build.VERSION.SDK_INT).forEach { choice ->
                FilterChip(selected = destinationUri == choice.uri, onClick = { onDestinationChanged(choice.uri) }, label = { Text(choice.label) })
            }
            savedDestinations.forEach { destination ->
                FilterChip(selected = destinationUri == destination.uri, onClick = { onDestinationChanged(destination.uri) }, label = { Text(destination.displayName) })
            }
        }
        Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder or SD card") }
        Text("Existing filename", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilenameConflictPolicy.entries.forEach { value ->
                FilterChip(selected = conflictPolicy == value, onClick = { onConflictPolicyChanged(value) }, label = { Text(value.name) })
            }
        }
        Text("Backend", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendType.entries.forEach { value ->
                FilterChip(selected = backend == value, onClick = { backend = value }, label = { Text(value.name) })
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compatible fallback", fontWeight = FontWeight.Medium)
                    Text("Fallback is allowed only before a backend task owns the destination.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(allowFallback, { allowFallback = it })
            }
        }
        Text("Verification", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            expectedChecksum,
            { expectedChecksum = it },
            label = { Text("Expected checksum") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Optional SHA-256 or SHA-512. A matching checksum is required before final completion.") },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChecksumAlgorithm.entries.forEach { value ->
                FilterChip(selected = checksumAlgorithm == value, onClick = { checksumAlgorithm = value }, label = { Text(value.name) })
            }
        }

        recommendation?.let { recommendation ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recommended: ${recommendation.backend.name}", fontWeight = FontWeight.Medium)
                    Text(recommendation.explanation, style = MaterialTheme.typography.bodySmall)
                    if (!recommendation.compatible) {
                        Text(
                            recommendation.compatibilityIssue ?: "This backend cannot start the transfer.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        val fallbackBackend = recommendation.fallbackBackend
                        if (recommendation.fallbackAllowed && fallbackBackend != null) {
                            Text("Fallback: ${fallbackBackend.name}, before task creation only", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Button(
            onClick = { onAdd(url, name, backend, destinationUri, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm) },
            enabled = url.isNotBlank() && destinationUri.isNotBlank() && recommendation?.compatible != false,
        ) { Text("Add to Default queue") }
    }
}

@Composable
fun QueuesScreen(queues: List<QueueDefinition>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(queues, key = QueueDefinition::id) { queue ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(queue.name, fontWeight = FontWeight.SemiBold)
                        Text("Up to ${queue.maxConcurrent} concurrent downloads", style = MaterialTheme.typography.bodySmall)
                    }
                    StatusPill(if (queue.isEnabled) "Enabled" else "Disabled")
                }
            }
        }
    }
}

@Composable
fun SchedulerScreen(rules: List<ScheduleRule>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rules, key = ScheduleRule::id) { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rule.name, fontWeight = FontWeight.SemiBold)
                    Text(if (rule.enabled) "Enabled" else "Disabled")
                    Text(rule.constraintsJson, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


@Composable
fun MediaInboxScreen(
    captures: List<MediaCaptureRecord>,
    variants: List<MediaVariant>,
    onDownload: (MediaCaptureRecord) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
) {
    if (captures.isEmpty()) {
        EmptyFeatureScreen("Media inbox", "Share a video, audio, HLS, or DASH URL to capture metadata and queue it safely.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(captures, key = MediaCaptureRecord::id) { capture ->
            val captureVariants = variants.filter { it.captureId == capture.id }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(capture.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(capture.sourceUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        StatusPill(capture.kind.name)
                    }
                    Text(
                        listOfNotNull(
                            capture.mimeType,
                            capture.container,
                            "${capture.variantCount} variant${if (capture.variantCount == 1) "" else "s"}",
                            capture.durationMs?.let { "${it / 1000}s" },
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(capture.status.name)
                        StatusPill(capture.resolutionStatus.name)
                        capture.downloadId?.let { StatusPill("Queued") }
                    }
                    if (captureVariants.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Variants", style = MaterialTheme.typography.labelLarge)
                            captureVariants.take(4).forEach { variant ->
                                val selected = capture.selectedVariantId == variant.id
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectVariant(capture, variant.id) },
                                    label = { Text(if (selected) "${variant.qualityLabel} selected" else variant.qualityLabel) },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onDownload(capture) },
                            enabled = capture.status != MediaCaptureStatus.DownloadCreated && capture.resolutionStatus != MediaResolutionStatus.RequiresRefresh,
                        ) { Text(if (capture.status == MediaCaptureStatus.DownloadCreated) "Added" else "Download") }
                        TextButton(onClick = { onResolve(capture) }) { Text(if (capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh) "Refresh" else "Resolve") }
                        TextButton(onClick = { onRemove(capture) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
fun RecoveryScreen(records: List<RecoveryRecord>, onValidate: (RecoveryRecord) -> Unit, onRemove: (RecoveryRecord) -> Unit) {
    if (records.isEmpty()) {
        EmptyFeatureScreen("Recovery is clear", "No orphaned or interrupted artifacts were detected.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(records, key = RecoveryRecord::id) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(record.classification.name, fontWeight = FontWeight.SemiBold)
                    Text(record.artifactPath, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(record.reason, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(record.recommendedAction.name)
                        StatusPill(if (record.safeToResume) "Safe resume" else "Paused")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onValidate(record) }) { Text(if (record.safeToResume) "Resume" else "Validate") }
                        TextButton(onClick = { onRemove(record) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(state: MainUiState, onRunAria2SmokeTest: () -> Unit) {
    val context = LocalContext.current
    val redactedSummary = PrivacyDiagnosticsRedactor.redactedHealthSummary(
        report = state.releaseSecurityReport,
        downloadCount = state.downloads.size,
        mediaCaptureCount = state.mediaCaptures.size,
        automationCount = state.automationCommands.size,
        rejectedHandoffCount = state.automationCommands.count { it.status.name == "Rejected" },
    )
    val installUpdateSummary = state.installUpdateReadinessReport.redactedSummary()
    val finalReleaseSummary = state.finalReleaseGateReport.redactedSummary()
    val supportSummary = "$redactedSummary\n\n$installUpdateSummary\n\n$finalReleaseSummary"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Runtime health", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Release safety", fontWeight = FontWeight.Medium)
                            Text(state.releaseSecurityReport.summary, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            onClick = { copyTextToClipboard(context, "XDM diagnostic summary", supportSummary) },
                            modifier = Modifier
                                .sizeIn(minWidth = 96.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Copy privacy-safe release summary" },
                        ) { Text("Copy summary") }
                    }
                    state.releaseSecurityReport.findings.take(3).forEach { finding ->
                        val severity = when (finding.severity) {
                            ReleaseSecuritySeverity.Info -> "Info"
                            ReleaseSecuritySeverity.Warning -> "Warning"
                            ReleaseSecuritySeverity.Blocking -> "Blocked"
                        }
                        Text("$severity: ${finding.title}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Install/update readiness", fontWeight = FontWeight.Medium)
                    Text(state.installUpdateReadinessReport.summary, style = MaterialTheme.typography.bodySmall)
                    state.installUpdateReadinessReport.checks.take(4).forEach { check ->
                        val severity = when (check.severity) {
                            ReleaseReadinessSeverity.Info -> "Info"
                            ReleaseReadinessSeverity.Warning -> "Warning"
                            ReleaseReadinessSeverity.Blocking -> "Blocked"
                        }
                        Text("$severity: ${check.title}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Package identity and Room schema are checked before beta packaging.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Final release gate ${state.finalReleaseGateReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Final release gate", fontWeight = FontWeight.Medium)
                    Text(state.finalReleaseGateReport.summary, style = MaterialTheme.typography.bodySmall)
                    state.finalReleaseGateReport.checks.take(4).forEach { check ->
                        val severity = when (check.severity) {
                            FinalReleaseGateSeverity.Info -> "Info"
                            FinalReleaseGateSeverity.Warning -> "Warning"
                            FinalReleaseGateSeverity.Blocking -> "Blocked"
                        }
                        Text("$severity: ${check.title}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Public release requires the full devtool validation gate, release docs, signed release verification, and artifact checksum records.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { DiagnosticLine("Desktop parity", state.desktopParityReport.summary) }
        item { DiagnosticLine("Protocol coverage", state.protocolExpansionReport.summary) }
        item { DiagnosticLine("Release packaging", state.releasePackagingReport.summary) }
        item { DiagnosticLine("Settings exchange", "Import/export snapshot is available from Settings") }
        item { DiagnosticLine("Database", "Room schema v13") }
        item { DiagnosticLine("Downloads", state.downloads.size.toString()) }
        item { DiagnosticLine("Queues", state.queues.size.toString()) }
        item { DiagnosticLine("Recovery records", state.recovery.size.toString()) }
        item { DiagnosticLine("Finalization journals", state.finalizationJournals.count { it.needsRecovery }.toString()) }
        item { DiagnosticLine("Media captures", state.mediaCaptures.size.toString()) }
        item { DiagnosticLine("Media variants", state.mediaVariants.size.toString()) }
        item { DiagnosticLine("Automation commands", state.automationCommands.size.toString()) }
        item { DiagnosticLine("Browser origins", state.automationCommands.mapNotNull { it.originHost }.distinct().size.toString()) }
        item { DiagnosticLine("Rejected handoffs", state.automationCommands.count { it.status.name == "Rejected" }.toString()) }
        if (state.automationCommands.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Recent browser handoffs", fontWeight = FontWeight.Medium)
                        state.automationCommands.take(4).forEach { command ->
                            Text(command.redactedDiagnosticLine(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { DiagnosticLine("Native backend", "HTTP/HTTPS, checkpoints, resume and segmentation") }
        item { DiagnosticLine("Execution", "UIDT on Android 14+, foreground dataSync fallback") }
        item { DiagnosticLine("Active transfers", state.activeTransfers.activeCount.toString()) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("aria2 runtime", fontWeight = FontWeight.Medium)
                            Text(state.aria2Diagnostics.status, style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = onRunAria2SmokeTest,
                            enabled = state.aria2Diagnostics.canRunSmokeTest,
                        ) {
                            Text(if (state.aria2Diagnostics.smokeTestRunning) "Testing…" else "Run probe")
                        }
                    }
                    Text(state.aria2Diagnostics.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "$label: $value" }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, modifier = Modifier.weight(0.35f), fontWeight = FontWeight.Medium)
            Text(
                value,
                modifier = Modifier.weight(0.65f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SettingsScreen(
    compact: Boolean,
    capabilities: List<BackendCapabilityRow>,
    migrations: List<BackendMigrationRecord>,
    installUpdateReadinessReport: com.mikeyphw.xdm.android.model.InstallUpdateReadinessReport,
    finalReleaseGateReport: com.mikeyphw.xdm.android.model.FinalReleaseGateReport,
    proxySettings: ProxyCredentialSettings,
    postProcessingSettings: PostProcessingSettings,
    settingsExportText: String,
    protocolExpansionReport: ProtocolExpansionReport,
    releasePackagingReport: ReleasePackagingReport,
    onCompactChanged: (Boolean) -> Unit,
    onProxyChanged: (ProxyCredentialSettings) -> Unit,
    onPostProcessingChanged: (PostProcessingSettings) -> Unit,
    onImportSettings: (String) -> Unit,
) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var proxyEnabled by remember(proxySettings) { mutableStateOf(proxySettings.enabled) }
    var proxyHost by remember(proxySettings) { mutableStateOf(proxySettings.host) }
    var proxyPort by remember(proxySettings) { mutableStateOf(proxySettings.port?.toString().orEmpty()) }
    var proxyUsername by remember(proxySettings) { mutableStateOf(proxySettings.username) }
    var proxyAlias by remember(proxySettings) { mutableStateOf(proxySettings.credentialAlias) }
    var postEnabled by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.enabled) }
    var postPreset by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.preset) }
    var postLabel by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.customCommandLabel) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Appearance", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compact download cards", fontWeight = FontWeight.Medium)
                        Text("Reduce vertical spacing in the download list.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = compact,
                        onCheckedChange = onCompactChanged,
                        modifier = Modifier.semantics { stateDescription = if (compact) "Compact cards enabled" else "Compact cards disabled" },
                    )
                }
            }
        }
        item { Text("Settings import/export", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Settings import export snapshot" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Portable settings snapshot", fontWeight = FontWeight.Medium)
                    Text("Exports layout density, destination defaults, filename conflict policy, proxy metadata, and post-processing choices. Secrets are not exported.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copyTextToClipboard(context, "XDM settings snapshot", settingsExportText) }) { Text("Copy export") }
                    OutlinedTextField(
                        importText,
                        { importText = it },
                        label = { Text("Paste settings snapshot") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                    )
                    Button(onClick = { onImportSettings(importText); importText = "" }, enabled = importText.isNotBlank()) { Text("Import snapshot") }
                }
            }
        }
        item { Text("Proxy and credentials", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Proxy credential profile ${proxySettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Proxy profile", fontWeight = FontWeight.Medium)
                            Text(proxySettings.redactedSummary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(proxyEnabled, { proxyEnabled = it })
                    }
                    OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(proxyPort, { proxyPort = it.filter { char -> char.isDigit() }.take(5) }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(proxyUsername, { proxyUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(proxyAlias, { proxyAlias = it }, label = { Text("Credential alias") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("Store a label only; passwords stay outside exported diagnostics and settings snapshots.") })
                    Button(onClick = { onProxyChanged(ProxyCredentialSettings(proxyEnabled, proxyHost, proxyPort.toIntOrNull()?.takeIf { it in 1..65535 }, proxyUsername, proxyAlias)) }) { Text("Save proxy profile") }
                }
            }
        }
        item { Text("Conversion and post-processing", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Conversion post processing ${postProcessingSettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Post-processing hook", fontWeight = FontWeight.Medium)
                            Text(postProcessingSettings.redactedSummary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(postEnabled, { postEnabled = it })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConversionPreset.entries.forEach { preset ->
                            FilterChip(selected = postPreset == preset, onClick = { postPreset = preset }, label = { Text(preset.displayName()) })
                        }
                    }
                    OutlinedTextField(postLabel, { postLabel = it }, label = { Text("Custom label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { onPostProcessingChanged(PostProcessingSettings(postEnabled, postPreset, postLabel)) }) { Text("Save post-processing") }
                    Text("This is the app-facing policy surface. Actual conversion execution remains gated behind explicit backend/package support.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Protocol expansion", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Protocol expansion ${protocolExpansionReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(protocolExpansionReport.summary, fontWeight = FontWeight.Medium)
                    protocolExpansionReport.rows.forEach { row -> Text("${row.protocol.uppercase()}: ${row.recommendation}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { Text("Accessibility and layout", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Touch targets, compact phones, and action labels are ready" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phase 15 polish", fontWeight = FontWeight.Medium)
                    Text("Primary actions keep 48 dp touch targets, long names truncate before controls, and filters/actions expose state descriptions for screen readers.", style = MaterialTheme.typography.bodySmall)
                    Text("Compact-phone layout keeps downloads, Diagnostics, and Settings inside existing routes.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Packaging and recovery", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Install update readiness ${installUpdateReadinessReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phase 16 readiness", fontWeight = FontWeight.Medium)
                    Text(installUpdateReadinessReport.summary, style = MaterialTheme.typography.bodySmall)
                    Text("Package identity stays com.mikeyphw.xdm.android, update builds keep Room schema v13, and recovery surfaces remain inside the existing Recovery and Diagnostics routes.", style = MaterialTheme.typography.bodySmall)
                    Text("Beta packaging keeps the aria2 runtime payload gate and privacy-safe diagnostic bundle.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Final release gate ${finalReleaseGateReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phase 17 final gate", fontWeight = FontWeight.Medium)
                    Text(finalReleaseGateReport.summary, style = MaterialTheme.typography.bodySmall)
                    Text("The last overlay does not add routes or database migrations; it locks the public release gate around full validation, release docs, signed APK verification, and artifact checksums.", style = MaterialTheme.typography.bodySmall)
                    Text("Use the full devtool --validate pass for the final release, not the medium selected-task gate.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Release packaging ${releasePackagingReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Release/non-debug APK packaging", fontWeight = FontWeight.Medium)
                    Text("Version ${releasePackagingReport.versionName} (${releasePackagingReport.versionCode})", style = MaterialTheme.typography.bodySmall)
                    Text("Release package: ${releasePackagingReport.packageId}", style = MaterialTheme.typography.bodySmall)
                    Text("Beta package: ${releasePackagingReport.betaPackageId}", style = MaterialTheme.typography.bodySmall)
                    Text("Run ${releasePackagingReport.checksumScript} to assemble release/beta APKs and write SHA-256 checksum files.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Backend strategy", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
        items(capabilities, key = { it.backend.name }) { capability ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (capability.backend == BackendType.Native) "XDM Native" else "aria2", fontWeight = FontWeight.SemiBold)
                        StatusPill(if (capability.available) "Available" else "Unavailable")
                    }
                    Text(capability.summary, style = MaterialTheme.typography.bodySmall)
                    Text("Protocols: ${capability.protocols.sorted().joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        listOfNotNull(
                            "Segments".takeIf { capability.segmentation },
                            "Mirrors".takeIf { capability.mirrors },
                            "Metalink".takeIf { capability.metalink },
                            "SAF".takeIf { capability.saf },
                            "Repair".takeIf { capability.selectiveRepair },
                            "Media".takeIf { capability.media },
                        ).joinToString(" • ").ifBlank { "No optional capabilities" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text("Diagnostics: ${capability.diagnosticDetail.name} • Battery: ${capability.batteryImpact.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (migrations.isNotEmpty()) {
            item { Text("Recent backend migrations", style = MaterialTheme.typography.headlineSmall) }
            items(migrations.take(5), key = BackendMigrationRecord::id) { migration ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${migration.sourceBackend.name} → ${migration.targetBackend.name}", fontWeight = FontWeight.Medium)
                        Text(migration.stage.name, style = MaterialTheme.typography.labelLarge)
                        Text(migration.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Text("Package: com.mikeyphw.xdm.android", style = MaterialTheme.typography.bodySmall) }
        item { Text("Version: 0.18.0-rc01", style = MaterialTheme.typography.bodySmall) }
    }
}


private fun copyTextToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun Download.accessibilitySummary(): String = buildString {
    append(fileName)
    append(", ")
    append(state.name.lowercase())
    append(" using ")
    append(backend.name)
    totalBytes?.let { total ->
        append(", ")
        append(bytesReceived.formatBytes())
        append(" of ")
        append(total.formatBytes())
    }
}

private fun Download.progressAccessibilitySummary(): String {
    val total = totalBytes ?: return "Progress unavailable"
    val percent = (progressFraction * 100).toInt().coerceIn(0, 100)
    return "$percent percent, ${bytesReceived.formatBytes()} of ${total.formatBytes()}"
}

@Composable
fun EmptyFeatureScreen(title: String, description: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Download.fileManagementSummary(): String = buildString {
    appendLine("File: $fileName")
    appendLine("State: ${state.name}")
    appendLine("Backend: ${backend.name}")
    appendLine("URL: $sourceUrl")
    appendLine("Destination: $destinationUri")
    appendLine("Progress: ${bytesReceived.formatBytes()}${totalBytes?.let { " / ${it.formatBytes()}" } ?: ""}")
    mimeType?.takeIf { it.isNotBlank() }?.let { appendLine("MIME type: $it") }
    userLabel?.takeIf { it.isNotBlank() }?.let { appendLine("Label: $it") }
    errorMessage?.takeIf { it.isNotBlank() }?.let { appendLine("Last error: $it") }
}.trimEnd()
