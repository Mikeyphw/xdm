package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBatchPhase46ContractTest {
    private val root = androidRoot()

    @Test
    fun mediaScreenExposesReviewFirstBatchActions() {
        val source = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt").readText()
        assertTrue(source.contains("Batch media intake"))
        assertTrue(source.contains("Paste URLs or page text"))
        assertTrue(source.contains("Inspect all"))
        assertTrue(source.contains("Add selected"))
        assertTrue(source.contains("Clear invalid"))
        assertTrue(source.contains("Copy rejected lines"))
        assertTrue(source.contains("onBatchInput"))
    }

    @Test
    fun viewModelRoutesBatchThroughMediaBatchPlanner() {
        val source = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val batchFlow = source
            .substringAfter("fun captureMediaBatchInput(text: String)")
            .substringBefore("fun openDownloadReview")

        assertTrue("MainViewModel must retain the media batch planner", source.contains("MediaBatchIntakePlanner"))
        assertTrue("MainViewModel must expose batch intake", source.contains("captureMediaBatchInput"))
        assertTrue(
            "Batch records and variants must persist in one repository transaction",
            batchFlow.contains("repository.saveMediaCapturesWithVariants(merged, plan.variants, now)"),
        )
        assertFalse("Batch intake must not save captures separately", batchFlow.contains("repository.saveMediaCaptures(merged)"))
        assertFalse("Batch intake must not replace variants in a second call", batchFlow.contains("repository.replaceMediaVariants"))
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
