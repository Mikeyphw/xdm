package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimePresentationModelsTest {
    @Test fun mimeWinsForAdaptiveMedia() {
        assertEquals(
            MimePresentationKind.AdaptiveMedia,
            MimePresentationResolver.resolve("stream.txt", "application/vnd.apple.mpegurl").kind,
        )
    }

    @Test fun manifestExtensionsAreMediaNotGenericDocuments() {
        assertEquals(MimePresentationKind.AdaptiveMedia, MimePresentationResolver.resolve("master.m3u8", null).kind)
        assertEquals(MimePresentationKind.AdaptiveMedia, MimePresentationResolver.resolve("manifest.mpd", null).kind)
    }

    @Test fun commonDocumentFamiliesHaveDedicatedPresentation() {
        assertEquals(MimePresentationKind.Pdf, MimePresentationResolver.resolve("paper.pdf", "application/octet-stream").kind)
        assertEquals(MimePresentationKind.Spreadsheet, MimePresentationResolver.resolve("budget.xlsx", null).kind)
        assertEquals(MimePresentationKind.Presentation, MimePresentationResolver.resolve("deck.pptx", null).kind)
        assertEquals(MimePresentationKind.Package, MimePresentationResolver.resolve("app.apk", null).kind)
    }

    @Test fun authoritativeMimeBeatsMisleadingExtension() {
        assertEquals(MimePresentationKind.Text, MimePresentationResolver.resolve("actually-video.mp4", "text/plain").kind)
    }

    @Test fun accessibilityDescriptionIncludesUsefulType() {
        assertTrue(MimePresentationResolver.contentDescription("movie.mp4", "video/mp4").contains("video"))
    }
}
