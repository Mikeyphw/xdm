package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaArtworkMergePolicyTest {
    @Test fun resolverArtworkCannotBeDowngradedByLaterPagePoster() {
        val persisted = capture("https://img.example/resolver.jpg", MediaThumbnailProvenance.Resolver)
        val incoming = capture("https://img.example/poster.jpg", MediaThumbnailProvenance.PagePoster)
        val merged = MediaArtworkMergePolicy.merge(incoming, persisted)
        assertEquals(persisted.thumbnailUrl, merged.thumbnailUrl)
        assertEquals(MediaThumbnailProvenance.Resolver, merged.thumbnailProvenance)
    }

    @Test fun richerIncomingArtworkCanUpgradePersistedHint() {
        val persisted = capture("https://img.example/og.jpg", MediaThumbnailProvenance.OpenGraph)
        val incoming = capture("https://img.example/resolver.jpg", MediaThumbnailProvenance.Resolver)
        val merged = MediaArtworkMergePolicy.merge(incoming, persisted)
        assertEquals(incoming.thumbnailUrl, merged.thumbnailUrl)
        assertEquals(MediaThumbnailProvenance.Resolver, merged.thumbnailProvenance)
    }

    @Test fun blankIncomingArtworkPreservesKnownArtwork() {
        val persisted = capture("https://img.example/poster.jpg", MediaThumbnailProvenance.PagePoster)
        val merged = MediaArtworkMergePolicy.merge(capture(null, MediaThumbnailProvenance.Unknown), persisted)
        assertEquals(persisted.thumbnailUrl, merged.thumbnailUrl)
        assertEquals(MediaThumbnailProvenance.PagePoster, merged.thumbnailProvenance)
    }

    private fun capture(thumbnail: String?, provenance: MediaThumbnailProvenance) = MediaCaptureRecord(
        id = "capture-1",
        sourceUrl = "https://example.com/video.mp4",
        pageUrl = "https://example.com/watch",
        title = "Example",
        status = MediaCaptureStatus.MetadataReady,
        kind = MediaSourceKind.VideoStream,
        mimeType = "video/mp4",
        container = "mp4",
        codecs = null,
        durationMs = 1_000,
        thumbnailUrl = thumbnail,
        thumbnailProvenance = provenance,
        fileName = "Example.mp4",
        variantCount = 1,
        downloadId = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
    )
}
