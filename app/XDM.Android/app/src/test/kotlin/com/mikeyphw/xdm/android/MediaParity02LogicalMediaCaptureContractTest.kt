package com.mikeyphw.xdm.android

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaParity02LogicalMediaCaptureContractTest {
    private val root: Path = generateSequence(Path.of(System.getProperty("user.dir") ?: ".").toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) && Files.isDirectory(it.resolve("app/src/main")) }
        ?: error("XDM Android root not found from ${System.getProperty("user.dir")}")
    private fun text(relative: String): String = String(Files.readAllBytes(root.resolve(relative)), Charsets.UTF_8)

    @Test fun room23CarriesLogicalMediaAndBoundedObservationEvidence() {
        val database = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
        val migrations = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt")
        val entities = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt")
        val repository = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        assertTrue(database.contains("version = 24"))
        assertTrue(migrations.contains("Migration22To23 = object : Migration(22, 23)"))
        assertTrue(migrations.contains("media_observations"))
        assertTrue(entities.contains("data class MediaObservationEntity"))
        assertTrue(entities.contains("val logicalMediaId: String?"))
        assertTrue(repository.contains("saveMediaObservationEvidence"))
        assertTrue(repository.contains("limit: Int = 384"))
    }

    @Test fun webViewAndFirefoxConvergeOnLogicalMediaInsteadOfRawParts() {
        val locator = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
        val main = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val envelope = text("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt")
        val popup = text("browser-extension/src/main/extension/xdm-firefox/popup.js")
        val store = text("browser-extension/src/main/extension/xdm-firefox/candidate-store.js")
        assertTrue(locator.contains("LogicalMediaGraphEngine"))
        assertTrue(locator.contains("saveMediaObservationEvidence"))
        assertTrue(main.contains("candidate.logicalMediaId ?: candidate.stableMediaId"))
        assertTrue(main.contains("LogicalMediaGraphEngine.identityUrl"))
        assertTrue(envelope.contains("val logicalMediaId: String? = null"))
        assertTrue(popup.contains("Send ${'$'}{count} to XDM"))
        assertTrue(popup.contains("logicalMediaCandidates"))
        assertTrue(store.contains("logicalMediaId"))
    }

    @Test fun signedExecutionUrlStaysSeparateFromCanonicalPresentationIdentity() {
        val graph = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/LogicalMediaGraph.kt")
        val main = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        assertTrue(graph.contains("fun identityUrl(raw: String)"))
        assertTrue(graph.contains("x_(?:amz|goog)_(?:credential|security_token|signature|expires|date)"))
        assertTrue(main.contains("exactUrl = source.url"))
        assertTrue(main.contains("canonicalLogicalUrl = LogicalMediaGraphEngine.identityUrl"))
        assertFalse(main.contains("canonicalMediaUrl = candidate.url"))
    }

    @Test fun hlsProtectionAndStressContractsRemainExplicit() {
        val graph = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/LogicalMediaGraph.kt")
        val tests = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/LogicalMediaGraphEngineTest.kt")
        assertTrue(graph.contains("MediaProtectionKind.Aes128"))
        assertTrue(graph.contains("MediaNativeCapability.ProtectedUnsupported"))
        assertTrue(graph.contains("MediaNativeCapability.FallbackRequired"))
        assertTrue(graph.contains("maxLogicalItems: Int = 96"))
        assertTrue(graph.contains("maxEvidence: Int = 384"))
        assertTrue(tests.contains("1000"))
        assertTrue(tests.contains("X-Amz-Credential"))
    }
}
