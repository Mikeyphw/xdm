package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptureFfmpegHotfixContractTest {
    private fun androidRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").exists() }
            ?: start
    }

    @Test
    fun browserCaptureSeparatesReplayCredentialsFromExpiryMetadata() {
        val root = androidRoot()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val urlPolicy = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt").readText()

        assertTrue(viewModel.contains("hasReplayCredentialBearingQuery(facts.url)"))
        assertTrue(viewModel.contains("browser-single-capture-session-required"))
        assertTrue(urlPolicy.contains("fun hasReplayCredentialBearingQuery"))
        assertTrue(urlPolicy.contains("Expiry/checksum hints"))
        assertFalse(urlPolicy.substringAfter("fun hasReplayCredentialBearingQuery").substringBefore("fun isCleartext").contains("\"expires\""))
    }

    @Test
    fun adaptiveFfmpegCarriesHlsHintAndExecutionDiagnostics() {
        val root = androidRoot()
        val manager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt").readText()
        val compiler = File(root, "media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegCommandCompiler.kt").readText()
        val runtimeModels = File(root, "media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegRuntimeModels.kt").readText()
        val application = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").readText()

        assertTrue(manager.contains("embedded-ffmpeg-request-context"))
        assertTrue(manager.contains("embedded-ffmpeg-execute"))
        assertTrue(manager.contains("embedded-ffprobe-verify"))
        assertTrue(manager.contains("ffmpegInputFormat(selected.mimeType, selected.url)"))
        assertTrue(compiler.contains("input.formatHint?.let"))
        assertTrue(runtimeModels.contains("enum class FfmpegInputFormat"))
        assertTrue(application.contains("debugRecorder = debugEventRecorder"))
    }

    @Test
    fun crossOriginBrowserCredentialBoundaryRemainsXar10Strict() {
        val root = androidRoot()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val policy = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionSecurityPolicy.kt").readText()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt").readText()

        assertTrue(viewModel.contains("HLS/DASH child inputs on a different origin must not inherit"))
        assertTrue(policy.contains("\"referer\""))
        assertTrue(policy.contains("\"origin\""))
        assertTrue(planner.contains("exactVariantHeaders"))
        assertTrue(planner.contains("mergeHeadersCaseInsensitive(inheritedHeaders, exactVariantHeaders)"))
    }
    @Test
    fun terminalEmbeddedFfmpegOutputDoesNotTrapThePrimaryDownloadButton() {
        val root = androidRoot()
        val manager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt").readText()

        assertTrue(manager.contains("blocksPrimaryAdmission"))
        assertTrue(manager.contains("MediaOutputState.Queued"))
        assertTrue(manager.contains("MediaOutputState.Active"))
        assertTrue(manager.contains("MediaOutputState.Completed"))
        assertFalse(manager.substringAfter("blocksPrimaryAdmission").substringBefore("private suspend fun execute").contains("MediaOutputState.Failed"))
        assertFalse(manager.substringAfter("blocksPrimaryAdmission").substringBefore("private suspend fun execute").contains("MediaOutputState.RecoveryRequired"))
        assertFalse(manager.substringAfter("blocksPrimaryAdmission").substringBefore("private suspend fun execute").contains("MediaOutputState.Cancelled"))
        assertTrue(manager.contains("embedded-ffmpeg-admission"))
    }

}
