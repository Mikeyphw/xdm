package com.mikeyphw.xdm.android.model

enum class DownloadActionKind {
    OpenFile,
    OpenDetails,
    ReviewRecovery,
    Pause,
    Resume,
    Retry,
    Cancel,
    StartNow,
    MoveToTop,
    MoveUp,
    MoveDown,
    MoveToBottom,
    CopyLink,
    CopyFileName,
    CopyDestination,
    ShareLink,
    ShareFile,
    OpenFolder,
    Rename,
    Redownload,
    RefreshLink,
    DeleteRecord,
    DeleteFileAndRecord,
}

enum class DownloadActionIcon {
    Open,
    Details,
    Recovery,
    Pause,
    Play,
    Refresh,
    Cancel,
    Queue,
    Move,
    Copy,
    Share,
    Folder,
    Rename,
    Delete,
}

data class DownloadAction(
    val kind: DownloadActionKind,
    val label: String,
    val icon: DownloadActionIcon,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    val destructive: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val supportingText: String = "",
)

object DownloadActionPlanner {
    fun actionsFor(download: Download): List<DownloadAction> = when (download.state) {
        DownloadState.Downloading,
        DownloadState.Connecting,
        DownloadState.Finalizing,
        DownloadState.Verifying,
        DownloadState.Repairing,
        -> listOf(
            pause(primary = true),
            details(),
            copyLink(download),
            shareLink(download),
            cancel(download),
            deleteRecord(download, label = "Remove from list"),
        )

        DownloadState.Queued,
        DownloadState.Created,
        -> listOf(
            startNow(primary = true),
            moveToTop(download),
            moveUp(download),
            moveDown(download),
            moveToBottom(download),
            details(),
            copyLink(download),
            cancel(download),
            deleteRecord(download, label = "Remove from list"),
        )

        DownloadState.Paused,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
        -> listOf(
            resume(primary = true),
            details(),
            refreshLink(download),
            redownload(download),
            copyLink(download),
            deleteRecord(download),
        )

        DownloadState.Failed -> listOf(
            retry(primary = true),
            details(),
            refreshLink(download),
            copyLink(download),
            redownload(download),
            deleteRecord(download),
        )

        DownloadState.Completed -> listOf(
            openFile(download, primary = true),
            details(label = "Open details"),
            openFolder(download),
            shareFile(download),
            copyLink(download),
            copyFileName(download),
            copyDestination(download),
            rename(download),
            redownload(download),
            deleteRecord(download),
            deleteFileAndRecord(download),
        )

        DownloadState.RecoveryRequired -> listOf(
            reviewRecovery(primary = true),
            details(),
            openFolder(download, label = "Locate file"),
            redownload(download, label = "Restart"),
            deleteRecord(download, label = "Remove record"),
        )

        DownloadState.Cancelled -> listOf(
            details(primary = true),
            copyLink(download),
            redownload(download),
            deleteRecord(download),
        )
    }

    fun primaryActionFor(download: Download): DownloadAction = actionsFor(download).firstOrNull { it.primary }
        ?: details(primary = true)

    fun batchActionsFor(downloads: List<Download>): List<DownloadAction> {
        if (downloads.isEmpty()) return emptyList()
        val states = downloads.mapTo(linkedSetOf()) { it.state }
        return buildList {
            if (states.any { it in activeStates }) add(pause(label = "Pause selected"))
            if (states.any { it in resumableStates || it == DownloadState.Failed }) add(resume(label = "Resume selected"))
            add(DownloadAction(
                kind = DownloadActionKind.CopyLink,
                label = "Copy selected links",
                icon = DownloadActionIcon.Copy,
                enabled = downloads.any { it.sourceUrl.isNotBlank() },
                requiresConfirmation = true,
                supportingText = "Copy ${downloads.size} full source link${if (downloads.size == 1) "" else "s"}. Links may contain private access parameters.",
            ))
            if (states.all { it in terminalStates }) add(deleteRecord(label = "Delete selected records"))
        }
    }

    private val activeStates = setOf(
        DownloadState.Downloading,
        DownloadState.Connecting,
        DownloadState.Finalizing,
        DownloadState.Verifying,
        DownloadState.Repairing,
    )
    private val resumableStates = setOf(
        DownloadState.Paused,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
    )
    private val terminalStates = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)

    private fun details(label: String = "Details", primary: Boolean = false) = DownloadAction(
        kind = DownloadActionKind.OpenDetails,
        label = label,
        icon = DownloadActionIcon.Details,
        primary = primary,
        supportingText = "Open status, destination, verification, and technical details.",
    )

    private fun reviewRecovery(primary: Boolean = false) = DownloadAction(
        kind = DownloadActionKind.ReviewRecovery,
        label = "Review recovery",
        icon = DownloadActionIcon.Recovery,
        primary = primary,
        supportingText = "Choose whether to resume, validate, locate, or restart safely.",
    )

    private fun pause(label: String = "Pause", primary: Boolean = false) = DownloadAction(
        kind = DownloadActionKind.Pause,
        label = label,
        icon = DownloadActionIcon.Pause,
        primary = primary,
        supportingText = "Pause transfer activity without deleting partial data.",
    )

    private fun resume(label: String = "Resume", primary: Boolean = false) = DownloadAction(
        kind = DownloadActionKind.Resume,
        label = label,
        icon = DownloadActionIcon.Play,
        primary = primary,
        supportingText = "Continue using the saved partial file when possible.",
    )

    private fun retry(primary: Boolean = false) = DownloadAction(
        kind = DownloadActionKind.Retry,
        label = "Retry",
        icon = DownloadActionIcon.Refresh,
        primary = primary,
        supportingText = "Retry the last failed transfer with the current configuration.",
    )

    private fun startNow(primary: Boolean = false) = DownloadAction(
        kind = DownloadActionKind.StartNow,
        label = "Start now",
        icon = DownloadActionIcon.Play,
        primary = primary,
        supportingText = "Move this queued item into active transfer consideration.",
    )

    private fun openFile(download: Download, primary: Boolean = false) = DownloadAction(
        kind = DownloadActionKind.OpenFile,
        label = "Open file",
        icon = DownloadActionIcon.Open,
        primary = primary,
        enabled = download.destinationUri.isNotBlank(),
        supportingText = "Open the completed file from its saved destination when Android grants access.",
    )

    private fun openFolder(download: Download, label: String = "Open location") = DownloadAction(
        kind = DownloadActionKind.OpenFolder,
        label = label,
        icon = DownloadActionIcon.Folder,
        enabled = download.destinationUri.isNotBlank(),
        supportingText = "Open or review the saved destination.",
    )

    private fun copyLink(download: Download) = DownloadAction(
        kind = DownloadActionKind.CopyLink,
        label = "Copy link",
        icon = DownloadActionIcon.Copy,
        enabled = download.sourceUrl.isNotBlank(),
        requiresConfirmation = true,
        supportingText = "Copy the full source URL. It may contain private access parameters and will be marked sensitive on the clipboard.",
    )

    private fun copyFileName(download: Download) = DownloadAction(
        kind = DownloadActionKind.CopyFileName,
        label = "Copy file name",
        icon = DownloadActionIcon.Copy,
        enabled = download.fileName.isNotBlank(),
        supportingText = "Copy the display file name.",
    )

    private fun copyDestination(download: Download) = DownloadAction(
        kind = DownloadActionKind.CopyDestination,
        label = "Copy path",
        icon = DownloadActionIcon.Copy,
        enabled = download.destinationUri.isNotBlank(),
        supportingText = "Copy the saved Android URI or verified path. Provider identifiers can be private.",
    )

    private fun shareLink(download: Download) = DownloadAction(
        kind = DownloadActionKind.ShareLink,
        label = "Share link",
        icon = DownloadActionIcon.Share,
        enabled = download.sourceUrl.isNotBlank(),
        requiresConfirmation = true,
        supportingText = "Share the full source URL with another app. It may contain private access parameters.",
    )

    private fun shareFile(download: Download) = DownloadAction(
        kind = DownloadActionKind.ShareFile,
        label = "Share file",
        icon = DownloadActionIcon.Share,
        enabled = download.destinationUri.isNotBlank(),
        supportingText = "Share the completed file when Android grants access.",
    )

    private fun refreshLink(download: Download) = DownloadAction(
        kind = DownloadActionKind.RefreshLink,
        label = "Refresh link",
        icon = DownloadActionIcon.Refresh,
        enabled = download.sourceUrl.isNotBlank(),
        supportingText = "Ask for a fresh URL before redownloading or retrying.",
    )

    private fun redownload(download: Download, label: String = "Redownload") = DownloadAction(
        kind = DownloadActionKind.Redownload,
        label = label,
        icon = DownloadActionIcon.Refresh,
        enabled = download.sourceUrl.isNotBlank(),
        requiresConfirmation = true,
        supportingText = "Create a fresh attempt from the same source.",
    )

    private fun rename(download: Download) = DownloadAction(
        kind = DownloadActionKind.Rename,
        label = "Rename",
        icon = DownloadActionIcon.Rename,
        enabled = download.fileName.isNotBlank(),
        supportingText = "Rename or move the saved file through a safe destination flow.",
    )

    private fun cancel(download: Download) = DownloadAction(
        kind = DownloadActionKind.Cancel,
        label = "Cancel",
        icon = DownloadActionIcon.Cancel,
        enabled = download.state != DownloadState.Completed,
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Stop this transfer and keep destructive choices behind confirmation.",
    )

    private fun deleteRecord(download: Download, label: String = "Delete record") = DownloadAction(
        kind = DownloadActionKind.DeleteRecord,
        label = label,
        icon = DownloadActionIcon.Delete,
        enabled = true,
        destructive = true,
        requiresConfirmation = true,
        supportingText = when (download.state) {
            DownloadState.Completed -> "Remove the history record without deleting the saved file."
            DownloadState.Downloading,
            DownloadState.Connecting,
            DownloadState.Finalizing,
            DownloadState.Verifying,
            DownloadState.Repairing,
            DownloadState.Queued,
            DownloadState.Created,
            -> "Cancel the transfer if needed, then remove the list record without deleting saved files."
            DownloadState.RecoveryRequired -> "Remove the recovery/list record without deleting user files."
            else -> "Remove the list record without deleting saved files."
        },
    )

    private fun deleteRecord(label: String) = DownloadAction(
        kind = DownloadActionKind.DeleteRecord,
        label = label,
        icon = DownloadActionIcon.Delete,
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Remove history records without deleting saved files.",
    )

    private fun deleteFileAndRecord(download: Download) = DownloadAction(
        kind = DownloadActionKind.DeleteFileAndRecord,
        label = "Delete file + record",
        icon = DownloadActionIcon.Delete,
        enabled = download.destinationUri.isNotBlank(),
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Delete the saved file as well as the history record after confirmation.",
    )

    private fun moveToTop(download: Download) = move(download, DownloadActionKind.MoveToTop, "Move to top")
    private fun moveUp(download: Download) = move(download, DownloadActionKind.MoveUp, "Move up")
    private fun moveDown(download: Download) = move(download, DownloadActionKind.MoveDown, "Move down")
    private fun moveToBottom(download: Download) = move(download, DownloadActionKind.MoveToBottom, "Move to bottom")

    private fun move(download: Download, kind: DownloadActionKind, label: String) = DownloadAction(
        kind = kind,
        label = label,
        icon = DownloadActionIcon.Move,
        enabled = download.queueId != null || download.state in setOf(DownloadState.Queued, DownloadState.Created),
        supportingText = "Reorder this item inside the waiting queue.",
    )
}
