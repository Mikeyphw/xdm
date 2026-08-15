package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationPhase08_09ContractTest {
    private val root = androidRoot()

    @Test
    fun phase08BrowserRuntimeRequiresPrivilegedEvidenceEncryptedXdmAndKeyBoundRelease() {
        val detector = source("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
        val store = source("browser-extension/src/main/extension/xdm-firefox/candidate-store.js")
        val observer = source("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
        val bridge = source("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
        val handoff = source("browser-extension/src/main/extension/xdm-firefox/handoff.js")
        val gradle = source("browser-extension/build.gradle.kts")
        val prepare = source("browser-extension/tools/prepare_extension.py")
        val verifier = source("browser-extension/tools/verify_release_artifacts.py")
        val cli = source("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageCli.kt")
        val buildConfig = source("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionBuildConfig.kt")
        val exportManager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportManager.kt")
        val acceptance = source("tools/run-browser-bridge-device-acceptance.sh")
        val debugger = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD4DebuggerModels.kt")

        assertTrue(detector.contains("requestFingerprint"))
        assertTrue(detector.contains("exactRequestUrl"))
        assertTrue(store.contains("requestFingerprint"))
        assertTrue(observer.contains("findPrivilegedEvidence"))
        assertTrue(observer.contains("candidate.source !== \"webRequest\""))
        assertTrue(observer.contains("requestHeaders: {}") || bridge.contains("requestHeaders: {}"))
        assertTrue(handoff.contains("function buildXdmCapture"))
        assertTrue(handoff.contains("return \"\";"))
        assertTrue(handoff.contains("buildEncryptedCaptureSession"))
        assertTrue(handoff.contains("requestFingerprint"))
        assertTrue(bridge.contains("plaintext fallback is disabled"))
        assertFalse(bridge.contains("legacy top-frame launcher fallback"))

        assertTrue(gradle.contains("androidAppVersion = \"0.21.0\""))
        assertTrue(gradle.contains("xdmCaptureKeyId"))
        assertTrue(gradle.contains("xdmCapturePublicKeySpki"))
        assertTrue(gradle.contains("browserExtensionReleaseGate"))
        assertTrue(gradle.contains("requiredReleaseCaptureValue"))
        assertTrue(prepare.contains("if args.channel == \"release\":"))
        assertTrue(prepare.contains("release packaging requires --capture-key-id, --capture-public-key-spki, and --capture-oaep-hash for every default target"))
        assertTrue(verifier.contains("--capture-key-id"))
        assertTrue(verifier.contains("--capture-public-key-spki"))
        assertTrue(verifier.contains("--capture-oaep-hash"))
        assertTrue(cli.contains("required(options, \"capture-oaep-hash\")"))
        assertTrue(cli.contains("captureKeyIdForSpki(capturePublicKeySpki)"))
        assertTrue(cli.contains("captureOaepHash = captureOaepHash"))
        assertTrue(gradle.contains("requiredReleaseCaptureValue(releaseCaptureOaepHash"))
        assertTrue(gradle.contains("requireReleaseCaptureKeyBinding(keyId, publicKey)"))
        assertTrue(buildConfig.contains("captureKeyIdForSpki"))
        assertTrue(buildConfig.contains("SHA-256(SPKI DER)"))
        assertTrue(buildConfig.contains("channel == BrowserExtensionSourceContract.Channel.Release"))
        assertFalse(buildConfig.contains("Channel.Release && defaultTarget"))
        assertTrue(prepare.contains("require_capture_key_binding(args.capture_key_id, args.capture_public_key_spki)"))
        assertTrue(verifier.contains("require_capture_key_binding(args.capture_key_id, args.capture_public_key_spki)"))
        assertFalse(gradle.contains("releaseCaptureOaepHash.orElse"))
        assertFalse(handoff.contains("captureOaepHash || \"SHA-256\""))
        assertFalse(exportManager.contains("capture?v=1&url="))
        assertFalse(acceptance.contains("capture?v=1&url="))
        assertTrue(debugger.contains("secure v2 handoff only"))
        assertFalse(debugger.contains("testUri(scheme, \"capture\")"))
    }

    @Test
    fun phase09EncryptedImportIsJournalFirstRoomFirstAndRequestIdentityBound() {
        val journal = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureImportJournal.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val external = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt")
        val automation = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt")
        val activity = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
        val envelope = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt")
        val media = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt")
        val coordinator = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserHandoffMediaCoordinator.kt")
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")

        assertTrue(journal.contains("ciphertext only"))
        assertTrue(journal.contains("AtomicFile(target)"))
        assertTrue(journal.contains("startWrite()"))
        assertTrue(journal.contains("finishWrite(output)"))
        assertTrue(journal.contains("failWrite(output)"))
        assertTrue(journal.contains("Existing browser capture import journal is unreadable"))
        assertFalse(journal.contains("sourceUrl"))
        assertFalse(journal.contains("Authorization"))
        assertFalse(journal.contains("observedPackage"))
        assertTrue(viewModel.contains("browserCaptureImportJournal.begin(payload"))
        assertTrue(viewModel.contains("browserCaptureImportMutex.withLock"))
        assertTrue(viewModel.contains("existingSummary.revision > decoded.revision"))
        assertTrue(viewModel.contains("MediaRequestHandoffStore.forCapture(handoff.captureId) == null"))
        assertTrue(viewModel.contains("MediaRequestHandoffStore.forVariant(variant.variantId) == null"))
        assertTrue(viewModel.contains("repository.saveMediaCapturesWithVariants(recordsToPersist, variantsToPersist"))
        assertTrue(viewModel.indexOf("repository.saveMediaCapturesWithVariants(recordsToPersist, variantsToPersist") < viewModel.indexOf("browserCaptureSessionRegistry.record"))
        assertTrue(viewModel.contains("MediaRequestHandoffStore.rememberCapture"))
        assertTrue(viewModel.contains("preservedLinkedIds"))
        assertTrue(repository.contains("replaceMediaVariantsForCapture(record.id"))
        assertTrue(external.contains("reviewEncryptedBrowserCapture"))
        assertTrue(external.contains("browserCaptureImportJournal"))
        assertTrue(activity.contains("ACTION_INTERNAL_BROWSER_CAPTURE_IMPORT"))
        assertTrue(activity.contains("recoverPendingBrowserCaptureImports(sessionId)"))
        assertTrue(automation.contains("if (payload.hasEncryptedCaptureEnvelope) return null"))
        assertTrue(envelope.contains("requestFingerprint"))
        assertTrue(media.contains("browserCaptureIdFor"))
        assertTrue(media.contains("requestFingerprint"))
        assertTrue(coordinator.contains("ignoredDeclaredStableMediaId"))
        assertTrue(coordinator.contains("StandardCopyOption.ATOMIC_MOVE"))

        val automationCapture = viewModel.substringAfter("private suspend fun executeCaptureMediaCommand")
            .substringBefore("private suspend fun openExternalAddDraft")
        assertTrue(automationCapture.contains("prepareBrowserRevision"))
        assertTrue(automationCapture.contains("repository.saveMediaCapturesWithVariants(recordsToPersist, variantsToPersist"))
        assertTrue(automationCapture.indexOf("repository.saveMediaCapturesWithVariants(recordsToPersist, variantsToPersist") < automationCapture.indexOf("rememberPreparedRevision"))
        assertTrue(automationCapture.indexOf("rememberPreparedRevision") < automationCapture.indexOf("AutomationCommandStatus.Applied"))
        assertTrue(automationCapture.contains("AutomationCommandStatus.Received"))

        val legacyCapture = viewModel.substringAfter("fun captureMediaRequest(facts: MediaRequestFacts)")
            .substringBefore("fun captureMediaBatchInput")
        assertTrue(legacyCapture.contains("Encrypted browser capture required"))
        assertTrue(legacyCapture.contains("ExternalUrlPolicy.hasCredentialBearingQuery"))
        assertTrue(legacyCapture.indexOf("repository.saveMediaCaptureWithVariants") < legacyCapture.indexOf("rememberPreparedRevision"))
        assertFalse(external.contains("Firefox sent"))
        assertTrue(external.contains("An encrypted browser media-capture handoff is ready for review"))
    }

    @Test
    fun phase09PrivacyAuditUsesRealFilesAndPlayerClassificationUsesStructuredMedia3Codes() {
        val privacy = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt")
        val developer = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
        val diagnostics = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt")
        val player = source("app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt")

        assertTrue(privacy.contains("filesystemRoots"))
        assertTrue(privacy.contains("inspectFilesystemRoot"))
        assertTrue(privacy.contains("readBoundedText"))
        assertTrue(privacy.contains("name.endsWith(\".bak\")"))
        assertTrue(privacy.contains("name.endsWith(\".new\")"))
        assertTrue(privacy.contains("\".tmp-\" in name"))
        assertFalse(privacy.contains("readNBytes"))
        assertTrue(developer.contains("secure-request-envelopes-v1"))
        assertTrue(developer.contains("browser-capture-import-journal"))
        assertTrue(diagnostics.contains("errorCodeName"))
        assertTrue(diagnostics.contains("NETWORK_ERROR_CODES"))
        assertTrue(diagnostics.contains("DRM_ERROR_CODES"))
        assertFalse(diagnostics.contains("merged.contains"))
        assertTrue(player.contains("error.errorCodeName"))
        assertTrue(player.contains("error.errorCode"))
        assertTrue(player.contains("error.cause?.javaClass?.name"))
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
