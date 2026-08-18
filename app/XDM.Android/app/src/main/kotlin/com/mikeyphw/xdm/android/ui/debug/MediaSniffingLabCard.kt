package com.mikeyphw.xdm.android.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.XdmActionFlowRow
import com.mikeyphw.xdm.android.XdmCardTitle
import com.mikeyphw.xdm.android.XdmListCard
import com.mikeyphw.xdm.android.XdmListSeparator
import com.mikeyphw.xdm.android.XdmMetadataText
import com.mikeyphw.xdm.android.XdmSupportingText
import com.mikeyphw.xdm.android.media.MediaSniffingLab
import com.mikeyphw.xdm.android.media.MediaSniffingLabRequest
import com.mikeyphw.xdm.android.media.MediaSniffingSource
import com.mikeyphw.xdm.android.copyTextToClipboard

@Composable
fun MediaSniffingLabCard() {
    val context = LocalContext.current
    var rawInput by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var sourceKey by rememberSaveable { mutableStateOf(MediaSniffingSource.ManualPage.labStateKey()) }
    val source = MediaSniffingSource.entries.firstOrNull { it.labStateKey() == sourceKey } ?: MediaSniffingSource.ManualPage
    var report by remember { mutableStateOf(MediaSniffingLab.inspect(MediaSniffingLabRequest(rawInput = ""))) }
    val labStateKey = "media-sniffing-lab-${source.labDisplayLabel()}"

    XdmListCard(modifier = Modifier.fillMaxWidth()) {
        XdmCardTitle("Media Sniffing Lab")
        XdmSupportingText("Static sniff only. Paste a URL, HTML, JSON, or script snippet; this lab does not fetch pages or run scripts.", maxLines = 4)
        XdmActionFlowRow {
            MediaSniffingLab.allowedSources.forEach { option ->
                FilterChip(
                    selected = source == option,
                    onClick = { sourceKey = option.labStateKey() },
                    label = { Text(option.labDisplayLabel()) },
                )
            }
        }
        XdmMetadataText("Lab source: ${source.labDisplayLabel()} • $labStateKey", maxLines = 2)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base page, optional") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = rawInput,
            onValueChange = { rawInput = it },
            label = { Text("Snippet or link") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        XdmActionFlowRow {
            Button(onClick = {
                report = MediaSniffingLab.inspect(
                    MediaSniffingLabRequest(
                        rawInput = rawInput,
                        baseUrl = baseUrl.takeIf { it.isNotBlank() },
                        source = source,
                    ),
                )
            }) { Text("Run static sniff") }
            Button(onClick = { copyTextToClipboard(context, "XDM media sniffing lab", report.copyText) }) {
                Text("Copy sanitized lab report")
            }
        }
        XdmListSeparator()
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            XdmMetadataText(report.statusLabel, maxLines = 2)
            XdmSupportingText(report.summary, maxLines = 3)
            report.candidateRows.take(3).forEach { row ->
                XdmMetadataText("${row.kindLabel} • rank ${row.rank} • ${row.reason}", maxLines = 3)
            }
            if (report.candidateRows.isEmpty()) XdmMetadataText(report.primaryCandidateLabel, maxLines = 2)
        }
    }
}

private fun MediaSniffingSource.labDisplayLabel(): String = when (this) {
    MediaSniffingSource.ManualPage -> "Manual page"
    MediaSniffingSource.BatchInput -> "Batch input"
    MediaSniffingSource.SharedText -> "Shared text"
    MediaSniffingSource.BrowserExtension -> "Browser extension"
    MediaSniffingSource.NetworkObservation -> "Network observation"
    MediaSniffingSource.AppPageProbe -> "App page probe"
}

private fun MediaSniffingSource.labStateKey(): String = when (this) {
    MediaSniffingSource.ManualPage -> "manual-page"
    MediaSniffingSource.BatchInput -> "batch-input"
    MediaSniffingSource.SharedText -> "shared-text"
    MediaSniffingSource.BrowserExtension -> "browser-extension"
    MediaSniffingSource.NetworkObservation -> "network-observation"
    MediaSniffingSource.AppPageProbe -> "app-page-probe"
}
