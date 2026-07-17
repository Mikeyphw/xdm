package com.mikeyphw.xdm.android

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
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
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
    onTogglePause: (Download) -> Unit,
    onMigrateBackend: (Download) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    var filter by remember { mutableStateOf<DownloadState?>(null) }
    Column(Modifier.fillMaxSize()) {
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
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            listOf(DownloadState.Downloading, DownloadState.Queued, DownloadState.Completed, DownloadState.Failed).forEach { state ->
                FilterChip(selected = filter == state, onClick = { filter = state }, label = { Text(state.name) })
            }
        }
        val visible = downloads.filter { filter == null || it.state == filter }
        if (visible.isEmpty()) {
            EmptyFeatureScreen("No downloads", "Add a URL to create the first download.")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
                items(visible, key = Download::id) { download ->
                    DownloadCard(download, compact, capabilities, checksumResults, verificationRecords, onTogglePause, onMigrateBackend)
                }
            }
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
) {
    Card(Modifier.fillMaxWidth()) {
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
                }
                if (download.state in setOf(DownloadState.Downloading, DownloadState.Connecting, DownloadState.Paused, DownloadState.Failed)) {
                    IconButton(onClick = { onTogglePause(download) }) {
                        val paused = download.state == DownloadState.Paused || download.state == DownloadState.Failed
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause")
                    }
                }
            }
            val totalBytes = download.totalBytes
            if (totalBytes != null) {
                LinearProgressIndicator(progress = { download.progressFraction }, modifier = Modifier.fillMaxWidth())
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
                Button(onClick = { onMigrateBackend(download) }) {
                    Text(if (download.bytesReceived > 0) "Restart with $target" else "Switch to $target")
                }
                if (download.bytesReceived > 0) {
                    Text("Existing partial bytes are preserved for recovery and are not reused silently.", style = MaterialTheme.typography.bodySmall)
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Runtime health", style = MaterialTheme.typography.headlineSmall) }
        item { DiagnosticLine("Database", "Room schema v9") }
        item { DiagnosticLine("Downloads", state.downloads.size.toString()) }
        item { DiagnosticLine("Queues", state.queues.size.toString()) }
        item { DiagnosticLine("Recovery records", state.recovery.size.toString()) }
        item { DiagnosticLine("Finalization journals", state.finalizationJournals.count { it.needsRecovery }.toString()) }
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
    Card(Modifier.fillMaxWidth()) {
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
    onCompactChanged: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Appearance", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compact download cards", fontWeight = FontWeight.Medium)
                        Text("Reduce vertical spacing in the download list.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(compact, onCompactChanged)
                }
            }
        }
        item { Text("Backend strategy", style = MaterialTheme.typography.headlineSmall) }
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
        item { Text("Version: 0.8.0-alpha01", style = MaterialTheme.typography.bodySmall) }
    }
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
