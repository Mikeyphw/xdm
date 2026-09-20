package com.mikeyphw.xdm.android.ui.debug

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.XdmApplication
import com.mikeyphw.xdm.android.MainViewModel
import com.mikeyphw.xdm.android.SettingsPageHeader
import com.mikeyphw.xdm.android.SettingsPanel
import com.mikeyphw.xdm.android.XdmActionFlowRow
import com.mikeyphw.xdm.android.XdmCardTitle
import com.mikeyphw.xdm.android.XdmListCard
import com.mikeyphw.xdm.android.XdmMetadataText
import com.mikeyphw.xdm.android.XdmSectionHeader
import com.mikeyphw.xdm.android.XdmSupportingText
import com.mikeyphw.xdm.android.XdmTechnicalText
import com.mikeyphw.xdm.android.XdmStatusBadge
import com.mikeyphw.xdm.android.XdmStatusTone
import com.mikeyphw.xdm.android.model.DebugRecorderProvider
import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.NoOpDebugEventRecorder
import com.mikeyphw.xdm.android.model.ProblemIncident
import com.mikeyphw.xdm.android.model.RollingJsonlDebugEventRecorder
import com.mikeyphw.xdm.android.copyTextToClipboard
import com.mikeyphw.xdm.android.shareDebugCenterZipExport
import com.mikeyphw.xdm.android.shareTextReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class DebugCenterPage(val label: String) {
    Problems("Problems"),
    Logs("Logs"),
    Tests("Tests"),
    Results("Results"),
    History("History"),
    Health("Health"),
}

@Composable
fun DebugCenterScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    legacyContent: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val appRecorder = (context.applicationContext as? DebugRecorderProvider)?.debugEventRecorder ?: NoOpDebugEventRecorder
    val xdmApplication = context.applicationContext as? XdmApplication
    val appContainer = xdmApplication?.container
    val problemReporter = xdmApplication?.problemReporter
    val problemFlow = remember(problemReporter) { problemReporter?.problems ?: kotlinx.coroutines.flow.MutableStateFlow<List<ProblemIncident>>(emptyList()) }
    val problems by problemFlow.collectAsState()
    val rootDirectory = remember(context) { File(context.filesDir, "debug-center") }
    val store = remember(rootDirectory) { DebugTestStore(rootDirectory) }
    val runner = remember(rootDirectory, appRecorder) { DebugTestRunner(rootDirectory, appRecorder, store) }
    val liveRun by runner.currentRun.collectAsState()
    val scope = rememberCoroutineScope()

    var page by remember(state.selectedProblemId) {
        mutableStateOf(if (state.selectedProblemId != null || problems.any { !it.resolved }) DebugCenterPage.Problems else DebugCenterPage.Tests)
    }
    var selectedIds by remember { mutableStateOf(DebugTestRegistry.defaultSelection()) }
    var latestRun by remember { mutableStateOf(store.loadRuns().firstOrNull() ?: emptyDebugRun()) }
    var history by remember { mutableStateOf(store.loadRuns()) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    val displayedRun = liveRun ?: latestRun
    val running = runningJob?.isActive == true

    fun startRun(ids: Set<String>) {
        if (ids.isEmpty() || runningJob?.isActive == true) return
        runningJob = scope.launch {
            val completed = runner.runSelected(state, ids, appContainer)
            latestRun = completed
            history = store.loadRuns()
            page = DebugCenterPage.Results
        }
        page = DebugCenterPage.Results
    }

    fun stopRun() {
        runningJob?.cancel()
        runner.markStopped()
        liveRun?.let { stopped ->
            latestRun = stopped
            history = store.loadRuns()
        }
    }

    fun refreshHistory() {
        history = store.loadRuns()
    }

    fun exportZip(run: DebugTestRun) {
        val timeline = (appRecorder as? RollingJsonlDebugEventRecorder)?.copySanitizedTimeline().orEmpty()
        val outcome = runCatching {
            store.exportRunZip(
                run = run,
                supportReportText = state.supportReportText,
                debugTimelineJsonl = timeline,
                problemIncidentsText = problemReporter?.exportText().orEmpty(),
                currentRunId = liveRun?.id ?: latestRun?.id.orEmpty(),
            )
        }
        val zip = outcome.getOrNull()
        if (zip == null) {
            Toast.makeText(
                context,
                "Diagnostics export blocked: final ZIP privacy/integrity verification failed.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        latestRun = run
        history = store.loadRuns()
        Toast.makeText(context, "Diagnostics ZIP verified and ready to share.", Toast.LENGTH_SHORT).show()
        shareDebugCenterZip(context, zip, run)
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsPageHeader("Diagnostics & support", { viewModel.selectSettingsPanel(SettingsPanel.Overview) })
        }
        item {
            XdmSupportingText(
                "Run safe local checks, inspect health, and create redacted support information. Nothing is uploaded automatically.",
                maxLines = 5,
            )
        }
        item {
            XdmListCard(compact = true) {
                XdmCardTitle("Support report")
                XdmSupportingText("Credential-like headers, cookies, tokens, signatures, and sensitive URL values are redacted locally.", maxLines = 3)
                XdmActionFlowRow {
                    OutlinedButton(onClick = { copyTextToClipboard(context, "XDM support report", state.supportReportText) }) { Text("Copy report") }
                    OutlinedButton(onClick = { shareTextReport(context, "XDM support report", state.supportReportText) }) { Text("Export report") }
                }
            }
        }
        item {
            XdmListCard(compact = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        XdmCardTitle("Debug logging")
                        XdmSupportingText(
                            if (state.verboseDebugLoggingEnabled)
                                "Verbose logging is on. Trace events are retained locally with the normal privacy redaction and rolling size limit."
                            else
                                "Standard logging is on. XDM retains normal, warning, and error events while suppressing high-volume trace events.",
                            maxLines = 4,
                        )
                    }
                    Switch(
                        checked = state.verboseDebugLoggingEnabled,
                        onCheckedChange = viewModel::setVerboseDebugLoggingEnabled,
                    )
                }
            }
        }
        item {
            XdmActionFlowRow {
                DebugCenterPage.entries.forEach { candidate ->
                    FilterChip(
                        selected = page == candidate,
                        onClick = {
                            if (candidate == DebugCenterPage.History) refreshHistory()
                            page = candidate
                        },
                        label = { Text(candidate.label) },
                    )
                }
            }
        }

        when (page) {
            DebugCenterPage.Problems -> debugProblemsPage(
                problems = problems.sortedWith(compareByDescending<ProblemIncident> { it.id == state.selectedProblemId }.thenByDescending { it.lastSeenEpochMs }),
                selectedProblemId = state.selectedProblemId,
                onResolve = { problemReporter?.resolve(it) },
                onReopen = { problemReporter?.reopen(it) },
                onClearResolved = { problemReporter?.clearResolved() },
                onCopy = { problem -> copyTextToClipboard(context, "XDM problem details", problem.toExportText()) },
            )
            DebugCenterPage.Logs -> debugLogsPage(
                recorder = appRecorder as? RollingJsonlDebugEventRecorder,
                context = context,
            )
            DebugCenterPage.Tests -> debugTestsPage(
                selectedIds = selectedIds,
                running = running,
                onSelectionChange = { selectedIds = it },
                onRunSelected = { startRun(selectedIds) },
                onRunAll = {
                    val all = DebugTestRegistry.tests.mapTo(linkedSetOf()) { it.id }
                    selectedIds = all
                    startRun(all)
                },
            )
            DebugCenterPage.Results -> debugResultsPage(
                run = displayedRun,
                running = running,
                onStop = ::stopRun,
                onRetestFailed = {
                    val failed = displayedRun.failureIds()
                    if (failed.isNotEmpty()) {
                        selectedIds = failed
                        startRun(failed)
                    }
                },
                onRunAgain = { startRun(displayedRun.selectedTestIds.toSet()) },
                onCopy = { copyTextToClipboard(context, "XDM diagnostics report", displayedRun.toReportText()) },
                onCopyResult = { result -> copyTextToClipboard(context, "XDM diagnostic details", result.toReportText()) },
                onExport = { exportZip(displayedRun) },
            )
            DebugCenterPage.History -> debugHistoryPage(
                history = history,
                onOpen = { run ->
                    latestRun = run
                    page = DebugCenterPage.Results
                },
                onCopy = { run -> copyTextToClipboard(context, "XDM diagnostics report", run.toReportText()) },
                onExport = { run -> exportZip(run) },
            )
            DebugCenterPage.Health -> item { legacyContent() }
        }
    }
}



private fun LazyListScope.debugLogsPage(
    recorder: RollingJsonlDebugEventRecorder?,
    context: Context,
) {
    if (recorder == null) {
        item {
            XdmListCard {
                XdmCardTitle("Logs unavailable")
                XdmSupportingText("The structured debug recorder is not installed in this process.", maxLines = 3)
            }
        }
        return
    }

    item {
        var query by remember { mutableStateOf("") }
        var severity by remember { mutableStateOf("All") }
        var refreshKey by remember { mutableStateOf(0) }
        var liveRefresh by remember { mutableStateOf(true) }
        var expandedId by remember { mutableStateOf<String?>(null) }
        var confirmClear by remember { mutableStateOf(false) }
        LaunchedEffect(liveRefresh) {
            while (liveRefresh) {
                delay(2_000)
                refreshKey++
            }
        }
        val lines = remember(refreshKey) { recorder.readRecentJsonLines() }
        val visible = remember(lines, query, severity) {
            lines.filter { line ->
                (severity == "All" || debugJsonString(line, "severity") == severity) &&
                    (query.isBlank() || line.contains(query, ignoreCase = true))
            }
        }
        val counts = remember(lines) {
            listOf("Error", "Warning", "Info", "Trace").associateWith { level ->
                lines.count { debugJsonString(it, "severity") == level }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear structured logs?") },
                text = { Text("This permanently removes the current and retained rotated debug sessions from this device. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        recorder.clear()
                        expandedId = null
                        confirmClear = false
                        refreshKey++
                        Toast.makeText(context, "Structured logs cleared", Toast.LENGTH_SHORT).show()
                    }) { Text("Clear logs") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            XdmListCard(compact = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        XdmCardTitle("Structured logs")
                        XdmSupportingText(
                            "Private, redacted diagnostic events from the current and retained sessions.",
                            maxLines = 2,
                        )
                    }
                    XdmStatusBadge(if (liveRefresh) "Live" else "Paused", tone = if (liveRefresh) XdmStatusTone.Success else XdmStatusTone.Neutral)
                }
                XdmActionFlowRow {
                    XdmStatusBadge("${lines.size} loaded", tone = XdmStatusTone.Info)
                    if ((counts["Error"] ?: 0) > 0) XdmStatusBadge("${counts["Error"]} errors", tone = XdmStatusTone.Error)
                    if ((counts["Warning"] ?: 0) > 0) XdmStatusBadge("${counts["Warning"]} warnings", tone = XdmStatusTone.Warning)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search logs") },
                    placeholder = { Text("Area, action, result, details…") },
                    singleLine = true,
                )
                XdmActionFlowRow {
                    listOf("All", "Error", "Warning", "Info", "Trace").forEach { level ->
                        val count = if (level == "All") lines.size else counts[level] ?: 0
                        FilterChip(
                            selected = severity == level,
                            onClick = { severity = level },
                            label = { Text("$level ($count)") },
                        )
                    }
                }
                XdmActionFlowRow {
                    OutlinedButton(onClick = { liveRefresh = !liveRefresh }) { Text(if (liveRefresh) "Pause live" else "Resume live") }
                    OutlinedButton(onClick = { refreshKey++ }) { Text("Refresh") }
                    if (query.isNotBlank() || severity != "All") {
                        TextButton(onClick = { query = ""; severity = "All" }) { Text("Reset filters") }
                    }
                }
                XdmActionFlowRow {
                    OutlinedButton(
                        onClick = { copyTextToClipboard(context, "XDM structured logs", visible.asReversed().joinToString("\n")) },
                        enabled = visible.isNotEmpty(),
                    ) { Text("Copy visible") }
                    OutlinedButton(
                        onClick = { shareTextReport(context, "XDM structured logs", lines.asReversed().joinToString("\n")) },
                        enabled = lines.isNotEmpty(),
                    ) { Text("Export logs") }
                    TextButton(onClick = { confirmClear = true }, enabled = lines.isNotEmpty()) { Text("Clear logs") }
                }
                XdmMetadataText("Showing ${visible.size} of ${lines.size} · newest first · up to 500 entries / 256 KiB")
            }

            if (visible.isEmpty()) {
                XdmListCard {
                    XdmCardTitle(if (lines.isEmpty()) "No logs yet" else "No matching logs")
                    XdmSupportingText(
                        if (lines.isEmpty()) "Use XDM normally and events will appear here automatically. Trace entries require Verbose debug logging."
                        else "No entries match the current search and severity filter.",
                        maxLines = 3,
                    )
                    if (lines.isNotEmpty()) TextButton(onClick = { query = ""; severity = "All" }) { Text("Show all logs") }
                }
            } else {
                visible.forEach { line ->
                    val id = debugJsonString(line, "id").ifBlank { line.hashCode().toString() }
                    val isExpanded = expandedId == id
                    val level = debugJsonString(line, "severity")
                    val tone = when (level) {
                        "Error" -> XdmStatusTone.Error
                        "Warning" -> XdmStatusTone.Warning
                        "Info" -> XdmStatusTone.Info
                        else -> XdmStatusTone.Neutral
                    }
                    val details = debugJsonObjectSummary(line, "safeDetails")
                    XdmListCard(compact = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                XdmActionFlowRow {
                                    XdmStatusBadge(level.ifBlank { "Unknown" }, tone = tone)
                                    XdmMetadataText(debugJsonString(line, "area"))
                                }
                                XdmCardTitle(debugJsonString(line, "action").ifBlank { "Diagnostic event" })
                                XdmSupportingText(debugJsonString(line, "result").ifBlank { "No result" }, maxLines = 2)
                                if (details.isNotBlank()) XdmSupportingText(details, maxLines = if (isExpanded) 6 else 2)
                                XdmMetadataText(formatDebugTimestamp(debugJsonLong(line, "timestampMillis")))
                            }
                            TextButton(onClick = { expandedId = if (isExpanded) null else id }) { Text(if (isExpanded) "Hide" else "Details") }
                        }
                        if (isExpanded) {
                            XdmMetadataText("Raw redacted JSON")
                            XdmTechnicalText(line, maxLines = 24)
                            TextButton(onClick = { copyTextToClipboard(context, "XDM log entry", line) }) { Text("Copy JSON") }
                        }
                    }
                }
            }
        }
    }
}

private fun debugJsonObjectSummary(json: String, key: String): String {
    val marker = "\"$key\":{"
    val start = json.indexOf(marker)
    if (start < 0) return ""
    val bodyStart = start + marker.length
    var index = bodyStart
    var inString = false
    var escaped = false
    while (index < json.length) {
        val ch = json[index]
        if (escaped) escaped = false
        else if (ch == '\\' && inString) escaped = true
        else if (ch == '"') inString = !inString
        else if (ch == '}' && !inString) break
        index++
    }
    if (index <= bodyStart) return ""
    return json.substring(bodyStart, index)
        .replace("\",\"", " · ")
        .replace(Regex("\\\"([^\\\"]+)\\\":\\\"([^\\\"]*)\\\"")) { match -> "${match.groupValues[1]}: ${match.groupValues[2]}" }
        .replace("\\\\n", " ")
        .replace("\\\\t", " ")
        .trim()
}

private fun debugJsonString(json: String, key: String): String {
    val marker = "\"$key\":\""
    val start = json.indexOf(marker)
    if (start < 0) return ""
    var index = start + marker.length
    val out = StringBuilder()
    var escaped = false
    while (index < json.length) {
        val ch = json[index++]
        if (escaped) {
            out.append(when (ch) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> ch })
            escaped = false
        } else if (ch == '\\') {
            escaped = true
        } else if (ch == '"') {
            break
        } else {
            out.append(ch)
        }
    }
    return out.toString()
}

private fun debugJsonLong(json: String, key: String): Long {
    val marker = "\"$key\":"
    val start = json.indexOf(marker)
    if (start < 0) return 0L
    return json.substring(start + marker.length).takeWhile { it.isDigit() || it == '-' }.toLongOrNull() ?: 0L
}

private fun formatDebugTimestamp(epochMillis: Long): String = if (epochMillis <= 0L) {
    "Unknown time"
} else {
    java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(epochMillis))
}

private fun LazyListScope.debugProblemsPage(
    problems: List<ProblemIncident>,
    selectedProblemId: String?,
    onResolve: (String) -> Unit,
    onReopen: (String) -> Unit,
    onClearResolved: () -> Unit,
    onCopy: (ProblemIncident) -> Unit,
) {
    val activeCount = problems.count { !it.resolved }
    item {
        XdmListCard {
            XdmCardTitle("Runtime problems")
            XdmSupportingText(
                if (activeCount == 0)
                    "No unresolved runtime problems are recorded. XDM keeps this list local and bounded."
                else
                    "$activeCount unresolved problem${if (activeCount == 1) "" else "s"}. Repeated occurrences are grouped so the notification drawer is not spammed.",
                maxLines = 4,
            )
            XdmActionFlowRow {
                OutlinedButton(onClick = onClearResolved, enabled = problems.any(ProblemIncident::resolved)) { Text("Clear resolved") }
            }
        }
    }
    if (problems.isEmpty()) return
    items(problems, key = { it.id }) { problem ->
        XdmListCard(compact = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(problem.title)
                    XdmMetadataText(
                        listOfNotNull(
                            problem.area.name.replace(Regex("([a-z])([A-Z])"), "$1 $2"),
                            if (problem.occurrenceCount == 1) "1 occurrence" else "${problem.occurrenceCount} occurrences",
                            "Opened from notification".takeIf { problem.id == selectedProblemId },
                        ).joinToString(" • "),
                        maxLines = 2,
                    )
                }
                XdmStatusBadge(
                    text = if (problem.resolved) "Resolved" else problem.severity.name,
                    tone = when {
                        problem.resolved -> XdmStatusTone.Neutral
                        problem.severity == com.mikeyphw.xdm.android.model.DebugSeverity.Error -> XdmStatusTone.Error
                        problem.severity == com.mikeyphw.xdm.android.model.DebugSeverity.Warning -> XdmStatusTone.Warning
                        else -> XdmStatusTone.Info
                    },
                )
            }
            XdmSupportingText(problem.summary, maxLines = 5)
            problem.suggestedAction?.takeIf(String::isNotBlank)?.let { action ->
                XdmMetadataText("Recommended action: $action", maxLines = 4)
            }
            problem.operationId?.takeIf(String::isNotBlank)?.let { XdmTechnicalText("Operation: $it", maxLines = 2) }
            problem.downloadId?.takeIf(String::isNotBlank)?.let { XdmTechnicalText("Download: $it", maxLines = 2) }
            XdmActionFlowRow {
                if (problem.resolved) {
                    OutlinedButton(onClick = { onReopen(problem.id) }) { Text("Reopen") }
                } else {
                    Button(onClick = { onResolve(problem.id) }) { Text("Mark resolved") }
                }
                TextButton(onClick = { onCopy(problem) }) { Text("Copy details") }
            }
        }
    }
}

private fun shareDebugCenterZip(
    context: Context,
    zip: File,
    run: DebugTestRun,
) {
    shareDebugCenterZipExport(
        context = context,
        zip = zip,
        subject = "XDM diagnostics export",
        reportText = run.toReportText(),
    )
}

private fun LazyListScope.debugTestsPage(
    selectedIds: Set<String>,
    running: Boolean,
    onSelectionChange: (Set<String>) -> Unit,
    onRunSelected: () -> Unit,
    onRunAll: () -> Unit,
) {
    item {
        XdmListCard {
            XdmCardTitle("Quick tests")
            XdmSupportingText("Use a preset for one area, or select exact checks below.", maxLines = 3)
            XdmActionFlowRow {
                listOf(DebugTestGroup.BasicHealth, DebugTestGroup.Downloads, DebugTestGroup.Browser, DebugTestGroup.Media).forEach { group ->
                    OutlinedButton(
                        onClick = { onSelectionChange(DebugTestRegistry.groupIds(group)) },
                        enabled = !running,
                    ) { Text(group.label) }
                }
            }
            XdmActionFlowRow {
                Button(onClick = onRunSelected, enabled = selectedIds.isNotEmpty() && !running) { Text("Run selected") }
                OutlinedButton(onClick = onRunAll, enabled = !running) { Text("Run all") }
            }
        }
    }

    DebugTestRegistry.definitions.groupBy { it.group }.forEach { (group, definitions) ->
        item {
            XdmListCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        XdmCardTitle(group.label)
                        XdmMetadataText("${definitions.count { it.id in selectedIds }} of ${definitions.size} selected")
                    }
                    TextButton(
                        onClick = { onSelectionChange((selectedIds + definitions.map { it.id }).toSet()) },
                        enabled = !running,
                    ) { Text("Select") }
                    TextButton(
                        onClick = { onSelectionChange(selectedIds - definitions.map { it.id }.toSet()) },
                        enabled = !running,
                    ) { Text("Clear") }
                }
                definitions.forEach { definition ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = definition.id in selectedIds,
                            enabled = !running,
                            onCheckedChange = { checked ->
                                onSelectionChange(if (checked) selectedIds + definition.id else selectedIds - definition.id)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(definition.displayTitle)
                            XdmMetadataText(definition.description, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.debugResultsPage(
    run: DebugTestRun,
    running: Boolean,
    onStop: () -> Unit,
    onRetestFailed: () -> Unit,
    onRunAgain: () -> Unit,
    onCopy: () -> Unit,
    onCopyResult: (DebugTestResult) -> Unit,
    onExport: () -> Unit,
) {
    item {
        XdmListCard {
            XdmCardTitle("Test results")
            if (running) {
                XdmStatusBadge("Running", tone = XdmStatusTone.Info)
                XdmSupportingText(run.summaryLabel, maxLines = 2)
                if (run.total > 0) {
                    LinearProgressIndicator(
                        progress = { run.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (run.results.isNotEmpty()) {
                XdmStatusBadge(
                    text = if (run.failed > 0) "Run complete • action needed" else "Run complete",
                    tone = if (run.failed > 0) XdmStatusTone.Warning else XdmStatusTone.Success,
                )
                XdmSupportingText(run.summaryLabel, maxLines = 2)
            } else {
                XdmSupportingText("No test run yet", maxLines = 2)
            }
            XdmActionFlowRow {
                if (running) {
                    Button(onClick = onStop) { Text("Stop") }
                }
                Button(onClick = onRetestFailed, enabled = !running && run.failureIds().isNotEmpty()) { Text("Retest failed") }
                OutlinedButton(onClick = onRunAgain, enabled = !running && run.selectedTestIds.isNotEmpty()) { Text("Run again") }
                OutlinedButton(onClick = onCopy, enabled = run.results.isNotEmpty()) { Text("Copy results") }
                OutlinedButton(onClick = onExport, enabled = run.results.isNotEmpty() && !running) { Text("Export ZIP") }
            }
        }
    }

    items(run.sortedResults(), key = { it.testId }) { result ->
        var showTechnicalDetails by remember(result.testId, result.startedAtEpochMs) { mutableStateOf(false) }
        val important = result.status == DebugTestStatus.Failed || result.status == DebugTestStatus.Warning
        val redactedDetails = remember(result.details) { DebugRedactor.redactDetails(result.details) }
        XdmListCard(compact = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.displayTitle)
                }
                XdmStatusBadge(
                    result.status.label,
                    tone = when (result.status) {
                        DebugTestStatus.Failed -> XdmStatusTone.Error
                        DebugTestStatus.Warning -> XdmStatusTone.Warning
                        DebugTestStatus.Passed -> XdmStatusTone.Success
                        DebugTestStatus.Running -> XdmStatusTone.Info
                        else -> XdmStatusTone.Neutral
                    },
                )
            }
            XdmSupportingText(conciseDebugSummary(result.summary), maxLines = if (important) 4 else 3)
            result.suggestedAction?.takeIf(String::isNotBlank)?.let { action ->
                XdmMetadataText("Recommended action: ${DebugRedactor.redactText(action)}", maxLines = 4)
            }
            if (important && (result.errorCode?.isNotBlank() == true || redactedDetails.isNotEmpty() || result.summary.contains('\n'))) {
                TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                    Text(if (showTechnicalDetails) "Hide technical details" else "Technical details")
                }
            }
            if (showTechnicalDetails) {
                XdmTechnicalText("Group: ${result.groupId}")
                result.errorCode?.takeIf(String::isNotBlank)?.let { code ->
                    XdmTechnicalText("Error: ${DebugRedactor.redactText(code)}", maxLines = 5)
                }
                if (result.summary.contains('\n')) {
                    XdmTechnicalText("Full summary: ${DebugRedactor.redactText(result.summary)}", maxLines = 12)
                }
                redactedDetails.forEach { (key, value) ->
                    XdmTechnicalText("$key: $value", maxLines = 8)
                }
                TextButton(onClick = { onCopyResult(result) }) { Text("Copy technical details") }
            }
        }
    }
}

private fun conciseDebugSummary(summary: String): String {
    val firstUsefulLine = summary.lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
    return if (firstUsefulLine.length <= 240) firstUsefulLine else firstUsefulLine.take(237) + "…"
}

private fun LazyListScope.debugHistoryPage(
    history: List<DebugTestRun>,
    onOpen: (DebugTestRun) -> Unit,
    onCopy: (DebugTestRun) -> Unit,
    onExport: (DebugTestRun) -> Unit,
) {
    if (history.isEmpty()) {
        item {
            XdmListCard {
                XdmCardTitle("No previous runs")
                XdmSupportingText("Run selected tests or the full suite to create private history.", maxLines = 3)
            }
        }
        return
    }
    items(history) { run ->
        XdmListCard {
            XdmCardTitle("Diagnostic run")
            XdmTechnicalText(run.id, maxLines = 2)
            XdmSupportingText(run.summaryLabel, maxLines = 2)
            XdmMetadataText("Started: ${run.startedAtEpochMs} • Finished: ${run.finishedAtEpochMs ?: "running"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen(run) }) { Text("Open") }
                OutlinedButton(onClick = { onCopy(run) }) { Text("Copy") }
                OutlinedButton(onClick = { onExport(run) }) { Text("Export ZIP") }
            }
        }
    }
}

private fun emptyDebugRun(): DebugTestRun = DebugTestRun(
    id = "no-run",
    startedAtEpochMs = 0L,
    finishedAtEpochMs = null,
    selectedTestIds = emptyList(),
    results = emptyList(),
)

