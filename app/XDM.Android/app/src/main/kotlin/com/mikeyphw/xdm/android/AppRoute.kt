package com.mikeyphw.xdm.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppRoute(val label: String, val icon: ImageVector) {
    Downloads("Downloads", Icons.Rounded.Download),
    Add("Add", Icons.Rounded.AddCircle),
    Queues("Queues", Icons.Rounded.Queue),
    Scheduler("Scheduler", Icons.Rounded.CalendarMonth),
    Media("Media", Icons.Rounded.Movie),
    Recovery("Recovery", Icons.Rounded.HealthAndSafety),
    Diagnostics("Diagnostics", Icons.Rounded.Build),
    Settings("Settings", Icons.Rounded.Settings),
}
