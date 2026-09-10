package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux13EndToEndUiUxReleaseSealContractTest {
    private val root = androidRoot()

    @Test
    fun storageAndDownloadsShareOneTruthModel() {
        val queue = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidQueueConditionsReader.kt")
        val downloads = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt")
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")

        assertTrue(queue.contains("destinationWriter.health(raw)"))
        assertTrue(queue.contains("DestinationSpaceState.Known"))
        assertTrue(queue.contains("DestinationSpaceState.Unknown"))
        assertTrue(queue.contains("DestinationSpaceState.Unavailable"))
        assertFalse(queue.contains("public-downloads://"))
        assertFalse(queue.contains("app-private://"))

        assertTrue(downloads.contains("Waiting(\"Waiting\")"))
        assertTrue(downloads.contains("waitingStates = queuedStates + DownloadState.Paused"))
        assertTrue(downloads.contains("Waiting — ${'$'}policy"))
        assertTrue(details.contains("What happened:"))
        assertTrue(details.contains("What XDM will do:"))
        assertTrue(details.contains("What you can do:"))
        assertTrue(details.contains("Technical details"))
    }

    @Test
    fun mediaLibraryActivityAndLocatorKeepUserFacingLifecycle() {
        val media = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
        val library = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
        val activity = source("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt") +
            source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt") +
            source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val locator = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")

        assertTrue(media.contains("MediaConsumerState.Downloaded"))
        assertTrue(media.contains("Text(\"Open\")"))
        assertTrue(media.contains("Download again"))
        assertTrue(library.contains("No media yet"))
        assertTrue(library.contains("Find media"))
        assertTrue(library.contains("Delete saved file"))
        assertTrue(activity.contains("Queue & recovery"))
        assertTrue(activity.contains("Retry storage check"))
        assertTrue(locator.contains("onRenderProcessGone"))
        assertTrue(locator.contains("webViewDisposed = true"))
        assertTrue(locator.contains("recreate()"))
    }

    @Test
    fun settingsExposeOneDeveloperCenterAndFinalValidationCannotBeDeferred() {
        val settings = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
        val manifest = source("PROJECT_MANIFEST.json")
        val finalGate = source("tools/run-final-release-gate.sh")

        assertTrue(settings.contains("Diagnostics & support"))
        assertTrue(settings.contains("Developer mode"))
        assertTrue(settings.contains("Developer Center"))
        assertFalse(settings.contains("\"Debug Workbench\""))
        assertFalse(settings.contains("\"Advanced Debug Workbench\""))
        assertFalse(settings.contains("\"Developer tools\""))

        assertTrue(manifest.contains("\"current_overlay\": \"xdm_android_post_ux13_roadmap_completion_hotfix_v1.zip\""))
        assertTrue(manifest.contains("\"next_phase\": \"complete\""))
        assertTrue(manifest.contains("\"validation_deferred\": false"))
        assertTrue(manifest.contains("\"version\": 21"))
        assertTrue(finalGate.contains("validate-ux13-end-to-end-ui-ux-release-seal.py"))
        assertTrue(finalGate.contains("run-bug-hunt-phase11-validation-matrix.sh --static-only --ci"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
}
