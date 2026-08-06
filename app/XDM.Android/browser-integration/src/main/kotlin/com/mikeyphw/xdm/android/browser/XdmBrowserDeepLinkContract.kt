package com.mikeyphw.xdm.android.browser

/** Versioned, browser-safe handoff contract shared by XDM Android and its Firefox extension. */
object XdmBrowserDeepLinkContract {
    const val CurrentVersion = 1

    const val ReleaseScheme = "xdmdownload"
    const val DebugScheme = "xdmdownload-debug"

    const val CaptureHost = "capture"
    const val AddHost = "add"

    const val VersionParameter = "v"
    const val UrlParameter = "url"
    const val PageUrlParameter = "page"
    const val PageTitleParameter = "title"
    const val FileNameParameter = "filename"
    const val MimeTypeParameter = "mime"
    const val MediaKindParameter = "kind"
    const val StableMediaIdParameter = "stableMediaId"
    const val SessionRevisionParameter = "sessionRevision"
    const val ContentLengthParameter = "length"
    const val DurationMsParameter = "durationMs"
    const val ThumbnailUrlParameter = "thumbnail"
    const val FrameUrlParameter = "frame"

    const val MaxDeepLinkBytes = 64 * 1024
    const val MaxMediaUrlBytes = 32 * 1024
    const val MaxPageUrlBytes = 8 * 1024
    const val MaxPageTitleCharacters = 240
    const val MaxFileNameCharacters = 160
    const val MaxMimeTypeCharacters = 120
    const val MaxMediaKindCharacters = 32
    const val MaxStableMediaIdCharacters = 160
    const val MaxThumbnailUrlBytes = 8 * 1024

    val BuildVariantSchemes: Set<String> = setOf(ReleaseScheme, DebugScheme)
}
