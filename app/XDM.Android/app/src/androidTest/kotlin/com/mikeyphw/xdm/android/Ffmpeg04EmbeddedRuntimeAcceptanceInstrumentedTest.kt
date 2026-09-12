package com.mikeyphw.xdm.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegPostProcessor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ffmpeg04EmbeddedRuntimeAcceptanceInstrumentedTest {
    @Test
    fun embeddedRuntimeMuxesSeparateTracksAndProbesOutputWithoutTermux() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = File(context.cacheDir, "ffmpeg04-fixtures").apply {
            deleteRecursively()
            mkdirs()
        }
        val video = copyAsset(context, "ffmpeg04/video-only.mp4", File(fixtureRoot, "video-only.mp4"))
        val audio = copyAsset(context, "ffmpeg04/audio-only.m4a", File(fixtureRoot, "audio-only.m4a"))
        val output = File(fixtureRoot, "muxed-output.mp4")

        val runtime = EmbeddedFfmpegRuntime(context)
        val capability = runtime.capabilities(force = true)
        assertTrue(capability.summary, capability.ready)
        assertTrue("runtime lock must attest the packaged executables", capability.attestationVerified)
        assertTrue("runtime build flags must match the pinned downloader profile", capability.buildConfigurationVerified)

        val result = FfmpegPostProcessor(runtime, File(fixtureRoot, "work")).muxTracks(
            video = video,
            audio = audio,
            output = output,
            expectedDurationMs = 1_000L,
        )
        assertTrue(result.summary, result.success)
        assertTrue(output.isFile && output.length() > 1_024L)

        val probe = runtime.probe(output.absolutePath).getOrThrow()
        assertEquals(1, probe.videoStreams.size)
        assertEquals(1, probe.audioStreams.size)
        assertTrue(probe.durationSeconds != null && probe.durationSeconds!! >= 0.9)
    }

    private fun copyAsset(
        context: android.content.Context,
        asset: String,
        target: File,
    ): File {
        target.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
