package com.mikeyphw.xdm.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardPlanner
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.util.formatBytes
import com.mikeyphw.xdm.android.util.formatSpeed

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadRow(
    download: Download,
    compact: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    val action = download.primaryRowAction()
    val totalBytes = download.totalBytes
    val supporting = download.rowSupportingText()
    val progressVisible = totalBytes != null && download.state !in setOf(DownloadState.Created, DownloadState.Cancelled)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                this.selected = selected
                role = Role.Button
                contentDescription = buildString {
                    append(download.accessibilitySummary())
                    if (selectionMode) append(if (selected) ". Selected" else ". Not selected")
                }
            },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else XdmTheme.extendedColors.groupedSurface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 10.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XdmFileTypeIcon(
                fileName = download.fileName,
                mimeType = download.mimeType,
                contentDescription = null,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        download.fileName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    XdmStatusBadge(download.rowBadgeLabel(), tone = download.state.statusTone())
                }
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progressVisible) {
                    XdmProgressLine(
                        progress = download.progressFraction,
                        stateLabel = download.progressAccessibilitySummary(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        download.rowProgressText(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        download.rowTrailingText(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onPrimaryAction,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                    contentDescription = "${action.label} ${download.fileName}"
                },
            ) {
                Icon(action.icon, contentDescription = action.label)
            }
        }
    }
}

private data class DownloadRowAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private fun Download.primaryRowAction(): DownloadRowAction = when (state) {
    DownloadState.Downloading,
    DownloadState.Connecting,
    DownloadState.Finalizing,
    DownloadState.Queued,
    -> DownloadRowAction("Pause", Icons.Rounded.Pause)

    DownloadState.Paused,
    DownloadState.WaitingForNetwork,
    DownloadState.WaitingForPower,
    -> DownloadRowAction("Resume", Icons.Rounded.PlayArrow)

    DownloadState.Failed -> DownloadRowAction("Retry", Icons.Rounded.Refresh)
    DownloadState.Completed -> DownloadRowAction("Details", Icons.Rounded.Check)
    else -> DownloadRowAction("Details", Icons.Rounded.MoreHoriz)
}

internal fun Download.primaryActionUsesToggle(): Boolean = state in setOf(
    DownloadState.Downloading,
    DownloadState.Connecting,
    DownloadState.Finalizing,
    DownloadState.Queued,
    DownloadState.Paused,
    DownloadState.WaitingForNetwork,
    DownloadState.WaitingForPower,
    DownloadState.Failed,
)

private fun Download.rowBadgeLabel(): String = when (state) {
    DownloadState.Downloading -> "${(progressFraction * 100).toInt()}%"
    DownloadState.Completed -> "Done"
    DownloadState.Failed -> "Retry"
    DownloadState.WaitingForNetwork -> "Network"
    DownloadState.WaitingForPower -> "Power"
    DownloadState.RecoveryRequired -> "Repair"
    else -> state.uiLabel()
}

private fun Download.rowSupportingText(): String {
    val host = hostFromUrl(sourceUrl)
    val reason = when {
        errorMessage.orEmpty().startsWith("Queue policy:") -> errorMessage.orEmpty().removePrefix("Queue policy:").trim()
        state == DownloadState.Failed && !errorMessage.isNullOrBlank() -> errorMessage.orEmpty()
        state == DownloadState.RecoveryRequired -> "Partial file needs a safe recovery decision"
        state == DownloadState.WaitingForNetwork -> "Waiting for an allowed network"
        state == DownloadState.WaitingForPower -> "Waiting for power conditions"
        state == DownloadState.Paused -> "Paused by you"
        state == DownloadState.Queued -> "Next in queue"
        state == DownloadState.Verifying -> "Checking file integrity"
        state == DownloadState.Repairing -> "Repairing damaged ranges"
        state == DownloadState.Finalizing -> "Finishing the file"
        state == DownloadState.Completed -> "Verified and ready"
        else -> DownloadDashboardPlanner.attentionSignal(this)?.guidance ?: host
    }
    return listOf(reason, totalBytes?.formatBytes(), host.takeIf { it.isNotBlank() && it !in reason }).filterNotNull().joinToString(" • ")
}

private fun Download.rowProgressText(): String {
    val total = totalBytes
    return when {
        total != null -> "${bytesReceived.formatBytes()} / ${total.formatBytes()}"
        bytesReceived > 0L -> bytesReceived.formatBytes()
        else -> destinationUri
    }
}

private fun Download.rowTrailingText(): String = when {
    speedBytesPerSecond > 0L -> speedBytesPerSecond.formatSpeed()
    state == DownloadState.Completed -> totalBytes?.formatBytes() ?: "Complete"
    state == DownloadState.Failed -> "Tap to retry"
    state == DownloadState.Paused -> "Tap to resume"
    state == DownloadState.WaitingForNetwork -> "Waiting"
    state == DownloadState.WaitingForPower -> "Waiting"
    state == DownloadState.Queued -> "Queued"
    else -> state.uiLabel()
}
