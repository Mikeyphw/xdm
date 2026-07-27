package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import com.mikeyphw.xdm.android.util.formatBytes

@Composable
internal fun DownloadDetails(
    download: Download,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    onTogglePause: (Download) -> Unit,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
    onPreviewPostProcessing: (Download) -> Unit,
    onRunPostProcessing: (Download) -> Unit,
    onStartIgnoringQueuePolicy: (Download) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestVerification = verificationRecords.firstOrNull { it.downloadId == download.id }
    val latestChecksum = checksumResults.firstOrNull { it.downloadId == download.id }
    val queuePolicyHeld = download.errorMessage.orEmpty().startsWith("Queue policy:") &&
        download.state !in setOf(DownloadState.Downloading, DownloadState.Connecting, DownloadState.Completed, DownloadState.Cancelled)

    Column(modifier.fillMaxWidth().xdmScreen(XdmScreenTags.DownloadsDetail, "Download details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = XdmTheme.extendedColors.groupedSurface,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Icon(
                        imageVector = if (download.state == DownloadState.Completed) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp).size(28.dp),
                    )
                }
                Text(
                    download.fileName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    hostFromUrl(download.sourceUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                download.totalBytes?.let {
                    XdmProgressLine(
                        progress = download.progressFraction,
                        stateLabel = download.progressAccessibilitySummary(),
                    )
                }
                XdmActionFlowRow {
                    if (download.primaryActionUsesToggle()) {
                        Button(
                            onClick = { onTogglePause(download) },
                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                        ) {
                            val resume = download.state in setOf(
                                DownloadState.Paused,
                                DownloadState.WaitingForNetwork,
                                DownloadState.WaitingForPower,
                                DownloadState.Failed,
                            )
                            Icon(
                                if (download.state == DownloadState.Failed) Icons.Rounded.Refresh
                                else if (resume) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = null,
                            )
                            Text(
                                when {
                                    download.state == DownloadState.Failed -> "Retry"
                                    resume -> "Resume"
                                    else -> "Pause"
                                },
                            )
                        }
                    }
                    if (queuePolicyHeld) {
                        Button(onClick = { onStartIgnoringQueuePolicy(download) }) { Text("Start now") }
                    }
                }
            }
        }

        XdmGroupedList {
            DownloadDetailRow("Status", download.plainStatus())
            XdmListSeparator()
            DownloadDetailRow("Progress", download.progressSummary())
            XdmListSeparator()
            DownloadDetailRow("Save to", download.destinationUri)
            XdmListSeparator()
            DownloadDetailRow("Source", hostFromUrl(download.sourceUrl))
            if (latestVerification != null || latestChecksum != null || download.state in setOf(DownloadState.Verifying, DownloadState.Repairing)) {
                XdmListSeparator()
                DownloadDetailRow("Verification", verificationSummary(download, latestVerification, latestChecksum))
            }
        }

        download.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
            XdmNoticeRow(
                text = error.removePrefix("Queue policy:").trim(),
                tone = if (queuePolicyHeld) XdmStatusTone.Warning else XdmStatusTone.Error,
                actionLabel = if (queuePolicyHeld) "Start now" else null,
                onAction = if (queuePolicyHeld) ({ onStartIgnoringQueuePolicy(download) }) else null,
            )
        }

        XdmTechnicalDetails {
            DownloadDetailRow("Engine", download.backend.uiLabel())
            DownloadDetailRow("Requested method", download.requestedBackend.uiLabel())
            DownloadDetailRow("Resume", if (download.bytesReceived > 0L) "Partial data preserved" else "Available when supported")
            DownloadDetailRow("Queue", download.queueId ?: "Default queue")
            DownloadDetailRow("Request data", "Protected and redacted")
            DownloadDetailRow("Conflict policy", download.conflictPolicy.uiLabel())
            if (download.backendSelectionExplanation.isNotBlank()) {
                DownloadDetailRow("Method choice", download.backendSelectionExplanation)
            }
            BackendMigrationAction(download, capabilities, onMigrateBackend)
        }

        if (download.state in setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)) {
            XdmGroupedList {
                XdmListRow(
                    headline = "Copy source link",
                    supporting = "Copies the public URL only.",
                    onClick = { copyTextToClipboard(context, "XDM source URL", download.sourceUrl) },
                )
                XdmListSeparator()
                XdmListRow(
                    headline = "Copy file information",
                    supporting = "Creates a human-readable summary for support.",
                    onClick = { copyTextToClipboard(context, "XDM file info", download.fileManagementSummary()) },
                )
                XdmListSeparator()
                XdmListRow(
                    headline = "Preview post-processing",
                    supporting = "Shows the safe rules that would run.",
                    onClick = { onPreviewPostProcessing(download) },
                )
                XdmListSeparator()
                XdmListRow(
                    headline = "Run post-processing",
                    supporting = "Runs typed actions through approved templates.",
                    onClick = { onRunPostProcessing(download) },
                )
                XdmListSeparator()
                XdmListRow(
                    headline = "Remove from history",
                    supporting = "Keeps the downloaded file in its destination.",
                    onClick = { onRemoveHistory(download) },
                )
            }
        }
    }
}

@Composable
private fun DownloadDetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.34f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.66f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BackendMigrationAction(
    download: Download,
    capabilities: List<BackendCapabilityRow>,
    onMigrateBackend: (Download) -> Unit,
) {
    val targetBackend = when (download.backend) {
        BackendType.Native -> BackendType.Aria2
        BackendType.Aria2 -> BackendType.Native
        BackendType.Automatic -> null
    }
    val targetCapability = capabilities.firstOrNull { it.backend == targetBackend }
    val destinationScheme = download.destinationUri.substringBefore(':').lowercase()
    val documentDestination = destinationScheme in setOf("content", "xdm")
    val compatible = targetCapability?.available == true && (!documentDestination || targetCapability.saf)
    if (
        targetBackend != null &&
        compatible &&
        download.state in setOf(DownloadState.Paused, DownloadState.Failed, DownloadState.RecoveryRequired)
    ) {
        TextButton(onClick = { onMigrateBackend(download) }) {
            Text(if (download.bytesReceived > 0L) "Restart with ${targetBackend.uiLabel()}" else "Switch to ${targetBackend.uiLabel()}")
        }
    }
}

private fun Download.plainStatus(): String = when (state) {
    DownloadState.Created -> "Ready to enter the queue"
    DownloadState.Queued -> "Waiting for its turn"
    DownloadState.Connecting -> "Connecting to the source"
    DownloadState.Downloading -> "Downloading normally"
    DownloadState.Paused -> "Paused by you"
    DownloadState.WaitingForNetwork -> "Waiting for an allowed network"
    DownloadState.WaitingForPower -> "Waiting for power conditions"
    DownloadState.Verifying -> "Checking file integrity"
    DownloadState.Repairing -> "Repairing damaged ranges"
    DownloadState.Finalizing -> "Finishing the file"
    DownloadState.Completed -> "Verified and ready"
    DownloadState.Failed -> "Needs a retry"
    DownloadState.RecoveryRequired -> "Needs a safe recovery decision"
    DownloadState.Cancelled -> "Cancelled"
}

private fun Download.progressSummary(): String {
    val total = totalBytes
    return when {
        total != null -> "${(progressFraction * 100).toInt()}% • ${bytesReceived.formatBytes()} of ${total.formatBytes()}"
        bytesReceived > 0L -> bytesReceived.formatBytes()
        else -> state.uiLabel()
    }
}

private fun verificationSummary(
    download: Download,
    verification: VerificationRecord?,
    checksum: ChecksumResult?,
): String {
    val stateLabel = verification?.status?.uiLabel() ?: when (download.state) {
        DownloadState.Verifying -> VerificationStatus.Running.uiLabel()
        DownloadState.Repairing -> "Repair in progress"
        else -> "Pending"
    }
    val checksumLabel = checksum?.let {
        when (it.matchesExpectation) {
            true -> "${it.algorithm.uiLabel()} matched"
            false -> "${it.algorithm.uiLabel()} mismatch"
            null -> "${it.algorithm.uiLabel()} recorded"
        }
    }
    return listOfNotNull(stateLabel, checksumLabel, verification?.message?.takeIf(String::isNotBlank)).joinToString(" • ")
}
