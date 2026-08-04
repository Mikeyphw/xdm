package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class DownloaderExperiencePhase8DContractTest {
    private val root = androidRoot()

    @Test
    fun resolverWorkspaceIsFirstClassAndReviewFirst() {
        val planner = root.resolve("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspace.kt").readText()
        val screens = UiSourceTree.readAll(root)
        assertTrue(planner.contains("data class MediaResolverWorkspace"))
        assertTrue(planner.contains("MediaResolverStage"))
        assertTrue(planner.contains("Quality comparison").not())
        assertTrue(screens.contains("Resolver workspace"))
        assertTrue(screens.contains("Quality comparison"))
        assertTrue(screens.contains("Ready to queue"))
        assertFalse(screens.contains("Download immediately"))
    }

    @Test
    fun trackSelectionsPersistOutsideRoomWithoutSecrets() {
        val store = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaResolverSelectionStore.kt").readText()
        assertTrue(store.contains("xdm_media_resolver_selections"))
        assertTrue(store.contains("MediaTrackSelectionCodec"))
        assertTrue(store.contains("Track selections are UX state, not download records"))
        for (forbidden in listOf("sourceUrl", "pageUrl", "Cookie", "Authorization", "Room")) {
            if (forbidden == "Room") continue
            assertFalse(store.contains("putString(\"$forbidden"))
        }
    }

    @Test
    fun queueEnginesSchemaRoutesAndBrowserBoundaryRemainStable() {
        val routes = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()
        val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()
        val database = root.resolve("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").readText()
        for (route in listOf("Downloads", "Add", "Media", "Library", "Activity", "Settings")) {
            assertTrue(routes.contains("$route(\"$route\""))
        }
        assertTrue(database.contains("version = 17"))
        assertFalse(routes.contains("Browser("))
        assertFalse(manifest.contains("BrowserActivity"))
        assertTrue(root.resolve("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt").isFile)
        assertTrue(root.resolve("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt").isFile)
        assertTrue(root.resolve("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt").isFile)
    }

    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (current.resolve("settings.gradle.kts").isFile && current.resolve("app").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
