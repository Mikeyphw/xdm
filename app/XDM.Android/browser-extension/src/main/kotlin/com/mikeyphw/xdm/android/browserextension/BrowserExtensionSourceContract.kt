package com.mikeyphw.xdm.android.browserextension

/** Stable source-level contract shared with Phase 39 packaging and export work. */
object BrowserExtensionSourceContract {
    const val ContractVersion = 1
    const val ExtensionId = "xdm-android-media-bridge@mikeyphw"
    const val DevelopmentVersion = "1.0.0"
    const val DefaultApplicationId = "com.mikeyphw.xdm.android"
    const val DefaultScheme = "xdmdownload"
    const val BodyInspectionLimitBytes = 786_432

    enum class Target(val wireValue: String) {
        Xdm("xdm"),
        OneDmPlus("1dm"),
        Ask("ask"),
    }
}
