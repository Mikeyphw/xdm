package com.mikeyphw.xdm.android.browserextension

import java.security.MessageDigest
import java.util.Base64

private val extensionVersionPattern = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]+)?$")
private val appVersionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$")
private val applicationIdPattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val schemePattern = Regex("^[a-z][a-z0-9+.-]{1,40}$")

internal fun captureKeyIdForSpki(spkiBase64Url: String): String {
    val encoded = spkiBase64Url.trim()
    require(encoded.matches(Regex("^[A-Za-z0-9_-]{128,2048}$"))) { "Invalid browser capture public key" }
    val der = runCatching { Base64.getUrlDecoder().decode(encoded) }
        .getOrElse { throw IllegalArgumentException("Invalid browser capture public-key encoding", it) }
    require(der.isNotEmpty()) { "Invalid browser capture public key" }
    return MessageDigest.getInstance("SHA-256")
        .digest(der)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(24)
}

data class BrowserExtensionBuildConfig(
    val extensionVersion: String = BrowserExtensionSourceContract.DevelopmentVersion,
    val appVersion: String,
    val applicationId: String,
    val channel: BrowserExtensionSourceContract.Channel,
    val xdmScheme: String,
    val defaultTarget: BrowserExtensionSourceContract.Target = BrowserExtensionSourceContract.Target.Xdm,
    val themeMode: BrowserExtensionSourceContract.ThemeMode = BrowserExtensionSourceContract.ThemeMode.Dark,
    val contractVersion: Int = BrowserExtensionSourceContract.ContractVersion,
    val captureKeyId: String = "",
    val capturePublicKeySpki: String = "",
    val captureOaepHash: String,
) {
    init {
        require(extensionVersionPattern.matches(extensionVersion)) { "Invalid Firefox extension version" }
        require(appVersionPattern.matches(appVersion)) { "Invalid app version" }
        require(applicationIdPattern.matches(applicationId)) { "Invalid application ID" }
        require(schemePattern.matches(xdmScheme)) { "Invalid XDM browser scheme" }
        require(contractVersion == BrowserExtensionSourceContract.ContractVersion) { "Unsupported bridge contract version" }
        require(captureKeyId.isBlank() || captureKeyId.matches(Regex("^[A-Za-z0-9._:-]{8,96}$"))) { "Invalid browser capture key id" }
        require(capturePublicKeySpki.isBlank() || capturePublicKeySpki.matches(Regex("^[A-Za-z0-9_-]{128,2048}$"))) { "Invalid browser capture public key" }
        require(captureOaepHash in setOf("SHA-1", "SHA-256")) { "Unsupported browser capture OAEP hash" }
        require(captureKeyId.isBlank() == capturePublicKeySpki.isBlank()) { "Capture key id and public key must be supplied together" }
        if (capturePublicKeySpki.isNotBlank()) {
            require(captureKeyId == captureKeyIdForSpki(capturePublicKeySpki)) {
                "Capture key id must equal the first 24 hex characters of SHA-256(SPKI DER)"
            }
        }
        if (channel == BrowserExtensionSourceContract.Channel.Release) {
            require(captureKeyId.isNotBlank() && capturePublicKeySpki.isNotBlank()) {
                "Release browser packages require the Android capture public key even when XDM is not the default target; generate/export from XDM or supply the release key inputs"
            }
        }
    }

    val outputFileName: String
        get() = "XDM-Android-Firefox-$extensionVersion-${channel.wireValue}-${themeMode.fileToken}.xpi"
}
