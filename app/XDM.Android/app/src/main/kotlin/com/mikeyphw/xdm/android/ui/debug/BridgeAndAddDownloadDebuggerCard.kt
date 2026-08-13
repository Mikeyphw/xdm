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
import com.mikeyphw.xdm.android.AddDownloadDebugReporter
import com.mikeyphw.xdm.android.BrowserBridgeDebugReporter
import com.mikeyphw.xdm.android.BrowserBridgeDiagnosticsRedactor
import com.mikeyphw.xdm.android.BuildConfig
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.XdmActionFlowRow
import com.mikeyphw.xdm.android.XdmCardTitle
import com.mikeyphw.xdm.android.XdmListCard
import com.mikeyphw.xdm.android.XdmListSeparator
import com.mikeyphw.xdm.android.XdmMetadataText
import com.mikeyphw.xdm.android.XdmSupportingText
import com.mikeyphw.xdm.android.copyTextToClipboard

@Composable
fun BrowserBridgeDebuggerCard(state: MainUiState) {
    val context = LocalContext.current
    val report = BrowserBridgeDebugReporter.summarize(
        status = state.browserBridgeStatus,
        diagnostics = state.browserBridgeDiagnostics,
        scheme = BuildConfig.XDM_BROWSER_SCHEME,
    )
    XdmListCard(modifier = Modifier.fillMaxWidth()) {
        XdmCardTitle("Browser bridge debugger")
        XdmSupportingText(report.boundaryLabel, maxLines = 3)
        XdmMetadataText(report.schemeLabel, maxLines = 3)
        XdmMetadataText(report.exportLabel, maxLines = 3)
        XdmMetadataText(report.extensionLabel, maxLines = 2)
        XdmMetadataText(BrowserBridgeDiagnosticsRedactor.sanitize(report.lastAcceptedLabel), maxLines = 3)
        XdmMetadataText(BrowserBridgeDiagnosticsRedactor.sanitize(report.lastRejectedLabel), maxLines = 3)
        XdmActionFlowRow {
            Button(onClick = { copyTextToClipboard(context, "XDM browser bridge debugger", report.copyText) }) {
                Text("Copy bridge debugger")
            }
        }
    }
}

@Composable
fun AddDownloadDebuggerCard(state: MainUiState) {
    val context = LocalContext.current
    val report = AddDownloadDebugReporter.summarize(state.externalAddDraft, state.destinationUri)
    XdmListCard(modifier = Modifier.fillMaxWidth()) {
        XdmCardTitle("Add Download debugger")
        XdmSupportingText(report.boundaryLabel, maxLines = 3)
        XdmMetadataText(report.statusLabel, maxLines = 2)
        XdmSupportingText(report.summary, maxLines = 3)
        XdmListSeparator()
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            report.rows.forEach { row ->
                XdmMetadataText("${row.label}: ${row.value}", maxLines = 3)
            }
        }
        XdmActionFlowRow {
            Button(onClick = { copyTextToClipboard(context, "XDM Add debugger", report.copyText) }) {
                Text("Copy Add debugger")
            }
        }
    }
}
