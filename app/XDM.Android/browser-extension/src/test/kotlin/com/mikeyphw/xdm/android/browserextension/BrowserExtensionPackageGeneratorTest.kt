package com.mikeyphw.xdm.android.browserextension

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.TimeZone
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionPackageGeneratorTest {
    private val config = BrowserExtensionBuildConfig(
        extensionVersion = "1.0.0",
        appVersion = "0.21.0",
        applicationId = "com.mikeyphw.xdm.android",
        channel = BrowserExtensionSourceContract.Channel.Release,
        xdmScheme = "xdmdownload",
        defaultTarget = BrowserExtensionSourceContract.Target.Xdm,
        themeMode = BrowserExtensionSourceContract.ThemeMode.Dark,
        captureKeyId = captureKeyIdForSpki("A".repeat(256)),
        capturePublicKeySpki = "A".repeat(256),
        captureOaepHash = "SHA-256",
    )

    @Test
    fun `same inputs produce byte-identical xpi`() {
        val generator = BrowserExtensionPackageGenerator(::sourceEntry)
        val first = generator.generate(config)
        val second = generator.generate(config)
        assertTrue(first.archiveBytes.contentEquals(second.archiveBytes))
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.fileName, second.fileName)
    }

    @Test
    fun `same inputs are byte-identical across device time zones`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = BrowserExtensionPackageGenerator(::sourceEntry).generate(config)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
            val saoPaulo = BrowserExtensionPackageGenerator(::sourceEntry).generate(config)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyo = BrowserExtensionPackageGenerator(::sourceEntry).generate(config)
            assertTrue(utc.archiveBytes.contentEquals(saoPaulo.archiveBytes))
            assertTrue(utc.archiveBytes.contentEquals(tokyo.archiveBytes))
            assertEquals(utc.sha256, saoPaulo.sha256)
            assertEquals(utc.sha256, tokyo.sha256)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `theme changes generated package and deterministic filename`() {
        val generator = BrowserExtensionPackageGenerator(::sourceEntry)
        val dark = generator.generate(config)
        val amoled = generator.generate(config.copy(themeMode = BrowserExtensionSourceContract.ThemeMode.Amoled))
        assertFalse(dark.archiveBytes.contentEquals(amoled.archiveBytes))
        assertTrue(dark.fileName.endsWith("-dark.xpi"))
        assertTrue(amoled.fileName.endsWith("-amoled.xpi"))
    }

    @Test
    fun `generated package embeds only configured public capture identity`() {
        val secure = config.copy(
            captureKeyId = captureKeyIdForSpki("A".repeat(256)),
            capturePublicKeySpki = "A".repeat(256),
        )
        val result = BrowserExtensionPackageGenerator(::sourceEntry).generate(secure)
        val report = BrowserExtensionPackageValidator.validate(result.archiveBytes, secure)
        assertTrue(report.valid)
    }

    @Test
    fun `build configuration rejects script-breaking app versions`() {
        assertTrue(runCatching { config.copy(appVersion = "bad\nversion") }.isFailure)
        assertTrue(runCatching { config.copy(appVersion = "bad\"version") }.isFailure)
    }

    @Test
    fun `release configuration fails closed without capture key material for every target`() {
        BrowserExtensionSourceContract.Target.entries.forEach { target ->
            assertTrue("release target ${target.wireValue} must remain key-bound", runCatching {
                config.copy(
                    defaultTarget = target,
                    captureKeyId = "",
                    capturePublicKeySpki = "",
                )
            }.isFailure)
        }
    }

    @Test
    fun `capture key id must be derived from supplied spki bytes`() {
        assertTrue(runCatching { config.copy(captureKeyId = "0123456789abcdef01234567") }.isFailure)
        assertEquals(captureKeyIdForSpki(config.capturePublicKeySpki), config.captureKeyId)
    }

    @Test
    fun `debug ask configuration remains keyless for local development`() {
        val debug = BrowserExtensionBuildConfig(
            extensionVersion = "1.0.0",
            appVersion = "0.21.0",
            applicationId = "com.mikeyphw.xdm.android",
            channel = BrowserExtensionSourceContract.Channel.Debug,
            xdmScheme = "xdmdownload",
            defaultTarget = BrowserExtensionSourceContract.Target.Ask,
            captureOaepHash = "SHA-256",
        )
        assertTrue(debug.captureKeyId.isBlank())
        assertTrue(debug.capturePublicKeySpki.isBlank())
    }

    @Test
    fun `validator rejects traversal entry`() {
        val bytes = zipOf("../escape.js" to "bad".toByteArray())
        val report = BrowserExtensionPackageValidator.validate(bytes, config)
        assertFalse(report.valid)
        assertTrue(report.errors.any { it.contains("Unsafe archive entry") })
    }

    @Test
    fun `generated package contains exact inventory and no template files`() {
        val result = BrowserExtensionPackageGenerator(::sourceEntry).generate(config)
        val report = BrowserExtensionPackageValidator.validate(result.archiveBytes, config)
        assertTrue(report.valid)
        assertEquals(BrowserExtensionSourceContract.PackagedEntries, report.entryNames)
        assertFalse(report.entryNames.any { ".template." in it })
    }

    private fun sourceEntry(name: String): ByteArray {
        val path = java.nio.file.Path.of("src/main/extension/xdm-firefox", name)
        return java.nio.file.Files.readAllBytes(path)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val crc = CRC32().apply { update(bytes) }.value
                zip.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc
                    time = 315_532_800_000L
                })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
