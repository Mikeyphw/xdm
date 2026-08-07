package com.mikeyphw.xdm.android.model

enum class DownloadActionKind {
    OpenFile,
    OpenDetails,
    ReviewRecovery,
    LocateFile,
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
    CopyFriendlyLocation,
    ShareLink,
    ShareFile,
    OpenFolder,
    Rename,
    Redownload,
    RefreshLink,
    RestartFromZero,
    DeleteFile,
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
    fun actionsFor(download: Download, context: DownloadActionContext = DownloadActionContext()): List<DownloadAction> = when (download.state) {
        DownloadState.Downloading,
        DownloadState.Connecting,
        DownloadState.Finalizing,
        -> listOf(
            pause(primary = true),
            cancel(download),
            details(),
            copyLink(context),
        )

        DownloadState.Verifying,
        DownloadState.Repairing,
        -> listOf(
            details(primary = true),
            cancel(download, supporting = "Cancel is durable and must win before a file can be published."),
            copyLink(context),
        )

        DownloadState.Queued,
        DownloadState.Created,
        -> listOf(
            startNow(primary = true),
            moveToTop(download, context),
            moveUp(download, context),
            moveDown(download, context),
            moveToBottom(download, context),
            details(),
            copyLink(context),
            cancel(download),
            deleteHistory(download, label = "Delete download entry"),
        )

        DownloadState.Paused,
        DownloadState.WaitingForNetwork,
        DownloadState.WaitingForPower,
        -> listOf(
            resume(primary = true, validated = context.validatedPartialAvailable),
            details(),
            refreshLink(download),
            redownload(download),
            copyLink(context),
            deleteHistory(download),
        )

        DownloadState.Failed -> listOf(
            retry(primary = true),
            details(),
            refreshLink(download),
            copyLink(context),
            redownload(download),
            deleteHistory(download),
        )

        DownloadState.Completed -> buildList {
            add(openFile(context, primary = true))
            add(shareFile(context))
            if (context.artifact.locationBrowsable) add(openLocation(context))
            add(details(label = "Open details"))
            add(copyFileName(download))
            add(copyFriendlyLocation(context))
            add(copyDestination(context))
            add(rename(context))
            add(redownload(download))
            add(deleteFile(context))
            add(deleteHistory(download))
            add(deleteFileAndHistory(context))
        }

        DownloadState.RecoveryRequired -> if (download.isFinalSaveRecovery()) {
            listOf(
                retrySave(primary = true),
                reviewRecovery(),
                details(),
                deleteHistory(download),
            )
        } else {
            listOf(
                reviewRecovery(primary = true),
                locateFile(),
                restartFromZero(download),
                details(),
                deleteHistory(download),
            )
        }

        DownloadState.Cancelled -> listOf(
            details(primary = true),
            refreshLink(download),
            copyLink(context),
            redownload(download),
            deleteHistory(download),
        )
    }

    fun primaryActionFor(download: Download, context: DownloadActionContext = DownloadActionContext()): DownloadAction =
        actionsFor(download, context).firstOrNull { it.primary } ?: details(primary = true)

    fun batchActionsFor(downloads: List<Download>): List<DownloadAction> {
        if (downloads.isEmpty()) return emptyList()
        val states = downloads.mapTo(linkedSetOf()) { it.state }
        return buildList {
            if (states.any { it in pausableStates }) add(pause(label = "Pause selected"))
            if (states.any { it in resumableStates || it == DownloadState.Failed }) add(resume(label = "Resume selected", validated = false))
            add(
                DownloadAction(
                    kind = DownloadActionKind.CopyLink,
                    label = "Copy redacted source links",
                    icon = DownloadActionIcon.Copy,
                    enabled = downloads.any { ExternalUrlPolicy.persistableUrl(it.sourceUrl) != null },
                    supportingText = "Copies only persistence-safe URLs. Credential-bearing query values remain redacted.",
                ),
            )
            if (states.all { it in terminalStates }) add(deleteHistory(label = "Delete selected download entries"))
        }
    }

    private val pausableStates = setOf(DownloadState.Downloading, DownloadState.Connecting, DownloadState.Finalizing)
    private val resumableStates = setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)
    private val terminalStates = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)
    private val queueStates = setOf(DownloadState.Created, DownloadState.Queued, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)

    private fun details(label: String = "Details", primary: Boolean = false) = DownloadAction(
        DownloadActionKind.OpenDetails,
        label,
        DownloadActionIcon.Details,
        primary = primary,
        supportingText = "Open truthful transfer, storage, verification, and recovery details.",
    )

    private fun reviewRecovery(primary: Boolean = false) = DownloadAction(
        DownloadActionKind.ReviewRecovery,
        "Review recovery",
        DownloadActionIcon.Recovery,
        primary = primary,
        supportingText = "Open Recovery with this exact download selected.",
    )

    private fun locateFile() = DownloadAction(
        DownloadActionKind.LocateFile,
        "Locate file",
        DownloadActionIcon.Folder,
        supportingText = "Choose the missing artifact and validate it before reassociation.",
    )

    private fun restartFromZero(download: Download) = DownloadAction(
        DownloadActionKind.RestartFromZero,
        "Restart from zero",
        DownloadActionIcon.Refresh,
        enabled = download.sourceUrl.isNotBlank(),
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Discard stale attempt ownership and create a fresh generation while preserving request settings.",
    )

    private fun pause(label: String = "Pause", primary: Boolean = false) = DownloadAction(
        DownloadActionKind.Pause,
        label,
        DownloadActionIcon.Pause,
        primary = primary,
        supportingText = "Pause the active transfer without deleting validated partial data.",
    )

    private fun resume(label: String = "Resume", primary: Boolean = false, validated: Boolean) = DownloadAction(
        DownloadActionKind.Resume,
        label,
        DownloadActionIcon.Play,
        primary = primary,
        supportingText = if (validated) {
            "Continue from partial data whose durable validators are still available."
        } else {
            "XDM will validate the current partial artifact before deciding whether bytes can be reused."
        },
    )

    private fun retry(primary: Boolean = false) = DownloadAction(
        DownloadActionKind.Retry,
        "Retry",
        DownloadActionIcon.Refresh,
        primary = primary,
        supportingText = "Create a real new network attempt with the preserved request configuration.",
    )

    private fun retrySave(primary: Boolean = false) = DownloadAction(
        DownloadActionKind.Retry,
        "Retry save",
        DownloadActionIcon.Refresh,
        primary = primary,
        supportingText = "Retry final publication from the preserved completed staging file without intentionally redownloading the payload.",
    )

    private fun Download.isFinalSaveRecovery(): Boolean =
        errorMessage.orEmpty().startsWith("Final save failed")

    private fun startNow(primary: Boolean = false) = DownloadAction(
        DownloadActionKind.StartNow,
        "Start now",
        DownloadActionIcon.Play,
        primary = primary,
        supportingText = "Request an immediate queue claim. This never routes through Pause.",
    )

    private fun openFile(context: DownloadActionContext, primary: Boolean = false) = DownloadAction(
        DownloadActionKind.OpenFile,
        "Open file",
        DownloadActionIcon.Open,
        enabled = context.artifact.readable,
        primary = primary,
        supportingText = context.artifact.detail,
    )

    private fun openLocation(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.OpenFolder,
        "Open provider location",
        DownloadActionIcon.Folder,
        enabled = context.artifact.locationBrowsable,
        supportingText = "Open the provider location that actually contains the completed artifact.",
    )

    private fun copyLink(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.CopyLink,
        "Copy redacted source URL",
        DownloadActionIcon.Copy,
        enabled = !context.publicSourceUrl.isNullOrBlank(),
        supportingText = "Copies a persistence-safe URL. Tokens, signatures, cookies, and credential query values are not copied.",
    )

    private fun copyFileName(download: Download) = DownloadAction(
        DownloadActionKind.CopyFileName,
        "Copy file name",
        DownloadActionIcon.Copy,
        enabled = download.fileName.isNotBlank(),
        supportingText = "Copy the display file name.",
    )

    private fun copyFriendlyLocation(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.CopyFriendlyLocation,
        "Copy friendly location",
        DownloadActionIcon.Copy,
        enabled = context.artifact.friendlyLocation.isNotBlank(),
        supportingText = "Copy the human-readable provider and folder description.",
    )

    private fun copyDestination(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.CopyDestination,
        "Copy Android URI",
        DownloadActionIcon.Copy,
        enabled = !context.artifact.androidUri.isNullOrBlank(),
        supportingText = "Copy the canonical Android content URI. The clipboard entry is marked sensitive.",
    )

    private fun shareFile(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.ShareFile,
        "Share file",
        DownloadActionIcon.Share,
        enabled = context.artifact.shareable,
        supportingText = "Share the completed artifact with a temporary read grant.",
    )

    private fun refreshLink(download: Download) = DownloadAction(
        DownloadActionKind.RefreshLink,
        "Replace source URL",
        DownloadActionIcon.Refresh,
        enabled = download.state !in pausableStates && download.state !in setOf(DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing),
        supportingText = "Enter a fresh URL while retaining destination, queue, checksum, backend preference, and post-processing links.",
    )

    private fun redownload(download: Download, label: String = "Redownload") = DownloadAction(
        DownloadActionKind.Redownload,
        label,
        DownloadActionIcon.Refresh,
        enabled = download.sourceUrl.isNotBlank(),
        requiresConfirmation = true,
        supportingText = "Create a fresh entry preserving the original destination, queue, conflict policy, backend preference, checksum, request session, and post-processing rules where still valid.",
    )

    private fun rename(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.Rename,
        "Rename file",
        DownloadActionIcon.Rename,
        enabled = context.artifact.renameable,
        supportingText = if (context.artifact.renameable) "Rename through the owning Android provider and update the canonical URI." else "The current provider does not expose a safe rename operation.",
    )

    private fun deleteFile(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.DeleteFile,
        "Delete saved file",
        DownloadActionIcon.Delete,
        enabled = context.artifact.deletable,
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Delete the completed artifact only. The download entry remains and will show the file as missing.",
    )

    private fun deleteHistory(download: Download, label: String = "Delete download entry") = DownloadAction(
        DownloadActionKind.DeleteRecord,
        label,
        DownloadActionIcon.Delete,
        enabled = download.state !in pausableStates && download.state !in setOf(DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing),
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Delete the complete XDM history graph while keeping the saved file.",
    )

    private fun deleteHistory(label: String) = DownloadAction(
        DownloadActionKind.DeleteRecord,
        label,
        DownloadActionIcon.Delete,
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Delete the selected complete download history graphs after active ownership has stopped.",
    )

    private fun deleteFileAndHistory(context: DownloadActionContext) = DownloadAction(
        DownloadActionKind.DeleteFileAndRecord,
        "Delete file and download entry",
        DownloadActionIcon.Delete,
        enabled = context.artifact.deletable,
        destructive = true,
        requiresConfirmation = true,
        supportingText = "Delete the exact completed artifact first, then delete the complete XDM history graph only after storage deletion succeeds.",
    )

    private fun cancel(download: Download, supporting: String = "Stop this transfer and keep its download entry.") = DownloadAction(
        DownloadActionKind.Cancel,
        "Cancel transfer",
        DownloadActionIcon.Cancel,
        enabled = download.state !in terminalStates,
        destructive = true,
        requiresConfirmation = true,
        supportingText = supporting,
    )

    private fun moveToTop(download: Download, context: DownloadActionContext) = move(
        DownloadActionKind.MoveToTop,
        "Move to top",
        download.state in queueStates && context.canMoveUp(),
        "Place this item first in its queue.",
    )

    private fun moveUp(download: Download, context: DownloadActionContext) = move(
        DownloadActionKind.MoveUp,
        "Move up",
        download.state in queueStates && context.canMoveUp(),
        "Move this item one place earlier.",
    )

    private fun moveDown(download: Download, context: DownloadActionContext) = move(
        DownloadActionKind.MoveDown,
        "Move down",
        download.state in queueStates && context.canMoveDown(),
        "Move this item one place later.",
    )

    private fun moveToBottom(download: Download, context: DownloadActionContext) = move(
        DownloadActionKind.MoveToBottom,
        "Move to bottom",
        download.state in queueStates && context.canMoveDown(),
        "Place this item last in its queue.",
    )

    private fun move(kind: DownloadActionKind, label: String, enabled: Boolean, supporting: String) = DownloadAction(
        kind,
        label,
        DownloadActionIcon.Move,
        enabled = enabled,
        supportingText = supporting,
    )
}
