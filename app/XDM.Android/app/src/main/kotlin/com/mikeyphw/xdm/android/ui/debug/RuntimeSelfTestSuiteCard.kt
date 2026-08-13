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
import com.mikeyphw.xdm.android.DebugWorkbenchRuntimeSelfTestSuite
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.XdmActionFlowRow
import com.mikeyphw.xdm.android.XdmCardTitle
import com.mikeyphw.xdm.android.XdmListCard
import com.mikeyphw.xdm.android.XdmListSeparator
import com.mikeyphw.xdm.android.XdmMetadataText
import com.mikeyphw.xdm.android.XdmMetric
import com.mikeyphw.xdm.android.XdmMetricStrip
import com.mikeyphw.xdm.android.XdmSupportingText
import com.mikeyphw.xdm.android.copyTextToClipboard

@Composable
fun RuntimeSelfTestSuiteCard(state: MainUiState) {
    val context = LocalContext.current
    val report = DebugWorkbenchRuntimeSelfTestSuite.fromState(state)
    XdmListCard(modifier = Modifier.fillMaxWidth()) {
        XdmCardTitle("Runtime self-test suite")
        XdmSupportingText(report.boundaryLabel, maxLines = 4)
        XdmMetricStrip(
            listOf(
                XdmMetric("Status", report.statusLabel),
                XdmMetric("Summary", report.summaryLabel),
            ),
        )
        XdmListSeparator()
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            report.checks.forEach { check ->
                XdmMetadataText("${check.title}: ${check.statusLabel} • ${check.detail}", maxLines = 3)
            }
        }
        XdmActionFlowRow {
            Button(onClick = { copyTextToClipboard(context, "XDM runtime self-test", report.copyText) }) {
                Text("Copy self-test report")
            }
        }
    }
}
