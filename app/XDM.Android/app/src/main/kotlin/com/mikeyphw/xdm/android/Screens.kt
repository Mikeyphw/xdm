package com.mikeyphw.xdm.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
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
import com.mikeyphw.xdm.android.termux.TermuxRootMode
import com.mikeyphw.xdm.android.termux.TermuxBridgeStatus


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
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DownloadSort.Attention) }
    var showHistoryTools by remember { mutableStateOf(false) }
    val visible = downloads
        .filter { download -> filter == null || download.state == filter }
        .filter { download -> query.isBlank() || download.matchesQuery(query) }
        .sortForUi(sort)

    Column(Modifier.fillMaxSize()) {
        DownloadListSummary(
            downloads = downloads,
            active = active,
            historyReport = historyReport,
            showHistoryTools = showHistoryTools,
            onToggleHistoryTools = { showHistoryTools = !showHistoryTools },
            onCopyHistory = { copyTextToClipboard(context, "XDM history index", HistoryManagementPolicy.exportIndex(downloads)) },
            onClearFinished = onClearFinishedHistory,
            onPauseAll = onPauseAll,
            onResumeAll = onResumeAll,
        )
        DownloadListControls(
            query = query,
            onQueryChanged = { query = it },
            filter = filter,
            onFilterChanged = { filter = it },
            sort = sort,
            onSortChanged = { sort = it },
            downloads = downloads,
        )
        if (visible.isEmpty()) {
            val title = if (downloads.isEmpty()) "No downloads" else "No matching downloads"
            val description = if (downloads.isEmpty()) {
                "Add a URL to create the first download."
            } else {
                "Change the search, sort, or state filter to widen the list."
            }
            EmptyFeatureScreen(title, description)
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                items(visible, key = Download::id) { download ->
                    DownloadCard(download, compact, capabilities, checksumResults, verificationRecords, onTogglePause, onMigrateBackend, onRemoveHistory)
                }
            }
        }
    }
}


@Composable
private fun DownloadListSummary(
    downloads: List<Download>,
    active: ActiveTransferSummary,
    historyReport: HistoryManagementReport,
    showHistoryTools: Boolean,
    onToggleHistoryTools: () -> Unit,
    onCopyHistory: () -> Unit,
    onClearFinished: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val failed = downloads.count { it.state == DownloadState.Failed || it.state == DownloadState.RecoveryRequired }
    val completed = downloads.count { it.state == DownloadState.Completed }
    val paused = downloads.count { it.state == DownloadState.Paused }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Download overview", modifier = Modifier.semantics { heading() })
                    XdmSupportingText(
                        "${downloads.size} total • ${active.activeCount} active • $completed complete • $failed need attention",
                        maxLines = 2,
                    )
                }
                if (active.activeCount > 0) {
                    Button(onClick = onPauseAll) { Text("Pause all") }
                } else if (paused > 0) {
                    Button(onClick = onResumeAll) { Text("Resume all") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active.activeCount > 0) XdmStatusBadge("${active.activeCount} active", tone = XdmStatusTone.Info)
                if (active.speedBytesPerSecond > 0) XdmMetricText(active.speedBytesPerSecond.formatSpeed())
                TextButton(onClick = onToggleHistoryTools) { Text(if (showHistoryTools) "Hide history tools" else "History tools") }
            }
            if (showHistoryTools) {
                XdmSupportingText(historyReport.summary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onCopyHistory, enabled = downloads.isNotEmpty()) { Text("Copy history index") }
                    TextButton(onClick = onClearFinished, enabled = historyReport.removableHistory > 0) { Text("Clear finished history") }
                }
                XdmMetadataText("History management only removes app records; downloaded files stay in their destination.")
            }
        }
    }
}

@Composable
private fun DownloadListControls(
    query: String,
    onQueryChanged: (String) -> Unit,
    filter: DownloadState?,
    onFilterChanged: (DownloadState?) -> Unit,
    sort: DownloadSort,
    onSortChanged: (DownloadSort) -> Unit,
    downloads: List<Download>,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            label = { Text("Search downloads") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Search by file name, label, URL, destination, or backend.") },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadFilterChip(
                label = "All ${downloads.size}",
                selected = filter == null,
                onClick = { onFilterChanged(null) },
            )
            listOf(DownloadState.Downloading, DownloadState.Queued, DownloadState.Completed, DownloadState.Failed).forEach { state ->
                val count = downloads.count { it.state == state }
                DownloadFilterChip(
                    label = "${state.uiLabel()} $count",
                    selected = filter == state,
                    onClick = { onFilterChanged(state) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadSort.entries.forEach { value ->
                FilterChip(
                    selected = sort == value,
                    onClick = { onSortChanged(value) },
                    label = { Text(value.label) },
                    modifier = Modifier.semantics { stateDescription = if (sort == value) "Sorted by ${value.label}" else "Not sorted by ${value.label}" },
                )
            }
        }
    }
}

@Composable
private fun DownloadFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.semantics { stateDescription = if (selected) "$label selected" else "$label not selected" },
    )
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
    var expanded by remember(download.id) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = download.accessibilitySummary() },
    ) {
        Column(Modifier.padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle(download.fileName, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        XdmStatusBadge(download.state.uiLabel(), tone = download.state.statusTone())
                        XdmMetadataText(download.backend.uiLabel(), maxLines = 1)
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
                    XdmMetricText("${download.bytesReceived.formatBytes()} / ${totalBytes.formatBytes()}")
                    XdmMetricText(if (download.speedBytesPerSecond > 0) download.speedBytesPerSecond.formatSpeed() else "Waiting")
                }
            } else {
                XdmMetadataText(download.destinationUri, maxLines = 1)
            }
            download.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 2) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
            if (expanded) {
                DownloadDetails(
                    download = download,
                    capabilities = capabilities,
                    checksumResults = checksumResults,
                    verificationRecords = verificationRecords,
                    onMigrateBackend = onMigrateBackend,
                    onRemoveHistory = onRemoveHistory,
                    onCopyUrl = { copyTextToClipboard(context, "XDM source URL", download.sourceUrl) },
                    onCopyFileInfo = { copyTextToClipboard(context, "XDM file info", download.fileManagementSummary()) },
                )
            }
        }
    }
}

@Composable
private fun DownloadDetails(
    download: Download,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
    onCopyUrl: () -> Unit,
    onCopyFileInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        XdmMetadataText("Destination: ${download.destinationUri}", maxLines = 2)
        XdmMetadataText("Source: ${download.sourceUrl}", maxLines = 2)
        if (download.backendSelectionExplanation.isNotBlank()) {
            XdmSupportingText(download.backendSelectionExplanation, maxLines = 3)
        }
        download.mimeType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") || it.contains("mpegurl") || it.contains("dash") }?.let {
            XdmMetadataText("Media type: $it")
        }
        val latestVerification = verificationRecords.firstOrNull { it.downloadId == download.id }
        val latestChecksum = checksumResults.firstOrNull { it.downloadId == download.id }
        if (download.state == DownloadState.Verifying || download.state == DownloadState.Repairing || latestVerification != null || latestChecksum != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val status = latestVerification?.status ?: if (download.state == DownloadState.Verifying) VerificationStatus.Running else VerificationStatus.Pending
                    XdmCardTitle("Verification: ${status.uiLabel()}")
                    latestChecksum?.let { checksum ->
                        val result = when (checksum.matchesExpectation) {
                            true -> "match"
                            false -> "mismatch"
                            null -> "recorded"
                        }
                        XdmMetadataText("${checksum.algorithm.uiLabel()}: $result")
                    }
                    latestVerification?.message?.let { XdmMetadataText(it, maxLines = 2) }
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
        val targetCompatible = targetCapability?.available == true && (!documentDestination || targetCapability.saf)
        if (
            download.state in setOf(DownloadState.Paused, DownloadState.Failed, DownloadState.RecoveryRequired) &&
            targetBackend != null &&
            targetCompatible
        ) {
            val target = targetBackend.uiLabel()
            Button(
                onClick = { onMigrateBackend(download) },
                modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
            ) {
                Text(if (download.bytesReceived > 0) "Restart with $target" else "Switch to $target")
            }
            if (download.bytesReceived > 0) {
                XdmMetadataText("Existing partial bytes are preserved for recovery and are not reused silently.")
            }
        }
        if (download.state in setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onCopyUrl) { Text("Copy URL") }
                TextButton(onClick = onCopyFileInfo) { Text("Copy file info") }
                TextButton(onClick = { onRemoveHistory(download) }) { Text("Remove history") }
            }
        }
    }
}


@Composable
fun AddDownloadScreen(
    destinationUri: String,
    conflictPolicy: FilenameConflictPolicy,
    savedDestinations: List<DestinationPermission>,
    externalDraftId: String? = null,
    initialUrl: String? = null,
    initialFileName: String? = null,
    onDestinationChanged: (String) -> Unit,
    onSafDestinationSelected: (String) -> Unit,
    onConflictPolicyChanged: (FilenameConflictPolicy) -> Unit,
    onAdd: (String, String, BackendType, String, FilenameConflictPolicy, Boolean, String, ChecksumAlgorithm) -> Unit,
    recommend: (String, String, BackendType, String, FilenameConflictPolicy, Boolean) -> BackendRecommendation,
) {
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var name by remember { mutableStateOf(initialFileName.orEmpty()) }
    var backend by remember { mutableStateOf(BackendType.Automatic) }
    var allowFallback by remember { mutableStateOf(true) }
    var expectedChecksum by remember { mutableStateOf("") }
    var checksumAlgorithm by remember { mutableStateOf(ChecksumAlgorithm.Sha256) }
    var advancedExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(externalDraftId) {
        if (externalDraftId != null) {
            url = initialUrl.orEmpty()
            name = initialFileName.orEmpty()
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onSafDestinationSelected(it.toString()) }
    }
    val recommendation = url.takeIf(String::isNotBlank)?.let {
        recommend(url, name, backend, destinationUri, conflictPolicy, allowFallback)
    }
    val canSubmit = url.isNotBlank() && destinationUri.isNotBlank() && recommendation?.compatible != false

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (externalDraftId != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            XdmCardTitle("Link received")
                            XdmSupportingText("Review the shared or browser-provided link, then start the download when ready.")
                        }
                    }
                }
            }
            item {
                XdmSupportingText("Paste a URL, choose where it should land, then start the transfer. Advanced backend and verification controls stay folded until needed.")
            }
            item {
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            item {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Filename") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Optional. XDM will infer a name from the URL when this is empty.") },
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        XdmCardTitle("Destination")
                        XdmMetadataText(destinationUri.ifBlank { "Choose where completed files should be saved." }, maxLines = 2)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DestinationCatalog.available(Build.VERSION.SDK_INT).forEach { choice ->
                                FilterChip(selected = destinationUri == choice.uri, onClick = { onDestinationChanged(choice.uri) }, label = { Text(choice.label) })
                            }
                            savedDestinations.forEach { destination ->
                                FilterChip(selected = destinationUri == destination.uri, onClick = { onDestinationChanged(destination.uri) }, label = { Text(destination.displayName) })
                            }
                        }
                        Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder or SD card") }
                    }
                }
            }
            recommendation?.let { recommendation ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                XdmCardTitle("Recommended backend", modifier = Modifier.weight(1f))
                                XdmStatusBadge(recommendation.backend.uiLabel(), tone = if (recommendation.compatible) XdmStatusTone.Success else XdmStatusTone.Error)
                            }
                            XdmSupportingText(recommendation.explanation)
                            if (!recommendation.compatible) {
                                Text(
                                    recommendation.compatibilityIssue ?: "This backend cannot start the transfer.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            } else {
                                val fallbackBackend = recommendation.fallbackBackend
                                if (recommendation.fallbackAllowed && fallbackBackend != null) {
                                    XdmMetadataText("Fallback: ${fallbackBackend.uiLabel()}, before task creation only")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                XdmCardTitle("Advanced download options")
                                XdmMetadataText("Existing-file behavior, backend selection, fallback, and checksum verification.")
                            }
                            TextButton(onClick = { advancedExpanded = !advancedExpanded }) { Text(if (advancedExpanded) "Hide" else "Show") }
                        }
                        if (advancedExpanded) {
                            XdmCardTitle("Existing filename")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilenameConflictPolicy.entries.forEach { value ->
                                    FilterChip(selected = conflictPolicy == value, onClick = { onConflictPolicyChanged(value) }, label = { Text(value.uiLabel()) })
                                }
                            }
                            XdmCardTitle("Backend")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BackendType.entries.forEach { value ->
                                    FilterChip(selected = backend == value, onClick = { backend = value }, label = { Text(value.uiLabel()) })
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    XdmCardTitle("Compatible fallback")
                                    XdmMetadataText("Allowed only before a backend task owns the destination.")
                                }
                                Switch(checked = allowFallback, onCheckedChange = { allowFallback = it })
                            }
                            XdmCardTitle("Verification")
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
                                    FilterChip(selected = checksumAlgorithm == value, onClick = { checksumAlgorithm = value }, label = { Text(value.uiLabel()) })
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                XdmMetadataText(if (canSubmit) "Ready to add to the default queue." else "Enter a valid URL and destination.", modifier = Modifier.weight(1f))
                Button(
                    onClick = { onAdd(url, name, backend, destinationUri, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm) },
                    enabled = canSubmit,
                ) { Text("Start download") }
            }
        }
    }
}

@Composable
fun QueuesScreen(
    queues: List<QueueDefinition>,
    onCreateQueue: (String, Int) -> Unit,
    onUpdateQueue: (QueueDefinition, String, Int, Boolean) -> Unit,
    onToggleQueue: (QueueDefinition, Boolean) -> Unit,
    onDeleteQueue: (QueueDefinition) -> Unit,
) {
    var newQueueName by remember { mutableStateOf("") }
    var newQueueLimit by remember { mutableStateOf("3") }
    val newLimit = newQueueLimit.toIntOrNull()?.coerceIn(1, 16) ?: 3

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            XdmListCard {
                XdmCardTitle("Create queue")
                XdmSupportingText("Queues group downloads and set how many transfers may run at the same time.")
                OutlinedTextField(
                    value = newQueueName,
                    onValueChange = { newQueueName = it.take(48) },
                    label = { Text("Queue name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newQueueLimit,
                    onValueChange = { newQueueLimit = it.filter { char -> char.isDigit() }.take(2) },
                    label = { Text("Concurrent downloads") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Use 1–16. Higher values may drain battery faster.") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            onCreateQueue(newQueueName, newLimit)
                            newQueueName = ""
                            newQueueLimit = "3"
                        },
                    ) { Text("Create queue") }
                    XdmMetadataText("Starts enabled")
                }
            }
        }
        if (queues.isEmpty()) {
            item {
                XdmListCard {
                    XdmCardTitle("No download queues")
                    XdmSupportingText("Create a queue to separate nightly, media, large-file, or low-priority downloads.")
                }
            }
        } else {
            items(queues, key = QueueDefinition::id) { queue ->
                QueueManagementCard(
                    queue = queue,
                    onUpdateQueue = onUpdateQueue,
                    onToggleQueue = onToggleQueue,
                    onDeleteQueue = onDeleteQueue,
                )
            }
        }
    }
}

@Composable
private fun QueueManagementCard(
    queue: QueueDefinition,
    onUpdateQueue: (QueueDefinition, String, Int, Boolean) -> Unit,
    onToggleQueue: (QueueDefinition, Boolean) -> Unit,
    onDeleteQueue: (QueueDefinition) -> Unit,
) {
    var editing by remember(queue.id) { mutableStateOf(false) }
    var draftName by remember(queue.id, editing) { mutableStateOf(queue.name) }
    var draftLimit by remember(queue.id, editing) { mutableStateOf(queue.maxConcurrent.toString()) }
    var draftEnabled by remember(queue.id, editing) { mutableStateOf(queue.isEnabled) }
    val draftLimitNumber = draftLimit.toIntOrNull()?.coerceIn(1, 16) ?: queue.maxConcurrent
    val dirty = draftName.trim() != queue.name || draftLimitNumber != queue.maxConcurrent || draftEnabled != queue.isEnabled

    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(queue.name)
                XdmMetadataText("Up to ${queue.maxConcurrent} concurrent download${if (queue.maxConcurrent == 1) "" else "s"}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(enabledLabel(queue.isEnabled), enabledTone(queue.isEnabled))
                Switch(
                    checked = queue.isEnabled,
                    onCheckedChange = { onToggleQueue(queue, it) },
                    modifier = Modifier.semantics { stateDescription = if (queue.isEnabled) "Queue enabled" else "Queue disabled" },
                )
            }
        }
        if (queue.id == "default") {
            XdmMetadataText("Default queue is protected so new downloads always have a landing place.")
        }
        if (editing) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it.take(48) },
                label = { Text("Queue name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draftLimit,
                onValueChange = { draftLimit = it.filter { char -> char.isDigit() }.take(2) },
                label = { Text("Concurrent downloads") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("Use 1–16. Current effective value: $draftLimitNumber") },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                XdmSupportingText("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(
                    onClick = {
                        onUpdateQueue(queue, draftName, draftLimitNumber, draftEnabled)
                        editing = false
                    },
                    enabled = dirty && draftName.isNotBlank(),
                ) { Text("Save queue") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                TextButton(onClick = { onDeleteQueue(queue); editing = false }, enabled = queue.id != "default") { Text("Delete queue") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { onDeleteQueue(queue) }, enabled = queue.id != "default") { Text("Delete") }
            }
        }
    }
}

@Composable
fun SchedulerScreen(
    rules: List<ScheduleRule>,
    queues: List<QueueDefinition>,
    onCreateSchedule: (String, String?, String) -> Unit,
    onUpdateSchedule: (ScheduleRule, String, String?, Boolean, String) -> Unit,
    onToggleSchedule: (ScheduleRule, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleRule) -> Unit,
) {
    var newScheduleName by remember { mutableStateOf("") }
    var selectedQueueId by remember { mutableStateOf<String?>(queues.firstOrNull()?.id) }
    var unmeteredOnly by remember { mutableStateOf(true) }
    var chargingRequired by remember { mutableStateOf(false) }
    var minimumBattery by remember { mutableStateOf("30") }
    var startTime by remember { mutableStateOf("01:00") }
    var endTime by remember { mutableStateOf("06:00") }
    var days by remember { mutableStateOf("Weekdays") }
    val createConstraints = buildScheduleConstraintsJson(days, startTime, endTime, unmeteredOnly, chargingRequired, minimumBattery)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            XdmListCard {
                XdmCardTitle("Create schedule")
                XdmSupportingText("Schedules enable queues automatically when time, power, battery, and network conditions line up.")
                OutlinedTextField(
                    value = newScheduleName,
                    onValueChange = { newScheduleName = it.take(48) },
                    label = { Text("Schedule name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                QueuePicker(queues = queues, selectedQueueId = selectedQueueId, onSelected = { selectedQueueId = it })
                ScheduleConditionEditor(
                    days = days,
                    onDaysChanged = { days = it },
                    startTime = startTime,
                    onStartTimeChanged = { startTime = it.take(5) },
                    endTime = endTime,
                    onEndTimeChanged = { endTime = it.take(5) },
                    unmeteredOnly = unmeteredOnly,
                    onUnmeteredOnlyChanged = { unmeteredOnly = it },
                    chargingRequired = chargingRequired,
                    onChargingRequiredChanged = { chargingRequired = it },
                    minimumBattery = minimumBattery,
                    onMinimumBatteryChanged = { minimumBattery = it.filter { char -> char.isDigit() }.take(3) },
                )
                Button(
                    onClick = {
                        onCreateSchedule(newScheduleName, selectedQueueId, createConstraints)
                        newScheduleName = ""
                    },
                ) { Text("Create schedule") }
            }
        }
        if (rules.isEmpty()) {
            item {
                XdmListCard {
                    XdmCardTitle("No schedules")
                    XdmSupportingText("Create a schedule to run queues only during safe windows, such as Wi‑Fi while charging overnight.")
                }
            }
        } else {
            items(rules, key = ScheduleRule::id) { rule ->
                ScheduleManagementCard(
                    rule = rule,
                    queues = queues,
                    onUpdateSchedule = onUpdateSchedule,
                    onToggleSchedule = onToggleSchedule,
                    onDeleteSchedule = onDeleteSchedule,
                )
            }
        }
    }
}

@Composable
private fun QueuePicker(queues: List<QueueDefinition>, selectedQueueId: String?, onSelected: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XdmMetadataText("Queue")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selectedQueueId == null, onClick = { onSelected(null) }, label = { Text("All queues") })
            queues.forEach { queue ->
                FilterChip(selected = selectedQueueId == queue.id, onClick = { onSelected(queue.id) }, label = { Text(queue.name) })
            }
        }
    }
}

@Composable
private fun ScheduleConditionEditor(
    days: String,
    onDaysChanged: (String) -> Unit,
    startTime: String,
    onStartTimeChanged: (String) -> Unit,
    endTime: String,
    onEndTimeChanged: (String) -> Unit,
    unmeteredOnly: Boolean,
    onUnmeteredOnlyChanged: (Boolean) -> Unit,
    chargingRequired: Boolean,
    onChargingRequiredChanged: (Boolean) -> Unit,
    minimumBattery: String,
    onMinimumBatteryChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(days, onDaysChanged, label = { Text("Days") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(startTime, onStartTimeChanged, label = { Text("Start") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(endTime, onEndTimeChanged, label = { Text("End") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XdmSupportingText("Unmetered network only", modifier = Modifier.weight(1f))
            Switch(checked = unmeteredOnly, onCheckedChange = onUnmeteredOnlyChanged)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XdmSupportingText("Charging required", modifier = Modifier.weight(1f))
            Switch(checked = chargingRequired, onCheckedChange = onChargingRequiredChanged)
        }
        OutlinedTextField(
            minimumBattery,
            onMinimumBatteryChanged,
            label = { Text("Minimum battery %") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Leave blank to ignore battery level.") },
        )
    }
}

@Composable
private fun ScheduleManagementCard(
    rule: ScheduleRule,
    queues: List<QueueDefinition>,
    onUpdateSchedule: (ScheduleRule, String, String?, Boolean, String) -> Unit,
    onToggleSchedule: (ScheduleRule, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleRule) -> Unit,
) {
    var editing by remember(rule.id) { mutableStateOf(false) }
    var draftName by remember(rule.id, editing) { mutableStateOf(rule.name) }
    var draftQueueId by remember(rule.id, editing) { mutableStateOf(rule.queueId) }
    var draftEnabled by remember(rule.id, editing) { mutableStateOf(rule.enabled) }
    var draftDays by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "days", "Every day")) }
    var draftStart by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "startTime", "")) }
    var draftEnd by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "endTime", "")) }
    var draftUnmetered by remember(rule.id, editing) { mutableStateOf(scheduleBoolean(rule.constraintsJson, "unmetered", false)) }
    var draftCharging by remember(rule.id, editing) { mutableStateOf(scheduleBoolean(rule.constraintsJson, "charging", false)) }
    var draftBattery by remember(rule.id, editing) { mutableStateOf(scheduleInt(rule.constraintsJson, "minimumBatteryPercent")?.toString().orEmpty()) }
    val draftConstraints = buildScheduleConstraintsJson(draftDays, draftStart, draftEnd, draftUnmetered, draftCharging, draftBattery)

    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(rule.name)
                XdmMetadataText(rule.queueId?.let { id -> "Queue: ${queues.firstOrNull { it.id == id }?.name ?: id}" } ?: "All queues")
                XdmMetadataText(nextRunSummary(rule.enabled, rule.constraintsJson))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(enabledLabel(rule.enabled), enabledTone(rule.enabled))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleSchedule(rule, it) },
                    modifier = Modifier.semantics { stateDescription = if (rule.enabled) "Schedule enabled" else "Schedule disabled" },
                )
            }
        }
        scheduleConstraintSummary(rule.constraintsJson).forEach { summary -> XdmMetadataText(summary) }
        if (editing) {
            OutlinedTextField(draftName, { draftName = it.take(48) }, label = { Text("Schedule name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            QueuePicker(queues = queues, selectedQueueId = draftQueueId, onSelected = { draftQueueId = it })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                XdmSupportingText("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it })
            }
            ScheduleConditionEditor(
                days = draftDays,
                onDaysChanged = { draftDays = it },
                startTime = draftStart,
                onStartTimeChanged = { draftStart = it.take(5) },
                endTime = draftEnd,
                onEndTimeChanged = { draftEnd = it.take(5) },
                unmeteredOnly = draftUnmetered,
                onUnmeteredOnlyChanged = { draftUnmetered = it },
                chargingRequired = draftCharging,
                onChargingRequiredChanged = { draftCharging = it },
                minimumBattery = draftBattery,
                onMinimumBatteryChanged = { draftBattery = it.filter { char -> char.isDigit() }.take(3) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(
                    onClick = {
                        onUpdateSchedule(rule, draftName, draftQueueId, draftEnabled, draftConstraints)
                        editing = false
                    },
                    enabled = draftName.isNotBlank(),
                ) { Text("Save schedule") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                TextButton(onClick = { onDeleteSchedule(rule); editing = false }) { Text("Delete schedule") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { onDeleteSchedule(rule) }) { Text("Delete") }
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
            val captureVariants = variants.filter { it.captureId == capture.id }.sortedBy { it.position }
            MediaCaptureCard(capture, captureVariants, onDownload, onResolve, onSelectVariant, onRemove)
        }
    }
}

@Composable
private fun MediaCaptureCard(
    capture: MediaCaptureRecord,
    captureVariants: List<MediaVariant>,
    onDownload: (MediaCaptureRecord) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
) {
    var variantSelectorExpanded by remember(capture.id) { mutableStateOf(false) }
    val selectedVariant = captureVariants.firstOrNull { it.id == capture.selectedVariantId } ?: captureVariants.firstOrNull()
    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(capture.title, maxLines = 1)
                XdmMetadataText(mediaOriginLabel(capture), maxLines = 1)
            }
            StatusPill(capture.kind.uiLabel(), XdmStatusTone.Info)
        }
        XdmSupportingText(
            listOfNotNull(
                capture.mimeType,
                capture.container,
                capture.durationMs?.let { formatDurationSeconds(it) },
            ).joinToString(" • ").ifBlank { "Media details will appear after resolution." },
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            StatusPill(capture.status.uiLabel(), if (capture.status == MediaCaptureStatus.DownloadCreated) XdmStatusTone.Success else XdmStatusTone.Neutral)
            StatusPill(capture.resolutionStatus.uiLabel(), if (capture.resolutionStatus == MediaResolutionStatus.Failed || capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh) XdmStatusTone.Warning else XdmStatusTone.Neutral)
            capture.downloadId?.let { StatusPill("Queued", XdmStatusTone.Success) }
        }
        if (captureVariants.isNotEmpty()) {
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText("Selected variant")
                        XdmMetricText(selectedVariant?.qualityLabel ?: "Automatic")
                    }
                    TextButton(onClick = { variantSelectorExpanded = !variantSelectorExpanded }) { Text(if (variantSelectorExpanded) "Hide variants" else "Choose variant") }
                }
                if (variantSelectorExpanded) {
                    captureVariants.forEach { variant ->
                        VariantSelectorRow(
                            variant = variant,
                            selected = capture.selectedVariantId == variant.id || (capture.selectedVariantId == null && selectedVariant?.id == variant.id),
                            onSelect = { onSelectVariant(capture, variant.id) },
                        )
                    }
                }
            }
        } else {
            XdmMetadataText("No variants yet. Resolve metadata to discover quality options.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(
                onClick = { onDownload(capture) },
                enabled = capture.status != MediaCaptureStatus.DownloadCreated && capture.resolutionStatus != MediaResolutionStatus.RequiresRefresh,
            ) { Text(if (capture.status == MediaCaptureStatus.DownloadCreated) "Added" else "Download selected") }
            TextButton(onClick = { onResolve(capture) }) { Text(if (capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh) "Refresh metadata" else "Resolve metadata") }
            TextButton(onClick = { onRemove(capture) }) { Text("Remove capture") }
        }
    }
}

@Composable
private fun VariantSelectorRow(variant: MediaVariant, selected: Boolean, onSelect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(variant.qualityLabel)
                XdmMetadataText(variantDetails(variant), maxLines = 2)
            }
            FilterChip(selected = selected, onClick = onSelect, label = { Text(if (selected) "Selected" else "Select") })
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
            RecoveryRecordCard(record, onValidate, onRemove)
        }
    }
}

@Composable
private fun RecoveryRecordCard(record: RecoveryRecord, onValidate: (RecoveryRecord) -> Unit, onRemove: (RecoveryRecord) -> Unit) {
    var technicalExpanded by remember(record.id) { mutableStateOf(false) }
    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(recoveryProblemTitle(record))
                XdmSupportingText(record.reason)
            }
            StatusPill(record.classification.uiLabel(), record.classification.statusTone())
        }
        XdmMetadataText(recoveryRecommendedExplanation(record), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            StatusPill(record.recommendedAction.uiLabel(), record.classification.statusTone())
            StatusPill(if (record.safeToResume) "Safe to resume" else "Needs review", if (record.safeToResume) XdmStatusTone.Success else XdmStatusTone.Warning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { onValidate(record) }) { Text(recoveryPrimaryActionLabel(record)) }
            TextButton(onClick = { technicalExpanded = !technicalExpanded }) { Text(if (technicalExpanded) "Hide technical details" else "Technical details") }
            TextButton(onClick = { onRemove(record) }) { Text("Remove record only") }
        }
        XdmMetadataText("Remove record only hides this recovery item; it does not delete downloaded files or partial data.")
        if (technicalExpanded) {
            XdmListCard(compact = true) {
                XdmMetadataText("Artifact path: ${record.artifactPath}", maxLines = 3)
                XdmMetadataText(record.downloadId?.let { "Download ID: $it" } ?: "No linked download")
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    state: MainUiState,
    onRunAria2SmokeTest: () -> Unit,
    onRunTermuxProbe: () -> Unit,
) {
    val context = LocalContext.current
    val redactedSummary = PrivacyDiagnosticsRedactor.redactedHealthSummary(
        report = state.releaseSecurityReport,
        downloadCount = state.downloads.size,
        mediaCaptureCount = state.mediaCaptures.size,
        automationCount = state.automationCommands.size,
        rejectedHandoffCount = state.automationCommands.count { it.status == AutomationCommandStatus.Rejected },
    )
    val installUpdateSummary = state.installUpdateReadinessReport.redactedSummary()
    val finalReleaseSummary = state.finalReleaseGateReport.redactedSummary()
    val supportSummary = "$redactedSummary\n\n$installUpdateSummary\n\n$finalReleaseSummary"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { XdmSectionHeader("Runtime health") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("App integrity")
                            XdmSupportingText(state.releaseSecurityReport.summary)
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
                        XdmMetadataText("$severity: ${finding.title}")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Update compatibility")
                    XdmSupportingText(state.installUpdateReadinessReport.summary)
                    state.installUpdateReadinessReport.checks.take(4).forEach { check ->
                        val severity = when (check.severity) {
                            ReleaseReadinessSeverity.Info -> "Info"
                            ReleaseReadinessSeverity.Warning -> "Warning"
                            ReleaseReadinessSeverity.Blocking -> "Blocked"
                        }
                        XdmMetadataText("$severity: ${check.title}")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Final release gate ${state.finalReleaseGateReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Release readiness")
                    XdmSupportingText(state.finalReleaseGateReport.summary)
                    state.finalReleaseGateReport.checks.take(4).forEach { check ->
                        val severity = when (check.severity) {
                            FinalReleaseGateSeverity.Info -> "Info"
                            FinalReleaseGateSeverity.Warning -> "Warning"
                            FinalReleaseGateSeverity.Blocking -> "Blocked"
                        }
                        XdmMetadataText("$severity: ${check.title}")
                    }
                }
            }
        }
        item { DiagnosticLine("Desktop parity", state.desktopParityReport.summary) }
        item { DiagnosticLine("Protocol coverage", state.protocolExpansionReport.summary) }
        item { DiagnosticLine("Settings exchange", "Import/export snapshot is available from Settings") }
        item { DiagnosticLine("Downloads", state.downloads.size.toString()) }
        item { DiagnosticLine("Queues", state.queues.size.toString()) }
        item { DiagnosticLine("Recovery records", state.recovery.size.toString()) }
        item { DiagnosticLine("Finalization journals", state.finalizationJournals.count { it.needsRecovery }.toString()) }
        item { DiagnosticLine("Media captures", state.mediaCaptures.size.toString()) }
        item { DiagnosticLine("Media variants", state.mediaVariants.size.toString()) }
        item { DiagnosticLine("Automation commands", state.automationCommands.size.toString()) }
        item { DiagnosticLine("Browser origins", state.automationCommands.mapNotNull { it.originHost }.distinct().size.toString()) }
        item { DiagnosticLine("Rejected handoffs", state.automationCommands.count { it.status == AutomationCommandStatus.Rejected }.toString()) }
        if (state.automationCommands.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        XdmCardTitle("Recent browser handoffs")
                        state.automationCommands.take(4).forEach { command ->
                            XdmMetadataText(command.redactedDiagnosticLine())
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
                            XdmCardTitle("aria2 runtime")
                            XdmMetricText(state.aria2Diagnostics.status)
                        }
                        Button(
                            onClick = onRunAria2SmokeTest,
                            enabled = state.aria2Diagnostics.canRunSmokeTest,
                        ) {
                            Text(if (state.aria2Diagnostics.smokeTestRunning) "Testing…" else "Run probe")
                        }
                    }
                    XdmMetadataText(state.aria2Diagnostics.detail)
                }
            }
        }
        item { TermuxBridgeDiagnosticsCard(state.termuxBridge, onRunTermuxProbe) }
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
            XdmCardTitle(label, modifier = Modifier.weight(0.35f))
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
private fun TermuxBridgeDiagnosticsCard(termux: TermuxBridgeStatus, onRunProbe: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux bridge ${termux.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux bridge")
                    XdmMetricText(termux.readinessLabel)
                }
                Button(onClick = onRunProbe, enabled = termux.canRunProbe) { Text("Probe tools") }
            }
            XdmSupportingText(termux.summary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                StatusPill(if (termux.termuxInstalled) "Termux installed" else "Termux missing", if (termux.termuxInstalled) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (termux.runCommandPermissionGranted) "RUN_COMMAND ready" else "Permission needed", if (termux.runCommandPermissionGranted) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (termux.rootAvailable) "Root available" else "Root optional", if (termux.rootAvailable) XdmStatusTone.Info else XdmStatusTone.Neutral)
            }
            termux.toolRows.forEach { row ->
                XdmMetadataText("${row.tool.displayName}: ${row.statusLabel} — ${row.versionLine}", maxLines = 2)
            }
            termux.recentRuns.firstOrNull()?.let { run ->
                XdmMetadataText("Last Termux run: ${run.summary} ${run.exitCode?.let { "exit $it" } ?: "pending"}", maxLines = 2)
            }
            TextButton(
                onClick = { copyTextToClipboard(context, "XDM Termux diagnostics", termux.diagnosticsSummary()) },
                modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
            ) { Text("Copy Termux diagnostics") }
        }
    }
}

@Composable
private fun TermuxBridgeSettingsCard(
    termux: TermuxBridgeStatus,
    onRunProbe: () -> Unit,
    onOpenTermux: () -> Unit,
    onRootModeChanged: (TermuxRootMode) -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux backend ${termux.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("External tools through Termux")
                    XdmSupportingText("Use Termux for aria2c, FFmpeg, FFprobe, yt-dlp, and Python without adding a raw shell to XDM.")
                }
                StatusPill(termux.readinessLabel, termux.statusTone())
            }
            XdmMetadataText(termux.summary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = onRunProbe, enabled = termux.canRunProbe) { Text("Probe tools") }
                TextButton(onClick = onOpenTermux, enabled = termux.termuxInstalled) { Text("Open Termux") }
            }
            XdmSectionHeader("Optional root mode")
            XdmSupportingText("Root is off by default and only unlocks typed file/process actions; XDM never exposes a raw root shell endpoint.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TermuxRootMode.entries.forEach { mode ->
                    FilterChip(
                        selected = termux.rootMode == mode,
                        onClick = { onRootModeChanged(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            XdmMetadataText(termux.rootMode.description)
        }
    }
}

private fun TermuxBridgeStatus.statusTone(): XdmStatusTone = when {
    !termuxInstalled || !runCommandPermissionGranted -> XdmStatusTone.Warning
    toolRows.any { it.available } -> XdmStatusTone.Success
    else -> XdmStatusTone.Info
}

@Composable
private fun StatusPill(text: String, tone: XdmStatusTone = XdmStatusTone.Neutral) {
    XdmStatusBadge(text, tone = tone)
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
    termuxBridge: TermuxBridgeStatus,
    onCompactChanged: (Boolean) -> Unit,
    onProxyChanged: (ProxyCredentialSettings) -> Unit,
    onPostProcessingChanged: (PostProcessingSettings) -> Unit,
    onImportSettings: (String) -> Unit,
    onRunTermuxProbe: () -> Unit,
    onOpenTermux: () -> Unit,
    onRootModeChanged: (TermuxRootMode) -> Unit,
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
    val proxyDraft = ProxyCredentialSettings(proxyEnabled, proxyHost, proxyPort.toIntOrNull()?.takeIf { it in 1..65535 }, proxyUsername, proxyAlias)
    val proxyDirty = proxyDraft != proxySettings
    val proxyPortValid = proxyPort.isBlank() || proxyPort.toIntOrNull()?.let { it in 1..65535 } == true
    val postDraft = PostProcessingSettings(postEnabled, postPreset, postLabel)
    val postDirty = postDraft != postProcessingSettings

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { XdmSectionHeader("Appearance") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Compact download cards")
                        XdmMetadataText("Reduce vertical spacing in the download list.")
                    }
                    Switch(
                        checked = compact,
                        onCheckedChange = onCompactChanged,
                        modifier = Modifier.semantics { stateDescription = if (compact) "Compact cards enabled" else "Compact cards disabled" },
                    )
                }
            }
        }
        item { XdmSectionHeader("Settings import/export") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Settings import export snapshot" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Portable settings snapshot")
                    XdmSupportingText("Copy a safe backup, paste one back here, and review whether it looks ready before importing.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { copyTextToClipboard(context, "XDM settings snapshot", settingsExportText) }) { Text("Copy export") }
                        StatusPill(if (importText.isBlank()) "No import" else "Import ready", if (importText.isBlank()) XdmStatusTone.Neutral else XdmStatusTone.Info)
                    }
                    OutlinedTextField(
                        importText,
                        { importText = it },
                        label = { Text("Paste settings snapshot") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        supportingText = { Text("Secrets are not included in exported snapshots.") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onImportSettings(importText); importText = "" }, enabled = importText.isNotBlank()) { Text("Import snapshot") }
                        TextButton(onClick = { importText = "" }, enabled = importText.isNotBlank()) { Text("Clear") }
                    }
                }
            }
        }
        item { XdmSectionHeader("Proxy and credentials") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Proxy credential profile ${proxySettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("Proxy profile")
                            XdmMetadataText(proxySettings.redactedSummary)
                        }
                        StatusPill(if (proxyDirty) "Unsaved" else "Saved", if (proxyDirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XdmSupportingText("Use proxy", modifier = Modifier.weight(1f))
                        Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                    }
                    OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        proxyPort,
                        { proxyPort = it.filter { char -> char.isDigit() }.take(5) },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = !proxyPortValid,
                        supportingText = { Text(if (proxyPortValid) "Optional. Use 1–65535." else "Port must be between 1 and 65535.") },
                    )
                    OutlinedTextField(proxyUsername, { proxyUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(proxyAlias, { proxyAlias = it }, label = { Text("Credential alias") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("Store a label only; passwords stay outside exported diagnostics and settings snapshots.") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onProxyChanged(proxyDraft) }, enabled = proxyDirty && proxyPortValid) { Text("Save proxy profile") }
                        TextButton(
                            onClick = {
                                proxyEnabled = proxySettings.enabled
                                proxyHost = proxySettings.host
                                proxyPort = proxySettings.port?.toString().orEmpty()
                                proxyUsername = proxySettings.username
                                proxyAlias = proxySettings.credentialAlias
                            },
                            enabled = proxyDirty,
                        ) { Text("Reset") }
                    }
                }
            }
        }
        item { XdmSectionHeader("Termux backend") }
        item { TermuxBridgeSettingsCard(termuxBridge, onRunTermuxProbe, onOpenTermux, onRootModeChanged) }
        item { XdmSectionHeader("Conversion and post-processing") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Conversion post processing ${postProcessingSettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("Post-processing hook")
                            XdmMetadataText(postProcessingSettings.redactedSummary)
                        }
                        StatusPill(if (postDirty) "Unsaved" else "Saved", if (postDirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XdmSupportingText("Run after completion", modifier = Modifier.weight(1f))
                        Switch(checked = postEnabled, onCheckedChange = { postEnabled = it })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConversionPreset.entries.forEach { preset ->
                            FilterChip(selected = postPreset == preset, onClick = { postPreset = preset }, label = { Text(preset.displayName()) })
                        }
                    }
                    OutlinedTextField(postLabel, { postLabel = it }, label = { Text("Custom label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPostProcessingChanged(postDraft) }, enabled = postDirty) { Text("Save post-processing") }
                        TextButton(
                            onClick = {
                                postEnabled = postProcessingSettings.enabled
                                postPreset = postProcessingSettings.preset
                                postLabel = postProcessingSettings.customCommandLabel
                            },
                            enabled = postDirty,
                        ) { Text("Reset") }
                    }
                    XdmMetadataText("Conversion starts only when the selected backend supports the chosen preset.")
                }
            }
        }
        item { XdmSectionHeader("Protocol expansion") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Protocol expansion ${protocolExpansionReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    XdmCardTitle(protocolExpansionReport.summary)
                    protocolExpansionReport.rows.forEach { row -> XdmMetadataText("${row.protocol.uppercase()}: ${row.recommendation}") }
                }
            }
        }
        item { XdmSectionHeader("Backend strategy") }
        items(capabilities, key = { it.backend.name }) { capability ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        XdmCardTitle(capability.backend.uiLabel())
                        StatusPill(if (capability.available) "Available" else "Unavailable", if (capability.available) XdmStatusTone.Success else XdmStatusTone.Warning)
                    }
                    XdmSupportingText(capability.summary)
                    XdmMetadataText("Protocols: ${capability.protocols.sorted().joinToString().ifBlank { "None" }}")
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
                    XdmMetadataText("Diagnostics: ${capability.diagnosticDetail.uiLabel()} • Battery: ${capability.batteryImpact.uiLabel()}")
                }
            }
        }
        if (migrations.isNotEmpty()) {
            item { XdmSectionHeader("Recent backend migrations") }
            items(migrations.take(5), key = BackendMigrationRecord::id) { migration ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        XdmCardTitle("${migration.sourceBackend.uiLabel()} → ${migration.targetBackend.uiLabel()}")
                        XdmMetricText(migration.stage.uiLabel())
                        XdmMetadataText(migration.message)
                    }
                }
            }
        }
    }
}




private enum class DownloadSort(val label: String) {
    Attention("Needs attention"),
    Recent("Newest first"),
    Name("Name"),
    Progress("Progress"),
}

private fun List<Download>.sortForUi(sort: DownloadSort): List<Download> = when (sort) {
    DownloadSort.Attention -> sortedWith(
        compareByDescending<Download> { if (it.state == DownloadState.Failed || it.state == DownloadState.RecoveryRequired) 1 else 0 }
            .thenByDescending { if (it.state == DownloadState.Downloading || it.state == DownloadState.Connecting) 1 else 0 }
            .thenByDescending { it.createdAtEpochMs },
    )
    DownloadSort.Recent -> sortedByDescending { it.createdAtEpochMs }
    DownloadSort.Name -> sortedBy { it.fileName.lowercase() }
    DownloadSort.Progress -> sortedByDescending { it.progressFraction }
}

private fun Download.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return true
    return listOf(fileName, sourceUrl, destinationUri, userLabel.orEmpty(), backend.uiLabel(), state.uiLabel())
        .any { it.lowercase().contains(needle) }
}

private fun copyTextToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun Download.accessibilitySummary(): String = buildString {
    append(fileName)
    append(", ")
    append(state.uiLabel().lowercase())
    append(" using ")
    append(backend.uiLabel())
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


private fun buildScheduleConstraintsJson(
    days: String,
    startTime: String,
    endTime: String,
    unmeteredOnly: Boolean,
    chargingRequired: Boolean,
    minimumBattery: String,
): String {
    val json = JSONObject()
    days.trim().takeIf(String::isNotBlank)?.let { json.put("days", it) }
    startTime.trim().takeIf(String::isNotBlank)?.let { json.put("startTime", it) }
    endTime.trim().takeIf(String::isNotBlank)?.let { json.put("endTime", it) }
    if (unmeteredOnly) json.put("unmetered", true)
    if (chargingRequired) json.put("charging", true)
    minimumBattery.toIntOrNull()?.coerceIn(0, 100)?.let { json.put("minimumBatteryPercent", it) }
    return json.toString()
}

private fun scheduleBoolean(rawJson: String, key: String, default: Boolean): Boolean = runCatching {
    JSONObject(rawJson).optBoolean(key, default)
}.getOrDefault(default)

private fun scheduleString(rawJson: String, key: String, default: String): String = runCatching {
    JSONObject(rawJson).optString(key).ifBlank { default }
}.getOrDefault(default)

private fun scheduleInt(rawJson: String, key: String): Int? = runCatching {
    JSONObject(rawJson).takeIf { it.has(key) }?.optInt(key)
}.getOrNull()

private fun nextRunSummary(enabled: Boolean, rawJson: String): String {
    if (!enabled) return "Disabled; it will not run until enabled."
    val start = scheduleString(rawJson, "startTime", "")
    val end = scheduleString(rawJson, "endTime", "")
    val days = scheduleString(rawJson, "days", "")
    val window = when {
        start.isNotBlank() && end.isNotBlank() -> "$start–$end"
        start.isNotBlank() -> "after $start"
        else -> "when conditions match"
    }
    return listOf(days.takeIf(String::isNotBlank), "Next eligible window: $window").filterNotNull().joinToString(" • ")
}

private fun mediaOriginLabel(capture: MediaCaptureRecord): String = listOfNotNull(
    capture.pageUrl?.let(::hostFromUrl),
    hostFromUrl(capture.sourceUrl),
).distinct().joinToString(" • ").ifBlank { "Captured media" }

private fun hostFromUrl(url: String): String = runCatching {
    url.substringAfter("://", url).substringBefore('/').substringBefore('?').ifBlank { url }
}.getOrDefault(url)

private fun formatDurationSeconds(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun variantDetails(variant: MediaVariant): String = listOfNotNull(
    variant.mimeType,
    variant.width?.let { width -> variant.height?.let { height -> "${width}×${height}" } },
    variant.bitrateBitsPerSecond?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
    variant.codecs,
    variant.language?.takeIf(String::isNotBlank)?.let { "Language: $it" },
).joinToString(" • ").ifBlank { variant.kind.name.lowercase().replaceFirstChar { it.titlecase() } }

private fun recoveryProblemTitle(record: RecoveryRecord): String = when (record.classification) {
    RecoveryClassification.ReadyToResume -> "Interrupted download can resume"
    RecoveryClassification.NeedsRemoteValidation -> "Download needs remote validation"
    RecoveryClassification.NeedsRepair -> "Partial file needs repair"
    RecoveryClassification.MissingPartialFile -> "Partial file is missing"
    RecoveryClassification.RemoteFileChanged -> "Remote file changed"
    RecoveryClassification.CompletionRecovered -> "Completed file was recovered"
    RecoveryClassification.FinalizationInterrupted -> "Finishing was interrupted"
    RecoveryClassification.BackendTaskOrphaned -> "Backend task lost its owner"
    RecoveryClassification.OrphanedArtifact -> "Untracked partial file found"
}

private fun recoveryRecommendedExplanation(record: RecoveryRecord): String = when (record.recommendedAction) {
    RecoveryAction.Resume -> "The partial data looks reusable. Resume keeps existing bytes and continues safely."
    RecoveryAction.Validate -> "Validate checks the partial data before XDM decides whether it can be reused."
    RecoveryAction.VerifyAndRepair -> "XDM should verify trusted blocks and repair only the damaged range when possible."
    RecoveryAction.RestartFromZero -> "Restart discards reuse assumptions and creates a fresh backend task."
    RecoveryAction.AdoptOrphan -> "Adopt links this artifact to a managed download only after validation."
    RecoveryAction.LocateFile -> "Locate the missing file before any resume or repair action."
    RecoveryAction.RemoveRecord -> "Remove only clears the recovery warning; it does not delete user files."
}

private fun recoveryPrimaryActionLabel(record: RecoveryRecord): String = when (record.recommendedAction) {
    RecoveryAction.Resume -> "Resume download"
    RecoveryAction.Validate -> "Validate safely"
    RecoveryAction.VerifyAndRepair -> "Verify and repair"
    RecoveryAction.RestartFromZero -> "Restart download"
    RecoveryAction.AdoptOrphan -> "Validate and adopt"
    RecoveryAction.LocateFile -> "Locate file"
    RecoveryAction.RemoveRecord -> "Review record"
}

private fun scheduleConstraintSummary(rawJson: String): List<String> {
    if (rawJson.isBlank() || rawJson.trim() == "{}") return listOf("No additional conditions")
    return runCatching {
        val json = JSONObject(rawJson)
        buildList {
            json.optString("days").takeIf { it.isNotBlank() }?.let { add("Days: ${humanizeValue(it)}") }
            json.optString("startTime").takeIf { it.isNotBlank() }?.let { start ->
                val end = json.optString("endTime").takeIf { it.isNotBlank() }
                add(if (end == null) "Starts at $start" else "Time: $start–$end")
            }
            if (json.optBoolean("unmetered", false) || json.optBoolean("unmeteredOnly", false)) add("Network: Unmetered only")
            if (json.optBoolean("charging", false) || json.optBoolean("requiresCharging", false)) add("Power: Charging required")
            json.optInt("minimumBatteryPercent", -1).takeIf { it >= 0 }?.let { add("Battery: At least $it%") }
            json.optString("networkType").takeIf { it.isNotBlank() }?.let { add("Network: ${humanizeValue(it)}") }
            if (isEmpty()) {
                json.keys().asSequence().forEach { key ->
                    val value = json.opt(key)
                    if (value != null && value != JSONObject.NULL) add("${humanizeValue(key)}: ${humanizeJsonValue(value)}")
                }
            }
        }.ifEmpty { listOf("No additional conditions") }
    }.getOrElse { listOf("Schedule conditions are saved and will be applied automatically") }
}

private fun humanizeJsonValue(value: Any): String = when (value) {
    is Boolean -> if (value) "Required" else "Not required"
    is JSONArray -> (0 until value.length()).joinToString(", ") { humanizeValue(value.optString(it)) }
    else -> humanizeValue(value.toString())
}

private fun humanizeValue(value: String): String = value
    .replace('_', ' ')
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .trim()
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

@Composable
fun EmptyFeatureScreen(title: String, description: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        XdmSectionHeader(title)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Download.fileManagementSummary(): String = buildString {
    appendLine("File: $fileName")
    appendLine("State: ${state.uiLabel()}")
    appendLine("Backend: ${backend.uiLabel()}")
    appendLine("URL: $sourceUrl")
    appendLine("Destination: $destinationUri")
    appendLine("Progress: ${bytesReceived.formatBytes()}${totalBytes?.let { " / ${it.formatBytes()}" } ?: ""}")
    mimeType?.takeIf { it.isNotBlank() }?.let { appendLine("MIME type: $it") }
    userLabel?.takeIf { it.isNotBlank() }?.let { appendLine("Label: $it") }
    errorMessage?.takeIf { it.isNotBlank() }?.let { appendLine("Last error: $it") }
}.trimEnd()
