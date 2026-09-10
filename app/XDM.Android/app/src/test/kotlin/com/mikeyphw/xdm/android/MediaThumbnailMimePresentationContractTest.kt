package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaThumbnailMimePresentationContractTest {
    @Test
    fun sharedArtworkPipelineIsWiredAcrossMediaDownloadsAndLibrary() {
        val root = androidRoot()
        val mime = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/MimePresentationModels.kt").readText()
        val primitives = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt").readText()
        val artwork = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/XdmMediaArtwork.kt").readText()
        val captureCard = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt").readText()
        val inbox = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt").readText()
        val downloadRow = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt").readText()
        val downloads = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt").readText()
        val library = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt").readText()

        assertTrue(mime.contains("MimePresentationKind.AdaptiveMedia"))
        assertTrue(mime.contains("application/vnd.android.package-archive"))
        assertTrue(mime.contains("application/pdf"))
        assertTrue(primitives.contains("MimePresentationResolver.resolve"))
        assertTrue(primitives.contains("MimePresentationKind.Subtitle"))
        assertTrue(artwork.contains("media-artwork-v1"))
        assertTrue(artwork.contains("MAX_DISK_BYTES = 32L * 1024L * 1024L"))
        assertTrue(artwork.contains("MediaMetadataRetriever.OPTION_CLOSEST_SYNC"))
        assertTrue(artwork.contains("presentation.kind == MimePresentationKind.Image"))
        assertTrue(artwork.contains("DebugSeverity.Trace"))
        assertTrue(captureCard.contains("captureVariants.firstOrNull { it.kind == MediaVariantKind.Thumbnail }"))
        assertTrue(captureCard.contains("XdmMediaArtwork("))
        assertTrue(inbox.contains("RecentlyQueuedMediaRow"))
        assertTrue(inbox.contains("thumbnailUrl = capture.thumbnailUrl ?: variants.firstOrNull"))
        assertTrue(downloadRow.contains("localUri = download.completedArtifactUri"))
        assertTrue(downloads.contains("mediaThumbnailByDownloadId"))
        assertTrue(downloads.contains("MediaVariantKind.Thumbnail"))
        assertTrue(library.contains("thumbnailUrl = item.thumbnailUrl"))
        assertTrue(library.contains("localUri = item.playbackUrl"))
    }

    private fun androidRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "core-model").isDirectory }
            ?: error("Could not locate XDM.Android root from $cwd")
    }
}
