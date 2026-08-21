package com.mikeyphw.xdm.android.browserextension

import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionPackageGeneratorTest {
    private val config = BrowserExtensionBuildConfig(
        appVersion = "0.21.0",
        applicationId = "com.mikeyphw.xdm.android",
        channel = BrowserExtensionSourceContract.Channel.Release,
        xdmScheme = "xdmdownload",
        defaultTarget = BrowserExtensionSourceContract.Target.Xdm,
    )

    @Test fun `same inputs produce byte-identical xpi`() {
        val generator=BrowserExtensionPackageGenerator(::sourceEntry); val first=generator.generate(config); val second=generator.generate(config)
        assertTrue(first.archiveBytes.contentEquals(second.archiveBytes)); assertEquals(first.sha256,second.sha256); assertEquals(first.fileName,second.fileName)
    }
    @Test fun `same inputs are byte-identical across device time zones`() {
        val original=TimeZone.getDefault(); try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); val utc=BrowserExtensionPackageGenerator(::sourceEntry).generate(config)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo")); val sp=BrowserExtensionPackageGenerator(::sourceEntry).generate(config)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")); val tokyo=BrowserExtensionPackageGenerator(::sourceEntry).generate(config)
            assertTrue(utc.archiveBytes.contentEquals(sp.archiveBytes)); assertTrue(utc.archiveBytes.contentEquals(tokyo.archiveBytes))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun `theme changes generated package and deterministic filename`() {
        val generator=BrowserExtensionPackageGenerator(::sourceEntry); val dark=generator.generate(config); val amoled=generator.generate(config.copy(themeMode=BrowserExtensionSourceContract.ThemeMode.Amoled))
        assertFalse(dark.archiveBytes.contentEquals(amoled.archiveBytes)); assertTrue(dark.fileName.endsWith("-dark.xpi")); assertTrue(amoled.fileName.endsWith("-amoled.xpi"))
    }
    @Test fun `release package is keyless and validates for every target`() {
        BrowserExtensionSourceContract.Target.entries.forEach { target ->
            val targetConfig=config.copy(defaultTarget=target); val result=BrowserExtensionPackageGenerator(::sourceEntry).generate(targetConfig)
            assertTrue(BrowserExtensionPackageValidator.validate(result.archiveBytes,targetConfig).valid)
            val configEntry=java.util.zip.ZipInputStream(result.archiveBytes.inputStream()).use { zip ->
                var found=""; while (true) { val e=zip.nextEntry?:break; if(e.name=="generated-config.js") { found=zip.readBytes().toString(Charsets.UTF_8); break } }; found
            }
            assertFalse(configEntry.contains("captureKeyId")); assertFalse(configEntry.contains("capturePublicKeySpki")); assertFalse(configEntry.contains("captureOaepHash"))
        }
    }
    @Test fun `build configuration rejects script-breaking app versions`() {
        assertTrue(runCatching { config.copy(appVersion="bad\nversion") }.isFailure); assertTrue(runCatching { config.copy(appVersion="bad\"version") }.isFailure)
    }
    @Test fun `validator rejects traversal entry`() {
        val report=BrowserExtensionPackageValidator.validate(zipOf("../escape.js" to "bad".toByteArray()),config); assertFalse(report.valid); assertTrue(report.errors.any{it.contains("Unsafe archive entry")})
    }
    @Test fun `generated package contains exact inventory and no template files`() {
        val result=BrowserExtensionPackageGenerator(::sourceEntry).generate(config); val report=BrowserExtensionPackageValidator.validate(result.archiveBytes,config)
        assertTrue(report.valid); assertEquals(BrowserExtensionSourceContract.PackagedEntries,report.entryNames); assertFalse(report.entryNames.any{".template." in it})
    }
    private fun sourceEntry(name:String)=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/extension/xdm-firefox",name))
    private fun zipOf(vararg entries:Pair<String,ByteArray>):ByteArray { val output=ByteArrayOutputStream(); ZipOutputStream(output).use { zip -> entries.forEach { (name,bytes) -> val crc=CRC32().apply{update(bytes)}.value; zip.putNextEntry(ZipEntry(name).apply { method=ZipEntry.STORED; size=bytes.size.toLong(); compressedSize=size; this.crc=crc; time=315_532_800_000L }); zip.write(bytes); zip.closeEntry() } }; return output.toByteArray() }
}
