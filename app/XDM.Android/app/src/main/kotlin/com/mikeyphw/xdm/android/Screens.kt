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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mikeyphw.xdm.android.model.BackendRecommendation
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import com.mikeyphw.xdm.android.util.formatBytes
import com.mikeyphw.xdm.android.util.formatSpeed

@Composable
fun DownloadsScreen(downloads: List<Download>, compact: Boolean, active: ActiveTransferSummary, onTogglePause: (Download) -> Unit, onPauseAll: () -> Unit, onResumeAll: () -> Unit) {
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
                    DownloadCard(download, compact, onTogglePause)
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(download: Download, compact: Boolean, onTogglePause: (Download) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(download.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${download.state.name} • ${download.backend.name}", style = MaterialTheme.typography.bodySmall)
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
    onAdd: (String, String, BackendType, String, FilenameConflictPolicy) -> Unit,
    recommend: (String, String, BackendType, String, FilenameConflictPolicy) -> BackendRecommendation,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf(BackendType.Automatic) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onSafDestinationSelected(it.toString()) }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("New download", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(name, { name = it }, label = { Text("Filename") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
        if (url.isNotBlank() && name.isNotBlank()) {
            val recommendation = recommend(url, name, backend, destinationUri, conflictPolicy)
            Text("${recommendation.backend.name}: ${recommendation.explanation}", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { onAdd(url, name, backend, destinationUri, conflictPolicy) },
            enabled = url.isNotBlank() && name.isNotBlank() && destinationUri.isNotBlank(),
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
                    AssistChip(onClick = {}, label = { Text(if (queue.isEnabled) "Enabled" else "Disabled") })
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
fun RecoveryScreen(records: List<RecoveryRecord>) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {}) { Text("Review") }
                        AssistChip(onClick = {}, label = { Text("Keep paused") })
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(state: MainUiState) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Foundation health", style = MaterialTheme.typography.headlineSmall)
        DiagnosticLine("Database", "Room schema v4")
        DiagnosticLine("Downloads", state.downloads.size.toString())
        DiagnosticLine("Queues", state.queues.size.toString())
        DiagnosticLine("Recovery records", state.recovery.size.toString())
        DiagnosticLine("Native backend", "HTTP/HTTPS engine, checkpoints, resume and segmentation ready")
        DiagnosticLine("Execution", "UIDT on Android 14+, foreground dataSync fallback")
        DiagnosticLine("Active transfers", state.activeTransfers.activeCount.toString())
        DiagnosticLine("aria2 backend", "Module ready; process not bundled yet")
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SettingsScreen(compact: Boolean, onCompactChanged: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compact download cards", fontWeight = FontWeight.Medium)
                    Text("Reduce vertical spacing in the download list.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(compact, onCompactChanged)
            }
        }
        Text("Package: com.mikeyphw.xdm.android", style = MaterialTheme.typography.bodySmall)
        Text("Version: 0.5.0-alpha01", style = MaterialTheme.typography.bodySmall)
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
