package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import java.util.Locale

/** D5 transfer and notification debugger. It derives safe explanations from app state and never controls transfers. */
data class TransferNotificationDebugReport(
    val statusLabel: String,
    val activeSummaryLabel: String,
    val primaryTransferLabel: String,
    val notificationPathLabel: String,
    val openFilePathLabel: String,
    val rows: List<DebugReportRow>,
    val timeline: List<TransferDebugTimelineRow>,
    val boundaryLabel: String,
    val copyText: String,
)

data class TransferDebugTimelineRow(
    val step: String,
    val detail: String,
)

object TransferNotificationDebugReporter {
    fun summarize(
        downloads: List<Download>,
        activeSummary: ActiveTransferSummary,
    ): TransferNotificationDebugReport {
        val primary = primaryDownload(downloads, activeSummary)
        val status = when {
            activeSummary.activeCount > 0 -> "Active"
            primary == null -> "Idle"
            primary.state == DownloadState.Completed -> "Completed"
            primary.state == DownloadState.Failed -> "Failed"
            primary.state == DownloadState.RecoveryRequired -> "Needs attention"
            primary.state == DownloadState.Paused -> "Paused"
            else -> primary.state.transferStateLabel()
        }
        val activeLabel = when (activeSummary.activeCount) {
            0 -> "No active transfer"
            1 -> "1 active transfer"
            else -> "${activeSummary.activeCount} active transfers"
        }
        val progressLabel = progressLabel(primary, activeSummary)
        val notificationPath = notificationPathLabel(primary)
        val openFilePath = openFilePathLabel(primary)
        val rows = listOf(
            DebugReportRow("Transfer state", primary?.state?.transferStateLabel() ?: "No selected transfer"),
            DebugReportRow("Active summary", activeLabel),
            DebugReportRow("Progress", progressLabel),
            DebugReportRow("Backend", primary?.backend?.transferBackendLabel() ?: "No backend selected"),
            DebugReportRow("Backend reason", primary?.backendSelectionExplanation?.takeIf(String::isNotBlank) ?: primary?.backendSelectionReason?.debugReasonLabel() ?: "No backend decision yet"),
            DebugReportRow("Notification", notificationPath),
            DebugReportRow("Open-file tap", openFilePath),
            DebugReportRow("Failure label", failureLabel(primary)),
        )
        val timeline = timeline(primary, activeSummary)
        val copy = buildString {
            appendLine("XDM Transfer + Notification Debugger")
            appendLine("Status: $status")
            appendLine("Active transfers: $activeLabel")
            primary?.let { download ->
                appendLine("Download fingerprint: ${DebugRedactor.fingerprint(download.id)}")
                appendLine("File: ${DebugRedactor.redactText(download.fileName)}")
                appendLine("Source: ${DebugRedactor.redactUrl(download.sourceUrl)}")
                appendLine("Destination: ${destinationCopyLabel(download.destinationUri)}")
            } ?: appendLine("Download: none selected")
            rows.forEach { row -> appendLine("${row.label}: ${row.value}") }
            timeline.forEach { row -> appendLine("Timeline: ${row.step} - ${row.detail}") }
            appendLine("Boundary: read-only diagnostics; no transfer control, viewer launch, file probe, or upload.")
        }.trimEnd()
        return TransferNotificationDebugReport(
            statusLabel = status,
            activeSummaryLabel = activeLabel,
            primaryTransferLabel = primary?.fileName?.takeIf(String::isNotBlank)?.let { "Selected transfer: ${DebugRedactor.redactText(it)}" } ?: "No transfer selected",
            notificationPathLabel = notificationPath,
            openFilePathLabel = openFilePath,
            rows = rows,
            timeline = timeline,
            boundaryLabel = "Read-only diagnostics. This debugger does not pause, resume, cancel, retry, launch viewers, inspect files, or upload reports.",
            copyText = copy,
        )
    }

    private fun primaryDownload(downloads: List<Download>, activeSummary: ActiveTransferSummary): Download? =
        activeSummary.primaryDownloadId?.let { id -> downloads.firstOrNull { it.id == id } }
            ?: downloads.sortedWith(compareByDescending<Download> { it.updatedAtEpochMs }.thenBy { it.fileName.lowercase(Locale.US) }).firstOrNull()

    private fun progressLabel(download: Download?, activeSummary: ActiveTransferSummary): String {
        if (download == null) return "No progress available"
        val total = download.totalBytes ?: activeSummary.totalBytes
        val received = if (activeSummary.primaryDownloadId == download.id) activeSummary.bytesReceived.takeIf { it > 0 } ?: download.bytesReceived else download.bytesReceived
        val percent = total?.takeIf { it > 0 }?.let { ((received * 100L) / it).coerceIn(0, 100).toString() + "%" }
        val speed = if (activeSummary.primaryDownloadId == download.id && activeSummary.speedBytesPerSecond > 0) {
            " at ${formatBytes(activeSummary.speedBytesPerSecond)}/s"
        } else {
            ""
        }
        return listOfNotNull(percent, "${formatBytes(received)} received${total?.let { " of ${formatBytes(it)}" }.orEmpty()}$speed").joinToString(" • ")
    }

    private fun notificationPathLabel(download: Download?): String = when (download?.state) {
        null -> "No notification path selected"
        DownloadState.Completed -> "Status notification with completed-file tap and Open XDM fallback"
        DownloadState.Failed -> "Status notification with Retry and Mute actions"
        DownloadState.RecoveryRequired -> "Status notification with Retry and recovery detail"
        DownloadState.Paused -> "Status notification with Resume and Mute actions"
        DownloadState.Cancelled -> "Status notification with Mute action"
        DownloadState.Queued, DownloadState.Connecting, DownloadState.Downloading, DownloadState.Finalizing, DownloadState.Repairing -> "Active notification with Pause, Resume all, and Cancel actions"
        DownloadState.WaitingForNetwork -> "Status notification waits for network before retry"
        DownloadState.WaitingForPower -> "Status notification waits for power before retry"
        DownloadState.Verifying -> "Active notification while verification completes"
        DownloadState.Created -> "No active notification until the transfer is queued"
    }

    private fun openFilePathLabel(download: Download?): String = when {
        download == null -> "No completed notification to inspect"
        download.state != DownloadState.Completed -> "Falls back to XDM details because the transfer is not complete"
        download.completedArtifactUri.isNullOrBlank() || download.completedArtifactGeneration != download.attemptGeneration -> "Falls back to XDM details if the generation-bound completed artifact is missing"
        else -> "Completed notification uses the non-exported open-file trampoline with a validated committed-artifact grant"
    }

    private fun failureLabel(download: Download?): String = when {
        download == null -> "No failure recorded"
        download.errorMessage.isNullOrBlank() && download.state !in setOf(DownloadState.Failed, DownloadState.RecoveryRequired) -> "No failure recorded"
        download.state == DownloadState.RecoveryRequired -> "recovery-required"
        download.errorMessage?.contains("network", ignoreCase = true) == true -> "network-or-host-failure"
        download.errorMessage?.contains("permission", ignoreCase = true) == true -> "permission-or-storage-failure"
        download.errorMessage?.contains("viewer", ignoreCase = true) == true -> "viewer-unavailable"
        download.state == DownloadState.Failed -> "transfer-failed"
        else -> "attention-needed"
    }

    private fun timeline(download: Download?, activeSummary: ActiveTransferSummary): List<TransferDebugTimelineRow> {
        if (download == null) return listOf(TransferDebugTimelineRow("Idle", "No download record is selected."))
        val rows = mutableListOf<TransferDebugTimelineRow>()
        rows += TransferDebugTimelineRow("Created", "Download record exists and has a safe fingerprint ${DebugRedactor.fingerprint(download.id)}.")
        if (download.state in setOf(DownloadState.Queued, DownloadState.Connecting, DownloadState.Downloading, DownloadState.Finalizing, DownloadState.Repairing, DownloadState.Verifying)) {
            rows += TransferDebugTimelineRow("Runtime", "Runtime summary reports ${activeSummary.activeCount} active transfer(s).")
        }
        rows += TransferDebugTimelineRow("Backend", "${download.backend.transferBackendLabel()} handles this transfer; requested backend is ${download.requestedBackend.transferBackendLabel()}.")
        rows += TransferDebugTimelineRow("Notification", notificationPathLabel(download))
        if (download.state == DownloadState.Completed) {
            rows += TransferDebugTimelineRow("Open-file", openFilePathLabel(download))
        }
        if (download.state in setOf(DownloadState.Failed, DownloadState.RecoveryRequired)) {
            rows += TransferDebugTimelineRow("Attention", failureLabel(download))
        }
        return rows
    }

    private fun BackendType.transferBackendLabel(): String = when (this) {
        BackendType.Automatic -> "Automatic"
        BackendType.Native -> "Native engine"
        BackendType.Aria2 -> "aria2 engine"
    }

    private fun DownloadState.transferStateLabel(): String = when (this) {
        DownloadState.Created -> "Created"
        DownloadState.Queued -> "Queued"
        DownloadState.Connecting -> "Connecting"
        DownloadState.Downloading -> "Downloading"
        DownloadState.Paused -> "Paused"
        DownloadState.WaitingForNetwork -> "Waiting for network"
        DownloadState.WaitingForPower -> "Waiting for power"
        DownloadState.Verifying -> "Verifying"
        DownloadState.Repairing -> "Repairing"
        DownloadState.Finalizing -> "Finalizing"
        DownloadState.Completed -> "Completed"
        DownloadState.Failed -> "Failed"
        DownloadState.Cancelled -> "Cancelled"
        DownloadState.RecoveryRequired -> "Recovery required"
    }

    private fun Any.debugReasonLabel(): String = toString()
        .replace('_', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun destinationCopyLabel(destinationUri: String): String = if (destinationUri.isBlank()) "not selected" else "selected (${DebugRedactor.fingerprint(destinationUri)})"
}
