package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRemovalPhase6ContractTest {
    @Test
    fun downloaderFocusedRoutesAreStable() {
        assertEquals(
            listOf("Downloads", "Add", "Media", "Library", "Activity", "Settings"),
            AppRoute.entries.map(AppRoute::label),
        )
        listOf("Queues", "Scheduler", "Recovery", "Diagnostics").forEach { legacy ->
            assertEquals(AppRoute.Activity, AppRoute.restore(legacy))
        }
        assertEquals(AppRoute.Downloads, AppRoute.restore("Browser"))
        assertEquals(AppRoute.Downloads, AppRoute.restore(null))
    }

    @Test
    fun shellPromotesLibraryAndActivityWithoutLosingAdd() {
        val root = androidRoot()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        assertTrue(shell.contains("private val primaryRoutes = routeTopology.filterNot { it == AppRoute.Add }"))
        assertTrue(shell.contains("onAddDownload = { viewModel.navigate(AppRoute.Add) }"))
        assertTrue(shell.contains("AppRoute.Library -> MediaLibraryScreen"))
        assertTrue(shell.contains("AppRoute.Activity -> ActivityHub"))
        listOf("ActivityPanel.Queues", "ActivityPanel.Schedule", "ActivityPanel.Recovery", "ActivityPanel.Diagnostics").forEach {
            assertTrue("Activity workspace missing $it", shell.contains(it))
        }
        assertFalse(shell.contains("AppRoute.Queues ->"))
        assertFalse(shell.contains("AppRoute.Scheduler ->"))
        assertFalse(shell.contains("AppRoute.Recovery ->"))
        assertFalse(shell.contains("AppRoute.Diagnostics ->"))
    }

    @Test
    fun libraryAndActivityOwnTheirFocusedSurfaces() {
        val root = androidRoot()
        val screens = UiSourceTree.readAll(root)
        val userScreens = UiSourceTree.readUser(root)
        assertTrue(screens.contains("fun MediaLibraryScreen("))
        assertTrue(screens.contains("fun ActivityOverviewScreen("))
        assertTrue(screens.contains("Completed media, playback readiness, sidecar health"))
        assertTrue(screens.contains("A single operational workspace for queue control"))
        assertEquals(0, Regex("OfflineLibraryV2Card\\(libraryV2\\)").findAll(userScreens).count())
        assertEquals(0, Regex("OfflineLibraryV2Card\\(library\\)").findAll(userScreens).count())
    }

    @Test
    fun downloaderAndExternalHandoffRemainPresent() {
        val root = androidRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val locatorFile =
            File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")

        val appSources =
            File(root, "app/src/main/kotlin").walkTopDown()
                .filter {
                    it.isFile &&
                        it.extension == "kt" &&
                        it.canonicalFile != locatorFile.canonicalFile
                }
                .joinToString("\n") { it.readText() }
        assertTrue(manifest.contains(".ExternalAddDownloadActivity"))
        assertTrue(manifest.contains("android.intent.action.SEND"))
        assertTrue(manifest.contains("com.android.browser.action.DOWNLOAD"))
        assertFalse(appSources.contains("android.webkit"))
        assertFalse(appSources.contains("WebView("))

        val locator = locatorFile.readText()
        assertTrue(locator.contains("WebView(this)"))
        assertTrue(locator.contains("MediaSniffingEngine()"))
        listOf(
            "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
            "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
            "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
            "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
        ).forEach { path -> assertTrue("Missing preserved downloader file $path", File(root, path).isFile) }
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
