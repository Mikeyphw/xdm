package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.storage.DestinationUris

internal fun destinationUiLabel(destinationUri: String): String {
    val value = destinationUri.trim()
    return when {
        value.isBlank() -> "Default save location"
        value == DestinationUris.DIRECT_DOWNLOADS -> "Download/XDM (direct)"
        value == DestinationUris.PUBLIC_DOWNLOADS -> "Downloads folder"
        value == DestinationUris.MEDIA_MOVIES -> "Movies library"
        value == DestinationUris.MEDIA_MUSIC -> "Music library"
        value == DestinationUris.MEDIA_PICTURES -> "Pictures library"
        value == DestinationUris.MEDIA_DOCUMENTS -> "Documents folder"
        value == DestinationUris.APP_PRIVATE_DOWNLOADS -> "XDM private folder"
        value.startsWith("content://", ignoreCase = true) -> "Saved in Android shared storage"
        value.startsWith("file://", ignoreCase = true) -> "Saved file path"
        value.startsWith("/", ignoreCase = true) -> "Device file path"
        else -> "Saved destination"
    }
}

internal fun destinationUiHint(destinationUri: String): String {
    val value = destinationUri.trim()
    return when {
        value == DestinationUris.DIRECT_DOWNLOADS -> "Personal direct-storage mode; download engines use the shared filesystem path after all-files access is granted."
        value == DestinationUris.PUBLIC_DOWNLOADS -> "Visible in your Downloads folder."
        value == DestinationUris.MEDIA_MOVIES -> "Visible in Android media apps and file managers."
        value == DestinationUris.MEDIA_MUSIC -> "Visible in Android audio apps and file managers."
        value == DestinationUris.MEDIA_PICTURES -> "Visible in Android photo apps and file managers."
        value == DestinationUris.MEDIA_DOCUMENTS -> "Visible in Android Documents."
        value == DestinationUris.APP_PRIVATE_DOWNLOADS -> "Private to XDM; choose Public Downloads for normal file-manager visibility."
        value.startsWith("content://", ignoreCase = true) -> "Android stores shared files with access-safe content links instead of raw paths."
        value.startsWith("file://", ignoreCase = true) || value.startsWith("/") -> "Direct file path."
        else -> "Destination is configured and will be resolved when the file is saved."
    }
}


internal fun destinationCardLabel(download: Download): String {
    val compactLabel = when (download.destinationUri.trim()) {
        DestinationUris.DIRECT_DOWNLOADS -> "Download/XDM"
        DestinationUris.PUBLIC_DOWNLOADS -> "Downloads"
        DestinationUris.MEDIA_MOVIES -> "Movies"
        DestinationUris.MEDIA_MUSIC -> "Music"
        DestinationUris.MEDIA_PICTURES -> "Pictures"
        DestinationUris.MEDIA_DOCUMENTS -> "Documents"
        DestinationUris.APP_PRIVATE_DOWNLOADS -> "XDM private folder"
        else -> destinationUiLabel(download.destinationUri)
            .removeSuffix(" folder")
            .removePrefix("Saved in ")
    }
    return if (download.state == DownloadState.Completed) {
        "Saved to $compactLabel"
    } else {
        "Destination: $compactLabel"
    }
}
