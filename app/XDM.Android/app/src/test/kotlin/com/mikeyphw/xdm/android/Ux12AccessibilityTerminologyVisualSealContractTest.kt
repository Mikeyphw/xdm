package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux12AccessibilityTerminologyVisualSealContractTest {
    private val root = androidRoot()

    @Test
    fun sharedDesignSystemSupportsLargeTextAndNonColorStatusSemantics() {
        val design = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmDesignSystem.kt")
        val primitives = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt")
        val shell = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt")

        assertTrue(design.contains("fun xdmResponsiveMaxLines"))
        assertTrue(design.contains("LocalDensity.current.fontScale >= 1.30f"))
        assertTrue(design.contains("stateDescription = \"${'$'}{tone.accessibilityLabel()}: ${'$'}text\""))
        assertTrue(design.contains("SelectionContainer"))
        assertTrue(design.contains("FontFamily.Monospace"))

        assertTrue(primitives.contains("fontScale >= 1.60f -> 1"))
        assertTrue(primitives.contains("fontScale >= 1.30f -> metrics.size.coerceAtMost(2)"))
        assertTrue(primitives.contains("maxWidth < 420.dp || LocalDensity.current.fontScale >= 1.30f"))
        assertTrue(primitives.contains("stateDescription = \"${'$'}{tone.accessibilityLabel()} notice\""))

        assertTrue(shell.contains("alwaysShowLabel = fontScale < 1.60f || selected"))
        assertTrue(shell.contains("maxLines = if (fontScale >= 1.30f) 2 else 1"))
        assertTrue(shell.contains("val sidebarWidth = if (LocalDensity.current.fontScale >= 1.30f) 272.dp else 224.dp"))
        assertTrue(shell.contains(".width(sidebarWidth)"))
    }

    @Test
    fun currentUserFacingVocabularyIsConsistentAndPlainLanguage() {
        val labels = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmUiLabels.kt")
        val panels = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ActivityPanel.kt")
        val activity = source("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt")
        val scheduler = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")

        assertTrue(labels.contains("DownloadState.Completed -> \"Completed\""))
        assertTrue(labels.contains("MediaCaptureStatus.MetadataReady -> \"Ready\""))
        assertTrue(labels.contains("MediaCaptureStatus.MetadataMissing -> \"Refresh needed\""))
        assertTrue(labels.contains("MediaResolutionStatus.Failed -> \"Unavailable\""))
        assertTrue(panels.contains("Overview(\"Needs action\")"))
        assertTrue(panels.contains("Decisions(\"Queue holds\")"))
        assertTrue(activity.contains("Nothing needs action"))
        assertTrue(activity.contains("Queue holds"))
        assertFalse(scheduler.contains("explainably held"))
    }

    @Test
    fun errorsAndRawDiagnosticsHaveClearHierarchy() {
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val diagnostics = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt")
        val browser = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/BrowserExtensionSettingsScreen.kt")

        assertTrue(details.contains("What happened:"))
        assertTrue(details.contains("What XDM will do:"))
        assertTrue(details.contains("What you can do:"))
        assertTrue(details.contains("XdmTechnicalDetails"))
        assertTrue(details.contains("XdmTechnicalText"))
        assertTrue(diagnostics.contains("Copy technical details"))
        assertTrue(diagnostics.contains("XdmTechnicalText"))
        assertTrue(browser.contains("XdmTechnicalText"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
}
