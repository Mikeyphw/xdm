package com.mikeyphw.xdm.android

/** Stable secondary pages inside the Settings destination. */
enum class SettingsPanel(val label: String) {
    Overview("Settings"),
    StorageDestinations("Storage & destinations"),
    /** Compatibility value retained for previously persisted navigation; now represents Download behavior. */
    AdvancedDownloads("Download behavior"),
    Network("Network"),
    Media("Media & capture"),
    ExternalTools("External tools"),
    PostProcessing("Post-processing"),
    Appearance("Appearance"),
    BackupRestore("Backup & restore"),
    Privacy("Privacy"),
    BrowserExtension("Browser integration"),
    DebugWorkbench("Diagnostics & support"),
    DeveloperTools("Developer Center"),
}
