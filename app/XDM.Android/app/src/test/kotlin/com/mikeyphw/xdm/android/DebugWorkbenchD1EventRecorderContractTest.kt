package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD1EventRecorderContractTest {
    private fun androidRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").exists() }
            ?: start
    }

    @Test
    fun debugFoundationUsesBoundedJsonlAndNoRoomMigration() {
        val source = File(androidRoot(), "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt").readText()

        assertTrue(source.contains("enum class DebugArea"))
        assertTrue(source.contains("enum class DebugSeverity"))
        assertTrue(source.contains("data class DebugEvent"))
        assertTrue(source.contains("interface DebugEventRecorder"))
        assertTrue(source.contains("object NoOpDebugEventRecorder"))
        assertTrue(source.contains("class RollingJsonlDebugEventRecorder"))
        assertTrue(source.contains("current.jsonl"))
        assertTrue(source.contains("maxSessionBytes: Long = 2L * 1024L * 1024L"))
        assertTrue(source.contains("exportSupportBundle"))
        assertTrue(source.contains("ZipOutputStream"))
    }

    @Test
    fun safeRuntimeHooksArePresentWithoutStartingDownloads() {
        val root = androidRoot()
        val intake = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt").readText()
        val media = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt").readText()
        val batch = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBatchIntake.kt").readText()
        val external = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt").readText()
        val notification = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedNotificationDebugEvents.kt").readText()

        assertTrue(intake.contains("debugRecorder.record"))
        assertTrue(intake.contains("area = DebugArea.AddDownload"))
        assertTrue(media.contains("recordDebugSniff"))
        assertTrue(batch.contains("action = \"batch-intake\""))
        assertTrue(external.contains("action = \"external-media-review\""))
        assertTrue(notification.contains("fallback-to-xdm-details"))
        assertTrue(notification.contains("DebugRedactor::fingerprint"))
    }

    @Test
    fun d1DocumentationAndValidatorAreRecorded() {
        val root = androidRoot()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val doc = File(root, "docs/architecture/DEBUG-WORKBENCH-D1-EVENT-RECORDER.md").readText()

        assertTrue(manifest.contains("debug_workbench_phase_d1_event_recorder"))
        assertTrue(doc.contains("rolling JSONL"))
        assertTrue(doc.contains("No automatic upload"))
        assertTrue(doc.contains("MediaSniffingEngine"))
        assertTrue(doc.contains("Completed notification"))
    }
}
