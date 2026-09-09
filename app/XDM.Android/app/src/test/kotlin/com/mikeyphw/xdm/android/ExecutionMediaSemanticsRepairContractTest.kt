package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionMediaSemanticsRepairContractTest {
    private fun androidRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }).absoluteFile
        repeat(8) {
            if (File(current, "PROJECT_MANIFEST.json").isFile && File(current, "transfer-api").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate XDM Android root")
    }

    private fun source(path: String): String = File(androidRoot(), path).readText()

    @Test
    fun directBrowserFileAndProgressiveMediaStayDirectAcrossLayers() {
        val backend = source("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt")
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        val migration = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt")
        val main = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val handoff = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt")
        assertTrue(backend.contains("MediaTransferShape.DirectMedia ->"))
        assertTrue(backend.contains("selectionPolicy.compatibilityIssue(request, capabilities)"))
        assertFalse(backend.contains("request.isMediaRequest && !capabilities.supportsMediaPlaylists"))
        assertTrue(runtime.contains("transferShape = mediaHandoff?.transferShape"))
        assertFalse(runtime.contains("isMediaRequest = mediaHandoff != null"))
        assertTrue(migration.contains("transferShape = handoff?.transferShape"))
        assertFalse(migration.contains("isMediaRequest = handoff != null"))
        assertTrue(main.contains("candidate?.let(::transferShapeForCandidate) ?: inferTransferShape(url)"))
        assertTrue(handoff.contains("refreshedTransferShape(source.transferShape"))
        assertTrue(handoff.contains("refreshedTransferShape(it.transferShape, exactUrl)"))
    }

    @Test
    fun directMediaResolutionAndExecutionFailuresAreIndependent() {
        val main = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val workspace = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")
        assertTrue(main.contains("transferShape == MediaTransferShape.DirectFile || transferShape == MediaTransferShape.DirectMedia"))
        assertTrue(main.contains("Direct media is ready"))
        assertFalse(main.contains("resolutionStatus = MediaResolutionStatus.Failed"))
        assertTrue(workspace.contains("MediaTransferShape.AdaptivePlaylist"))
        assertTrue(workspace.contains("selectedQuality = selectedVideo?.qualityLabel ?: if"))
    }

    @Test
    fun liveLocatorRecreationKeepsSecretsOutOfBundleAndContextInProcess() {
        val locator = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
        val savedState = locator.substringAfter("private fun encodeSavedCandidate").substringBefore("private fun restoreLocatorState")
        assertTrue(locator.contains("private object MediaLocatorRequestContextCache"))
        assertTrue(locator.contains("data class MediaLocatorRequestContext"))
        assertTrue(savedState.contains("requestContextKey"))
        assertFalse(savedState.contains("put(\"requestHeaders\""))
        assertFalse(savedState.contains("put(\"headers\""))
        assertFalse(savedState.contains("put(\"url\""))
        assertFalse(savedState.contains("put(\"pageUrl\""))
        assertTrue(locator.contains("variantUrls = candidate.variants.associate"))
        assertTrue(locator.contains("MediaLocatorRequestContextCache.get"))
        assertTrue(locator.contains("val savedKind = runCatching { MediaSourceKind.valueOf"))
        assertTrue(locator.contains("savedKind?.restoreMimeHint()"))
        assertTrue(locator.contains("kind = savedKind ?: base.kind"))
        assertTrue(locator.contains("requestContext.variantUrls[variantId]"))
    }
}
