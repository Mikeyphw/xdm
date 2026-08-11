package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCenterV9ContractTest {
    private val root = androidRoot()

    @Test
    fun debugCenterV9HasRealRunnerRegistryStoreAndZipExport() {
        val models = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestModels.kt")
        val catalog = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
        val runner = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestRunner.kt")
        val store = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestStore.kt")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt")
        val sharing = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugCenterExportSharing.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(models.contains("enum class DebugTestStatus"))
        assertTrue(models.contains("Pending"))
        assertTrue(models.contains("Running"))
        assertTrue(catalog.contains("interface DebugTest"))
        assertTrue(catalog.contains("object DebugTestRegistry"))
        assertTrue(runner.contains("class DebugTestRunner"))
        assertTrue(runner.contains("MutableStateFlow"))
        assertTrue(runner.contains("suspend fun runSelected"))
        assertTrue(runner.contains("markStopped"))
        assertTrue(store.contains("class DebugTestStore"))
        assertTrue(store.contains("saveRun"))
        assertTrue(store.contains("loadRuns"))
        assertTrue(store.contains("exportRunZip"))
        assertTrue(store.contains("ZipOutputStream"))
        assertTrue(screen.contains("Run Selected"))
        assertTrue(screen.contains("Run All"))
        assertTrue(screen.contains("Retest Failed"))
        assertTrue(screen.contains("Export ZIP"))
        assertTrue(screen.contains("shareDebugCenterZipExport"))
        assertTrue(sharing.contains("FileProvider.getUriForFile"))
        assertTrue(sharing.contains("application/zip"))
        assertTrue(manifest.contains(".debugcenter.fileprovider"))
    }

    @Test
    fun debugCenterV9PreservesLegacyWorkbenchCards() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
        assertTrue(screen.contains("DebugCenterScreen"))
        assertTrue(screen.contains("MediaSniffingLabCard()"))
        assertTrue(screen.contains("BrowserBridgeDebuggerCard(state)"))
        assertTrue(screen.contains("AddDownloadDebuggerCard(state)"))
        assertTrue(screen.contains("TransferNotificationDebuggerCard(state)"))
        assertTrue(screen.contains("RuntimeSelfTestSuiteCard(state)"))
    }

    @Test
    fun debugEventsCarryOperationCorrelationIds() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt")
        assertTrue(model.contains("operationId"))
        assertTrue(model.contains("parentOperationId"))
        assertTrue(model.contains("appendJson(\"operationId\""))
        assertTrue(model.contains("appendJson(\"parentOperationId\""))
    }


    @Test
    fun debugCenterV9RunsRealDownloadMediaBrowserAndQueueChecks() {
        val catalog = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
        assertTrue(catalog.contains("DownloadRepositoryRoundTripDebugTest"))
        assertTrue(catalog.contains("repository.save(download)"))
        assertTrue(catalog.contains("repository.findDownload(id)"))
        assertTrue(catalog.contains("MediaDownloadTransactionDebugTest"))
        assertTrue(catalog.contains("repository.createDownloadFromMediaCapture"))
        assertTrue(catalog.contains("FirefoxSecureHandoffDebugTest"))
        assertTrue(catalog.contains("manager.selfTestKeyWrap"))
        assertTrue(catalog.contains("FirefoxEncryptedEnvelopeDecodeDebugTest"))
        assertTrue(catalog.contains("manager.decrypt(payload"))
        assertTrue(catalog.contains("QueuePolicyAcceptanceDebugTest"))
        assertTrue(catalog.contains("QueueIntelligencePlanner.decision"))
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
