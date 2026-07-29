package com.mikeyphw.xdm.android

/** Stable secondary pages inside the Settings destination. */
enum class SettingsPanel(val label: String) {
    Overview("Settings"),
    AdvancedDownloads("Advanced download rules"),
    Privacy("Privacy"),
    BrowserExtension("Browser extension"),
    DebugWorkbench("Debug Workbench"),
    DeveloperTools("Developer tools"),
}
