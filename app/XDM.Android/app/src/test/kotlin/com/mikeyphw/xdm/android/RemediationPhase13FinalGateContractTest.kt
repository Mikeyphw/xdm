package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationPhase13FinalGateContractTest {
    private val root = androidRoot()
    private val repositoryRoot = requireNotNull(root.parentFile?.parentFile)

    @Test
    fun releaseReadinessConsumesExplicitFailClosedValidationEvidence() {
        val build = source("app/build.gradle.kts")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val gate = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGate.kt")

        assertTrue(build.contains("validationEvidence(\"xdm.validation.staticPassed\")"))
        assertTrue(build.contains("validationEvidence(\"xdm.validation.fullPassed\")"))
        assertTrue(build.contains(".orElse(false)"))
        assertTrue(build.contains("XDM_STATIC_VALIDATION_PASSED"))
        assertTrue(build.contains("XDM_FULL_VALIDATION_PASSED"))
        assertTrue(viewModel.contains("BuildConfig.XDM_STATIC_VALIDATION_PASSED"))
        assertTrue(viewModel.contains("BuildConfig.XDM_FULL_VALIDATION_PASSED"))
        assertFalse(viewModel.contains("staticValidatorsComplete = true"))
        assertFalse(viewModel.contains("fullValidationPassed = true"))
        assertFalse(viewModel.contains("releaseSafetyReady = true"))
        assertFalse(viewModel.contains("installUpdateReady = true"))
        assertFalse(viewModel.contains("aria2PayloadGateRetained = true"))
        assertFalse(viewModel.contains("updateKeepsPackageIdentity = true"))
        assertTrue(gate.contains("staticValidationPassed: Boolean = false"))
        assertTrue(gate.contains("fullValidationPassed: Boolean = false"))
        assertTrue(gate.contains("val releaseReady: Boolean"))
        assertTrue(gate.contains("releaseReady = ready"))
        assertTrue(gate.contains("FinalOverlayArtifact: String = \"xdm_android_privacy_quality_final_gate_overlay_v2.zip\""))
        assertTrue(gate.contains("CurrentRoomSchema: Int = 20"))
    }

    @Test
    fun privacyQualityAndFailureClassificationUseRealStructuredEvidence() {
        val privacy = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt")
        val developer = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
        val quality = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt")
        val planner = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
        val player = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt")
        val execution = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")

        assertTrue(privacy.contains("scannedFilesystemRootCount"))
        assertTrue(privacy.contains("scannedFilesystemFileCount"))
        assertTrue(privacy.contains("filesystemCoverageIssueCount"))
        assertTrue(privacy.contains("filesystemCoverageComplete"))
        assertTrue(privacy.contains("coverageComplete = coverageIssues == 0"))
        assertTrue(privacy.contains("MAX_FILESYSTEM_FILE_SIZE = 256L * 1024L"))
        assertTrue(privacy.contains("findings.map { it.surface }.distinct().size"))
        assertTrue(privacy.contains("MAX_FILESYSTEM_FILES = 256"))
        assertTrue(privacy.contains("MAX_FILESYSTEM_BYTES = 128 * 1024"))
        assertTrue(developer.contains("secure-request-envelopes-v1"))
        assertTrue(developer.contains("browser-capture-import-journal"))
        assertTrue(developer.contains("browser-capture-session-index"))
        assertTrue(developer.contains("queue-scheduling-recovery"))

        assertTrue(quality.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(quality.contains("capture.sourceUrl.toByteArray"))
        assertTrue(quality.contains("request=${'$'}requestFingerprint"))
        assertFalse(quality.contains("substringBeforeLast('/'))"))
        assertTrue(planner.contains("ExternalUrlPolicy.hasCredentialBearingQuery(capture.sourceUrl)"))
        assertTrue(planner.contains("structuredProtectionMarker"))
        assertTrue(planner.contains("resolverReport = null"))
        assertFalse(planner.contains("displayLabel.contains(\"protected\""))
        assertFalse(planner.contains("it.contains(\"widevine\""))

        assertTrue(player.contains("error.errorCodeName"))
        assertTrue(player.contains("error.causeClassName"))
        assertTrue(player.contains("code in NETWORK_ERROR_CODES"))
        assertTrue(player.contains("code in DRM_ERROR_CODES"))
        assertTrue(execution.contains("download?.state == DownloadState.Failed && download.backend == BackendType.Aria2"))
        assertTrue(execution.contains("plan.strategy == MediaDownloadStrategy.YtDlp"))
        assertFalse(execution.contains("errorMessage?.contains("))
    }

    @Test
    fun browserDirectAddAndDirectV3MediaCaptureRemainSeparateReviewRoutes() {
        val handoff = source("browser-extension/src/main/extension/xdm-firefox/handoff.js")
        val bridge = source("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
        val detector = source("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
        val bridgeTest = source("browser-extension/tests/test_phase43a_bridge.js")

        assertTrue(handoff.contains("function buildXdmAdd"))
        assertTrue(handoff.contains("function buildXdmCapture"))
        assertTrue(handoff.contains("params.set(\"v\", String(CONFIG.contractVersion || 3))"))
        assertTrue(handoff.contains("params.set(\"url\", url)"))
        assertTrue(bridge.contains("input.prebuiltXdmLink || (allowDirectAdd ? builtLinks.xdm : \"\")"))
        assertTrue(detector.contains("HARD_NON_MEDIA_MIME_RE"))
        assertTrue(bridgeTest.contains("direct-v3 XDM handoff"))
    }

    @Test
    fun finalDevtoolValidationCannotBeDeferredAndRunsTheCampaignGate() {
        val build = source("app/build.gradle.kts")
        val finalGate = source("tools/run-final-release-gate.sh")
        val publicationGate = source("tools/run-bug-hunt-phase10-release-gate.sh")
        val commonValidation = source("tools/run-final-common-validation.sh")
        val devtool = File(repositoryRoot, ".devtool.toml").readText()
        val workflow = File(repositoryRoot, ".github/workflows/android.yml").readText()
        val projectManifest = source("PROJECT_MANIFEST.json")

        assertTrue(build.contains("tasks.register<Exec>(\"finalRemediationStaticGate\")"))
        assertTrue(build.contains("tools/run-final-release-gate.sh"))
        assertTrue(finalGate.contains("validate-remediation-phase13-final-gate.py"))
        assertTrue(finalGate.contains("validate-debug-workbench-d7-final-debug-seal.py"))
        assertTrue(devtool.contains("\":app:finalRemediationStaticGate\""))
        assertTrue(devtool.contains("\":browser-extension:test\""))
        assertTrue(devtool.contains("\":browser-extension:jsTest\""))
        assertTrue(devtool.contains("\":browser-extension:validateFirefoxExtension\""))
        assertTrue(devtool.contains("\"lintDebug\""))
        assertTrue(devtool.contains("\":core-model:test\""))
        assertTrue(devtool.contains("\":media:test\""))
        assertTrue(devtool.contains("\"assembleDebug\""))
        assertTrue(devtool.contains("\":app:assembleDebugAndroidTest\""))
        assertTrue(publicationGate.contains("bash tools/run-final-release-gate.sh --ci"))
        assertTrue(publicationGate.contains("bash tools/run-final-common-validation.sh"))
        assertTrue(finalGate.contains("bash tools/run-final-common-validation.sh"))
        assertTrue(commonValidation.contains(":app:compileDebugKotlin"))
        assertTrue(commonValidation.contains(":core-model:test"))
        assertTrue(commonValidation.contains(":media:test"))
        assertTrue(commonValidation.contains(":app:lintDebug"))
        assertTrue(commonValidation.contains(":browser-extension:validateFirefoxExtension"))
        assertTrue(workflow.contains("XDM_ARIA2_ARCHIVE_SHA256: ${'$'}{{ secrets.XDM_ARIA2_ARCHIVE_SHA256 }}"))
        assertTrue(workflow.contains("Install pinned official ARM64 aria2 runtime"))
        assertTrue(publicationGate.contains("-Pxdm.validation.aria2PayloadVerified=true"))
        assertTrue(publicationGate.contains("verify-aria2-runtime.py --require-payload --require-16kb-alignment --require-trusted-archive-digest"))
        assertTrue(publicationGate.contains(":browser-extension:packageFirefoxExtensionDark"))
        assertTrue(publicationGate.contains(":browser-extension:packageFirefoxExtensionAmoled"))
        assertTrue(publicationGate.contains(":browser-extension:verifyFirefoxExtensionReleaseArtifacts"))
        assertFalse(workflow.contains("XDM_CAPTURE_KEY_ID"))
        assertFalse(workflow.contains("XDM_CAPTURE_PUBLIC_KEY_SPKI"))
        assertFalse(workflow.contains("XDM_CAPTURE_OAEP_HASH"))
        assertFalse(workflow.contains("Require browser capture release key inputs"))
        assertTrue(finalGate.contains("validate-1dm-media-locator-xpi-v3.py"))
        assertFalse(devtool.contains(":browser-extension:packageFirefoxExtensionDark"))
        assertFalse(devtool.contains(":browser-extension:packageFirefoxExtensionAmoled"))
        assertFalse(devtool.contains(":browser-extension:verifyFirefoxExtensionReleaseArtifacts"))
        assertFalse(commonValidation.contains(":browser-extension:packageFirefoxExtensionDark"))
        assertFalse(commonValidation.contains(":browser-extension:packageFirefoxExtensionAmoled"))
        assertFalse(commonValidation.contains(":browser-extension:verifyFirefoxExtensionReleaseArtifacts"))
        assertTrue(projectManifest.contains("\"current_overlay\": \"xdm_android_privacy_quality_final_gate_overlay_v2.zip\""))
        assertTrue(projectManifest.contains("\"static_validation_evidence_required\": true"))
        assertTrue(projectManifest.contains("\"full_validation_evidence_required\": true"))
        assertTrue(projectManifest.contains("\"validation_evidence_defaults_false\": true"))
        assertTrue(projectManifest.contains("\"version\": 20"))
        assertTrue(projectManifest.contains("\"room_schema_locked\": 20"))
        assertTrue(projectManifest.contains("\"version_code\": 22"))
        assertTrue(projectManifest.contains("\"readiness_evidence_fail_closed\": true"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
