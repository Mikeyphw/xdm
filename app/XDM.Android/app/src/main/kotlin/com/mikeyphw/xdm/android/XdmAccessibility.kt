package com.mikeyphw.xdm.android

import android.view.View
import android.view.ViewTreeObserver
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
    const val MediaOptionsChooser = "xdm_media_options_chooser"
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

internal data class XdmFocusRestoreToken(
    val key: String,
    val capturedFocusAcquisition: Long,
)

private data class XdmPendingFocusRestore(
    val key: String,
    val requestGeneration: Long,
    val baselineFocusAcquisition: Long,
    var observedFocusAcquisition: Long = 0L,
    var attempts: Int = 0,
    var stableChecks: Int = 0,
)

@Stable
class XdmFocusRestorationController {
    private val requesters = linkedMapOf<String, FocusRequester>()
    private var lastFocusedKey: String? = null
    private var focusedKey: String? = null
    private var focusAcquisitionGeneration: Long = 0L

    private var hostView: View? = null
    private var hostWindowFocused: Boolean = false
    private var pendingRestore: XdmPendingFocusRestore? = null
    private var restoreAttemptScheduled: Boolean = false
    private var stabilityCheckScheduled: Boolean = false

    internal var restoreRequestGeneration: Long = 0L
        private set

    fun register(key: String, requester: FocusRequester) {
        requesters[key] = requester
        if (pendingRestore?.key == key) scheduleRestoreAttempt()
    }

    fun unregister(key: String, requester: FocusRequester) {
        if (requesters[key] !== requester) return
        requesters.remove(key)
        if (focusedKey == key) focusedKey = null
        if (lastFocusedKey == key) lastFocusedKey = null
    }

    /** Compatibility hook retained for callers that explicitly record an opener. */
    fun markLastFocused(key: String) {
        if (key in requesters) lastFocusedKey = key
    }

    /**
     * Capture only a restore point that owns Compose focus *now*.
     *
     * This intentionally does not fall back to lastFocusedKey: a sheet opened from an
     * unrelated control must never restore some older registered target on dismissal.
     */
    internal fun captureFocusedRestoreTarget(): XdmFocusRestoreToken? {
        val key = focusedKey ?: return null
        if (key !in requesters) return null
        return XdmFocusRestoreToken(key = key, capturedFocusAcquisition = focusAcquisitionGeneration)
    }

    internal fun updateFocused(key: String, requester: FocusRequester, focused: Boolean) {
        // Ignore callbacks from a restore point that has already been replaced by a
        // newer requester for the same logical key during adaptive recomposition.
        if (requesters[key] !== requester) return
        if (focused) {
            focusAcquisitionGeneration += 1L
            focusedKey = key
            lastFocusedKey = key
            pendingRestore?.takeIf { it.key == key && focusAcquisitionGeneration > it.baselineFocusAcquisition }?.let { pending ->
                pending.observedFocusAcquisition = focusAcquisitionGeneration
                pending.stableChecks = 0
                scheduleStabilityCheck(pending.requestGeneration)
            }
        } else if (focusedKey == key) {
            focusedKey = null
            pendingRestore?.takeIf { it.key == key }?.let { pending ->
                pending.observedFocusAcquisition = 0L
                pending.stableChecks = 0
                scheduleRestoreAttempt()
            }
        }
    }

    /**
     * Begin a generation-scoped restore transaction for a target captured when the
     * modal opened. Success requires a *new* focus acquisition after this request.
     */
    internal fun requestRestore(target: XdmFocusRestoreToken): Boolean {
        if (target.capturedFocusAcquisition > focusAcquisitionGeneration) return false
        restoreRequestGeneration += 1L
        pendingRestore = XdmPendingFocusRestore(
            key = target.key,
            requestGeneration = restoreRequestGeneration,
            baselineFocusAcquisition = focusAcquisitionGeneration,
        )
        scheduleRestoreAttempt()
        return true
    }

    /** Compatibility entrypoint retained for existing callers/contracts. */
    fun requestRestoreLastFocus(): Boolean {
        val key = lastFocusedKey ?: return false
        if (key !in requesters) return false
        return requestRestore(XdmFocusRestoreToken(key, focusAcquisitionGeneration))
    }

    /** Compatibility entrypoint retained for existing callers/contracts. */
    fun restoreLastFocus(): Boolean = requestRestoreLastFocus()

    internal fun attachHostView(view: View) {
        hostView = view
        hostWindowFocused = view.hasWindowFocus()
        if (hostWindowFocused) scheduleRestoreAttempt()
    }

    internal fun detachHostView(view: View) {
        if (hostView === view) {
            hostView = null
            hostWindowFocused = false
            restoreAttemptScheduled = false
            stabilityCheckScheduled = false
        }
    }

    internal fun onHostWindowFocusChanged(hasFocus: Boolean) {
        hostWindowFocused = hasFocus
        if (!hasFocus) {
            pendingRestore?.stableChecks = 0
            return
        }
        scheduleRestoreAttempt()
    }

    private fun scheduleRestoreAttempt() {
        val view = hostView ?: return
        if (restoreAttemptScheduled || pendingRestore == null) return
        restoreAttemptScheduled = true
        // Anchor restoration to the Android window-focus callback, then cross one
        // host-view traversal boundary. This avoids guessing how many Compose frames
        // a Material3 dialog needs to dispose while still keeping retries bounded.
        view.postOnAnimation {
            restoreAttemptScheduled = false
            performRestoreAttempt(view)
        }
    }

    private fun performRestoreAttempt(view: View) {
        if (hostView !== view || !hostWindowFocused || !view.hasWindowFocus()) return
        val pending = pendingRestore ?: return

        if (
            pending.observedFocusAcquisition > pending.baselineFocusAcquisition &&
            focusedKey == pending.key
        ) {
            scheduleStabilityCheck(pending.requestGeneration)
            return
        }

        val requester = requesters[pending.key]
        pending.attempts += 1
        if (requester != null) {
            // requestFocus() returning true is only admission, not completion. The
            // onFocusChanged(true) event above is the authoritative success evidence.
            runCatching { requester.requestFocus() }
        }

        if (pendingRestore?.requestGeneration != pending.requestGeneration) return
        if (pending.attempts >= MaximumRestoreAttempts) {
            if (focusedKey != pending.key || pending.observedFocusAcquisition <= pending.baselineFocusAcquisition) {
                pendingRestore = null
            }
            return
        }

        if (focusedKey != pending.key || pending.observedFocusAcquisition <= pending.baselineFocusAcquisition) {
            scheduleRestoreAttempt()
        }
    }

    private fun scheduleStabilityCheck(requestGeneration: Long) {
        val view = hostView ?: return
        if (stabilityCheckScheduled) return
        stabilityCheckScheduled = true
        view.postOnAnimation {
            stabilityCheckScheduled = false
            val pending = pendingRestore
            if (pending == null || pending.requestGeneration != requestGeneration) return@postOnAnimation

            val stillFocused =
                hostWindowFocused &&
                    view.hasWindowFocus() &&
                    focusedKey == pending.key &&
                    pending.observedFocusAcquisition > pending.baselineFocusAcquisition

            if (!stillFocused) {
                pending.stableChecks = 0
                scheduleRestoreAttempt()
                return@postOnAnimation
            }

            pending.stableChecks += 1
            if (pending.stableChecks >= RequiredStableTraversalChecks) {
                pendingRestore = null
            } else {
                scheduleStabilityCheck(requestGeneration)
            }
        }
    }

    private companion object {
        const val MaximumRestoreAttempts = 12
        const val RequiredStableTraversalChecks = 2
    }
}

val LocalXdmFocusRestorationController = compositionLocalOf { XdmFocusRestorationController() }

@Composable
fun rememberXdmFocusRestorationController(): XdmFocusRestorationController {
    val hostView = androidx.compose.ui.platform.LocalView.current
    val controller = remember(hostView) { XdmFocusRestorationController() }

    DisposableEffect(hostView, controller) {
        val observer = hostView.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            controller.onHostWindowFocusChanged(hasFocus)
        }
        controller.attachHostView(hostView)
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            controller.detachHostView(hostView)
        }
    }

    return controller
}

@Composable
fun Modifier.xdmFocusRestorePoint(key: String): Modifier {
    val controller = LocalXdmFocusRestorationController.current
    val requester = remember { FocusRequester() }

    DisposableEffect(key, requester, controller) {
        controller.register(key, requester)
        onDispose { controller.unregister(key, requester) }
    }

    return focusRequester(requester).onFocusChanged { state ->
        controller.updateFocused(key, requester, state.isFocused)
    }
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
    contentDescription = label
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
