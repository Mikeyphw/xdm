package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRemovalPreservationContractTest {
    @Test
    fun phaseZeroInventoryRecordsANonDestructiveBoundary() {
        val root = androidRoot()
        val inventory = File(root, "docs/browser-removal/BROWSER-DOWNLOADER-BOUNDARY.json")
        val document = File(root, "docs/browser-removal/PHASE-0-1-BASELINE-PRESERVATION.md")
        val validator = File(root, "tools/validate-browser-removal-phase-0-1.py")

        assertTrue("Browser/downloader boundary inventory is missing", inventory.isFile)
        assertTrue("Phase 0/1 contract document is missing", document.isFile)
        assertTrue("Phase 0/1 validator is missing", validator.isFile)

        val text = inventory.readText()
        assertTrue(text.contains("\"runtime_removal_started\": false"))
        assertTrue(text.contains("\"production_kotlin_modified\": false"))
        assertTrue(text.contains("\"preserve_downloader_runtime\""))
        assertTrue(text.contains("\"preserve_external_handoff_despite_browser_naming\""))
    }

    @Test
    fun dedicatedExternalReceiverRemainsReviewFirst() {
        val root = androidRoot()
        val receiver = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt").readText()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()

        assertTrue(receiver.contains("class ExternalAddDownloadActivity : MainActivity()"))
        assertTrue(activity.contains("AutomationCommandAction.PromptAddDownload"))
        assertTrue(activity.contains("AutomationCommandAction.CaptureMedia"))
        assertTrue(activity.contains("viewModel.ingestAutomationCommand(draft)"))
        assertFalse("External intent intake must not start transfers directly", activity.contains("executionStarter.start"))
        assertFalse("External intent intake must not call the download creator directly", activity.contains("viewModel.addDownload("))
    }

    @Test
    fun sharesheetIntakeInspectsTextSubjectAndClipData() {
        val activity = File(androidRoot(), "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()

        listOf(
            "Intent.EXTRA_TEXT",
            "Intent.EXTRA_SUBJECT",
            "intent.clipData",
            "item.uri?.toString()",
            "item.coerceToText(this@MainActivity)?.toString()",
            "BrowserHandoffContract.ExtraDownloadUrl",
            "TaskerContract.ExtraUrl",
            "com.android.browser.extra.URL",
            "org.mozilla.gecko.extra.URI",
        ).forEach { marker -> assertTrue("External intake lost $marker", activity.contains(marker)) }
    }

    @Test
    fun manifestKeepsDownloaderHandoffContractSeparate() {
        val manifest = File(androidRoot(), "app/src/main/AndroidManifest.xml").readText()
        val block = activityBlock(manifest, ".ExternalAddDownloadActivity")

        listOf(
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
            "android.intent.action.VIEW",
            "android.intent.category.BROWSABLE",
            "android:scheme=\"http\"",
            "android:scheme=\"https\"",
            "android:scheme=\"ftp\"",
            "android:mimeType=\"application/octet-stream\"",
            "android:mimeType=\"application/vnd.apple.mpegurl\"",
            "android:mimeType=\"application/dash+xml\"",
        ).forEach { marker -> assertTrue("External receiver lost $marker", block.contains(marker)) }

        Regex("android:mimeType=\\\"([^\\\"]+)\\\"").findAll(block).forEach { match ->
            val mime = match.groupValues[1]
            assertTrue("Manifest MIME types must remain lowercase: $mime", mime == mime.lowercase())
        }
    }

    @Test
    fun browserIntegrationModuleRemainsExternalHandoffOnly() {
        val root = androidRoot()
        val sourceRoot = File(root, "browser-integration/src/main/kotlin")
        val source = sourceRoot.walkTopDown().filter(File::isFile).filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

        assertTrue(source.contains("object BrowserHandoffContract"))
        assertTrue(source.contains("object SharedLinkParser"))
        assertFalse("External browser integration must not depend on Android WebKit", source.contains("android.webkit"))
        assertFalse("External browser integration must not instantiate WebView", source.contains("WebView("))
        assertFalse(source.contains("WebViewClient"))
        assertFalse(source.contains("WebChromeClient"))
    }

    @Test
    fun transferEnginesAndExecutionRuntimeRemainProtected() {
        val root = androidRoot()
        val contracts = mapOf(
            "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt" to "class NativeHttpDownloadBackend",
            "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt" to "class EmbeddedAria2Backend",
            "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt" to "class TransferExecutionRuntime",
            "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionStarter.kt" to "class TransferExecutionStarter",
        )
        contracts.forEach { (path, marker) ->
            val file = File(root, path)
            assertTrue("Protected downloader source is missing: $path", file.isFile)
            assertTrue("Protected downloader symbol is missing: $marker", file.readText().contains(marker))
        }
    }

    @Test
    fun mediaResolverQueueWorkerAndPlaybackRemainProtected() {
        val root = androidRoot()
        val contracts = mapOf(
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt" to "class MediaDownloadPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt" to "class MediaExecutionDispatcher",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt" to "class MediaExecutionLibraryPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaNativeDirectDownloadEngine.kt" to "class MediaNativeDirectDownloadPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaOfflineLibraryV2.kt" to "class MediaOfflineLibraryV2Planner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt" to "class MediaPlayerDiagnosticsPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueActions.kt" to "class MediaQueueActionPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueTelemetry.kt" to "class MediaQueueTelemetryPlanner",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt" to "class MediaTermuxRuntimeAdapter",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt" to "class MediaWorkerBridgePlanner",
            "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt" to "fun Media3DirectPlayerCard",
        )
        contracts.forEach { (path, marker) ->
            val file = File(root, path)
            assertTrue("Protected media/downloader source is missing: $path", file.isFile)
            assertTrue("Protected media/downloader symbol is missing: $marker", file.readText().contains(marker))
        }
    }

    private fun activityBlock(manifest: String, activityName: String): String {
        val nameMarker = "android:name=\"$activityName\""
        val marker = manifest.indexOf(nameMarker)
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
