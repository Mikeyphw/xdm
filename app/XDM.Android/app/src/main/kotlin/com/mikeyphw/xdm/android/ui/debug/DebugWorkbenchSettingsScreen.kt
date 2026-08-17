package com.mikeyphw.xdm.android.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.MainViewModel
import com.mikeyphw.xdm.android.SettingsPanel
import com.mikeyphw.xdm.android.SettingsPageHeader
import com.mikeyphw.xdm.android.XdmActionFlowRow
import com.mikeyphw.xdm.android.XdmCardTitle
import com.mikeyphw.xdm.android.XdmFlatCard
import com.mikeyphw.xdm.android.XdmListCard
import com.mikeyphw.xdm.android.XdmListSeparator
import com.mikeyphw.xdm.android.XdmMetadataText
import com.mikeyphw.xdm.android.XdmMetric
import com.mikeyphw.xdm.android.XdmMetricStrip
import com.mikeyphw.xdm.android.XdmSectionHeader
import com.mikeyphw.xdm.android.XdmSupportingText
import com.mikeyphw.xdm.android.model.displayLabel
import com.mikeyphw.xdm.android.model.supportLabel
import com.mikeyphw.xdm.android.copyTextToClipboard

@Composable
fun DebugWorkbenchSettingsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val report = state.debugWorkbenchReport
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Debug Workbench", onBack = { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Debug Workbench")
                XdmSupportingText("Read-only support diagnostics. Nothing here starts transfers, opens viewers, probes files, or uploads reports.", maxLines = 4)
                XdmMetricStrip(
                    listOf(
                        XdmMetric("Status", report.overallLabel),
                        XdmMetric("Checks", "${report.passingChecks} pass • ${report.warningChecks} notes • ${report.failingChecks} attention"),
                        XdmMetric("First check", report.checks.firstOrNull()?.state?.displayLabel() ?: "No checks"),
                    ),
                )
                XdmMetadataText("Areas: ${report.debugAreas.joinToString { it.supportLabel() }}", maxLines = 4)
                XdmActionFlowRow {
                    Button(onClick = { copyTextToClipboard(context, "XDM debug status", report.toClipboardReport()) }) {
                        Text("Copy debug status")
                    }
                    Button(onClick = { copyTextToClipboard(context, "XDM support report", state.supportReportText) }) {
                        Text("Copy support report")
                    }
                }
            }
        }
        item { XdmSectionHeader("Health checks") }
        item {
            XdmFlatCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    report.checks.forEachIndexed { index, check ->
                        XdmCardTitle(check.title)
                        XdmSupportingText("${check.state.displayLabel()} • ${check.detail}", maxLines = 3)
                        if (index != report.checks.lastIndex) XdmListSeparator()
                    }
                }
            }
        }
        item { MediaSniffingLabCard() }
        item { BrowserBridgeDebuggerCard(state) }
        item { AddDownloadDebuggerCard(state) }
        item { TransferNotificationDebuggerCard(state) }
        item { RuntimeSelfTestSuiteCard(state) }
    }
}
