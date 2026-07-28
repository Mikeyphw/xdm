package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardBucket
import com.mikeyphw.xdm.android.model.DownloadDashboardPlanner
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.DownloadReviewPlanner
import com.mikeyphw.xdm.android.model.DownloadReviewReadiness
import com.mikeyphw.xdm.android.model.DownloadState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderExperiencePhase8ABContractTest {
    @Test
    fun addDownloadUsesAReviewFirstManualAndExternalFlow() {
        val root = androidRoot()
        val screens = UiSourceTree.readAll(root)
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()

        listOf(
            "Paste detected URL",
            "DownloadReviewPlanner.plan(",
            "Review download",
            "Add to queue",
            "Inspect media",
            "never creates a transfer automatically",
        ).forEach { assertTrue("Add Download missing $it", screens.contains(it)) }
        assertTrue(shell.contains("viewModel.inspectManualMedia(url, fileName)"))
        assertTrue(viewModel.contains("fun inspectManualMedia(url: String, fileName: String)"))
        val planner = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloaderExperience.kt").readText()
        listOf("DownloadRepository", "executionStarter", "TransferExecutionRuntime").forEach { forbidden ->
            assertFalse("Review planner must not execute transfers through $forbidden", planner.contains(forbidden))
        }
    }

    @Test
    fun reviewPlannerClassifiesBeforeAnyQueueSubmission() {
        val playlist = DownloadReviewPlanner.plan(
            url = "https://cdn.example/live/master.m3u8",
            destinationUri = "xdm://downloads",
        )
        assertEquals(DownloadIntakeKind.AdaptiveMedia, playlist.kind)
        assertEquals(DownloadReviewReadiness.ChoiceRecommended, playlist.readiness)
        assertTrue(playlist.canInspectAsMedia)
        assertTrue(playlist.canStartDirectly)

        val invalid = DownloadReviewPlanner.plan("file:///sdcard/private.bin", destinationUri = "xdm://downloads")
        assertEquals(DownloadReviewReadiness.InvalidLink, invalid.readiness)
        assertFalse(invalid.canStartDirectly)
    }

    @Test
    fun downloadsAreAGroupedOperationalDashboard() {
        val root = androidRoot()
        val screens = UiSourceTree.readAll(root)
        listOf(
            "DownloadsWorkspacePlanner.visibleDownloads",
            "DownloadWorkspaceFilter.entries",
            "Organize downloads",
            "combinedClickable",
        ).forEach { assertTrue("Downloads workspace missing $it", screens.contains(it)) }
        val model = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloaderExperience.kt").readText()
        listOf("Needs attention", "Retry available").forEach {
            assertTrue("Dashboard model missing $it", model.contains(it))
        }

        val dashboard = DownloadDashboardPlanner.plan(
            listOf(
                download("failed", DownloadState.Failed, "No space left on device"),
                download("running", DownloadState.Downloading),
                download("queued", DownloadState.Queued),
                download("done", DownloadState.Completed),
            ),
        )
        assertEquals(
            listOf(
                DownloadDashboardBucket.NeedsAttention,
                DownloadDashboardBucket.Active,
                DownloadDashboardBucket.Queued,
                DownloadDashboardBucket.Completed,
            ),
            dashboard.sections.map { it.bucket },
        )
    }

    @Test
    fun productBoundaryAndDownloaderEnginesRemainSealed() {
        val root = androidRoot()
        val routes = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()
        val production = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile }
            .joinToString("\n") { it.readText() }
        assertEquals(
            listOf("Downloads", "Add", "Media", "Library", "Activity", "Settings"),
            AppRoute.entries.map(AppRoute::label),
        )
        assertFalse(routes.contains("Browser("))
        assertFalse(production.contains("android.webkit"))
        listOf(
            "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
            "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
            "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
            "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt",
        ).forEach { assertTrue("Missing preserved engine $it", File(root, it).isFile) }
    }

    private fun download(id: String, state: DownloadState, error: String? = null) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://example.com/$id.bin",
        destinationUri = "xdm://downloads",
        state = state,
        backend = BackendType.Native,
        bytesReceived = 0,
        totalBytes = 100,
        speedBytesPerSecond = 0,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
        errorMessage = error,
    )

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
