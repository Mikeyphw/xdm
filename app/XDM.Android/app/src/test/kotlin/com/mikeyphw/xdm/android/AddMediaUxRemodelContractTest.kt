package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMediaUxRemodelContractTest {
    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(current, "PROJECT_MANIFEST.json").isFile && File(current, "app/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate XDM Android root")
    }

    private fun source(path: String): String = File(androidRoot(), path).readText()

    @Test
    fun addSheetUsesOneExplicitDownloadActionWithoutSecondConfirmation() {
        val add = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        val shell = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")

        assertTrue(shell.contains("XdmAdaptiveSheet("))
        assertTrue(shell.contains("title = \"New download\""))
        assertTrue(add.contains("Add a download with a single explicit action"))
        assertTrue(add.contains("else -> \"Download\""))
        assertTrue(add.contains("onAdd("))
        assertTrue(add.contains("Advanced options"))
        assertTrue(add.contains("Browser context attached"))
        assertTrue(add.contains("destinationUiLabel(destinationUri)"))
        assertTrue(add.contains("preferMediaInspection"))
        assertTrue(add.contains("Inspect media"))
        listOf("reviewConfirmed", "Review download", "Add to queue", "Step 1 of 2", "Step 2 of 2").forEach { stale ->
            assertFalse("obsolete Add confirmation token returned: $stale", add.contains(stale))
        }
    }

    @Test
    fun mediaWorkspaceKeepsDirectMediaSimpleAndAdaptiveChoicesConditional() {
        val inbox = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
        val workspace = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")

        assertTrue(inbox.contains("Direct media downloads in one tap"))
        assertTrue(inbox.contains("AnimatedVisibility(mediaToolsExpanded)"))
        assertTrue(inbox.contains("More tools"))
        assertTrue(card.contains("val hasTrackChoices"))
        assertTrue(card.contains("if (hasTrackChoices && videoVariants.isNotEmpty())"))
        assertTrue(card.contains("if (hasTrackChoices)"))
        assertTrue(card.contains("showTrackControls = hasTrackChoices"))
        assertTrue(card.contains("if (showTrackControls)"))
        assertTrue(card.contains("MediaConsumerState.Ready -> Button("))
        assertTrue(card.contains("summary.primaryActionLabel"))
        assertTrue(workspace.contains("MediaConsumerState.Downloaded -> \"Open\""))
        assertTrue(card.contains("Text(\"Edit\")"))
        assertTrue(card.contains("MediaConsumerState.Unavailable -> \"Unavailable\""))
    }

    @Test
    fun warningCleanupAndReleaseAuthorityCarryForward() {
        val post = source("app/src/test/kotlin/com/mikeyphw/xdm/android/PostDl03ReleaseFollowupContractTest.kt")
        val gate = source("tools/run-final-release-gate.sh")
        val manifest = source("PROJECT_MANIFEST.json")

        assertTrue(post.contains("System.getProperty(\"user.dir\") ?: \".\""))
        assertTrue(post.contains("requireNotNull(root.parentFile?.parentFile)"))
        assertTrue(gate.contains("tools/validate-add-media-ux-remodel.py"))
        assertTrue(gate.contains("post-UX13 roadmap-completion hotfix remains the historical UI release baseline"))
        assertTrue(gate.contains("ACT01/ACT02 list quick-actions final seal is the current experience-polish validation authority"))
        assertTrue(gate.contains("UX13 remains the end-to-end UI/UX baseline"))
        assertTrue(gate.contains("Add/Media UX remodel remains a retained functional milestone"))
        assertTrue(gate.contains("execution/media semantics repair remains its functional baseline"))
        assertTrue(manifest.contains("\"current_release_authority\": \"post_ux13_roadmap_completion_hotfix\""))
        assertTrue(manifest.contains("\"room_schema_current\": 21"))
    }
}
