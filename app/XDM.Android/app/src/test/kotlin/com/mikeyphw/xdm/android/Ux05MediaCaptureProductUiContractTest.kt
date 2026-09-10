package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux05MediaCaptureProductUiContractTest {
    private fun source(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val root = generateSequence(cwd) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
        return File(root, relative).readText()
    }

    @Test
    fun mediaUsesConsumerLifecycleAndDownloadedOpenAction() {
        val planner = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
        listOf("Captured", "Ready", "Downloading", "Downloaded", "RefreshNeeded", "Unavailable").forEach {
            assertTrue("missing media consumer state $it", planner.contains(it))
        }
        assertTrue(card.contains("MediaConsumerState.Downloaded ->"))
        assertTrue(card.contains("Button(onClick = { onOpenOutput(output) })"))
        assertTrue(card.contains("\"Download again\""))
        assertTrue(card.contains("Text(\"Details\")"))
        assertFalse(card.contains("Text(\"Options\")"))
    }

    @Test
    fun mediaHidesCaptureInternalsUntilDetails() {
        val inbox = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        assertTrue(inbox.contains("XdmTechnicalDetails(label = \"Capture details\")"))
        assertTrue(inbox.contains("Browser session values stay private"))
        assertTrue(inbox.contains("Paste links or page source. XDM will find supported media links"))
        assertTrue(inbox.contains("actionLabel = \"Open Live locator\""))
        assertFalse(inbox.contains("reviewable candidate(s)"))
        assertFalse(inbox.contains("output generation"))
    }
}
