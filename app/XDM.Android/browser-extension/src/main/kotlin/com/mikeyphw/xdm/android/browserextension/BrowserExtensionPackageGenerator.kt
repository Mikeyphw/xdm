package com.mikeyphw.xdm.android.browserextension

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrowserExtensionPackageGenerator(
    private val sourceLoader: (String) -> ByteArray = ::loadClasspathEntry,
) {
    fun generate(config: BrowserExtensionBuildConfig): BrowserExtensionExportResult {
        val rendered = BrowserExtensionSourceContract.SourceEntries.associate { sourceName ->
            packagedName(sourceName) to renderEntry(sourceName, sourceLoader(sourceName), config)
        }
        val output = ByteArrayOutputStream()
        writeDeterministicZip(rendered, output)
        val bytes = output.toByteArray()
        BrowserExtensionPackageValidator.validate(bytes, config).requireValid()
        return BrowserExtensionExportResult(
            fileName = config.outputFileName,
            byteCount = bytes.size.toLong(),
            sha256 = BrowserExtensionHash.sha256(bytes),
            archiveBytes = bytes,
        )
    }

    fun generateToFile(config: BrowserExtensionBuildConfig, output: File): BrowserExtensionExportResult {
        val result = generate(config)
        output.parentFile?.mkdirs()
        output.outputStream().buffered().use { it.write(result.archiveBytes) }
        require(output.length() == result.byteCount) { "Generated XPI byte-count mismatch" }
        return result
    }

    private fun renderEntry(name: String, bytes: ByteArray, config: BrowserExtensionBuildConfig): ByteArray {
        if (!name.contains(".template.")) return bytes
        val text = bytes.toString(Charsets.UTF_8)
        val rendered = when (name) {
            "manifest.template.json" -> text.replace("@@EXTENSION_VERSION@@", jsonEscape(config.extensionVersion))
            "generated-config.template.js" -> XdmThemeCssGenerator.render(
                text
                    .replace("@@CONTRACT_VERSION@@", config.contractVersion.toString())
                    .replace("@@EXTENSION_VERSION@@", jsEscape(config.extensionVersion))
                    .replace("@@APP_VERSION@@", jsEscape(config.appVersion))
                    .replace("@@APPLICATION_ID@@", jsEscape(config.applicationId))
                    .replace("@@CHANNEL@@", jsEscape(config.channel.wireValue))
                    .replace("@@XDM_SCHEME@@", jsEscape(config.xdmScheme))
                    .replace("@@DEFAULT_TARGET@@", jsEscape(config.defaultTarget.wireValue))
                    .replace("@@CAPTURE_KEY_ID@@", jsEscape(config.captureKeyId))
                    .replace("@@CAPTURE_PUBLIC_KEY_SPKI@@", jsEscape(config.capturePublicKeySpki))
                    .replace("@@CAPTURE_OAEP_HASH@@", jsEscape(config.captureOaepHash)),
                config.themeMode,
            )
            "generated-theme.template.css" -> XdmThemeCssGenerator.render(text, config.themeMode)
            else -> error("Unsupported extension template: $name")
        }
        require("@@" !in rendered) { "Unresolved template token in $name" }
        return rendered.toByteArray(Charsets.UTF_8)
    }

    private fun writeDeterministicZip(entries: Map<String, ByteArray>, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            entries.toSortedMap().forEach { (name, bytes) ->
                require(isSafeEntryName(name)) { "Unsafe archive entry: $name" }
                val crc = CRC32().apply { update(bytes) }.value
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc
                    time = DeterministicZipEpochMillis
                    extra = byteArrayOf()
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun packagedName(sourceName: String): String = when (sourceName) {
        "manifest.template.json" -> "manifest.json"
        "generated-config.template.js" -> "generated-config.js"
        "generated-theme.template.css" -> "generated-theme.css"
        else -> sourceName
    }

    private fun isSafeEntryName(name: String): Boolean = name.isNotBlank() &&
        !name.startsWith('/') &&
        '\\' !in name &&
        name.split('/').none { it == ".." || it.isBlank() }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun jsEscape(value: String): String = jsonEscape(value)

    companion object {
        private val DeterministicZipEpochMillis: Long
            get() = LocalDateTime.of(1980, 1, 1, 0, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

        private fun loadClasspathEntry(name: String): ByteArray {
            val path = "${BrowserExtensionSourceContract.ResourceRoot}/$name"
            val loader = BrowserExtensionPackageGenerator::class.java.classLoader
            return requireNotNull(loader.getResourceAsStream(path)) { "Missing extension resource: $path" }.use { it.readBytes() }
        }
    }
}
