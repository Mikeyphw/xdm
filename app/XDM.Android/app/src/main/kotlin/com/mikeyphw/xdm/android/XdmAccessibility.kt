package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import kotlin.math.pow

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
    const val BrowserSessionHealth = "xdm_browser_session_health"
    const val EngineEscalation = "xdm_engine_escalation"
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
    const val Player = "xdm_media_player"
}

val XdmMinimumTouchTarget = 48.dp

object XdmTraversalOrder {
    const val Navigation = 0f
    const val Content = 1f
    const val List = 2f
    const val Detail = 3f
    const val Sheet = 4f
    const val Dialog = 5f
    const val PlayerControls = 6f

    val keyboardDpadSwitchAccessOrder: List<String> = listOf(
        "navigation", "content", "download-list", "download-detail", "sheet-or-dialog", "player-controls",
    )
}

@Stable
class XdmFocusRestorationController {
    private val requesters = linkedMapOf<String, FocusRequester>()
    private var lastFocusedKey: String? = null

    fun register(key: String, requester: FocusRequester) { requesters[key] = requester }

    fun unregister(key: String, requester: FocusRequester) {
        if (requesters[key] === requester) requesters.remove(key)
        if (lastFocusedKey == key && key !in requesters) lastFocusedKey = null
    }

    fun markLastFocused(key: String) { lastFocusedKey = key }

    fun restoreLastFocus(): Boolean {
        val requester = lastFocusedKey?.let(requesters::get) ?: return false
        return runCatching { requester.requestFocus(); true }.getOrDefault(false)
    }
}

val LocalXdmFocusRestorationController = compositionLocalOf { XdmFocusRestorationController() }

@Composable
fun rememberXdmFocusRestorationController(): XdmFocusRestorationController = remember { XdmFocusRestorationController() }

@Composable
fun Modifier.xdmFocusRestorePoint(key: String): Modifier {
    val controller = LocalXdmFocusRestorationController.current
    val requester = remember { FocusRequester() }
    DisposableEffect(key, requester, controller) {
        controller.register(key, requester)
        onDispose { controller.unregister(key, requester) }
    }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) controller.markLastFocused(key) }
}

fun Modifier.xdmTraversalOrder(order: Float): Modifier = semantics { traversalIndex = order }

object XdmContrastPolicy {
    const val MinimumNormalTextContrast = 4.5f
    const val MinimumLargeTextContrast = 3.0f
    const val MinimumNonTextContrast = 3.0f

    fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.relativeLuminanceCompat(), background.relativeLuminanceCompat())
        val darker = minOf(foreground.relativeLuminanceCompat(), background.relativeLuminanceCompat())
        return ((lighter + 0.05f) / (darker + 0.05f))
    }

    fun passesNormalText(foreground: Color, background: Color): Boolean = contrastRatio(foreground, background) >= MinimumNormalTextContrast
    fun passesLargeText(foreground: Color, background: Color): Boolean = contrastRatio(foreground, background) >= MinimumLargeTextContrast
    fun passesNonText(foreground: Color, background: Color): Boolean = contrastRatio(foreground, background) >= MinimumNonTextContrast

    fun ensureReadableContentColor(background: Color, preferred: Color, fallback: Color): Color =
        if (passesNormalText(preferred, background)) preferred else fallback

    fun requiredSurfaceNames(): Set<String> = setOf("status", "warning", "progress", "disabled", "selected")
}

private fun Color.relativeLuminanceCompat(): Float {
    fun channel(value: Float): Float = if (value <= 0.03928f) value / 12.92f else (((value + 0.055f) / 1.055f).toDouble()).pow(2.4).toFloat()
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

object XdmAccessibilityPolicy {
    const val MinimumTouchTargetDp = 48
    const val FiveItemNavigationLargeFontScale = 2.0f
    const val ByteProgressAnnouncementSuppression = "continuous byte progress is visual-only"
    const val TerminalLiveRegionMode = "polite"

    fun touchTargetPasses(widthDp: Int, heightDp: Int): Boolean = widthDp >= MinimumTouchTargetDp && heightDp >= MinimumTouchTargetDp
    fun shouldUsePoliteLiveRegion(previousState: String, nextState: String): Boolean = previousState != nextState && !nextState.contains("bytes", ignoreCase = true)
    fun bottomNavigationSurvivesLargeFont(itemCount: Int, fontScale: Float): Boolean = itemCount <= 5 && fontScale <= FiveItemNavigationLargeFontScale
}

fun Modifier.xdmScreen(tag: String, label: String): Modifier =
    testTag(tag).semantics(mergeDescendants = false) {
        contentDescription = "$label screen"
        paneTitle = label
        isTraversalGroup = true
    }

fun Modifier.xdmPane(label: String, traversal: Float? = null): Modifier = semantics {
    paneTitle = label
    isTraversalGroup = true
    traversal?.let { traversalIndex = it }
}

fun Modifier.xdmFocusablePane(label: String, traversal: Float? = null): Modifier =
    xdmPane(label, traversal).focusable()

fun Modifier.xdmMinimumTouchTarget(): Modifier =
    sizeIn(minWidth = XdmMinimumTouchTarget, minHeight = XdmMinimumTouchTarget)

fun Modifier.xdmStateDescription(description: String): Modifier =
    semantics { stateDescription = description }

fun Modifier.xdmPoliteStateAnnouncement(description: String): Modifier = semantics {
    stateDescription = description
    liveRegion = LiveRegionMode.Polite
}

fun Modifier.xdmHeadingPane(label: String): Modifier = semantics {
    heading()
    paneTitle = label
}
