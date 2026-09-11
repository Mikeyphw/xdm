package com.mikeyphw.xdm.android.model

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Metadata embedded in every verified diagnostics bundle manifest. */
data class DiagnosticBundleMetadata(
    val diagnosticsSchema: Int = 1,
    val diagnosticsVersion: String = "v5",
    val appVersion: String = "unknown",
    val buildType: String = "unknown",
    val roomSchemaVersion: Int? = null,
    val runId: String = "unknown",
    val testSummary: String = "not supplied",
    val redactionScannerVersion: String = DiagnosticExportIntegrity.ScannerVersion,
)

/** Result of scanning the exact ZIP bytes that may be shared by the user. */
data class DiagnosticBundleScan(
    val safe: Boolean,
    val entryCount: Int,
    val manifestVerified: Boolean,
    val errors: List<String>,
) {
    val summary: String
        get() = if (safe) {
            "Final diagnostics ZIP passed privacy, JSONL, manifest, and path verification."
        } else {
            "Final diagnostics ZIP is unsafe: ${errors.joinToString("; ")}"
        }
}

/**
 * Final-artifact diagnostics privacy/integrity boundary.
 *
 * Collection-time redaction is defense in depth. A diagnostics ZIP is not shareable until the
 * exact bytes written to disk pass this scanner. The writer performs a preflight so the manifest
 * can truthfully state that redaction passed, then rescans the resulting ZIP before returning it.
 */
object DiagnosticExportIntegrity {
    const val ManifestEntryName = "diagnostic-manifest.json"
    const val ScannerVersion = "media-parity01-final-zip-v1"
    private const val MaxEntryBytes = 8 * 1024 * 1024
    private const val MaxBundleBytes = 24 * 1024 * 1024

    private val sensitiveQueryNames = setOf(
        "access_token", "auth", "auth_token", "authkey", "auth_key", "code", "cookie", "credential",
        "expires", "hdnea", "hdnts", "hash", "hmac", "jwt", "key", "md5", "password", "policy",
        "sess", "session", "session_id", "sessionid", "session_key", "sig", "signature", "secret",
        "ticket", "token", "x_amz_credential", "x_amz_security_token", "x_amz_signature",
        "x_goog_credential", "x_goog_security_token", "x_goog_signature",
    )
    private val sensitiveHeaderNames = setOf(
        "authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key", "x-auth-token",
        "x-csrf-token", "x-xsrf-token",
    )
    private val structuredSensitiveNames = setOf(
        "authorization", "proxy_authorization", "cookie", "set_cookie", "token", "secret", "password",
        "session", "sess", "md5", "signature", "sig", "api_key", "access_key", "refresh_token",
    )
    private val queryPattern = Regex("(?i)([?&])([^=&#\\s]+)=([^&#\\s\"']+)")
    private val headerPattern = Regex("(?im)^\\s*([A-Za-z0-9_-]+)\\s*:\\s*([^\\r\\n]+)$")
    private val jsonFieldPattern = Regex("(?i)\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
    private val bearerPattern = Regex("(?i)\\b(?:bearer|basic)\\s+(?!<redacted>)[A-Za-z0-9._~+/=-]{8,}")
    private val manifestItemPattern = Regex("\\{\\\"name\\\":\\\"([^\\\"]+)\\\",\\\"sha256\\\":\\\"([0-9a-f]{64})\\\",\\\"bytes\\\":(\\d+)\\}")
    private val ambiguousCopyNamePattern = Regex(".*\\(\\d+\\).*")

    fun sanitizeJsonl(value: String): String = value
        .lineSequence()
        .filter(String::isNotBlank)
        .joinToString("\n") { DebugRedactor.redactExportLine(it) }
        .let { if (it.isBlank()) "" else "$it\n" }

    /** Return the newest complete JSONL records that fit the requested character budget. */
    fun tailWholeJsonlRecords(value: String, maxChars: Int): String {
        if (maxChars <= 0 || value.isBlank()) return ""
        val selected = ArrayDeque<String>()
        var used = 0
        value.lineSequence().filter(String::isNotBlank).toList().asReversed().forEach { line ->
            val cost = line.length + 1
            if (cost > maxChars && selected.isEmpty()) return@forEach
            if (used + cost > maxChars) return@forEach
            selected.addFirst(line)
            used += cost
        }
        return selected.joinToString("\n").let { if (it.isBlank()) "" else "$it\n" }
    }

    fun buildManifest(entries: Map<String, ByteArray>, metadata: DiagnosticBundleMetadata): ByteArray {
        val safe = metadata.sanitized()
        val body = entries.toSortedMap().entries.joinToString(",") { (name, bytes) ->
            "{\"name\":\"${jsonEscape(name)}\",\"sha256\":\"${sha256(bytes)}\",\"bytes\":${bytes.size}}"
        }
        return buildString {
            append('{')
            append("\"format\":1,")
            append("\"diagnosticsSchema\":${safe.diagnosticsSchema},")
            append("\"diagnosticsVersion\":\"${jsonEscape(safe.diagnosticsVersion)}\",")
            append("\"appVersion\":\"${jsonEscape(safe.appVersion)}\",")
            append("\"buildType\":\"${jsonEscape(safe.buildType)}\",")
            append("\"roomSchemaVersion\":${safe.roomSchemaVersion ?: "null"},")
            append("\"runId\":\"${jsonEscape(safe.runId)}\",")
            append("\"testSummary\":\"${jsonEscape(safe.testSummary)}\",")
            append("\"hash\":\"SHA-256\",")
            append("\"redactionScanner\":{")
            append("\"version\":\"${jsonEscape(safe.redactionScannerVersion)}\",")
            append("\"result\":\"passed\"},")
            append("\"entries\":[").append(body).append("]}\n")
        }.toByteArray(Charsets.UTF_8)
    }

    /** Writes deterministic entry order/timestamps and verifies the exact resulting ZIP. */
    fun writeVerifiedZip(
        destination: File,
        rawEntries: Map<String, ByteArray>,
        metadata: DiagnosticBundleMetadata = DiagnosticBundleMetadata(),
    ): DiagnosticBundleScan {
        require(rawEntries.isNotEmpty()) { "Diagnostics export has no entries" }
        require(ManifestEntryName !in rawEntries) { "$ManifestEntryName is generated by the exporter" }
        val preflightErrors = validateEntries(rawEntries)
        if (preflightErrors.isNotEmpty()) {
            destination.delete()
            throw SecurityException("Diagnostics export preflight failed: ${preflightErrors.joinToString("; ")}")
        }
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("Could not replace the previous diagnostics export")
        }
        val entries = rawEntries.toSortedMap().toMutableMap().apply {
            put(ManifestEntryName, buildManifest(rawEntries, metadata))
        }.toSortedMap()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply { time = 0L }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val scan = scanZip(destination)
        if (!scan.safe) {
            destination.delete()
            throw SecurityException(scan.summary)
        }
        return scan
    }

    fun scanZip(file: File): DiagnosticBundleScan {
        val errors = mutableListOf<String>()
        if (!file.isFile) return DiagnosticBundleScan(false, 0, false, listOf("export file is missing"))
        if (ambiguousCopyNamePattern.matches(file.nameWithoutExtension)) {
            errors += "ambiguous duplicate-style export name"
        }
        val bytesByName = linkedMapOf<String, ByteArray>()
        runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
                if (entries.map(ZipEntry::getName).distinct().size != entries.size) errors += "duplicate ZIP entry names"
                var totalBytes = 0L
                entries.forEach { entry ->
                    if (!isSafeEntryName(entry.name)) {
                        errors += "unsafe diagnostics ZIP entry path '${entry.name}'"
                        return@forEach
                    }
                    if (entry.size > MaxEntryBytes) {
                        errors += "entry ${entry.name} exceeds diagnostics size limit"
                        return@forEach
                    }
                    val data = zip.getInputStream(entry).use { input -> input.readNBytes(MaxEntryBytes + 1) }
                    if (data.size > MaxEntryBytes) {
                        errors += "entry ${entry.name} exceeds diagnostics size limit"
                        return@forEach
                    }
                    totalBytes += data.size
                    if (totalBytes > MaxBundleBytes) errors += "diagnostics ZIP exceeds bounded uncompressed size"
                    bytesByName[entry.name] = data
                }
            }
        }.onFailure { errors += "ZIP cannot be read: ${it.javaClass.simpleName}" }

        errors += validateEntries(bytesByName.filterKeys { it != ManifestEntryName })
        val manifestVerified = verifyManifest(bytesByName, errors)
        return DiagnosticBundleScan(
            safe = errors.isEmpty() && manifestVerified,
            entryCount = bytesByName.size,
            manifestVerified = manifestVerified,
            errors = errors.distinct(),
        )
    }

    /** Fast in-memory contract used by release truth surfaces; the final ZIP scan remains authoritative. */
    fun contractSelfTest(): Boolean {
        val unsafe = "https://cdn.example.test/master.m3u8?md5=PLANTED_SECRET&sess=SESSION_SECRET"
        val safe = DebugRedactor.redactExportLine(unsafe)
        return detectSecrets("self-test.txt", safe).isEmpty() &&
            "PLANTED_SECRET" !in safe && "SESSION_SECRET" !in safe &&
            isStructurallyValidJsonObject("{\"safeDetails\":{\"url\":\"$safe\"}}") &&
            !isStructurallyValidJsonObject("{\"safeDetails\":}") &&
            detectSecrets("self-test.txt", unsafe).isNotEmpty()
    }

    private fun validateEntries(entries: Map<String, ByteArray>): List<String> {
        val errors = mutableListOf<String>()
        var totalBytes = 0L
        entries.forEach { (name, bytes) ->
            if (!isSafeEntryName(name)) errors += "unsafe diagnostics ZIP entry path '$name'"
            if (bytes.size > MaxEntryBytes) errors += "entry $name exceeds diagnostics size limit"
            totalBytes += bytes.size
            val text = bytes.toString(Charsets.UTF_8)
            detectSecrets(name, text).forEach(errors::add)
            if (name.endsWith(".jsonl", ignoreCase = true)) {
                val records = text.lineSequence().filter(String::isNotBlank).toList()
                records.forEachIndexed { index, line ->
                    if (!isStructurallyValidJsonObject(line)) errors += "$name record ${index + 1} is malformed/truncated JSON"
                }
            }
        }
        if (totalBytes > MaxBundleBytes) errors += "diagnostics ZIP exceeds bounded uncompressed size"
        return errors.distinct()
    }

    private fun verifyManifest(bytesByName: Map<String, ByteArray>, errors: MutableList<String>): Boolean {
        val raw = bytesByName[ManifestEntryName] ?: run {
            errors += "diagnostic manifest is missing"
            return false
        }
        val manifestText = raw.toString(Charsets.UTF_8)
        if (!isStructurallyValidJsonObject(manifestText)) errors += "diagnostic manifest is malformed"
        val requiredMetadataMarkers = listOf(
            "\"diagnosticsSchema\":",
            "\"diagnosticsVersion\":",
            "\"appVersion\":",
            "\"buildType\":",
            "\"roomSchemaVersion\":",
            "\"runId\":",
            "\"testSummary\":",
            "\"redactionScanner\":",
            "\"version\":\"$ScannerVersion\"",
            "\"result\":\"passed\"",
        )
        requiredMetadataMarkers.filterNot(manifestText::contains).forEach { marker ->
            errors += "diagnostic manifest missing required metadata marker $marker"
        }
        detectSecrets(ManifestEntryName, manifestText).forEach(errors::add)
        val declared = manifestItemPattern.findAll(manifestText).associate { match ->
            match.groupValues[1] to (match.groupValues[2] to match.groupValues[3].toLong())
        }
        val expectedNames = bytesByName.keys - ManifestEntryName
        if (declared.keys != expectedNames) {
            errors += "diagnostic manifest entry inventory does not match ZIP contents"
            return false
        }
        var valid = true
        expectedNames.forEach { name ->
            val bytes = requireNotNull(bytesByName[name])
            val (declaredHash, declaredBytes) = requireNotNull(declared[name])
            if (sha256(bytes) != declaredHash || bytes.size.toLong() != declaredBytes) {
                errors += "diagnostic manifest mismatch for $name"
                valid = false
            }
        }
        return valid && requiredMetadataMarkers.all(manifestText::contains)
    }

    private fun detectSecrets(entryName: String, text: String): List<String> {
        val findings = mutableListOf<String>()
        queryPattern.findAll(text).forEach { match ->
            val name = normalizeName(match.groupValues[2])
            val value = match.groupValues[3]
            if (isSensitiveQueryName(name) && !isRedacted(value)) findings += "$entryName contains unredacted query credential '$name'"
        }
        headerPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1].lowercase(Locale.US)
            val value = match.groupValues[2]
            if (name in sensitiveHeaderNames && !isRedacted(value)) findings += "$entryName contains unredacted sensitive header '$name'"
        }
        jsonFieldPattern.findAll(text).forEach { match ->
            val name = normalizeName(match.groupValues[1])
            val value = match.groupValues[2]
            if ((name in structuredSensitiveNames || name.replace('_', '-') in sensitiveHeaderNames) && !isRedacted(value)) {
                findings += "$entryName contains unredacted structured credential '$name'"
            }
        }
        if (bearerPattern.containsMatchIn(text)) findings += "$entryName contains an unredacted authorization token"
        return findings.distinct()
    }

    internal fun isStructurallyValidJsonObject(value: String): Boolean = JsonStructureParser(value).isValidObject()

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.startsWith('\\')) return false
        if ('\\' in name) return false
        return name.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun isSensitiveQueryName(name: String): Boolean = name in sensitiveQueryNames ||
        setOf("_auth", "_credential", "_key", "_password", "_secret", "_sess", "_session", "_session_id", "_signature", "_token")
            .any(name::endsWith)

    private fun normalizeName(raw: String): String = raw.trim().lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun isRedacted(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.US)
        return normalized.isBlank() || normalized.contains("<redacted>") || normalized.contains("redacted") || normalized == "***"
    }

    private fun DiagnosticBundleMetadata.sanitized(): DiagnosticBundleMetadata = copy(
        diagnosticsSchema = diagnosticsSchema.coerceAtLeast(1),
        diagnosticsVersion = DebugRedactor.redactText(diagnosticsVersion).ifBlank { "unknown" },
        appVersion = DebugRedactor.redactText(appVersion).ifBlank { "unknown" },
        buildType = DebugRedactor.redactText(buildType).ifBlank { "unknown" },
        runId = DebugRedactor.redactText(runId).ifBlank { "unknown" },
        testSummary = DebugRedactor.redactText(testSummary).ifBlank { "not supplied" },
        redactionScannerVersion = ScannerVersion,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /** Small dependency-free JSON grammar validator used only at the diagnostic export boundary. */
    private class JsonStructureParser(private val source: String) {
        private var index = 0

        fun isValidObject(): Boolean {
            index = 0
            skipWhitespace()
            if (!parseObject()) return false
            skipWhitespace()
            return index == source.length
        }

        private fun parseValue(): Boolean {
            skipWhitespace()
            if (index >= source.length) return false
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> false
            }
        }

        private fun parseObject(): Boolean {
            if (!consume('{')) return false
            skipWhitespace()
            if (consume('}')) return true
            while (true) {
                skipWhitespace()
                if (!parseString()) return false
                skipWhitespace()
                if (!consume(':')) return false
                if (!parseValue()) return false
                skipWhitespace()
                if (consume('}')) return true
                if (!consume(',')) return false
            }
        }

        private fun parseArray(): Boolean {
            if (!consume('[')) return false
            skipWhitespace()
            if (consume(']')) return true
            while (true) {
                if (!parseValue()) return false
                skipWhitespace()
                if (consume(']')) return true
                if (!consume(',')) return false
            }
        }

        private fun parseString(): Boolean {
            if (!consume('"')) return false
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return true
                    char == '\\' -> {
                        if (index >= source.length) return false
                        when (source[index++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                if (index >= source.length || !source[index++].isHexDigit()) return false
                            }
                            else -> return false
                        }
                    }
                    char.code < 0x20 -> return false
                }
            }
            return false
        }

        private fun parseNumber(): Boolean {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) return false
            } else {
                if (index >= source.length || source[index] !in '1'..'9') return false
                while (index < source.length && source[index].isDigit()) index++
            }
            if (consume('.')) {
                if (index >= source.length || !source[index].isDigit()) return false
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                if (index >= source.length || !source[index].isDigit()) return false
                while (index < source.length && source[index].isDigit()) index++
            }
            return index > start
        }

        private fun parseLiteral(literal: String): Boolean {
            if (!source.regionMatches(index, literal, 0, literal.length)) return false
            index += literal.length
            return true
        }

        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in charArrayOf(' ', '\t', '\r', '\n')) index++
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
