package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostUx13RoadmapCompletionHotfixContractTest {
    private val root = projectRoot()

    @Test
    fun directBrowserMediaRoutesIntoXdmWithoutReviewDialog() {
        val external = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt")
        val activity = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")

        assertTrue(external.contains("payload.hasDirectCaptureSession"))
        assertTrue(external.contains("routeDirectBrowserCapture(draft)"))
        assertTrue(external.contains("ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT"))
        assertTrue(external.contains("EXTRA_INTERNAL_BROWSER_DIRECT_CAPTURE_URI"))
        assertTrue(external.contains("EXTRA_INTERNAL_BROWSER_DIRECT_PRIVATE_NETWORK_APPROVED"))
        assertFalse(external.contains("Open browser media in XDM"))
        assertFalse(external.contains("reviewDirectBrowserCapture"))
        assertFalse(external.contains("bounded candidate set"))
        assertFalse(external.contains("candidate(s)"))

        val routeStart = external.indexOf("private fun routeDirectBrowserCapture")
        val routeEnd = external.indexOf("private fun reviewEncryptedBrowserCapture", routeStart)
        assertTrue(routeStart >= 0 && routeEnd > routeStart)
        val route = external.substring(routeStart, routeEnd)
        assertFalse(route.contains("AlertDialog.Builder"))
        assertTrue(route.contains("startActivity("))
        assertTrue(route.contains("finish()"))

        assertTrue(activity.contains("ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT"))
        assertTrue(activity.contains("ingestDirectBrowserCaptureSession"))
        // Generic commands and legacy encrypted-capture recovery keep their established boundaries.
        assertTrue(external.contains("Open in XDM"))
        assertTrue(external.contains("ExternalCommandAuthorization.UserConfirmed"))
        assertTrue(external.contains("reviewEncryptedBrowserCapture"))
    }

    @Test
    fun roadmapCarryForwardGapsAreClosedInProductSource() {
        val media = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val downloads = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
        val workspace = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt")
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val labels = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmUiLabels.kt")
        val developer = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
        val readme = source("README.md")

        assertTrue(media.contains("XdmTechnicalDetails(label = \"Technical details\")"))
        assertTrue(media.contains("XdmTechnicalText(diagnostic)"))
        assertFalse(media.contains("XdmMetadataText(diagnostic)"))
        listOf("Running", "Ready", "Needs action", "Failed", "Completed").forEach { state ->
            assertTrue("missing normalized media feedback state $state", media.contains("XdmStatusBadge(\"$state\""))
        }
        listOf(
            "Fetching a bounded page prefix and checking it for media candidates.",
            "reviewable media item(s)",
            "No reviewable media found",
            "durable revision of this browser capture session",
            "durable request handoffs",
            "media candidate(s) for review",
        ).forEach { stale -> assertFalse("stale normal-user media copy: $stale", viewModel.contains(stale)) }

        assertTrue(downloads.contains("val showMetrics = downloadingCount > 0 || waitingCount > 0 || queuedCount > 0"))
        assertTrue(downloads.contains("if (showMetrics)"))
        assertFalse(workspace.contains("Needs recovery"))
        assertFalse(details.contains("Needs recovery"))
        assertFalse(labels.contains("Needs recovery"))

        assertFalse(developer.contains("XdmCardTitle(\"Developer Center\")"))
        assertTrue(developer.contains("Technical controls are separated from normal app settings"))
        assertTrue(readme.contains("post-UX13 roadmap-completion hotfix is the current UI release authority"))
        assertTrue(readme.contains("Live Locator uses a constrained in-app WebView"))
    }

    @Test
    fun hotfixIsTruthfulFinalAuthorityAndKeepsUx13AsBaseline() {
        val manifest = source("PROJECT_MANIFEST.json")
        val gate = source("tools/run-final-release-gate.sh")
        val report = source("XDM_POST_UX13_ROADMAP_COMPLETION_HOTFIX_REPORT.md")

        assertTrue(manifest.contains("\"current_overlay\": \"xdm_android_post_ux13_roadmap_completion_hotfix_v1.zip\""))
        assertTrue(manifest.contains("\"roadmap_remaining_gaps\": []"))
        assertTrue(manifest.contains("\"version\": 21"))
        assertTrue(gate.contains("validate-post-ux13-roadmap-completion-hotfix.py"))
        assertTrue(gate.contains("run-bug-hunt-phase11-validation-matrix.sh --static-only --ci"))
        assertTrue(report.contains("xdm_android_ux13_end_to_end_ui_ux_release_seal_v1.zip"))
        assertTrue(report.contains("No other unresolved UX01–UX13 product promise was found"))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
