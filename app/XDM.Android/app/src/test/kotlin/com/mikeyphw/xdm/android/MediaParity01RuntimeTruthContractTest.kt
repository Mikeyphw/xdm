package com.mikeyphw.xdm.android

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaParity01RuntimeTruthContractTest {
    private val root: Path = Path.of(System.getProperty("user.dir")).let { cwd ->
        when {
            Files.isRegularFile(cwd.resolve("settings.gradle.kts")) -> cwd
            Files.isRegularFile(cwd.resolve("app/XDM.Android/settings.gradle.kts")) -> cwd.resolve("app/XDM.Android")
            else -> error("XDM Android root not found from $cwd")
        }
    }

    private fun text(relative: String): String = Files.readString(root.resolve(relative))

    @Test
    fun legacyFirefoxCryptoTestsAreNotRequiredBlockers() {
        val catalog = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
        assertFalse(catalog.contains("FirefoxSecureHandoffDebugTest"))
        assertFalse(catalog.contains("FirefoxEncryptedEnvelopeDecodeDebugTest"))
        assertFalse(catalog.contains("firefox-secure-handoff"))
        assertFalse(catalog.contains("firefox-encrypted-envelope-decode"))
        assertTrue(catalog.contains("FirefoxDirectV3HandoffDebugTest"))
        assertTrue(catalog.contains("XdmBrowserDeepLinkParser.parse"))
        assertTrue(catalog.contains("legacyEncryptedBlocker\" to \"retired"))
    }

    @Test
    fun validationFactsAreIndependentAndSchemaTruthIs22() {
        val build = text("app/build.gradle.kts")
        val main = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val developer = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
        val workspace = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")

        listOf(
            "xdm.validation.staticPassed",
            "xdm.validation.fullPassed",
            "xdm.validation.realDeviceSmokePassed",
            "xdm.validation.aria2PayloadVerified",
            "xdm.validation.diagnosticExportPassed",
            "xdm.validation.releaseDocsPassed",
            "xdm.validation.routeTopologyPassed",
            "xdm.validation.lintPassed",
            "xdm.validation.nativeSymbolsPassed",
        ).forEach { assertTrue("missing independent validation property $it", build.contains(it)) }
        assertTrue(main.contains("DiagnosticExportIntegrity.contractSelfTest()"))
        assertTrue(main.contains("releaseDocsComplete = releaseDocsValidated"))
        assertTrue(main.contains("noNewTopLevelRoutes = routeTopologyValidated"))
        assertFalse(main.contains("releaseDocsComplete = staticValidationPassed"))
        assertFalse(main.contains("noNewTopLevelRoutes = staticValidationPassed"))
        assertTrue(developer.contains("currentRoomSchemaVersion = 22"))
        assertFalse(developer.contains("currentRoomSchemaVersion = 21"))
        assertTrue(workspace.contains("Validation truth"))
        assertTrue(workspace.contains("XDM_DIAGNOSTIC_EXPORT_VALIDATED"))
    }

    @Test
    fun aria2DiagnosticUsesRealLifecycleSmokeAndOptionalUnavailableIsNonFatal() {
        val catalog = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
        assertTrue(catalog.contains("manager.probe()"))
        assertTrue(catalog.contains("manager.smokeTest()"))
        assertTrue(catalog.contains("Optional packaged aria2 runtime is unavailable"))
        assertTrue(catalog.contains("status = DebugTestStatus.Warning"))
        assertTrue(catalog.contains("Native downloads remain independently usable"))

        val manager = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")
        val backend = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")
        assertTrue(manager.contains("fun effectiveCapability(): Aria2CapabilityReport"))
        assertTrue(manager.contains("managed runtime is unhealthy"))
        assertTrue(manager.contains("Use Repair aria2"))
        assertTrue(backend.split("processManager.effectiveCapability()").size - 1 >= 3)
    }

    @Test
    fun exactFinalDiagnosticsZipIsTheShareBoundary() {
        val store = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestStore.kt")
        val screen = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt")
        assertTrue(store.contains("DiagnosticExportIntegrity.writeVerifiedZip"))
        assertTrue(store.contains("xdm-debug-${'$'}{safeFileName(run.id)}.zip"))
        assertTrue(store.contains("Diagnostics version: v5 / Media Parity01"))
        assertTrue(store.contains("Room schema: 22"))
        assertTrue(store.contains("Live Locator WebView"))
        assertTrue(screen.contains("Diagnostics export blocked: final ZIP privacy/integrity verification failed."))
        assertTrue(screen.contains("Diagnostics ZIP verified and ready to share."))
    }
}
