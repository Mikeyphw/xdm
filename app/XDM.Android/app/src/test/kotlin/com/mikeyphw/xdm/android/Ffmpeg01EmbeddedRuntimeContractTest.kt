package com.mikeyphw.xdm.android

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ffmpeg01EmbeddedRuntimeContractTest {
    private val root: Path = generateSequence(Path.of(System.getProperty("user.dir") ?: ".").toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) && Files.isDirectory(it.resolve("app/src/main")) }
        ?: error("XDM Android root not found from ${System.getProperty("user.dir")}")

    private fun text(relative: String): String = String(Files.readAllBytes(root.resolve(relative)), Charsets.UTF_8)

    @Test
    fun embeddedRuntimeIsPinnedAttestedAndShellFree() {
        val manifest = text("media-ffmpeg/runtime/ffmpeg-runtime.json")
        val installer = text("tools/install-ffmpeg-runtime.py")
        val verifier = text("tools/verify-ffmpeg-runtime.py")
        val runtime = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/EmbeddedFfmpegRuntime.kt")
        val launcher = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegProcessLauncher.kt")

        assertTrue(manifest.contains("\"ffmpegVersion\": \"9.0.1\""))
        assertTrue(manifest.contains("\"opensslVersion\": \"3.5.8\""))
        assertTrue(manifest.contains("\"ndkVersion\": \"29.0.14206865\""))
        assertTrue(manifest.contains("\"requiredLoadAlignment\": 16384"))
        assertTrue(manifest.contains("\"gplEnabled\": false"))
        assertTrue(manifest.contains("\"nonfreeEnabled\": false"))
        assertTrue(installer.contains("source digest mismatch"))
        assertTrue(installer.contains("max-page-size=16384"))
        assertTrue(verifier.contains("--require-payload"))
        assertTrue(verifier.contains("--require-16kb-alignment"))
        assertTrue(verifier.contains("assets/licenses/FFmpeg-LGPL-2.1.txt"))
        assertTrue(runtime.contains("applicationInfo.nativeLibraryDir"))
        assertTrue(runtime.contains("libxdm_ffmpeg.so"))
        assertTrue(runtime.contains("libxdm_ffprobe.so"))
        assertTrue(launcher.contains("ProcessBuilder"))
        assertFalse(launcher.contains("sh -c"))
        assertFalse(launcher.contains("Runtime.getRuntime().exec"))
    }

    @Test
    fun liveExecutionIsAppOwnedAndDeveloperCenterCanProbeIt() {
        val planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
        val execution = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
        val dispatcher = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
        val termux = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt")
        val manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt")
        val viewModel = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val workspace = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")

        assertTrue(planner.contains("requiresTermux = strategy == MediaDownloadStrategy.YtDlp"))
        assertTrue(execution.contains("EmbeddedFfmpegLive"))
        assertTrue(execution.contains("\"embedded-ffmpeg\""))
        assertTrue(dispatcher.contains("LaunchEmbeddedFfmpeg"))
        assertFalse(termux.contains("request.lane == MediaExecutionLane.LiveRecording"))
        assertTrue(manager.contains("FfmpegOperation.RecordStream"))
        assertTrue(manager.contains("runtime.probe"))
        assertTrue(manager.contains("prepared.promote()"))
        assertTrue(manager.contains("RecoveryRequired"))
        assertTrue(viewModel.contains("enqueueLiveRecording"))
        assertTrue(viewModel.contains("runFfmpegSelfTest"))
        assertTrue(workspace.contains("Embedded FFmpeg + FFprobe"))
        assertTrue(workspace.contains("Run FFmpeg self-test"))
    }
}
