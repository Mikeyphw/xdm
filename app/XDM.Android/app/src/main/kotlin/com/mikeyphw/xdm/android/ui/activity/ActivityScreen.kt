package com.mikeyphw.xdm.android

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.OperationalActivityEvent
import com.mikeyphw.xdm.android.model.OperationalActivitySeverity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
@UiSurface(UiAudience.User, "Review items that need attention and recent transfer activity")
fun ActivityWorkspaceScreen(
    events: List<OperationalActivityEvent>,
    selectedPanel: ActivityPanel,
    onPanelChanged: (ActivityPanel) -> Unit,
    onOpenManage: () -> Unit,
    onAction: (OperationalActivityEvent) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val normalizedPanel = selectedPanel.normalized(false).takeIf { it.isPrimary } ?: ActivityPanel.Attention
    val metrics = remember(events) { ActivityWorkspacePlanner.metrics(events) }
    val visibleEvents = remember(events, normalizedPanel) { ActivityWorkspacePlanner.forPanel(events, normalizedPanel) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .xdmScreen(XdmScreenTags.Activity, "Activity")
            .xdmStateDescription(
                if (normalizedPanel == ActivityPanel.Attention) "Needs attention selected" else "Recent selected",
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmSectionHeader("Activity")
                    XdmSupportingText("See what needs a decision now and what happened recently.", maxLines = 2)
                }
                TextButton(onClick = onOpenManage) { Text("Manage") }
            }
        }
        item {
            XdmListCard(compact = true) {
                XdmActionFlowRow {
                    StatusPill(
                        "${metrics.needsAttention} need attention",
                        if (metrics.needsAttention > 0) XdmStatusTone.Warning else XdmStatusTone.Success,
                    )
                    StatusPill(
                        "${metrics.decisionsWaiting} decisions",
                        if (metrics.decisionsWaiting > 0) XdmStatusTone.Info else XdmStatusTone.Neutral,
                    )
                    StatusPill("${metrics.eventsToday} today", XdmStatusTone.Neutral)
                }
            }
        }
        item {
            XdmActionFlowRow {
                ActivityPanel.primaryPanels.forEach { panel ->
                    FilterChip(
                        selected = normalizedPanel == panel,
                        onClick = { onPanelChanged(panel) },
                        label = { Text(panel.label) },
                        modifier = Modifier
                            .testTag(
                                if (panel == ActivityPanel.Attention) XdmScreenTags.ActivityAttention else XdmScreenTags.ActivityRecent,
                            )
                            .semantics {
                                stateDescription = if (normalizedPanel == panel) "${panel.label} selected" else "${panel.label} not selected"
                            },
                    )
                }
            }
        }
        if (visibleEvents.isEmpty()) {
            item {
                XdmListCard {
                    XdmCardTitle(if (normalizedPanel == ActivityPanel.Attention) "Nothing needs attention" else "No recent activity")
                    XdmSupportingText(
                        if (normalizedPanel == ActivityPanel.Attention) {
                            "Downloads that need a decision, permission, connection, verification, or recovery step will appear here."
                        } else {
                            "Transfer progress, completed downloads, queue decisions, and external handoffs will appear here."
                        },
                        maxLines = 4,
                    )
                }
            }
        } else {
            items(visibleEvents, key = OperationalActivityEvent::id) { event ->
                ActivityEventRow(event = event, onAction = onAction, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun ActivityEventRow(
    event: OperationalActivityEvent,
    onAction: (OperationalActivityEvent) -> Unit,
    onDismiss: (String) -> Unit,
) {
    XdmListCard(
        compact = true,
        modifier = Modifier.semantics {
            contentDescription = listOfNotNull(event.title, event.fileName, event.detail).joinToString(". ")
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(event.title, maxLines = 2)
                event.fileName?.takeIf(String::isNotBlank)?.let { XdmMetricText(it) }
                XdmMetadataText(formatActivityTime(event.createdAtEpochMs), maxLines = 1)
            }
            StatusPill(event.severity.userLabel, event.severity.tone)
        }
        XdmSupportingText(event.detail, maxLines = 3)
        XdmMetadataText(ActivityWorkspacePlanner.consequence(event), maxLines = 2)
        HorizontalDivider()
        if (event.actionLabel != null) {
            Button(onClick = { onAction(event) }) { Text(event.actionLabel) }
        } else {
            TextButton(onClick = { onDismiss(event.id) }) { Text("Dismiss") }
        }
    }
}

private val OperationalActivitySeverity.userLabel: String
    get() = when (this) {
        OperationalActivitySeverity.Info -> "Info"
        OperationalActivitySeverity.Success -> "Done"
        OperationalActivitySeverity.Warning -> "Waiting"
        OperationalActivitySeverity.Error -> "Action needed"
    }

private val OperationalActivitySeverity.tone: XdmStatusTone
    get() = when (this) {
        OperationalActivitySeverity.Info -> XdmStatusTone.Info
        OperationalActivitySeverity.Success -> XdmStatusTone.Success
        OperationalActivitySeverity.Warning -> XdmStatusTone.Warning
        OperationalActivitySeverity.Error -> XdmStatusTone.Error
    }

private val activityTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
    .withZone(ZoneId.systemDefault())

private fun formatActivityTime(epochMs: Long): String = activityTimeFormatter.format(Instant.ofEpochMilli(epochMs))
