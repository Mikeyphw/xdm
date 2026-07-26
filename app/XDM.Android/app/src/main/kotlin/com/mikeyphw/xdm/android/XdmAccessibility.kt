package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Stable semantics tags used by UIX R6 layout and accessibility tests. */
object XdmScreenTags {
    const val ShellCompact = "xdm_shell_compact"
    const val ShellMedium = "xdm_shell_medium"
    const val ShellExpanded = "xdm_shell_expanded"
    const val BottomNavigation = "xdm_bottom_navigation"
    const val NavigationSidebar = "xdm_navigation_sidebar"
    const val ContentCanvas = "xdm_content_canvas"

    const val Downloads = "xdm_screen_downloads"
    const val DownloadsList = "xdm_downloads_list"
    const val DownloadsDetail = "xdm_downloads_detail"
    const val AddDownload = "xdm_screen_add_download"
    const val AddReview = "xdm_add_review"
    const val Media = "xdm_screen_media"
    const val MediaCapture = "xdm_media_capture"
    const val MediaTrackSheet = "xdm_media_track_sheet"
    const val Library = "xdm_screen_library"
    const val LibraryList = "xdm_library_list"
    const val LibraryGrid = "xdm_library_grid"
    const val Activity = "xdm_screen_activity"
    const val ActivityAttention = "xdm_activity_attention"
    const val ActivityRecent = "xdm_activity_recent"
    const val Settings = "xdm_screen_settings"
    const val DeveloperTools = "xdm_screen_developer_tools"
}

val XdmMinimumTouchTarget = 48.dp

fun Modifier.xdmScreen(tag: String, label: String): Modifier =
    testTag(tag).semantics(mergeDescendants = false) {
        contentDescription = "$label screen"
    }

fun Modifier.xdmMinimumTouchTarget(): Modifier =
    sizeIn(minWidth = XdmMinimumTouchTarget, minHeight = XdmMinimumTouchTarget)

fun Modifier.xdmStateDescription(description: String): Modifier =
    semantics { stateDescription = description }
