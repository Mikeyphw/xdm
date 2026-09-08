package com.mikeyphw.xdm.android.transfer

import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.MediaTransferShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionSemanticsRepairTest {
    private val native = BackendCapabilities(
        protocols = setOf("http", "https"),
        supportsSegmentation = true,
        supportsMirrors = false,
        supportsSelectiveRepair = true,
        supportsSafDestination = true,
        supportsAuthentication = true,
        supportsProxy = false,
        maxConnectionsPerDownload = 4,
        supportsExpiringUrls = true,
        supportsMediaPlaylists = false,
    )
    private val aria2 = BackendCapabilities(
        protocols = setOf("http", "https", "ftp", "sftp", "magnet"),
        supportsSegmentation = true,
        supportsMirrors = true,
        supportsSelectiveRepair = false,
        supportsSafDestination = false,
        supportsAuthentication = false,
        supportsProxy = false,
        maxConnectionsPerDownload = 16,
        supportsExpiringUrls = false,
        supportsMediaPlaylists = false,
    )
    private val capabilities = mapOf(BackendType.Native to native, BackendType.Aria2 to aria2)
    private val policy = BackendSelectionPolicy()

    @Test
    fun browserRefererDoesNotTurnDirectFileIntoMediaWorkflow() {
        val request = DownloadRequest(
            id = "github-asset",
            sourceUrl = "https://release-assets.githubusercontent.com/releases/download/package.apk",
            destinationUri = "xdm://filesystem/downloads",
            fileName = "package.apk",
            headers = mapOf("Referer" to "https://github.com/example/project/releases"),
            transferShape = MediaTransferShape.DirectFile,
        )
        val recommendation = policy.recommend(request, capabilities)

        assertEquals(BackendType.Native, recommendation.backend)
        assertEquals(BackendSelectionReason.SafRequiresNative, recommendation.reason)
        assertTrue(recommendation.compatible)
        assertNull(policy.compatibilityIssue(request, native))
    }

    @Test
    fun progressiveMp4WithCapturedContextIsNativeDirectHttpNotPlaylist() {
        val request = DownloadRequest(
            id = "progressive",
            sourceUrl = "https://cdn.example.test/movie.mp4",
            destinationUri = "file:///tmp/movie.mp4",
            fileName = "movie.mp4",
            mimeType = "video/mp4",
            headers = mapOf("Cookie" to "session=secret", "Referer" to "https://example.test/watch"),
        )
        val recommendation = policy.recommend(request, capabilities)

        assertEquals(MediaTransferShape.DirectMedia, request.transferShape)
        assertEquals(BackendType.Native, recommendation.backend)
        assertEquals(BackendSelectionReason.DirectMediaPrefersNative, recommendation.reason)
        assertNull(policy.compatibilityIssue(request, native))
    }

    @Test
    fun adaptivePlaylistStillRequiresPlaylistCapableExecution() {
        val request = DownloadRequest(
            id = "playlist",
            sourceUrl = "https://cdn.example.test/master.m3u8",
            destinationUri = "xdm://filesystem/downloads",
            fileName = "master.m3u8",
        )

        assertEquals(MediaTransferShape.AdaptivePlaylist, request.transferShape)
        assertTrue(policy.compatibilityIssue(request, native)?.contains("adaptive playlist", ignoreCase = true) == true)
        assertTrue(policy.compatibilityIssue(request, aria2)?.isNotBlank() == true)
    }
}
