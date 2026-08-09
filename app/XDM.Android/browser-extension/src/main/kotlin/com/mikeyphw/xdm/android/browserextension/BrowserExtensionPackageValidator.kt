package com.mikeyphw.xdm.android.browserextension

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object BrowserExtensionPackageValidator {
    private const val MaxEntryBytes = 2 * 1024 * 1024
    private const val MaxArchiveBytes = 8 * 1024 * 1024
    private val unsafePath = Regex("(?:^/|^[A-Za-z]:|(?:^|/)\\.\\.(?:/|$)|\\\\)")

    data class Report(
        val valid: Boolean,
        val errors: List<String>,
        val entryNames: List<String>,
    ) {
        fun requireValid() {
            require(valid) { errors.joinToString(prefix = "Invalid Firefox extension package: ", separator = "; ") }
        }
    }

    fun validate(bytes: ByteArray, expected: BrowserExtensionBuildConfig): Report {
        val errors = mutableListOf<String>()
        val entries = linkedMapOf<String, ByteArray>()
        val order = mutableListOf<String>()
        if (bytes.isEmpty()) errors += "Archive is empty"
        if (bytes.size > MaxArchiveBytes) errors += "Archive exceeds $MaxArchiveBytes bytes"

        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    order += name
                    when {
                        entry.isDirectory -> errors += "Directory entries are not allowed: $name"
                        name.isBlank() -> errors += "Blank archive entry"
                        unsafePath.containsMatchIn(name) -> errors += "Unsafe archive entry: $name"
                        entries.containsKey(name) -> errors += "Duplicate archive entry: $name"
                        else -> {
                            val data = zip.readBytesLimited(MaxEntryBytes + 1)
                            if (data.size > MaxEntryBytes) errors += "Archive entry too large: $name"
                            entries[name] = data
                        }
                    }
                    zip.closeEntry()
                }
            }
        }.onFailure { errors += "ZIP parse failed: ${it.message ?: it::class.java.simpleName}" }

        if (order != order.sorted()) errors += "Archive entries are not sorted"
        val required = BrowserExtensionSourceContract.PackagedEntries.toSet()
        val names = entries.keys
        (required - names).sorted().forEach { errors += "Missing archive entry: $it" }
        (names - required).sorted().forEach { errors += "Unexpected archive entry: $it" }
        names.filter { it.endsWith(".template.json") || it.endsWith(".template.js") || it.endsWith(".template.css") }
            .forEach { errors += "Template leaked into archive: $it" }

        val manifest = entries["manifest.json"]?.toString(Charsets.UTF_8).orEmpty()
        requireContains(manifest, "\"manifest_version\": 2", "Manifest v2 missing", errors)
        requireContains(manifest, "\"id\": \"${BrowserExtensionSourceContract.ExtensionId}\"", "Stable extension ID missing", errors)
        requireContains(manifest, "\"version\": \"${expected.extensionVersion}\"", "Manifest version mismatch", errors)

        val config = entries["generated-config.js"]?.toString(Charsets.UTF_8).orEmpty()
        listOf(
            "contractVersion: ${expected.contractVersion}",
            "extensionVersion: \"${expected.extensionVersion}\"",
            "appVersion: \"${escapeJs(expected.appVersion)}\"",
            "applicationId: \"${expected.applicationId}\"",
            "channel: \"${expected.channel.wireValue}\"",
            "xdmScheme: \"${expected.xdmScheme}\"",
            "defaultTarget: \"${expected.defaultTarget.wireValue}\"",
            "captureKeyId: \"${escapeJs(expected.captureKeyId)}\"",
            "capturePublicKeySpki: \"${escapeJs(expected.capturePublicKeySpki)}\"",
            "themeMode: \"${expected.themeMode.wireValue}\"",
        ).forEach { token -> requireContains(config, token, "Generated config mismatch: $token", errors) }

        val theme = entries["generated-theme.css"]?.toString(Charsets.UTF_8).orEmpty()
        requireContains(theme, "--xdm-theme-mode: ${expected.themeMode.wireValue}", "Generated theme mode mismatch", errors)
        entries.forEach { (name, data) ->
            if (name.endsWith(".js") || name.endsWith(".json") || name.endsWith(".css") || name.endsWith(".html")) {
                val text = data.toString(Charsets.UTF_8)
                if ("@@" in text) errors += "Unresolved template token in $name"
            }
        }
        return Report(errors.isEmpty(), errors.distinct(), order)
    }

    private fun requireContains(source: String, token: String, error: String, errors: MutableList<String>) {
        if (!source.contains(token)) errors += error
    }

    private fun escapeJs(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun ZipInputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() <= limit) {
            val read = read(buffer)
            if (read < 0) break
            if (read > 0) output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
