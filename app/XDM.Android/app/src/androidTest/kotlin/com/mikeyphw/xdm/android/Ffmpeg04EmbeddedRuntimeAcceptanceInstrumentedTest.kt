package com.mikeyphw.xdm.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegPostProcessor
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.storage.DestinationUris
import java.io.File
import java.net.URI
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
        val runtime = EmbeddedFfmpegRuntime(context)
        val capability = runtime.capabilities(force = true)
        assertTrue(capability.summary, capability.ready)
        assertTrue("runtime lock must attest the packaged executables", capability.attestationVerified)
        assertTrue("runtime build flags must match the pinned downloader profile", capability.buildConfigurationVerified)

        val destinationWriter = AndroidDestinationWriter(context)
        val prepared = destinationWriter.prepare(
            DestinationRequest(
                downloadId = "ff04-no-termux-acceptance",
                destinationUri = DestinationUris.APP_PRIVATE_DOWNLOADS,
                fileName = "ff04-muxed-output.mp4",
                mimeType = "video/mp4",
                conflictPolicy = FilenameConflictPolicy.Overwrite,
            ),
        )
        val staged = prepared.artifacts.stagingFile
        val result = FfmpegPostProcessor(runtime, File(fixtureRoot, "work")).muxTracks(
            video = video,
            audio = audio,
            output = staged,
            expectedDurationMs = 1_000L,
        )
        assertTrue(result.summary, result.success)
        assertTrue(staged.isFile && staged.length() > 1_024L)

        val stagedProbe = runtime.probe(staged.absolutePath).getOrThrow()
        assertEquals(1, stagedProbe.videoStreams.size)
        assertEquals(1, stagedProbe.audioStreams.size)
        assertTrue(stagedProbe.durationSeconds != null && stagedProbe.durationSeconds!! >= 0.9)

        val promoted = prepared.promote()
        assertTrue("app-private FF04 acceptance output must publish atomically", promoted.atomic)
        val committed = File(URI(promoted.committedUri))
        assertTrue(committed.isFile && committed.length() == promoted.bytesCommitted)
        val committedProbe = runtime.probe(committed.absolutePath).getOrThrow()
        assertEquals(1, committedProbe.videoStreams.size)
        assertEquals(1, committedProbe.audioStreams.size)
        assertTrue(committedProbe.durationSeconds != null && committedProbe.durationSeconds!! >= 0.9)
        committed.delete()
        prepared.deleteArtifacts()
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
