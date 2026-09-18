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
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.material3.TextButton
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
import com.mikeyphw.xdm.android.model.DownloadPresentationPolicy
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
    thumbnailUrl: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onQuickAction: (DownloadAction) -> Unit,
    onMoreActions: () -> Unit,
) {
    val primaryAction = DownloadActionPlanner.primaryActionFor(download, actionContext)
    val quickActions = DownloadActionPlanner.quickActionsFor(download, actionContext).take(1).ifEmpty { listOf(primaryAction) }
    val truth = DownloadUiTruthPlanner.truth(download, actionContext)
    val displayName = DownloadPresentationPolicy.displayName(download)
    val sourceHost = DownloadPresentationPolicy.sourceHost(download.sourceUrl).ifBlank { "Download source" }
    val finalizationFailure = DownloadPresentationPolicy.isFinalizationFailure(download)
    val totalBytes = download.totalBytes
    val phaseProgress = DownloadUiTruthPlanner.phaseProgress(download, actionContext)
    val progressVisible = phaseProgress != null || DownloadUiTruthPlanner.indeterminateProgressVisible(download, actionContext)
    val runningVerification = actionContext.latestVerification?.takeIf { it.status == com.mikeyphw.xdm.android.model.VerificationStatus.Running }
    val verificationTotalBytes = runningVerification?.totalBytes
    val byteText = when {
        finalizationFailure && download.bytesReceived > 0L -> "${download.bytesReceived.formatBytes()} · Transfer complete"
        download.state == DownloadState.Verifying && runningVerification != null && verificationTotalBytes != null ->
            "${runningVerification.bytesVerified.coerceAtMost(verificationTotalBytes).formatBytes()} / ${verificationTotalBytes.formatBytes()} verified"
        download.state == DownloadState.Verifying && runningVerification != null -> "${runningVerification.bytesVerified.formatBytes()} verified"
        download.state == DownloadState.Completed -> actionContext.artifact.sizeBytes?.formatBytes()
            ?: totalBytes?.formatBytes()
            ?: download.bytesReceived.takeIf { it > 0L }?.formatBytes()
            ?: "Completed"
        totalBytes != null -> "${download.bytesReceived.coerceAtMost(totalBytes).formatBytes()} / ${totalBytes.formatBytes()}"
        download.bytesReceived > 0L -> download.bytesReceived.formatBytes()
        else -> actionContext.artifact.friendlyLocation
    }
    val trailing = when {
        finalizationFailure -> "Needs attention"
        download.state == DownloadState.Downloading && download.speedBytesPerSecond > 0L -> download.speedBytesPerSecond.formatSpeed()
        download.state in setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower) && download.speedBytesPerSecond > 0L -> "${download.speedBytesPerSecond.formatSpeed()} last"
        download.state == DownloadState.Completed -> truth.trailingText
        download.state == DownloadState.RecoveryRequired -> ""
        else -> truth.trailingText
    }
    val rowStatus = DownloadsWorkspacePlanner.rowStatus(download, truth)
    val destination = destinationCardLabel(download)
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val secondary = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics {
                this.selected = selected
                role = Role.Button
                contentDescription = buildString {
                    append("$displayName. ${truth.status}. $byteText. ${truth.supportingText}")
                    if (selectionMode) append(if (selected) ". Selected" else ". Not selected")
                }
            },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else XdmTheme.extendedColors.groupedSurface,
        contentColor = foreground,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                XdmMediaArtwork(
                    fileName = download.fileName,
                    mimeType = download.mimeType,
                    thumbnailUrl = thumbnailUrl,
                    sourceUrl = download.sourceUrl,
                    localUri = download.completedArtifactUri,
                    width = if (compact) 54.dp else 64.dp,
                    height = if (compact) 42.dp else 48.dp,
                    contentDescription = null,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        displayName,
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        XdmStatusBadge(truth.badge, tone = download.state.statusTone())
                        Text(
                            sourceHost,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        rowStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (progressVisible) XdmProgressLine(progress = phaseProgress, stateLabel = truth.overallProgressText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(byteText, style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(trailing, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    Text(
                        destination,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickActions.forEach { quickAction ->
                    TextButton(
                        onClick = { onQuickAction(quickAction) },
                        enabled = quickAction.enabled,
                        modifier = Modifier
                            .sizeIn(minHeight = 48.dp)
                            .semantics { contentDescription = "${quickAction.label} $displayName" },
                    ) {
                        Icon(quickAction.iconVector(), contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(quickAction.label)
                    }
                }
                IconButton(
                    onClick = onMoreActions,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "More actions for $displayName" },
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
    DownloadActionIcon.Resume -> Icons.Rounded.Download
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
