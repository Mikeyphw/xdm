package com.mikeyphw.xdm.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BrowserRemovalPhase7ContractTest {
    @Test
    fun ordinaryWebNavigationIsNotClaimedButExplicitDownloadHandoffRemains() {
        val root = androidRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val filters = document.getElementsByTagName("intent-filter")
        var launcherCount = 0
        var externalShare = false
        var typedHttpDownload = false
        var browserDownloadAction = false

        for (index in 0 until filters.length) {
            val filter = filters.item(index) as Element
            val actions = filter.getElementsByTagName("action").asElements().mapNotNull { it.androidName() }.toSet()
            val categories = filter.getElementsByTagName("category").asElements().mapNotNull { it.androidName() }.toSet()
            val data = filter.getElementsByTagName("data").asElements()

            if ("android.intent.action.MAIN" in actions && "android.intent.category.LAUNCHER" in categories) launcherCount++
            if ("android.intent.action.SEND" in actions) externalShare = true
            if ("com.android.browser.action.DOWNLOAD" in actions) browserDownloadAction = true

            if ("android.intent.action.VIEW" in actions && "android.intent.category.BROWSABLE" in categories) {
                data.forEach { item ->
                    val scheme = item.androidAttribute("scheme")
                    val mime = item.androidAttribute("mimeType")
                    val path = item.androidAttribute("path")
                    val pathPrefix = item.androidAttribute("pathPrefix")
                    val pathPattern = item.androidAttribute("pathPattern")
                    if (scheme in setOf("http", "https")) {
                        val constrained = mime.isNotBlank() || path.isNotBlank() || pathPrefix.isNotBlank() || pathPattern.isNotBlank()
                        assertTrue("Generic web navigation filter returned for $scheme", constrained)
                        if (mime.isNotBlank()) typedHttpDownload = true
                    }
                }
            }
        }

        assertEquals(1, launcherCount)
        assertTrue(externalShare)
        assertTrue(typedHttpDownload)
        assertTrue(browserDownloadAction)
    }

    @Test
    fun productionAndPersistenceRemainBrowserFree() {
        val root = androidRoot()
        val production = listOf(
            File(root, "app/src/main"),
            File(root, "core-model/src/main"),
            File(root, "media/src/main"),
            File(root, "persistence/src/main"),
            File(root, "scheduler/src/main"),
            File(root, "storage/src/main"),
            File(root, "transfer-api/src/main"),
            File(root, "transfer-native/src/main"),
            File(root, "transfer-aria2/src/main"),
        ).flatMap { directory ->
            if (!directory.exists()) emptyList() else directory.walkTopDown().filter { it.isFile }.toList()
        }.joinToString("\n") { it.readText() }

        listOf("BrowserActivity", "BrowserScreen", "AppRoute.Browser", "android.webkit", "WebViewClient", "WebChromeClient").forEach {
            assertFalse("Forbidden browser runtime token returned: $it", production.contains(it))
        }

        val database = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").readText()
        val preferences = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt").readText()
        assertTrue(database.contains("version = 14"))
        listOf("browser_tab", "browser_history", "bookmark", "private_session", "browser_profile").forEach {
            assertFalse("Browser persistence returned: $it", (database + preferences).lowercase().contains(it))
        }
    }

    @Test
    fun finalDownloaderContractPreservesEveryExecutionLane() {
        val root = androidRoot()
        assertEquals(
            listOf("Downloads", "Add", "Media", "Library", "Activity", "Settings"),
            AppRoute.entries.map(AppRoute::label),
        )
        listOf(
            "app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaOfflineLibraryV2.kt",
            "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
            "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt",
            "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
            "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
            "docs/architecture/DOWNLOADER_PRODUCT_CONTRACT.md",
            "tools/validate-browser-removal-phase-7.py",
        ).forEach { path -> assertTrue("Missing final downloader contract path: $path", File(root, path).isFile) }
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.androidName(): String? = androidAttribute("name").takeIf(String::isNotBlank)

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(current, "PROJECT_MANIFEST.json").isFile && File(current, "app/src/main").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate XDM.Android root")
    }
}
