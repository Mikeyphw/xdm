package com.mikeyphw.xdm.android.model

import java.util.Locale

enum class ConversionPreset { None, VideoFastStart, AudioExtract, ArchiveExtract, CustomCommand }

data class ProxyCredentialSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int? = null,
    val username: String = "",
    val credentialAlias: String = "",
) {
    val configured: Boolean get() = enabled && host.isNotBlank() && (port == null || port in 1..65535)
    val redactedSummary: String get() = when {
        !enabled -> "Proxy disabled"
        host.isBlank() -> "Proxy enabled but host is missing"
        port != null -> "Proxy ${host.trim()}:$port${if (username.isBlank()) "" else " as ${username.trim()}"}"
        else -> "Proxy ${host.trim()}${if (username.isBlank()) "" else " as ${username.trim()}"}"
    }
}

data class PostProcessingSettings(
    val enabled: Boolean = false,
    val preset: ConversionPreset = ConversionPreset.None,
    val customCommandLabel: String = "",
) {
    val active: Boolean get() = enabled && preset != ConversionPreset.None
    val redactedSummary: String get() = when {
        !enabled -> "Post-processing disabled"
        preset == ConversionPreset.CustomCommand -> "Custom post-processing hook: ${customCommandLabel.ifBlank { "unnamed" }}"
        else -> "Post-processing preset: ${preset.displayName()}"
    }
}

data class SettingsExchangeSnapshot(
    val compactDensity: Boolean = false,
    val destinationUri: String = "",
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
    val proxy: ProxyCredentialSettings = ProxyCredentialSettings(),
    val postProcessing: PostProcessingSettings = PostProcessingSettings(),
) {
    fun toPortableText(): String = buildString {
        appendLine("xdm.settings.export=v1")
        appendLine("compactDensity=$compactDensity")
        appendLine("destinationUri=${destinationUri.escapeSettingValue()}")
        appendLine("conflictPolicy=${conflictPolicy.name}")
        appendLine("proxy.enabled=${proxy.enabled}")
        appendLine("proxy.host=${proxy.host.escapeSettingValue()}")
        appendLine("proxy.port=${proxy.port ?: ""}")
        appendLine("proxy.username=${proxy.username.escapeSettingValue()}")
        appendLine("proxy.credentialAlias=${proxy.credentialAlias.escapeSettingValue()}")
        appendLine("post.enabled=${postProcessing.enabled}")
        appendLine("post.preset=${postProcessing.preset.name}")
        appendLine("post.customCommandLabel=${postProcessing.customCommandLabel.escapeSettingValue()}")
    }.trimEnd()
}

object SettingsExchangeCodec {
    fun decode(text: String): SettingsExchangeSnapshot? {
        val values = text.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
            val index = trimmed.indexOf('=')
            if (index <= 0) return@mapNotNull null
            trimmed.substring(0, index) to trimmed.substring(index + 1).unescapeSettingValue()
        }.toMap()
        if (values["xdm.settings.export"] != "v1") return null
        return SettingsExchangeSnapshot(
            compactDensity = values["compactDensity"]?.toBooleanStrictOrNull() ?: false,
            destinationUri = values["destinationUri"].orEmpty(),
            conflictPolicy = values["conflictPolicy"]?.let { runCatching { FilenameConflictPolicy.valueOf(it) }.getOrNull() } ?: FilenameConflictPolicy.Rename,
            proxy = ProxyCredentialSettings(
                enabled = values["proxy.enabled"]?.toBooleanStrictOrNull() ?: false,
                host = values["proxy.host"].orEmpty(),
                port = values["proxy.port"]?.toIntOrNull()?.takeIf { it in 1..65535 },
                username = values["proxy.username"].orEmpty(),
                credentialAlias = values["proxy.credentialAlias"].orEmpty(),
            ),
            postProcessing = PostProcessingSettings(
                enabled = values["post.enabled"]?.toBooleanStrictOrNull() ?: false,
                preset = values["post.preset"]?.let { runCatching { ConversionPreset.valueOf(it) }.getOrNull() } ?: ConversionPreset.None,
                customCommandLabel = values["post.customCommandLabel"].orEmpty(),
            ),
        )
    }
}

data class HistoryManagementReport(val total: Int, val active: Int, val completed: Int, val failed: Int, val cancelled: Int, val removableHistory: Int) {
    val summary: String get() = "$total total • $active active • $completed complete • $failed failed • $removableHistory removable"
}

object HistoryManagementPolicy {
    private val activeStates = setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Queued, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing)
    private val removableStates = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)
    fun summarize(downloads: List<Download>) = HistoryManagementReport(downloads.size, downloads.count { it.state in activeStates }, downloads.count { it.state == DownloadState.Completed }, downloads.count { it.state == DownloadState.Failed || it.state == DownloadState.RecoveryRequired }, downloads.count { it.state == DownloadState.Cancelled }, downloads.count { it.state in removableStates })
    fun isSafeToRemoveFromHistory(download: Download): Boolean = download.state in removableStates
    fun exportIndex(downloads: List<Download>): String = buildString {
        appendLine("XDM Android history index")
        downloads.sortedByDescending { it.updatedAtEpochMs }.forEach { download -> appendLine("${download.state.name}\t${download.backend.name}\t${download.fileName}\t${download.sourceUrl.redactQuerySecrets()}") }
    }.trimEnd()
}

data class ProtocolSupportRow(val protocol: String, val native: Boolean, val aria2: Boolean, val recommendation: String)
data class ProtocolExpansionReport(val rows: List<ProtocolSupportRow>) {
    val supportedProtocols: Int get() = rows.count { it.native || it.aria2 }
    val summary: String get() = "$supportedProtocols supported protocol profiles across Native and aria2"
}

object ProtocolExpansionPolish {
    private val knownProtocols = listOf("http", "https", "ftp", "sftp", "magnet", "metalink", "torrent", "hls", "dash")
    fun summarize(capabilities: List<BackendCapabilityRow>): ProtocolExpansionReport {
        val nativeRow = capabilities.firstOrNull { it.backend == BackendType.Native }
        val aria2Row = capabilities.firstOrNull { it.backend == BackendType.Aria2 }
        val native = nativeRow?.protocols.orEmpty().map { it.lowercase(Locale.US) }.toSet()
        val aria2 = aria2Row?.protocols.orEmpty().map { it.lowercase(Locale.US) }.toSet()
        return ProtocolExpansionReport(knownProtocols.map { protocol ->
            val nativeSupported = when (protocol) { "hls", "dash" -> nativeRow?.media == true; else -> protocol in native }
            val aria2Supported = when (protocol) { "metalink" -> aria2Row?.metalink == true; "torrent" -> "magnet" in aria2; else -> protocol in aria2 }
            ProtocolSupportRow(protocol, nativeSupported, aria2Supported, when {
                protocol in setOf("hls", "dash") && nativeSupported -> "Use Native for Android media capture and manifest refresh."
                protocol in setOf("ftp", "sftp", "magnet", "metalink", "torrent") && aria2Supported -> "Use aria2 when the packaged runtime is available."
                nativeSupported && aria2Supported -> "Automatic can choose based on destination, auth, mirrors and size."
                nativeSupported -> "Native only."
                aria2Supported -> "aria2 only."
                else -> "Not available in this build."
            })
        })
    }
}

data class ReleasePackagingReport(val versionName: String, val versionCode: Int, val packageId: String, val debugPackageId: String, val betaPackageId: String, val releaseTask: String, val betaTask: String, val checksumScript: String) {
    val summary: String get() = "Release task $releaseTask • beta task $betaTask • checksum script $checksumScript"
}
object ReleasePackagingGate { fun report(versionName: String, versionCode: Int, packageId: String) = ReleasePackagingReport(versionName, versionCode, packageId, "$packageId.debug", "$packageId.beta", "assembleRelease", "assembleBeta", "tools/build-release-artifacts.sh") }

data class DesktopParityReport(val settingsImportExport: Boolean, val historyManagement: Boolean, val proxyCredentials: Boolean, val conversionPostProcessing: Boolean, val protocolExpansion: Boolean, val releasePackaging: Boolean) {
    val complete: Boolean get() = listOf(settingsImportExport, historyManagement, proxyCredentials, conversionPostProcessing, protocolExpansion, releasePackaging).all { it }
    val summary: String get() = if (complete) "Desktop parity surfaces are wired" else "Desktop parity still has missing surfaces"
}
object DesktopParityGate { fun evaluate(settingsImportExport: Boolean, historyManagement: Boolean, proxyCredentials: Boolean, conversionPostProcessing: Boolean, protocolExpansion: Boolean, releasePackaging: Boolean) = DesktopParityReport(settingsImportExport, historyManagement, proxyCredentials, conversionPostProcessing, protocolExpansion, releasePackaging) }

fun ConversionPreset.displayName(): String = when (this) { ConversionPreset.None -> "None"; ConversionPreset.VideoFastStart -> "Video fast-start metadata"; ConversionPreset.AudioExtract -> "Extract audio track"; ConversionPreset.ArchiveExtract -> "Extract archive after download"; ConversionPreset.CustomCommand -> "Custom command hook" }

private fun String.escapeSettingValue(): String = buildString { this@escapeSettingValue.forEach { char -> when (char) { '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); else -> append(char) } } }
private fun String.unescapeSettingValue(): String = buildString { var index = 0; while (index < this@unescapeSettingValue.length) { val char = this@unescapeSettingValue[index]; if (char == '\\' && index + 1 < this@unescapeSettingValue.length) { when (val next = this@unescapeSettingValue[index + 1]) { 'n' -> append('\n'); 'r' -> append('\r'); '\\' -> append('\\'); else -> append(next) }; index += 2 } else { append(char); index += 1 } } }
private fun String.redactQuerySecrets(): String { val sensitive = setOf("token", "sig", "signature", "key", "auth", "session", "credential"); val marker = indexOf('?'); if (marker < 0) return this; val base = substring(0, marker); val query = substring(marker + 1).split('&').joinToString("&") { pair -> val key = pair.substringBefore('=').lowercase(Locale.US); if (sensitive.any { it in key }) "${pair.substringBefore('=')}=<redacted>" else pair }; return "$base?$query" }
