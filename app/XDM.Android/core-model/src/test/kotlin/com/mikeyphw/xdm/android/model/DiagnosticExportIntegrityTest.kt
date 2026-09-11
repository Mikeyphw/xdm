package com.mikeyphw.xdm.android.model

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportIntegrityTest {
    @Test
    fun signedMediaAliasesAndNestedDetailsAreRedactedBeforeFinalZipScan() {
        val root = createTempDirectory("xdm-parity01-safe").toFile()
        try {
            val raw = """{"sessionId":"safe-debug-id","safeDetails":{"url":"https://cdn.example.test/master.m3u8?md5=MD5_SECRET&sess=SESS_SECRET&token=TOKEN_SECRET","nested":"https://example.test/a?signature=SIG_SECRET"}}"""
            val sanitized = DiagnosticExportIntegrity.sanitizeJsonl(raw)
            assertFalse(sanitized.contains("MD5_SECRET"))
            assertFalse(sanitized.contains("SESS_SECRET"))
            assertFalse(sanitized.contains("TOKEN_SECRET"))
            assertFalse(sanitized.contains("SIG_SECRET"))
            assertTrue(sanitized.contains("md5=<redacted>"))
            assertTrue(sanitized.contains("sess=<redacted>"))

            val zip = File(root, "xdm-debug-safe.zip")
            val scan = DiagnosticExportIntegrity.writeVerifiedZip(
                zip,
                mapOf(
                    "debug-events.jsonl" to sanitized.toByteArray(),
                    "report.txt" to "Authorization: <redacted>\nCookie: <redacted>\n".toByteArray(),
                ),
            )
            assertTrue(scan.safe)
            assertTrue(scan.manifestVerified)
            assertTrue(zip.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exactFinalZipRejectsPurposefullyPlantedSecretMarker() {
        val root = createTempDirectory("xdm-parity01-secret").toFile()
        try {
            val zip = File(root, "xdm-debug-secret.zip")
            assertThrows(SecurityException::class.java) {
                DiagnosticExportIntegrity.writeVerifiedZip(
                    zip,
                    mapOf(
                        "debug-events.jsonl" to """{"safeDetails":{"url":"https://cdn.example.test/video?md5=PLANTED_SECRET&sess=ALSO_SECRET"}}\n""".toByteArray(),
                    ),
                )
            }
            assertFalse(zip.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exactFinalZipRejectsMalformedOrTruncatedJsonl() {
        val root = createTempDirectory("xdm-parity01-jsonl").toFile()
        try {
            val zip = File(root, "xdm-debug-malformed.zip")
            assertThrows(SecurityException::class.java) {
                DiagnosticExportIntegrity.writeVerifiedZip(
                    zip,
                    mapOf("debug-events.jsonl" to """{"id":"one"}\n{"id":"truncated""".toByteArray()),
                )
            }
            assertFalse(zip.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun timelineTailNeverStartsOrEndsWithPartialJsonRecord() {
        val records = (1..8).joinToString("\n", postfix = "\n") { index ->
            """{"id":"$index","safeDetails":{"value":"${"x".repeat(24)}"}}"""
        }
        val tail = DiagnosticExportIntegrity.tailWholeJsonlRecords(records, maxChars = 150)
        assertTrue(tail.isNotBlank())
        tail.lineSequence().filter(String::isNotBlank).forEach { line ->
            assertTrue(DiagnosticExportIntegrity.isStructurallyValidJsonObject(line))
        }
    }


    @Test
    fun manifestCarriesBuildSchemaRunSummaryAndScannerAttestation() {
        val root = createTempDirectory("xdm-parity01-manifest").toFile()
        try {
            val zip = File(root, "xdm-debug-manifest.zip")
            val scan = DiagnosticExportIntegrity.writeVerifiedZip(
                destination = zip,
                rawEntries = mapOf("report.txt" to "safe\n".toByteArray()),
                metadata = DiagnosticBundleMetadata(
                    diagnosticsSchema = 1,
                    diagnosticsVersion = "v5",
                    appVersion = "1.2.3",
                    buildType = "debug",
                    roomSchemaVersion = 22,
                    runId = "debug-run-123",
                    testSummary = "16 tests • 14 passed • 2 warnings",
                ),
            )
            assertTrue(scan.safe)
            val manifest = ZipFile(zip).use { archive ->
                archive.getInputStream(archive.getEntry(DiagnosticExportIntegrity.ManifestEntryName)).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            assertTrue(manifest.contains("\"diagnosticsSchema\":1"))
            assertTrue(manifest.contains("\"diagnosticsVersion\":\"v5\""))
            assertTrue(manifest.contains("\"appVersion\":\"1.2.3\""))
            assertTrue(manifest.contains("\"buildType\":\"debug\""))
            assertTrue(manifest.contains("\"roomSchemaVersion\":22"))
            assertTrue(manifest.contains("\"runId\":\"debug-run-123\""))
            assertTrue(manifest.contains("\"result\":\"passed\""))
            assertTrue(manifest.contains(DiagnosticExportIntegrity.ScannerVersion))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun strictJsonlGrammarRejectsBalancedButInvalidJson() {
        val root = createTempDirectory("xdm-parity01-invalid-json").toFile()
        try {
            val zip = File(root, "xdm-debug-invalid-json.zip")
            assertThrows(SecurityException::class.java) {
                DiagnosticExportIntegrity.writeVerifiedZip(
                    zip,
                    mapOf("debug-events.jsonl" to "{\"safeDetails\":}\n".toByteArray()),
                )
            }
            assertFalse(zip.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ambiguousDuplicateStyleFilenameIsRejectedByFinalScanner() {
        val root = createTempDirectory("xdm-parity01-name").toFile()
        try {
            val original = File(root, "xdm-debug-run.zip")
            DiagnosticExportIntegrity.writeVerifiedZip(original, mapOf("report.txt" to "safe\n".toByteArray()))
            val ambiguous = File(root, "xdm-debug-run (1).zip")
            original.copyTo(ambiguous)
            val scan = DiagnosticExportIntegrity.scanZip(ambiguous)
            assertFalse(scan.safe)
            assertTrue(scan.errors.any { it.contains("ambiguous") })
        } finally {
            root.deleteRecursively()
        }
    }
}
