package com.mikeyphw.xdm.android.ui.debug

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.mikeyphw.xdm.android.model.RollingJsonlDebugEventRecorder
import com.mikeyphw.xdm.android.copyTextToClipboard
import com.mikeyphw.xdm.android.shareDebugCenterZipExport
import com.mikeyphw.xdm.android.shareTextReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private enum class DebugCenterPage(val label: String) {
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
    val appContainer = (context.applicationContext as? XdmApplication)?.container
    val rootDirectory = remember(context) { File(context.filesDir, "debug-center") }
    val store = remember(rootDirectory) { DebugTestStore(rootDirectory) }
    val runner = remember(rootDirectory, appRecorder) { DebugTestRunner(rootDirectory, appRecorder, store) }
    val liveRun by runner.currentRun.collectAsState()
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf(DebugCenterPage.Tests) }
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
        val zip = store.exportRunZip(
            run = run,
            supportReportText = state.supportReportText,
            debugTimelineJsonl = timeline,
        )
        latestRun = run
        history = store.loadRuns()
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

