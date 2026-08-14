package com.mikeyphw.xdm.android.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase5MediaSniffingContractTest {
    private val root = File(System.getProperty("user.dir") ?: ".")

    @Test fun pageProbeUsesBoundedReadsAndPreservesSessionHeaders() {
        val source = File(root, "../media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt").canonicalFile.readText()
        assertTrue(source.contains("while (total < limitBytes)"))
        assertFalse(source.contains("filterKeys { !PrivacyDiagnosticsRedactor.isSensitiveHeaderName"))
        assertTrue(source.contains("connection.setRequestProperty(name, value)"))
    }

    @Test fun kotlinPayloadDoesNotContainRawCrLfCharLiteralsOrDuplicateSelection() {
        val sniff = File(root, "../media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt").canonicalFile.readBytes()
        assertTrue(sniff.toString(Charsets.UTF_8).contains("value.any { it == '\r' || it == '\n' }"))
        val planner = File(root, "../media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt").canonicalFile.readText()
        assertEquals(1, Regex("val selectedMimeType = selected\\.mimeType").findAll(planner).count())
    }

    @Test fun mediaPlannerUsesExplicitShapesAndEvidenceBasedProtection() {
        val planner = File(root, "../media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt").canonicalFile.readText()
        assertTrue(planner.contains("BrowserHandoffMediaPolicy.classifyShape"))
        assertTrue(planner.contains("No authoritative DRM evidence"))
        assertFalse(planner.contains("title.contains(\"drm\""))
    }
}
