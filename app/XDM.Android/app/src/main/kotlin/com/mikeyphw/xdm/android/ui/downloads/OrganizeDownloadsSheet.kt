package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.OperationalActivitySummary
import com.mikeyphw.xdm.android.model.OrganizationPowerToolsReport
import com.mikeyphw.xdm.android.model.SavedSearch

@Composable
internal fun OrganizeDownloadsContent(
    downloads: List<Download>,
    visibleDownloads: List<Download>,
    selectedDownloads: List<Download>,
    historyReport: HistoryManagementReport,
    organizationReport: OrganizationPowerToolsReport,
    activitySummary: OperationalActivitySummary,
    tags: List<DownloadTag>,
    tagAssignments: List<DownloadTagAssignment>,
    savedSearches: List<SavedSearch>,
    query: String,
    filter: DownloadWorkspaceFilter,
    ordering: DownloadDashboardOrdering,
    includeArchived: Boolean,
    onOrderingChanged: (DownloadDashboardOrdering) -> Unit,
    onIncludeArchivedChanged: (Boolean) -> Unit,
    onSelectAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onArchiveSelected: (Boolean) -> Unit,
    onBulkPause: () -> Unit,
    onBulkResume: () -> Unit,
    onCreateTag: (String) -> Unit,
    onAssignTag: (DownloadTag) -> Unit,
    onSaveSearch: (String, String, DownloadState?, Boolean) -> Unit,
    onDeleteSavedSearch: (SavedSearch) -> Unit,
    onCopyHistory: () -> Unit,
    onClearFinishedHistory: () -> Unit,
    onOpenActivityAttention: () -> Unit,
    onOpenActivityDecisions: () -> Unit,
) {
    var tagName by remember { mutableStateOf("") }
    var searchName by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        XdmNoticeRow(
            text = organizationReport.summary,
            tone = XdmStatusTone.Info,
        )

        XdmGroupedList {
            XdmListRow(
                headline = "Show archived downloads",
                supporting = if (includeArchived) "Archived items are included in the list." else "Archived items stay out of the everyday workspace.",
                trailing = {
                    Switch(
                        checked = includeArchived,
                        onCheckedChange = onIncludeArchivedChanged,
                    )
                },
            )
            XdmListSeparator()
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XdmSectionLabel("Sort order")
                XdmActionFlowRow {
                    DownloadDashboardOrdering.entries.forEach { value ->
                        FilterChip(
                            selected = ordering == value,
                            onClick = { onOrderingChanged(value) },
                            label = { Text(value.label) },
                        )
                    }
                }
            }
        }

        XdmGroupedList {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XdmSectionLabel("Selection")
                XdmMetadataText("${visibleDownloads.size} visible • ${selectedDownloads.size} selected")
                XdmActionFlowRow {
                    TextButton(onClick = onSelectAllVisible, enabled = visibleDownloads.isNotEmpty()) { Text("Select visible") }
                    TextButton(onClick = onClearSelection, enabled = selectedDownloads.isNotEmpty()) { Text("Clear") }
                }
                XdmActionFlowRow {
                    TextButton(onClick = onBulkPause, enabled = selectedDownloads.isNotEmpty()) { Text("Pause") }
                    TextButton(onClick = onBulkResume, enabled = selectedDownloads.isNotEmpty()) { Text("Resume") }
                    TextButton(onClick = { onArchiveSelected(true) }, enabled = selectedDownloads.isNotEmpty()) { Text("Archive") }
                    TextButton(onClick = { onArchiveSelected(false) }, enabled = selectedDownloads.isNotEmpty()) { Text("Unarchive") }
                }
                if (selectedDownloads.isEmpty()) {
                    XdmMetadataText("Long-press a download to enter selection mode without adding permanent Select chips to every row.")
                }
            }
        }

        XdmGroupedList {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XdmSectionLabel("Tags")
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    label = { Text("New tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onCreateTag(tagName.trim()); tagName = "" },
                    enabled = tagName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create tag") }
                if (tags.isNotEmpty()) {
                    XdmActionFlowRow {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = selectedDownloads.any { download ->
                                    tagAssignments.any { assignment -> assignment.downloadId == download.id && assignment.tagId == tag.id }
                                },
                                onClick = { onAssignTag(tag) },
                                enabled = selectedDownloads.isNotEmpty(),
                                label = { Text(tag.name) },
                            )
                        }
                    }
                }
            }
        }

        XdmGroupedList {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XdmSectionLabel("Saved searches")
                OutlinedTextField(
                    value = searchName,
                    onValueChange = { searchName = it },
                    label = { Text("Saved search name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onSaveSearch(searchName.trim(), query, filter.asDownloadState(), includeArchived)
                        searchName = ""
                    },
                    enabled = searchName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save current view") }
                savedSearches.take(6).forEach { search ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(search.name, style = MaterialTheme.typography.bodyMedium)
                            XdmMetadataText(search.query.ifBlank { "All downloads" })
                        }
                        TextButton(onClick = { onDeleteSavedSearch(search) }) { Text("Delete") }
                    }
                }
            }
        }

        XdmGroupedList {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XdmSectionLabel("History and activity")
                XdmSupportingText(historyReport.summary)
                XdmActionFlowRow {
                    TextButton(onClick = onCopyHistory, enabled = downloads.isNotEmpty()) { Text("Copy history index") }
                    TextButton(onClick = onClearFinishedHistory, enabled = historyReport.removableHistory > 0) { Text("Clear finished history") }
                }
                XdmMetadataText("History actions remove app records only; downloaded files stay in their destination.")
                if (activitySummary.unresolved > 0 || activitySummary.policyHolds > 0) {
                    XdmActionFlowRow {
                        TextButton(onClick = onOpenActivityAttention, enabled = activitySummary.unresolved > 0) { Text("Open attention") }
                        TextButton(onClick = onOpenActivityDecisions, enabled = activitySummary.policyHolds > 0) { Text("Queue decisions") }
                    }
                }
            }
        }
    }
}

private fun DownloadWorkspaceFilter.asDownloadState(): DownloadState? = when (this) {
    DownloadWorkspaceFilter.Active -> DownloadState.Downloading
    DownloadWorkspaceFilter.Queued -> DownloadState.Queued
    DownloadWorkspaceFilter.Finished -> DownloadState.Completed
    DownloadWorkspaceFilter.All -> null
}
