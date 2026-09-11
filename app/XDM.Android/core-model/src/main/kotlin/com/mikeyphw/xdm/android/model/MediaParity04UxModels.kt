package com.mikeyphw.xdm.android.model

/** Final Media Parity V2 UX/release seal: user-facing browser/add/download notification contracts. */
enum class MediaParity04Surface {
    LightweightBrowser,
    FloatingMediaButton,
    MediaBottomSheet,
    FirefoxChooser,
    AddDownloadReview,
    Userscripts,
    ToastFeedback,
    NotificationActions,
    LongTextWrapping,
    DebugCenter,
}

data class MediaParity04AcceptanceRow(
    val surface: MediaParity04Surface,
    val promise: String,
    val evidence: String,
    val userVisible: Boolean = true,
)

object MediaParity04UxReleaseSeal {
    const val overlayId = "media_parity04_browser_ux_userscripts_notifications_release_seal_v1"
    const val currentRoomSchemaVersion = 24
    const val browserShell = "light-browser-with-floating-media-fab"
    const val mediaSelection = "logical-media-bottom-sheet-before-admission"
    const val userscriptSupport = "tampermonkey-style-local-match-include-grant-none"
    const val notificationPolicy = "tap-opens-completed-artifact-with-details-fallback-and-actions"
    const val noSilentFailurePolicy = "visible-toast-or-banner-for-user-recoverable-errors"
    const val textPolicy = "wrap-long-filenames-urls-and-validation-copy-before-ellipsis"

    val acceptanceRows: List<MediaParity04AcceptanceRow> = listOf(
        MediaParity04AcceptanceRow(
            MediaParity04Surface.LightweightBrowser,
            "The WebView surface behaves like a light browser with address, back/forward, reload/stop, find, share, external open, favicon, page status, and recoverable error UI.",
            "MediaLocatorActivity owns browser controls and no longer pins detected media below the WebView.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.FloatingMediaButton,
            "Detected media is opened from a floating Media button instead of a fixed overlay/list that steals page space.",
            "mediaFab updates from the logical candidate count and opens showMediaBottomSheet().",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.MediaBottomSheet,
            "The user chooses the logical media item before adding it to XDM, so raw segments/parts are not blindly sent or displayed as separate downloads.",
            "showMediaBottomSheet lists logical candidates and reviewCandidate persists only the selected item.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.FirefoxChooser,
            "Firefox extension capture stays at full parity with WebView by presenting the same logical chooser before app handoff.",
            "Parity02 chooser remains a carry-forward release requirement in the final gate.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.AddDownloadReview,
            "Add Download exposes one primary Download action, filename inference guidance, and Quality & tracks review for media/page intake.",
            "AddDownloadScreen shows filename source copy and a Quality & tracks card before queue admission.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.Userscripts,
            "The lightweight browser can load simple Tampermonkey-style userscripts without pretending to support privileged extension APIs.",
            "WebViewUserscriptStore validates @grant none scripts and injects matching scripts at document start / page finish.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.ToastFeedback,
            "Recoverable errors and warnings are visible; XDM does not fail silently.",
            "MediaLocatorActivity emits Toast feedback for invalid URLs, saves, external-open failures, and userscript failures.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.NotificationActions,
            "Completed download notifications let users act on the finished artifact and fall back to XDM details when Android cannot open it.",
            "TransferNotifications uses OpenDownloadedFileActivity and terminal actions include Open file, Details, and Dismiss.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.LongTextWrapping,
            "Long filenames, URLs, status lines, and validation explanations wrap before truncating instead of being eclipsed on narrow screens.",
            "Compose intake fields and locator candidate labels are multiline with bounded wrapping.",
        ),
        MediaParity04AcceptanceRow(
            MediaParity04Surface.DebugCenter,
            "Debug Options and Debug Workbench converge into one Developer Center explanation of current schema, validation blocks, runtime health, and media evidence.",
            "Final validators require schema 24 truth and Parity01/02/03/04 evidence together.",
            userVisible = false,
        ),
    )
}
