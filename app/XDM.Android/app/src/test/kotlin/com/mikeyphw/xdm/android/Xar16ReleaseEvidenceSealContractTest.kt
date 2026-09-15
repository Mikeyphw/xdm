package com.mikeyphw.xdm.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Xar16ReleaseEvidenceSealContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }

    @Test
    fun signedReleaseGateRequiresSameRunArtifactDeviceAndPublicationEvidence() {
        val gate = File(root, "tools/run-xar16-signed-release-gate.sh").readText()
        val builder = File(root, "tools/build-xar16-release-artifacts.sh").readText()
        val verifier = File(root, "tools/verify-xar16-release-evidence.py").readText()
        assertTrue(gate.contains("build-xar16-release-artifacts.sh"))
        assertTrue(gate.contains("run-xar16-install-upgrade-matrix.sh"))
        assertTrue(gate.contains("run-xar16-signed-release-journeys.sh"))
        assertTrue(gate.contains("generate-xar16-publication-bundle.sh"))
        assertTrue(builder.contains("xar16-same-run-artifacts.json"))
        assertTrue(builder.contains("phase10-release-attestation.json"))
        assertTrue(verifier.contains("apkSetInstalled"))
        assertTrue(verifier.contains("upgradeRebootLaunchVerified"))
        assertTrue(verifier.contains("signedReleaseJourneysPassed"))
    }

    @Test
    fun apkSetVerificationUsesSplitSetSemantics() {
        val verifier = File(root, "tools/verify-phase10-release-artifacts.py").readText()
        assertTrue(verifier.contains("split_apk: bool = False"))
        assertTrue(verifier.contains("set-level-required-inventory"))
        assertFalse(verifier.contains("split_results.append(verify_apk(target,args.signer_sha256,args.require_16kb,inventory))"))
    }

    @Test
    fun finalGateProjectsXar16EvidenceIntoRuntimeModel() {
        val build = File(root, "app/build.gradle.kts").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val model = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt").readText()
        assertTrue(build.contains("XDM_APK_SET_INSTALL_VERIFIED"))
        assertTrue(build.contains("verifyXar16ReleaseEvidenceSeal"))
        assertTrue(viewModel.contains("BuildConfig.XDM_APK_SET_INSTALL_VERIFIED"))
        assertTrue(model.contains("id = \"apkset.install\""))
        assertTrue(model.contains("id = \"publication.evidence\""))
        assertTrue(model.contains("id = \"release.journeys\""))
    }
}
