package com.mikeyphw.xdm.android.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.MainViewModel
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
import com.mikeyphw.xdm.android.copyTextToClipboard
import com.mikeyphw.xdm.android.model.displayLabel
import com.mikeyphw.xdm.android.model.supportLabel

@Composable
fun DebugWorkbenchSettingsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    DebugCenterScreen(
        state = state,
        viewModel = viewModel,
    ) {
        DebugWorkbenchLegacyCards(state)
    }
}

@Composable
private fun DebugWorkbenchLegacyCards(state: MainUiState) {
    val context = LocalContext.current
    val report = state.debugWorkbenchReport

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        XdmSectionHeader("Live status")
        XdmListCard {
            XdmCardTitle("Diagnostic health")
            XdmSupportingText(
                "Read-only support diagnostics. Nothing here starts transfers, opens viewers, modifies downloads, or uploads reports.",
                maxLines = 4,
            )
            XdmMetricStrip(
                listOf(
                    XdmMetric("Status", report.overallLabel),
                    XdmMetric(
                        "Checks",
                        "${report.passingChecks} passed • ${report.warningChecks} notes • ${report.failingChecks} need action",
                    ),
                    XdmMetric(
                        "First check",
                        report.checks.firstOrNull()?.state?.displayLabel() ?: "No checks",
                    ),
                ),
            )
            XdmMetadataText(
                "Areas: ${report.debugAreas.joinToString { it.supportLabel() }}",
                maxLines = 4,
            )
            XdmActionFlowRow {
                Button(
                    onClick = {
                        copyTextToClipboard(
                            context,
                            "XDM debug status",
                            report.toClipboardReport(),
                        )
                    },
                ) {
                    Text("Copy debug status")
                }
                Button(
                    onClick = {
                        copyTextToClipboard(
                            context,
                            "XDM support report",
                            state.supportReportText,
                        )
                    },
                ) {
                    Text("Copy support report")
                }
            }
        }

        XdmSectionHeader("Session controls")
        XdmSupportingText("Recorder: ${report.recorderStorageLabel}", maxLines = 2)
        XdmSupportingText("Retention: ${report.retentionLabel}", maxLines = 2)

        XdmSectionHeader("Support bundle")
        XdmSupportingText(report.supportBundleLabel, maxLines = 3)

        XdmSectionHeader("Health checks")
        XdmFlatCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                report.checks.forEachIndexed { index, check ->
                    XdmCardTitle(check.title)
                    XdmSupportingText(
                        "${check.state.displayLabel()} • ${check.detail}",
                        maxLines = 3,
                    )
                    if (index != report.checks.lastIndex) XdmListSeparator()
                }
            }
        }

        MediaSniffingLabCard()
        BrowserBridgeDebuggerCard(state)
        AddDownloadDebuggerCard(state)
        TransferNotificationDebuggerCard(state)
        XdmSectionHeader("Runtime self-checks")
        RuntimeSelfTestSuiteCard(state)
    }
}
