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
    val savedSearches: List<SavedSearch> = emptyList(),
    val destinationRules: List<DestinationRule> = emptyList(),
    val duplicateRules: List<DuplicateUrlRule> = emptyList(),
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
        savedSearches.forEachIndexed { index, search ->
            appendLine("savedSearch.$index=${listOf(search.id, search.name, search.query, search.state?.name.orEmpty(), search.includeArchived.toString()).joinToString("|") { it.escapeSettingValue() }}")
        }
        destinationRules.forEachIndexed { index, rule ->
            appendLine("destinationRule.$index=${listOf(rule.id, rule.name, rule.match.name, rule.pattern, rule.destinationUri, rule.enabled.toString(), rule.priority.toString()).joinToString("|") { it.escapeSettingValue() }}")
        }
        duplicateRules.forEachIndexed { index, rule ->
            appendLine("duplicateRule.$index=${listOf(rule.id, rule.hostPattern, rule.action.name, rule.enabled.toString()).joinToString("|") { it.escapeSettingValue() }}")
        }
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
            savedSearches = values.entries
                .filter { it.key.startsWith("savedSearch.") }
                .mapNotNull { (_, value) ->
                    val parts = value.split('|')
                    if (parts.size < 5) null else SavedSearch(
                        id = parts[0].ifBlank { "search-${parts[1].hashCode()}" },
                        name = parts[1],
                        query = parts[2],
                        state = parts[3].takeIf(String::isNotBlank)?.let { runCatching { DownloadState.valueOf(it) }.getOrNull() },
                        includeArchived = parts[4].toBooleanStrictOrNull() ?: false,
                        createdAtEpochMs = 0,
                    )
                },
            destinationRules = values.entries
                .filter { it.key.startsWith("destinationRule.") }
                .mapNotNull { (_, value) ->
                    val parts = value.split('|')
                    if (parts.size < 7) null else DestinationRule(
                        id = parts[0].ifBlank { "destination-${parts[1].hashCode()}" },
                        name = parts[1],
                        match = runCatching { DestinationRuleMatch.valueOf(parts[2]) }.getOrDefault(DestinationRuleMatch.Host),
                        pattern = parts[3],
                        destinationUri = parts[4],
                        enabled = parts[5].toBooleanStrictOrNull() ?: true,
                        priority = parts[6].toIntOrNull() ?: 0,
                    )
                },
            duplicateRules = values.entries
                .filter { it.key.startsWith("duplicateRule.") }
                .mapNotNull { (_, value) ->
                    val parts = value.split('|')
                    if (parts.size < 4) null else DuplicateUrlRule(
                        id = parts[0].ifBlank { "duplicate-${parts[1].hashCode()}" },
                        hostPattern = parts[1],
                        action = runCatching { DuplicateUrlAction.valueOf(parts[2]) }.getOrDefault(DuplicateUrlAction.Ask),
                        enabled = parts[3].toBooleanStrictOrNull() ?: true,
                    )
                },
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
    fun visible(downloads: List<Download>, includeArchived: Boolean): List<Download> =
        downloads.filter { includeArchived || !it.archived }
    fun exportIndex(downloads: List<Download>): String = buildString {
        appendLine("XDM Android history index")
        downloads.sortedByDescending { it.updatedAtEpochMs }.forEach { download -> appendLine("${download.state.name}\t${download.backend.name}\t${download.fileName}\t${download.sourceUrl.redactQuerySecrets()}") }
    }.trimEnd()
}

data class OrganizationPowerToolsReport(
    val tags: Int,
    val savedSearches: Int,
    val destinationRules: Int,
    val duplicateRules: Int,
    val archivedDownloads: Int,
) {
    val summary: String get() = "$tags tags • $savedSearches searches • $destinationRules destination rules • $duplicateRules duplicate rules • $archivedDownloads archived"
}

object OrganizationPowerTools {
    fun summarize(tags: List<DownloadTag>, searches: List<SavedSearch>, destinations: List<DestinationRule>, duplicates: List<DuplicateUrlRule>, downloads: List<Download>) =
        OrganizationPowerToolsReport(tags.size, searches.size, destinations.count { it.enabled }, duplicates.count { it.enabled }, downloads.count { it.archived })

    fun duplicateFor(url: String, downloads: List<Download>): Download? {
        val normalized = BrowserHandoffPolicy.normalizedUrl(url) ?: return null
        return downloads.firstOrNull { BrowserHandoffPolicy.normalizedUrl(it.sourceUrl) == normalized }
    }

    fun destinationFor(url: String, fileName: String, mimeType: String?, rules: List<DestinationRule>, fallback: String): String {
        val host = BrowserHandoffPolicy.originHost(url).orEmpty()
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return rules.filter { it.enabled }.sortedByDescending { it.priority }.firstOrNull { rule ->
            val pattern = rule.pattern.lowercase(Locale.US)
            when (rule.match) {
                DestinationRuleMatch.Host -> host.endsWith(pattern.removePrefix("*."))
                DestinationRuleMatch.Extension -> extension == pattern.removePrefix(".")
                DestinationRuleMatch.MimeType -> mimeType?.lowercase(Locale.US)?.startsWith(pattern.removeSuffix("*")) == true
                DestinationRuleMatch.Fallback -> true
            }
        }?.destinationUri ?: fallback
    }
}

data class BrowserIntegrationStatus(
    val shareHandoff: Boolean,
    val viewHandoff: Boolean,
    val clipboardInbox: Boolean,
    val recentOrigins: Int,
    val rejectedHandoffs: Int,
) {
    val summary: String get() = "Share ${ready(shareHandoff)} • browser ${ready(viewHandoff)} • clipboard ${ready(clipboardInbox)} • $recentOrigins origins • $rejectedHandoffs rejected"
    private fun ready(value: Boolean) = if (value) "ready" else "off"
}

object ClipboardInboxPolicy {
    fun itemsFromText(text: String, existing: List<ClipboardInboxItem>, now: Long): List<ClipboardInboxItem> {
        val seen = existing.map { BrowserHandoffPolicy.normalizedUrl(it.url) }.toSet()
        return BrowserHandoffPolicy.urlsInText(text)
            .filter { it !in seen }
            .map { url ->
                ClipboardInboxItem(
                    id = "clip-" + url.hashCode().toUInt().toString(16),
                    url = url,
                    title = BrowserHandoffPolicy.originHost(url),
                    sourceTextHash = text.hashCode().toUInt().toString(16),
                    status = "New",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
            }
    }
}

data class BackupRestoreReport(val safe: Boolean, val itemCount: Int, val message: String) {
    val summary: String get() = if (safe) "Backup ready: $itemCount portable items" else "Backup needs attention: $message"
}

object BackupRestorePolicy {
    private val forbidden = listOf("cookie", "authorization", "password", "secret", "token=", "post.body")
    fun evaluate(exportText: String): BackupRestoreReport {
        val lower = exportText.lowercase(Locale.US)
        val blocked = forbidden.firstOrNull { it in lower }
        val count = exportText.lineSequence().count { it.contains('=') }
        return if (blocked == null) BackupRestoreReport(true, count, "Safe to copy or restore") else BackupRestoreReport(false, count, "Contains blocked field $blocked")
    }
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
