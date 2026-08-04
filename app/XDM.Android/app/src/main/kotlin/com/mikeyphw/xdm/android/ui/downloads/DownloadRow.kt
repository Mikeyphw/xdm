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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
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
import com.mikeyphw.xdm.android.model.DownloadAction
import com.mikeyphw.xdm.android.model.DownloadActionContext
import com.mikeyphw.xdm.android.model.DownloadActionIcon
import com.mikeyphw.xdm.android.model.DownloadActionKind
import com.mikeyphw.xdm.android.model.DownloadActionPlanner
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadUiTruthPlanner
import com.mikeyphw.xdm.android.util.formatBytes
import com.mikeyphw.xdm.android.util.formatSpeed

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadRow(
    download: Download,
    actionContext: DownloadActionContext,
    compact: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrimaryAction: () -> Unit,
    onMoreActions: () -> Unit,
) {
    val action = DownloadActionPlanner.primaryActionFor(download, actionContext)
    val truth = DownloadUiTruthPlanner.truth(download, actionContext)
    val totalBytes = download.totalBytes
    val progressVisible = totalBytes != null && download.state !in setOf(DownloadState.Created, DownloadState.Cancelled)
    val byteText = when {
        totalBytes != null -> "${download.bytesReceived.coerceAtMost(totalBytes).formatBytes()} / ${totalBytes.formatBytes()}"
        download.bytesReceived > 0L -> download.bytesReceived.formatBytes()
        else -> actionContext.artifact.friendlyLocation
    }
    val trailing = when {
        download.state == DownloadState.Downloading && download.speedBytesPerSecond > 0L -> download.speedBytesPerSecond.formatSpeed()
        download.state == DownloadState.Completed -> actionContext.artifact.sizeBytes?.formatBytes() ?: truth.trailingText
        else -> truth.trailingText
    }

    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick).semantics {
            this.selected = selected
            role = Role.Button
            contentDescription = buildString {
                append("${download.fileName}. ${truth.status}. $byteText. ${truth.supportingText}")
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 10.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XdmFileTypeIcon(download.fileName, mimeType = download.mimeType, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(download.fileName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    XdmStatusBadge(truth.badge, tone = download.state.statusTone())
                }
                Text(
                    truth.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progressVisible) XdmProgressLine(progress = download.progressFraction, stateLabel = truth.overallProgressText)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(byteText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(trailing, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            Spacer(Modifier.width(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPrimaryAction,
                    enabled = action.enabled,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "${action.label} ${download.fileName}" },
                ) { Icon(action.iconVector(), contentDescription = null) }
                IconButton(
                    onClick = onMoreActions,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "More actions for ${download.fileName}" },
                ) { Icon(Icons.Rounded.MoreVert, contentDescription = null) }
            }
        }
    }
}

internal fun DownloadAction.iconVector(): androidx.compose.ui.graphics.vector.ImageVector = when (icon) {
    DownloadActionIcon.Open -> Icons.Rounded.Check
    DownloadActionIcon.Details -> Icons.Rounded.Info
    DownloadActionIcon.Recovery -> Icons.Rounded.Refresh
    DownloadActionIcon.Pause -> Icons.Rounded.Pause
    DownloadActionIcon.Play -> Icons.Rounded.PlayArrow
    DownloadActionIcon.Refresh -> Icons.Rounded.Refresh
    DownloadActionIcon.Cancel -> Icons.Rounded.Close
    DownloadActionIcon.Queue, DownloadActionIcon.Move, DownloadActionIcon.Rename -> Icons.Rounded.MoreHoriz
    DownloadActionIcon.Copy -> Icons.Rounded.ContentPaste
    DownloadActionIcon.Share -> Icons.Rounded.Link
    DownloadActionIcon.Folder -> Icons.Rounded.Folder
    DownloadActionIcon.Delete -> Icons.Rounded.Close
}

internal fun Download.primaryActionUsesToggle(context: DownloadActionContext): Boolean =
    DownloadActionPlanner.primaryActionFor(this, context).kind in setOf(
        DownloadActionKind.Pause,
        DownloadActionKind.Resume,
        DownloadActionKind.Retry,
    )
