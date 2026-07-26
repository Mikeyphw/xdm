package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract

fun XdmThemeMode.toBrowserExtensionThemeMode(): BrowserExtensionSourceContract.ThemeMode = when (this) {
    XdmThemeMode.Dark -> BrowserExtensionSourceContract.ThemeMode.Dark
    XdmThemeMode.Amoled -> BrowserExtensionSourceContract.ThemeMode.Amoled
}

data class BrowserExtensionExportPreferences(
    val exportTreeUri: String = "",
    val defaultTarget: BrowserExtensionSourceContract.Target = BrowserExtensionSourceContract.Target.Xdm,
    val requestedTheme: BrowserExtensionSourceContract.ThemeSelection = BrowserExtensionSourceContract.ThemeSelection.FollowApp,
    val lastExportTheme: BrowserExtensionSourceContract.ThemeMode? = null,
    val lastExportAppVersion: String = "",
    val lastExportExtensionVersion: String = "",
    val lastExportSha256: String = "",
    val lastExportEpochMs: Long = 0L,
    val lastExportFileName: String = "",
    val lastExportByteCount: Long = 0L,
) {
    fun resolvedTheme(appTheme: XdmThemeMode): BrowserExtensionSourceContract.ThemeMode =
        requestedTheme.resolve(appTheme.toBrowserExtensionThemeMode())

    fun isThemeStale(appTheme: XdmThemeMode): Boolean =
        lastExportFileName.isNotBlank() && lastExportTheme != resolvedTheme(appTheme)
}

enum class BrowserExtensionExportPhase {
    Idle,
    Exporting,
    Succeeded,
    Failed,
}

data class BrowserExtensionRuntimeStatus(
    val phase: BrowserExtensionExportPhase = BrowserExtensionExportPhase.Idle,
    val message: String = "Choose an export folder to generate the Firefox XPI.",
    val exportedUri: String = "",
)
