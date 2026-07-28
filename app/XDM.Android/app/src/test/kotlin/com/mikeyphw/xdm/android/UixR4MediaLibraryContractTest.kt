package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR4MediaLibraryContractTest {
    @Test
    fun mediaIsConsumerFirstAndKeepsEngineeringInternalsOut() {
        val root = androidRoot()
        val inbox = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt").readText()
        val card = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt").readText()
        val media = inbox + "\n" + card

        listOf(
            "Paste page URL",
            "Ready to download",
            "Recently queued",
            "MediaTrackPickerSheet",
            "Selected quality",
            "Audio track",
            "Subtitle track",
            "Estimated download size",
        ).forEach { assertTrue("Media R4 missing $it", media.contains(it)) }

        listOf(
            "Resolver workspace",
            "Recent resolutions",
            "yt-dlp metadata preview",
            "Termux",
            "Post-processing automation",
            "Queue telemetry",
            "Worker bridge",
            "Session privacy audit",
            "Media final validation",
            "Sidecar:",
            "toRedactedJson()",
        ).forEach { assertFalse("Normal Media leaks $it", media.contains(it, ignoreCase = true)) }
        assertFalse("Normal Media must not render raw source URLs", media.contains("Text(capture.sourceUrl"))
    }

    @Test
    fun libraryLeadsWithPlaybackAndOnlyShowsSupportDetailsAfterErrors() {
        val root = androidRoot()
        val library = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt").readText()
        val player = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt").readText()

        listOf(
            "MediaLibraryFilter.entries",
            "LazyVerticalGrid",
            "Play",
            "Resume download",
            "Retry",
            "More",
            "Remove library record",
        ).forEach { assertTrue("Library R4 missing $it", library.contains(it)) }
        assertFalse("Library must not render sidecar JSON", library.contains("toRedactedJson()") || library.contains("Sidecar:"))
        assertTrue("Player support details must be error-bound", player.contains("playerError?.let") && player.contains("Support details"))
        assertFalse("Normal playback must not display the retired diagnostics deck", player.contains("Player 2.0 diagnostics"))
    }

    @Test
    fun appWiringKeepsInternalPipelinesOutOfTheConsumerMediaSignature() {
        val root = androidRoot()
        val app = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val mediaCall = Regex("AppRoute\\.Media -> MediaInboxScreen\\((.*?)\\n\\s*\\)", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(app)?.groupValues?.get(1).orEmpty()
        assertTrue("Media route must still wire download and resolution actions", mediaCall.contains("onDownload") && mediaCall.contains("onResolve"))
        assertFalse("Consumer Media must not receive Termux pipeline state", mediaCall.contains("termuxMediaPipeline"))
        assertFalse("Consumer Media must not receive post-processing automation", mediaCall.contains("postProcessingAutomation"))
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
