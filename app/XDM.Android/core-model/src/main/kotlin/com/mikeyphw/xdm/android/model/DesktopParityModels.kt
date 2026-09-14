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
    fun portableCopy(): SettingsExchangeSnapshot = copy(
        destinationUri = destinationUri.takeUnless(String::isDeviceBoundDestinationUri).orEmpty(),
        proxy = proxy.copy(credentialAlias = ""),
        savedSearches = savedSearches.map { search ->
            search.copy(id = stableSettingsExchangeId("saved-search", listOf(search.name, search.query, search.state?.name.orEmpty(), search.includeArchived.toString())), createdAtEpochMs = 0)
        },
        destinationRules = destinationRules.mapNotNull { rule ->
            if (rule.destinationUri.isDeviceBoundDestinationUri()) return@mapNotNull null
            val normalized = OrganizationPowerTools.normalizedDestinationRule(rule) ?: return@mapNotNull null
            normalized.copy(
                id = stableSettingsExchangeId("destination-rule", listOf(normalized.name, normalized.match.name, normalized.pattern, normalized.destinationUri, normalized.priority.toString()))
            )
        },
        duplicateRules = duplicateRules.map { rule ->
            rule.copy(id = stableSettingsExchangeId("duplicate-rule", listOf(rule.hostPattern, rule.action.name)), hostPattern = rule.hostPattern.trim().lowercase(Locale.US))
        },
    )

    fun toPortableText(): String {
        val portable = portableCopy()
        return buildString {
            appendLine("xdm.settings.export=v1")
            appendLine("compactDensity=${portable.compactDensity}")
            appendLine("destinationUri=${portable.destinationUri.escapeSettingValue()}")
            appendLine("conflictPolicy=${portable.conflictPolicy.name}")
            appendLine("proxy.enabled=${portable.proxy.enabled}")
            appendLine("proxy.host=${portable.proxy.host.escapeSettingValue()}")
            appendLine("proxy.port=${portable.proxy.port ?: ""}")
            appendLine("proxy.username=${portable.proxy.username.escapeSettingValue()}")
            appendLine("proxy.credentialAlias=")
            appendLine("post.enabled=${portable.postProcessing.enabled}")
            appendLine("post.preset=${portable.postProcessing.preset.name}")
            appendLine("post.customCommandLabel=${portable.postProcessing.customCommandLabel.escapeSettingValue()}")
            portable.savedSearches.forEachIndexed { index, search ->
                appendLine("savedSearch.$index=${listOf(search.id, search.name, search.query, search.state?.name.orEmpty(), search.includeArchived.toString()).joinToString("|") { it.escapeSettingValue() }}")
            }
            portable.destinationRules.forEachIndexed { index, rule ->
                appendLine("destinationRule.$index=${listOf(rule.id, rule.name, rule.match.name, rule.pattern, rule.destinationUri, rule.enabled.toString(), rule.priority.toString()).joinToString("|") { it.escapeSettingValue() }}")
            }
            portable.duplicateRules.forEachIndexed { index, rule ->
                appendLine("duplicateRule.$index=${listOf(rule.id, rule.hostPattern, rule.action.name, rule.enabled.toString()).joinToString("|") { it.escapeSettingValue() }}")
            }
        }.trimEnd()
    }
}

enum class SettingsExchangeImportStatus { Idle, Accepted, Rejected }

data class SettingsExchangeImportResult(
    val status: SettingsExchangeImportStatus = SettingsExchangeImportStatus.Idle,
    val message: String = "No import attempted.",
    val snapshot: SettingsExchangeSnapshot? = null,
    val acceptedItems: Int = 0,
    val rejectedItems: List<String> = emptyList(),
) {
    val accepted: Boolean get() = status == SettingsExchangeImportStatus.Accepted && snapshot != null
    val summary: String get() = when (status) {
        SettingsExchangeImportStatus.Idle -> message
        SettingsExchangeImportStatus.Accepted -> buildString {
            append("Settings import accepted: $acceptedItems portable items")
            if (rejectedItems.isNotEmpty()) append("; skipped ${rejectedItems.size} unsafe or invalid items")
        }
        SettingsExchangeImportStatus.Rejected -> buildString {
            append("Settings import rejected: $message")
            if (rejectedItems.isNotEmpty()) append(" (${rejectedItems.joinToString("; ")})")
        }
    }

    companion object {
        val Idle = SettingsExchangeImportResult()
        fun rejected(message: String, rejectedItems: List<String> = emptyList()) =
            SettingsExchangeImportResult(SettingsExchangeImportStatus.Rejected, message, null, 0, rejectedItems)
    }
}

object SettingsExchangeCodec {
    fun decode(text: String): SettingsExchangeSnapshot? = decodeResult(text).snapshot

    fun decodeResult(text: String): SettingsExchangeImportResult {
        val rejected = mutableListOf<String>()
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { lineNumber, line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
            val index = trimmed.indexOf('=')
            if (index <= 0) {
                rejected += "line ${lineNumber + 1} is not key=value"
                return@forEachIndexed
            }
            values[trimmed.substring(0, index)] = trimmed.substring(index + 1)
        }
        if (values["xdm.settings.export"]?.unescapeSettingValue() != "v1") {
            return SettingsExchangeImportResult.rejected("unsupported or missing xdm.settings.export=v1 header", rejected)
        }

        fun decoded(key: String): String = values[key]?.unescapeSettingValue().orEmpty()
        val destinationUri = decoded("destinationUri").takeUnless(String::isDeviceBoundDestinationUri).orEmpty()
        if (decoded("destinationUri").isDeviceBoundDestinationUri()) rejected += "device-bound destinationUri was skipped"
        val proxyPort = decoded("proxy.port").toIntOrNull()?.takeIf { it in 1..65535 }
        if (decoded("proxy.port").isNotBlank() && proxyPort == null) rejected += "proxy.port is outside 1..65535"

        val savedSearches = values.entries
            .filter { it.key.startsWith("savedSearch.") }
            .mapNotNull { (key, value) ->
                val parts = splitEscapedSettingFields(value)
                if (parts.size < 5) {
                    rejected += "$key is malformed"
                    null
                } else {
                    val name = parts[1].trim().take(48)
                    val query = parts[2].trim().take(512)
                    if (name.isBlank() || query.isBlank()) {
                        rejected += "$key is missing name or query"
                        null
                    } else {
                        SavedSearch(
                            id = stableSettingsExchangeId("saved-search", listOf(name, query, parts[3], parts[4])),
                            name = name,
                            query = query,
                            state = parts[3].takeIf(String::isNotBlank)?.let { runCatching { DownloadState.valueOf(it) }.getOrNull() },
                            includeArchived = parts[4].toBooleanStrictOrNull() ?: false,
                            createdAtEpochMs = 0,
                        )
                    }
                }
            }
        val destinationRules = values.entries
            .filter { it.key.startsWith("destinationRule.") }
            .mapNotNull { (key, value) ->
                val parts = splitEscapedSettingFields(value)
                if (parts.size < 7) {
                    rejected += "$key is malformed"
                    null
                } else if (parts[4].isDeviceBoundDestinationUri()) {
                    rejected += "$key uses a device-bound destination grant"
                    null
                } else {
                    val imported = DestinationRule(
                        id = "ignored-import-id",
                        name = parts[1].trim().take(48),
                        match = runCatching { DestinationRuleMatch.valueOf(parts[2]) }.getOrDefault(DestinationRuleMatch.Host),
                        pattern = parts[3],
                        destinationUri = parts[4].trim(),
                        enabled = parts[5].toBooleanStrictOrNull() ?: true,
                        priority = parts[6].toIntOrNull() ?: 0,
                    )
                    OrganizationPowerTools.normalizedDestinationRule(imported)?.let { normalized ->
                        normalized.copy(id = stableSettingsExchangeId("destination-rule", listOf(normalized.name, normalized.match.name, normalized.pattern, normalized.destinationUri, normalized.priority.toString())))
                    } ?: run {
                        rejected += "$key has an invalid destination rule"
                        null
                    }
                }
            }
        val duplicateRules = values.entries
            .filter { it.key.startsWith("duplicateRule.") }
            .mapNotNull { (key, value) ->
                val parts = splitEscapedSettingFields(value)
                if (parts.size < 4) {
                    rejected += "$key is malformed"
                    null
                } else {
                    val hostPattern = parts[1].trim().lowercase(Locale.US).trimEnd('.').take(96)
                    if (hostPattern.isBlank() || ' ' in hostPattern) {
                        rejected += "$key has an invalid duplicate host pattern"
                        null
                    } else {
                        DuplicateUrlRule(
                            id = stableSettingsExchangeId("duplicate-rule", listOf(hostPattern, parts[2])),
                            hostPattern = hostPattern,
                            action = runCatching { DuplicateUrlAction.valueOf(parts[2]) }.getOrDefault(DuplicateUrlAction.Ask),
                            enabled = parts[3].toBooleanStrictOrNull() ?: true,
                        )
                    }
                }
            }
        val hardFailures = rejected.filterNot { it.contains("device-bound") }
        if (hardFailures.isNotEmpty()) {
            return SettingsExchangeImportResult.rejected("malformed or invalid fields", rejected)
        }
        val snapshot = SettingsExchangeSnapshot(
            compactDensity = decoded("compactDensity").toBooleanStrictOrNull() ?: false,
            destinationUri = destinationUri,
            conflictPolicy = decoded("conflictPolicy").let { runCatching { FilenameConflictPolicy.valueOf(it) }.getOrNull() } ?: FilenameConflictPolicy.Rename,
            proxy = ProxyCredentialSettings(
                enabled = decoded("proxy.enabled").toBooleanStrictOrNull() ?: false,
                host = decoded("proxy.host").trim().take(128),
                port = proxyPort,
                username = decoded("proxy.username").trim().take(128),
                credentialAlias = "",
            ),
            postProcessing = PostProcessingSettings(
                enabled = decoded("post.enabled").toBooleanStrictOrNull() ?: false,
                preset = decoded("post.preset").let { runCatching { ConversionPreset.valueOf(it) }.getOrNull() } ?: ConversionPreset.None,
                customCommandLabel = decoded("post.customCommandLabel").trim().take(96),
            ),
            savedSearches = savedSearches,
            destinationRules = destinationRules,
            duplicateRules = duplicateRules,
        )
        val acceptedItems = 3 + savedSearches.size + destinationRules.size + duplicateRules.size
        return SettingsExchangeImportResult(SettingsExchangeImportStatus.Accepted, "Settings snapshot accepted.", snapshot, acceptedItems, rejected)
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
        val normalized = ExternalUrlPolicy.normalizedUrl(url) ?: return null
        return downloads.firstOrNull { ExternalUrlPolicy.normalizedUrl(it.sourceUrl) == normalized }
    }

    fun duplicateActionFor(url: String, rules: List<DuplicateUrlRule>): DuplicateUrlAction {
        val host = ExternalUrlPolicy.originHost(url).orEmpty().lowercase(Locale.US).trimEnd('.')
        if (host.isBlank()) return DuplicateUrlAction.Ask
        return rules
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { rule ->
                val rawPattern = rule.hostPattern.trim().lowercase(Locale.US).trimEnd('.')
                val domain = rawPattern.removePrefix("*.")
                if (domain.isBlank()) return@mapNotNull null
                val matches = if (rawPattern.startsWith("*.")) {
                    host == domain || host.endsWith(".$domain")
                } else {
                    host == domain
                }
                if (!matches) return@mapNotNull null
                val exactBonus = if (rawPattern.startsWith("*.")) 0 else 1
                val specificity = domain.count { it == '.' } * 1_000 + domain.length * 2 + exactBonus
                specificity to rule.action
            }
            .maxByOrNull { it.first }
            ?.second
            ?: DuplicateUrlAction.Ask
    }

    fun normalizedDestinationRule(rule: DestinationRule): DestinationRule? {
        val normalizedPattern = when (rule.match) {
            DestinationRuleMatch.Host -> normalizeDestinationHostPattern(rule.pattern) ?: return null
            DestinationRuleMatch.Extension -> rule.pattern.trim().lowercase(Locale.US).removePrefix(".").take(24).takeIf { value -> value.isNotBlank() && value.all { it.isLetterOrDigit() || it in setOf('-', '_') } } ?: return null
            DestinationRuleMatch.MimeType -> rule.pattern.trim().lowercase(Locale.US).substringBefore(';').take(96).takeIf { value -> value.count { it == '/' } == 1 && !value.startsWith('/') && !value.endsWith('/') } ?: return null
            DestinationRuleMatch.Fallback -> "*"
        }
        val destination = rule.destinationUri.trim().takeIf(String::isNotBlank) ?: return null
        return rule.copy(name = rule.name.trim().take(48).ifBlank { "Destination rule" }, pattern = normalizedPattern, destinationUri = destination)
    }

    fun normalizeDestinationHostPattern(pattern: String): String? {
        val trimmed = pattern.trim().lowercase(Locale.US)
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trimEnd('.')
            .take(96)
        val domain = trimmed.removePrefix("*.")
        if (domain.isBlank() || ' ' in domain || !domain.contains('.')) return null
        return if (trimmed.startsWith("*.")) "*.${domain}" else domain
    }

    fun hostMatchesDestinationRule(host: String, pattern: String): Boolean {
        val normalizedHost = host.lowercase(Locale.US).trimEnd('.')
        val normalizedPattern = normalizeDestinationHostPattern(pattern) ?: return false
        return if (normalizedPattern.startsWith("*.")) {
            val domain = normalizedPattern.removePrefix("*.")
            normalizedHost.endsWith(".$domain")
        } else {
            normalizedHost == normalizedPattern
        }
    }

    fun destinationFor(url: String, fileName: String, mimeType: String?, rules: List<DestinationRule>, fallback: String): String {
        val host = ExternalUrlPolicy.originHost(url).orEmpty().lowercase(Locale.US).trimEnd('.')
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
        val enabled = rules.mapNotNull { normalizedDestinationRule(it) }.filter { it.enabled }.sortedByDescending { it.priority }
        val specific = enabled.firstOrNull { rule ->
            val pattern = rule.pattern.trim().lowercase(Locale.US)
            when (rule.match) {
                DestinationRuleMatch.Host -> hostMatchesDestinationRule(host, pattern)
                DestinationRuleMatch.Extension -> extension.isNotBlank() && extension == pattern.removePrefix(".")
                DestinationRuleMatch.MimeType -> when {
                    normalizedMime == null -> false
                    pattern.endsWith("/*") -> normalizedMime.startsWith(pattern.removeSuffix("*"))
                    else -> normalizedMime == pattern
                }
                DestinationRuleMatch.Fallback -> false
            }
        }
        return specific?.destinationUri
            ?: enabled.firstOrNull { it.match == DestinationRuleMatch.Fallback }?.destinationUri
            ?: fallback
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
        val seen = existing.map { ExternalUrlPolicy.normalizedUrl(it.url) }.toSet()
        return ExternalUrlPolicy.urlsInText(text)
            .filter { it !in seen }
            .map { url ->
                ClipboardInboxItem(
                    id = "clip-" + url.hashCode().toUInt().toString(16),
                    url = url,
                    title = ExternalUrlPolicy.originHost(url),
                    sourceTextHash = text.hashCode().toUInt().toString(16),
                    status = "New",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
            }
    }
}

data class BackupRestoreReport(val safe: Boolean, val itemCount: Int, val message: String) {
    val summary: String get() = if (safe) "Backup ready: $itemCount portable items" else "Backup needs action: $message"
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

data class ReleasePackagingReport(val versionName: String, val versionCode: Int, val packageId: String, val debugPackageId: String, val releaseTask: String, val checksumScript: String) {
    val summary: String get() = "Release task $releaseTask • checksum script $checksumScript"
}
object ReleasePackagingGate { fun report(versionName: String, versionCode: Int, packageId: String) = ReleasePackagingReport(versionName, versionCode, packageId, "$packageId.debug", "assembleRelease", "tools/build-release-artifacts.sh") }

data class DesktopParityReport(val settingsImportExport: Boolean, val historyManagement: Boolean, val proxyCredentials: Boolean, val conversionPostProcessing: Boolean, val protocolExpansion: Boolean, val releasePackaging: Boolean) {
    val complete: Boolean get() = listOf(settingsImportExport, historyManagement, proxyCredentials, conversionPostProcessing, protocolExpansion, releasePackaging).all { it }
    val summary: String get() = if (complete) "Desktop parity surfaces are wired" else "Desktop parity still has missing surfaces"
}
object DesktopParityGate { fun evaluate(settingsImportExport: Boolean, historyManagement: Boolean, proxyCredentials: Boolean, conversionPostProcessing: Boolean, protocolExpansion: Boolean, releasePackaging: Boolean) = DesktopParityReport(settingsImportExport, historyManagement, proxyCredentials, conversionPostProcessing, protocolExpansion, releasePackaging) }

fun ConversionPreset.displayName(): String = when (this) { ConversionPreset.None -> "None"; ConversionPreset.VideoFastStart -> "Video fast-start metadata"; ConversionPreset.AudioExtract -> "Extract audio track"; ConversionPreset.ArchiveExtract -> "Extract archive after download"; ConversionPreset.CustomCommand -> "Custom command hook" }

private fun String.escapeSettingValue(): String = buildString {
    this@escapeSettingValue.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '|' -> append("\\p")
            else -> append(char)
        }
    }
}

private fun String.unescapeSettingValue(): String = buildString {
    var index = 0
    while (index < this@unescapeSettingValue.length) {
        val char = this@unescapeSettingValue[index]
        if (char == '\\' && index + 1 < this@unescapeSettingValue.length) {
            when (val next = this@unescapeSettingValue[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                'p' -> append('|')
                '\\' -> append('\\')
                else -> append(next)
            }
            index += 2
        } else {
            append(char)
            index += 1
        }
    }
}

private fun splitEscapedSettingFields(value: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '\\' && index + 1 < value.length) {
            current.append(char)
            current.append(value[index + 1])
            index += 2
        } else if (char == '|') {
            parts += current.toString().unescapeSettingValue()
            current.clear()
            index += 1
        } else {
            current.append(char)
            index += 1
        }
    }
    parts += current.toString().unescapeSettingValue()
    return parts
}

private fun String.isDeviceBoundDestinationUri(): Boolean = trim().lowercase(Locale.US).let { value ->
    value.startsWith("content://") || value.startsWith("file://") || value.startsWith("/storage/") || value.startsWith("/sdcard/")
}

private fun stableSettingsExchangeId(prefix: String, fields: List<String>): String =
    "$prefix-" + fields.joinToString("\u001f").hashCode().toUInt().toString(16)

private fun String.redactQuerySecrets(): String {
    val sensitive = setOf("token", "sig", "signature", "key", "auth", "session", "credential")
    val marker = indexOf('?')
    if (marker < 0) return this
    val base = substring(0, marker)
    val query = substring(marker + 1).split('&').joinToString("&") { pair ->
        val key = pair.substringBefore('=').lowercase(Locale.US)
        if (sensitive.any { it in key }) "${pair.substringBefore('=')}=<redacted>" else pair
    }
    return "$base?$query"
}
