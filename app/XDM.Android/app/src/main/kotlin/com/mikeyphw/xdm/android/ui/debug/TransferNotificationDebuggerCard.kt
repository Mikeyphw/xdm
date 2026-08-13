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
import com.mikeyphw.xdm.android.TransferNotificationDebugReporter
import com.mikeyphw.xdm.android.XdmActionFlowRow
import com.mikeyphw.xdm.android.XdmCardTitle
import com.mikeyphw.xdm.android.XdmListCard
import com.mikeyphw.xdm.android.XdmListSeparator
import com.mikeyphw.xdm.android.XdmMetadataText
import com.mikeyphw.xdm.android.XdmSupportingText
import com.mikeyphw.xdm.android.copyTextToClipboard

@Composable
fun TransferNotificationDebuggerCard(state: MainUiState) {
    val context = LocalContext.current
    val report = TransferNotificationDebugReporter.summarize(state.downloads, state.activeTransfers)
    XdmListCard(modifier = Modifier.fillMaxWidth()) {
        XdmCardTitle("Transfer + notification debugger")
        XdmSupportingText(report.boundaryLabel, maxLines = 3)
        XdmMetadataText(report.statusLabel, maxLines = 2)
        XdmSupportingText(report.primaryTransferLabel, maxLines = 3)
        XdmMetadataText(report.notificationPathLabel, maxLines = 3)
        XdmMetadataText(report.openFilePathLabel, maxLines = 3)
        XdmListSeparator()
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            report.rows.take(6).forEach { row ->
                XdmMetadataText("${row.label}: ${row.value}", maxLines = 3)
            }
        }
        XdmActionFlowRow {
            Button(onClick = { copyTextToClipboard(context, "XDM transfer debugger", report.copyText) }) {
                Text("Copy transfer debugger")
            }
        }
    }
}
