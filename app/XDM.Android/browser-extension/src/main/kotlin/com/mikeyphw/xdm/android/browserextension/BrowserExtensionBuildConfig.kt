package com.mikeyphw.xdm.android.browserextension

private val extensionVersionPattern = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]+)?$")
private val appVersionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$")
private val applicationIdPattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val schemePattern = Regex("^[a-z][a-z0-9+.-]{1,40}$")

data class BrowserExtensionBuildConfig(
    val extensionVersion: String = BrowserExtensionSourceContract.DevelopmentVersion,
    val appVersion: String,
    val applicationId: String,
    val channel: BrowserExtensionSourceContract.Channel,
    val xdmScheme: String,
    val defaultTarget: BrowserExtensionSourceContract.Target = BrowserExtensionSourceContract.Target.Xdm,
    val themeMode: BrowserExtensionSourceContract.ThemeMode = BrowserExtensionSourceContract.ThemeMode.Dark,
    val contractVersion: Int = BrowserExtensionSourceContract.ContractVersion,
) {
    init {
        require(extensionVersionPattern.matches(extensionVersion)) { "Invalid Firefox extension version" }
        require(appVersionPattern.matches(appVersion)) { "Invalid app version" }
        require(applicationIdPattern.matches(applicationId)) { "Invalid application ID" }
        require(schemePattern.matches(xdmScheme)) { "Invalid XDM browser scheme" }
        require(contractVersion == BrowserExtensionSourceContract.ContractVersion) { "Unsupported bridge contract version" }
    }

    val outputFileName: String
        get() = "XDM-Android-Firefox-$extensionVersion-${channel.wireValue}-${themeMode.fileToken}.xpi"
}
