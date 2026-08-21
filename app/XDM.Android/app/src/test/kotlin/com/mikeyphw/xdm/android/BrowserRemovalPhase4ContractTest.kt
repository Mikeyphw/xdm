package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRemovalPhase4ContractTest {
    @Test
    fun builtInBrowserRuntimeIsAbsent() {
        val root = androidRoot()
        val routes = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()

        assertFalse(File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt").exists())
        assertFalse(File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserActivity.kt").exists())
        assertFalse(routes.contains("Browser(\"Browser\""))
        assertFalse(routes.contains("Icons.Rounded.Public"))
        assertFalse(shell.contains("AppRoute.Browser"))
        assertFalse(shell.contains("BrowserScreen("))
        assertFalse(manifest.contains(".BrowserActivity"))
        assertFalse(strings.contains("browser_activity_label"))

        val locatorFile =
            File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
        val locator = locatorFile.readText()

        val appSourceWithoutLocator =
            File(root, "app/src/main/kotlin").walkTopDown()
                .filter(File::isFile)
                .filter {
                    it.extension == "kt" &&
                        it.canonicalFile != locatorFile.canonicalFile
                }
                .joinToString("\n") { it.readText() }

        // A general embedded browser must still never return.
        assertFalse(appSourceWithoutLocator.contains("android.webkit"))
        assertFalse(appSourceWithoutLocator.contains("WebView("))
        assertFalse(appSourceWithoutLocator.contains("WebViewClient"))
        assertFalse(appSourceWithoutLocator.contains("WebChromeClient"))

        // WebKit is deliberately isolated to the media locator.
        assertTrue(locator.contains("class MediaLocatorActivity : ComponentActivity()"))
        assertTrue(locator.contains("WebView(this)"))
        assertTrue(locator.contains("MediaSniffingEngine()"))

        // The locator discovers/reviews media. It is not a downloader.
        assertFalse(locator.contains("viewModel.addDownload("))
        assertFalse(locator.contains("executionStarter.start"))

        // It must never be externally launchable.
        val locatorManifest =
            manifest.substringAfter("android:name=\".MediaLocatorActivity\"")
                .substringBefore("/>")
        assertTrue(locatorManifest.contains("android:exported=\"false\""))
    }

    @Test
    fun routeAndStartupStateNoLongerExposeBrowser() {
        val root = androidRoot()
        val mainActivity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val preferences = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt").readText()
        val routes = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()

        listOf("shouldOpenBrowserUrl", "openBrowserUrlFromIntent", "browserStartUrl", "consumeBrowserStartUrl", "openBrowserUrl(url: String)")
            .forEach { marker -> assertFalse("Browser startup marker remains: $marker", mainActivity.contains(marker) || viewModel.contains(marker)) }
        assertTrue(mainActivity.contains("consumeInternalAutomation(incoming)"))
        assertTrue(preferences.contains("lastRoute = AppRoute.restore(preferences[Keys.LastRoute])"))
        assertTrue(routes.contains("fun restore(storedName: String?): AppRoute"))
        assertTrue(routes.contains("?: Downloads"))
    }

    @Test
    fun externalDownloaderHandoffRemainsReviewFirst() {
        val root = androidRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val receiver = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt").readText()
        val block = activityBlock(manifest, ".ExternalAddDownloadActivity")

        assertTrue(receiver.contains("class ExternalAddDownloadActivity : ExternalHandoffReviewActivity()"))
        listOf(
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
            "android.intent.action.VIEW",
            "android:scheme=\"http\"",
            "android:scheme=\"https\"",
            "android:scheme=\"ftp\"",
        ).forEach { marker -> assertTrue("External receiver lost $marker", block.contains(marker)) }
        val review = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt").readText()
        assertTrue(review.contains("ExternalIntentDraftFactory.general"))
        assertTrue(review.contains("ExternalAutomationDispatch.persist"))
        assertTrue(activity.contains("viewModel::ingestPersistedAutomationCommand"))
        assertFalse(review.contains("executionStarter.start"))
        assertFalse(review.contains("viewModel.addDownload("))
    }

    @Test
    fun downloaderAndMediaRuntimeRemainProtected() {
        val root = androidRoot()
        val contracts = mapOf(
            "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt" to "class NativeHttpDownloadBackend",
            "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt" to "class EmbeddedAria2Backend",
            "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt" to "class TransferExecutionRuntime",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt" to "class MediaDownloadPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt" to "class MediaExecutionLibraryPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt" to "class MediaTermuxRuntimeAdapter",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt" to "class MediaWorkerBridgePlanner",
            "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt" to "fun Media3DirectPlayerCard",
        )
        contracts.forEach { (path, marker) ->
            val file = File(root, path)
            assertTrue("Protected source is missing: $path", file.isFile)
            assertTrue("Protected symbol is missing: $marker", file.readText().contains(marker))
        }
    }

    private fun activityBlock(manifest: String, activityName: String): String {
        val marker = manifest.indexOf("android:name=\"$activityName\"")
        check(marker >= 0) { "Activity $activityName is missing" }
        val start = manifest.lastIndexOf("<activity", marker)
        val end = manifest.indexOf("</activity>", marker)
        check(start >= 0 && end >= 0) { "Activity $activityName block is malformed" }
        return manifest.substring(start, end + "</activity>".length)
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }
        ?: error("Unable to locate XDM Android root")
}
