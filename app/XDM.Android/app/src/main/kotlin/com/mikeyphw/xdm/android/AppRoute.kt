package com.mikeyphw.xdm.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppRoute(val label: String, val icon: ImageVector) {
    Downloads("Downloads", Icons.Rounded.Download),
    Add("Add", Icons.Rounded.AddCircle),
    Media("Media", Icons.Rounded.Movie),
    Library("Library", Icons.Rounded.VideoLibrary),
    Activity("Activity", Icons.Rounded.History),
    Settings("Settings", Icons.Rounded.Settings),
    ;

    companion object {
        fun restore(storedName: String?): AppRoute = when (storedName) {
            "Queues", "Scheduler", "Recovery", "Diagnostics" -> Activity
            else -> entries.firstOrNull { it.name == storedName } ?: Downloads
        }
    }
}
