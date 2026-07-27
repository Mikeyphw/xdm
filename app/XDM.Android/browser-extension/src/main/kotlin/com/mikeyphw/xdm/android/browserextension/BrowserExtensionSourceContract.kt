package com.mikeyphw.xdm.android.browserextension

/** Stable source and packaging contract shared by Gradle and the Android runtime. */
object BrowserExtensionSourceContract {
    const val ContractVersion = 1
    const val ExtensionId = "xdm-android-media-bridge@mikeyphw"
    const val DevelopmentVersion = "1.1.0"
    const val DefaultApplicationId = "com.mikeyphw.xdm.android"
    const val DefaultScheme = "xdmdownload"
    const val BodyInspectionLimitBytes = 786_432
    const val ResourceRoot = "xdm-firefox"

    val SourceEntries: List<String> = listOf(
        "bridge-selftest.js",
        "candidate-store.js",
        "detector-core.js",
        "extension.css",
        "fab.js",
        "frame-bridge.js",
        "generated-config.template.js",
        "generated-theme.template.css",
        "handoff.js",
        "icons/icon48.png",
        "icons/icon96.png",
        "manifest.template.json",
        "network-observer.js",
        "page-sniffer.js",
        "popup.html",
        "popup.js",
    )

    val PackagedEntries: List<String> = SourceEntries.map { entry ->
        when (entry) {
            "manifest.template.json" -> "manifest.json"
            "generated-config.template.js" -> "generated-config.js"
            "generated-theme.template.css" -> "generated-theme.css"
            else -> entry
        }
    }.sorted()

    enum class Target(val wireValue: String, val label: String) {
        Xdm("xdm", "XDM"),
        OneDmPlus("1dm", "1DM+"),
        Ask("ask", "Ask every time"),
    }

    enum class ThemeMode(val wireValue: String, val label: String, val fileToken: String) {
        Dark("dark", "Dark", "dark"),
        Amoled("amoled", "AMOLED black", "amoled"),
    }

    enum class ThemeSelection(val wireValue: String, val label: String) {
        FollowApp("follow-app", "Follow app"),
        Dark("dark", "Dark"),
        Amoled("amoled", "AMOLED black");

        fun resolve(appTheme: ThemeMode): ThemeMode = when (this) {
            FollowApp -> appTheme
            Dark -> ThemeMode.Dark
            Amoled -> ThemeMode.Amoled
        }
    }

    enum class Channel(val wireValue: String) {
        Release("release"),
        Debug("debug"),
    }
}
