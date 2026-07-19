package com.mikeyphw.xdm.android.model

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

enum class ReleaseSecuritySeverity { Info, Warning, Blocking }

data class ReleaseSecurityFinding(
    val id: String,
    val severity: ReleaseSecuritySeverity,
    val title: String,
    val detail: String,
)

data class ReleaseSecurityReport(
    val versionName: String,
    val schemaVersion: Int,
    val buildType: String,
    val findings: List<ReleaseSecurityFinding>,
) {
    val blockingCount: Int get() = findings.count { it.severity == ReleaseSecuritySeverity.Blocking }
    val warningCount: Int get() = findings.count { it.severity == ReleaseSecuritySeverity.Warning }
    val betaReady: Boolean get() = blockingCount == 0
    val summary: String get() = when {
        blockingCount > 0 -> "$blockingCount blocking release issue${if (blockingCount == 1) "" else "s"}"
        warningCount > 0 -> "$warningCount release warning${if (warningCount == 1) "" else "s"}"
        else -> "Beta gate checks are clean"
    }
}

object ReleaseSecurityGate {
    fun evaluate(
        versionName: String,
        schemaVersion: Int,
        buildType: String,
        debuggable: Boolean,
        privacySafeDiagnostics: Boolean,
        releaseSigningConfigured: Boolean,
    ): ReleaseSecurityReport {
        val normalizedBuildType = buildType.trim().ifBlank { "unknown" }
        val findings = buildList {
            val minor = versionName.removeSuffix("-debug").removeSuffix("-beta")
                .split('.')
                .getOrNull(1)
                ?.toIntOrNull()
                ?: -1
            if (minor < 14) {
                add(
                    ReleaseSecurityFinding(
                        id = "version.phase14",
                        severity = ReleaseSecuritySeverity.Warning,
                        title = "Version metadata",
                        detail = "Expected version metadata from phase 14 or later.",
                    ),
                )
            }
            if (schemaVersion != 13) {
                add(
                    ReleaseSecurityFinding(
                        id = "database.schema",
                        severity = ReleaseSecuritySeverity.Blocking,
                        title = "Unexpected schema version",
                        detail = "Release hardening must not migrate Room unless a later release gate explicitly does it.",
                    ),
                )
            }
            if (!privacySafeDiagnostics) {
                add(
                    ReleaseSecurityFinding(
                        id = "diagnostics.privacy",
                        severity = ReleaseSecuritySeverity.Blocking,
                        title = "Diagnostics are not privacy-safe",
                        detail = "Diagnostic summaries must redact tokens, cookies, signatures and sensitive headers.",
                    ),
                )
            }
            if (debuggable && normalizedBuildType !in setOf("debug", "benchmark")) {
                add(
                    ReleaseSecurityFinding(
                        id = "build.debuggable",
                        severity = ReleaseSecuritySeverity.Blocking,
                        title = "Release build is debuggable",
                        detail = "Non-debug builds must not enable Android debugging.",
                    ),
                )
            }
            if (normalizedBuildType == "release" && !releaseSigningConfigured) {
                add(
                    ReleaseSecurityFinding(
                        id = "signing.release",
                        severity = ReleaseSecuritySeverity.Warning,
                        title = "Release signing missing",
                        detail = "Unsigned release builds are allowed locally, but a publishable build must provide release signing values.",
                    ),
                )
            }
            if (isEmpty()) {
                add(
                    ReleaseSecurityFinding(
                        id = "gate.clean",
                        severity = ReleaseSecuritySeverity.Info,
                        title = "Release gate",
                        detail = "Privacy, schema and build profile checks passed for this build.",
                    ),
                )
            }
        }
        return ReleaseSecurityReport(versionName, schemaVersion, normalizedBuildType, findings)
    }
}

object PrivacyDiagnosticsRedactor {
    private val sensitiveHeaderNames = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "x-api-key",
        "x-auth-token",
        "x-csrf-token",
        "x-xsrf-token",
    )
    private val sensitiveQueryNames = setOf(
        "access_token",
        "auth",
        "auth_token",
        "code",
        "cookie",
        "key",
        "password",
        "session",
        "sessionid",
        "sig",
        "signature",
        "token",
    )
    private val bearerPattern = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val querySecretPattern = Regex("(?i)([?&][^=&#]*(?:token|secret|password|session|cookie|signature|sig|key|auth)[^=&#]*=)[^&#\\s]+")

    fun isSensitiveHeaderName(name: String): Boolean = name.trim().lowercase(Locale.US) in sensitiveHeaderNames

    fun redactText(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return text
            .replace(bearerPattern) { match -> match.groupValues[1].lowercase(Locale.US) + " <redacted>" }
            .replace(querySecretPattern) { match -> match.groupValues[1] + "<redacted>" }
            .take(512)
    }

    fun redactUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return redactText(raw)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return redactText(raw)
        val host = uri.host?.lowercase(Locale.US) ?: return redactText(raw)
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.joinToString("&") { part ->
                val name = part.substringBefore('=', missingDelimiterValue = part).lowercase(Locale.US)
                if (name in sensitiveQueryNames || sensitiveQueryNames.any { marker -> name.contains(marker) }) {
                    "$name=<redacted>"
                } else {
                    part.take(160)
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?.let { "?$it" }
            .orEmpty()
        return "$scheme://$host$port$path$query"
    }

    fun redactHeaders(raw: String?): String? {
        val lines = raw?.lineSequence()?.map(String::trim)?.filter { it.isNotBlank() }?.toList().orEmpty()
        if (lines.isEmpty()) return null
        return lines.mapNotNull { line ->
            val name = line.substringBefore(':', missingDelimiterValue = line).trim()
            if (name.isBlank()) return@mapNotNull null
            val normalized = name.lowercase(Locale.US)
            if (normalized in sensitiveHeaderNames) "$normalized: <redacted>" else redactText(line)
        }.joinToString("\n").takeIf { it.isNotBlank() }
    }

    fun redactedCommandLine(record: AutomationCommandRecord): String = listOfNotNull(
        record.source.name,
        record.originHost?.let { redactText(it) },
        record.status.name,
        record.rejectionReason.name.takeIf { it != AutomationRejectionReason.None.name },
    ).joinToString(" • ")

    fun redactedHealthSummary(
        report: ReleaseSecurityReport,
        downloadCount: Int,
        mediaCaptureCount: Int,
        automationCount: Int,
        rejectedHandoffCount: Int,
    ): String = buildString {
        appendLine("XDM Android diagnostic summary")
        appendLine("Version: ${report.versionName}")
        appendLine("Build: ${report.buildType}")
        appendLine("Schema: ${report.schemaVersion}")
        appendLine("Release gate: ${report.summary}")
        appendLine("Downloads: $downloadCount")
        appendLine("Media captures: $mediaCaptureCount")
        appendLine("Automation commands: $automationCount")
        appendLine("Rejected handoffs: $rejectedHandoffCount")
        append("Fingerprint: ${fingerprint(report.versionName + report.buildType + downloadCount + mediaCaptureCount + automationCount + rejectedHandoffCount)}")
    }

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(12)
}

fun AutomationCommandRecord.redactedDiagnosticLine(): String = PrivacyDiagnosticsRedactor.redactedCommandLine(this)
