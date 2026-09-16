package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rm04DiagnosticsReleaseFinalSealContractTest {
    private val root = androidRoot()

    @Test fun diagnosticsTruthSeparatesRuntimePrivacyFromFinalZipAttestation() {
        val security = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt")
        val finalGate = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt")
        val readiness = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(security.contains("Runtime diagnostics gate:"))
        assertTrue(finalGate.contains("diagnostics.export-attestation"))
        assertTrue(finalGate.contains("Final diagnostics export redaction is not attested"))
        assertFalse(finalGate.contains("title = \"Diagnostics are not redacted\""))
        assertTrue(readiness.contains("diagnosticsRuntimePrivacyReady"))
        assertTrue(viewModel.contains("diagnosticsExportAttested = diagnosticsExportValidated"))
        assertTrue(viewModel.contains("redactedReportsOnly = diagnosticsRuntimePrivacyReady"))
    }

    @Test fun supportBundleKeepsPrivacyFailureDistinctFromMissingAttestation() {
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessSeal.kt")
        assertTrue(seal.contains("Final diagnostics export attestation"))
        assertTrue(seal.contains("Privacy redaction boundary"))
        assertTrue(seal.contains("diagnosticsExportAttested"))
        assertTrue(seal.contains("Runtime redaction is active, but final diagnostics ZIP privacy/integrity verification is not attested"))
    }

    @Test fun sameRunRm04EvidenceIsRequiredByXar16Publication() {
        val builder = source("tools/build-xar16-release-artifacts.sh")
        val verifier = source("tools/verify-xar16-release-evidence.py")
        val publication = source("tools/generate-xar16-publication-bundle.sh")
        val writer = source("tools/write-rm04-validation-evidence.py")

        assertTrue(builder.contains("write-rm04-validation-evidence.py"))
        assertTrue(builder.contains("rm04-validation-seal.json"))
        assertTrue(verifier.contains("'rm04-validation-seal.json'"))
        assertTrue(publication.contains("rm04-validation-seal.json"))
        assertTrue(writer.contains("canonicalNonDeviceValidationPassed"))
        assertTrue(writer.contains("finalDiagnosticsZipPrivacyIntegrity"))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
