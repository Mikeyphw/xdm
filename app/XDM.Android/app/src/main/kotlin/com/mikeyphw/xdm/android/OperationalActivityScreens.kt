package com.mikeyphw.xdm.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.OperationalActivityCategory
import com.mikeyphw.xdm.android.model.OperationalActivityEvent
import com.mikeyphw.xdm.android.model.OperationalActivityFilter
import com.mikeyphw.xdm.android.model.OperationalActivityPlanner
import com.mikeyphw.xdm.android.model.OperationalActivitySeverity
import com.mikeyphw.xdm.android.model.OperationalActivitySummary
import com.mikeyphw.xdm.android.model.OperationalActivityTimeRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ActivityTimelineScreen(
    events: List<OperationalActivityEvent>,
    onAction: (OperationalActivityEvent) -> Unit,
    onDismiss: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<OperationalActivityCategory?>(null) }
    var severity by remember { mutableStateOf<OperationalActivitySeverity?>(null) }
    var timeRange by remember { mutableStateOf(OperationalActivityTimeRange.SevenDays) }
    val filtered = OperationalActivityPlanner.filter(
        events,
        OperationalActivityFilter(query, category, severity, timeRange, attentionOnly = false),
    )

    Column(Modifier.fillMaxSize()) {
        ActivityFilterBar(
            query = query,
            onQueryChanged = { query = it },
            category = category,
            onCategoryChanged = { category = it },
            severity = severity,
            onSeverityChanged = { severity = it },
            timeRange = timeRange,
            onTimeRangeChanged = { timeRange = it },
        )
        ActivityEventList(
            events = filtered,
            emptyTitle = "No matching activity",
            emptyDescription = "Change the search, category, severity, or time range to widen the operational timeline.",
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun ActivityAttentionScreen(
    events: List<OperationalActivityEvent>,
    onAction: (OperationalActivityEvent) -> Unit,
    onDismiss: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = OperationalActivityPlanner.filter(
        events,
        OperationalActivityFilter(
            query = query,
            timeRange = OperationalActivityTimeRange.All,
            attentionOnly = true,
        ),
    )
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search attention") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ActivityEventList(
            events = filtered,
            emptyTitle = "Nothing needs attention",
            emptyDescription = "Policy holds, authentication failures, storage problems, verification failures, and recovery records will appear here.",
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun ActivityDecisionsScreen(
    events: List<OperationalActivityEvent>,
    onEvaluateNow: () -> Unit,
    onAction: (OperationalActivityEvent) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val decisions = events.filter { it.source == "queue-policy" }
    Column(Modifier.fillMaxSize()) {
        XdmListCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), compact = true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Explainable queue decisions")
                    XdmSupportingText("Network, power, storage, schedule, retry, and concurrency holds remain visible instead of silently stalling transfers.", maxLines = 3)
                }
                TextButton(onClick = onEvaluateNow) { Text("Evaluate now") }
            }
        }
        ActivityEventList(
            events = decisions,
            emptyTitle = "No queue decisions yet",
            emptyDescription = "Run queue evaluation or add a queued transfer to create a privacy-safe decision trail.",
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun OperationalActivityOverviewCard(
    summary: OperationalActivitySummary,
    events: List<OperationalActivityEvent>,
    onOpenTimeline: () -> Unit,
    onOpenAttention: () -> Unit,
    onOpenDecisions: () -> Unit,
) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle("Operational timeline")
                XdmSupportingText("Transfers, policy decisions, handoffs, verification, recovery, and diagnostics share one searchable flight recorder.", maxLines = 3)
            }
            TextButton(onClick = onOpenTimeline) { Text("Open timeline") }
        }
        XdmActionFlowRow {
            XdmStatusBadge("${summary.total} events", tone = XdmStatusTone.Neutral)
            summary.unresolved.takeIf { it > 0 }?.let { XdmStatusBadge("$it attention", tone = XdmStatusTone.Error) }
            summary.policyHolds.takeIf { it > 0 }?.let { XdmStatusBadge("$it decisions", tone = XdmStatusTone.Warning) }
            summary.networkHolds.takeIf { it > 0 }?.let { XdmStatusBadge("$it network", tone = XdmStatusTone.Info) }
            summary.storageHolds.takeIf { it > 0 }?.let { XdmStatusBadge("$it storage", tone = XdmStatusTone.Warning) }
        }
        events.take(3).forEach { event ->
            XdmMetadataText("${formatActivityTime(event.createdAtEpochMs)} • ${event.title} • ${event.fileName ?: event.category.label}", maxLines = 2)
        }
        XdmActionFlowRow {
            TextButton(onClick = onOpenAttention, enabled = summary.unresolved > 0) { Text("Attention") }
            TextButton(onClick = onOpenDecisions, enabled = summary.policyHolds > 0) { Text("Decisions") }
        }
    }
}

@Composable
fun OperationalDiagnosticsHeader(
    summary: OperationalActivitySummary,
    diagnosticsExport: String,
    onOpenTimeline: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val context = LocalContext.current
    XdmListCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), compact = true) {
        XdmCardTitle("Privacy-safe operational export")
        XdmSupportingText("Exports app/build identity, engines, transfer states, policy decisions, failure categories, and recent events. Cookie, authorization, token, signature, and credential values stay redacted.", maxLines = 4)
        XdmActionFlowRow {
            XdmStatusBadge("${summary.total} events", tone = XdmStatusTone.Neutral)
            XdmStatusBadge(if (diagnosticsExport.contains("<redacted>")) "redaction sealed" else "safe fields only", tone = XdmStatusTone.Success)
            summary.unresolved.takeIf { it > 0 }?.let { XdmStatusBadge("$it unresolved", tone = XdmStatusTone.Warning) }
        }
        XdmActionFlowRow {
            Button(
                onClick = { copyOperationalText(context, "XDM operational diagnostics", diagnosticsExport) },
                enabled = diagnosticsExport.isNotBlank(),
            ) { Text("Copy diagnostics") }
            TextButton(onClick = onOpenTimeline) { Text("Open timeline") }
            TextButton(onClick = onClearHistory, enabled = summary.total > 0) { Text("Clear resolved history") }
        }
        XdmMetadataText("Clearing Activity history never removes downloads, files, recovery records, or queue definitions. Unresolved locally recorded events are preserved.", maxLines = 3)
    }
}

@Composable
private fun ActivityFilterBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    category: OperationalActivityCategory?,
    onCategoryChanged: (OperationalActivityCategory?) -> Unit,
    severity: OperationalActivitySeverity?,
    onSeverityChanged: (OperationalActivitySeverity?) -> Unit,
    timeRange: OperationalActivityTimeRange,
    onTimeRangeChanged: (OperationalActivityTimeRange) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            label = { Text("Search filename, engine, category, or status") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = category == null, onClick = { onCategoryChanged(null) }, label = { Text("All categories") })
            }
            items(OperationalActivityCategory.entries) { item ->
                FilterChip(selected = category == item, onClick = { onCategoryChanged(if (category == item) null else item) }, label = { Text(item.label) })
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = severity == null, onClick = { onSeverityChanged(null) }, label = { Text("All severities") })
            }
            items(OperationalActivitySeverity.entries) { item ->
                FilterChip(selected = severity == item, onClick = { onSeverityChanged(if (severity == item) null else item) }, label = { Text(item.label) })
            }
            items(OperationalActivityTimeRange.entries) { item ->
                FilterChip(selected = timeRange == item, onClick = { onTimeRangeChanged(item) }, label = { Text(item.label) })
            }
        }
    }
}

@Composable
private fun ColumnScope.ActivityEventList(
    events: List<OperationalActivityEvent>,
    emptyTitle: String,
    emptyDescription: String,
    onAction: (OperationalActivityEvent) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (events.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            EmptyFeatureScreen(emptyTitle, emptyDescription)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(events, key = OperationalActivityEvent::id) { event ->
            ActivityEventCard(event, onAction, onDismiss)
        }
    }
}

@Composable
private fun ActivityEventCard(
    event: OperationalActivityEvent,
    onAction: (OperationalActivityEvent) -> Unit,
    onDismiss: (String) -> Unit,
) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(event.title, maxLines = 2)
                XdmMetadataText(formatActivityTime(event.createdAtEpochMs), maxLines = 1)
            }
            XdmStatusBadge(event.severity.label, tone = event.severity.tone)
        }
        XdmActionFlowRow {
            XdmStatusBadge(event.category.label, tone = XdmStatusTone.Neutral)
            event.engine?.let { XdmStatusBadge(it, tone = XdmStatusTone.Info) }
            if (event.unresolved) XdmStatusBadge("Unresolved", tone = XdmStatusTone.Warning)
            event.nextEligibleAtEpochMs?.let { XdmStatusBadge("Retry ${formatActivityTime(it)}", tone = XdmStatusTone.Neutral) }
        }
        event.fileName?.let { XdmMetricText(it) }
        XdmSupportingText(event.detail, maxLines = 4)
        HorizontalDivider()
        XdmActionFlowRow {
            event.actionLabel?.let {
                Button(onClick = { onAction(event) }) { Text(it) }
            }
            TextButton(onClick = { onDismiss(event.id) }) { Text("Dismiss") }
        }
    }
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

private fun copyOperationalText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
