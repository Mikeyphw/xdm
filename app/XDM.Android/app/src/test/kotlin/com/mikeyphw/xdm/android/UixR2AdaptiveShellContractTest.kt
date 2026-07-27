package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR2AdaptiveShellContractTest {
    @Test
    fun visibleNavigationHasExactlyFivePrimaryDestinations() {
        assertEquals(
            listOf("Downloads", "Media", "Library", "Activity", "Settings"),
            AppRoute.entries.filterNot { it == AppRoute.Add }.map(AppRoute::label),
        )
    }

    @Test
    fun addIsAnInternalAdaptiveModalInsteadOfAPermanentDestination() {
        val root = androidRoot()
        val app = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt").readText()

        assertTrue(app.contains("private val routeTopology = AppRoute.entries"))
        assertTrue(app.contains("filterNot { it == AppRoute.Add }"))
        assertTrue(app.contains("XdmAdaptiveSheet("))
        assertTrue(app.contains("visible = state.route == AppRoute.Add"))
        assertTrue(app.contains("rememberSaveable"))
        assertTrue(app.contains("onDismissRequest = { viewModel.navigate(previousPrimaryRoute) }"))
        assertFalse(shell.contains("AppRoute.Add"))
        assertFalse(app.contains("FloatingActionButton"))
        assertFalse(app.contains("CenterAlignedTopAppBar"))
    }

    @Test
    fun shellUsesExplicitPhoneTabletAndExpandedBreakpoints() {
        val root = androidRoot()
        val window = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmWindowClass.kt").readText()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt").readText()

        assertTrue(window.contains("widthDp < 600f"))
        assertTrue(window.contains("widthDp < 840f"))
        assertTrue(shell.contains(".width(224.dp)"))
        assertTrue(shell.contains("NavigationBar("))
        assertTrue(shell.contains("XdmNavigationSidebar("))
        assertTrue(shell.contains("WindowInsets.safeDrawing"))
        assertTrue(shell.contains("imePadding()"))
        assertTrue(shell.contains("widthIn(max = 1480.dp)"))
    }

    @Test
    fun themeIsDarkFirstFlatAndSemantic() {
        val root = androidRoot()
        val theme = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmTheme.kt").readText()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val design = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmDesignSystem.kt").readText()
        val tokens = File(root, "browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/XdmThemeTokens.kt").readText()

        assertTrue(theme.contains("darkColorScheme("))
        assertTrue(tokens.contains("background = 0xFF090B0F"))
        assertTrue(theme.contains("surfaceTint = Color.Transparent"))
        assertTrue(theme.contains("successContainer"))
        assertTrue(theme.contains("warningContainer"))
        assertTrue(activity.contains("XdmTheme"))
        assertFalse(activity.contains("isSystemInDarkTheme"))
        assertTrue(design.contains("tonalElevation = 0.dp"))
        assertTrue(design.contains("shadowElevation = 0.dp"))
    }

    @Test
    fun sharedPrimitivesAndFlatPrimarySurfacesArePresent() {
        val root = androidRoot()
        val primitives = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt").readText()
        listOf(
            "XdmPageHeader",
            "XdmMetricStrip",
            "XdmNoticeRow",
            "XdmGroupedList",
            "XdmListRow",
            "XdmSegmentedControl",
            "XdmFileTypeIcon",
            "XdmProgressLine",
            "XdmSectionLabel",
            "XdmTechnicalDetails",
            "XdmAdaptiveSheet",
            "XdmEmptyState",
        ).forEach { primitive ->
            val declaration = Regex("fun(?:\\s+<[^>]+>)?\\s+$primitive\\(")
            assertTrue("Missing $primitive", declaration.containsMatchIn(primitives))
        }
        assertTrue(primitives.contains("testTag(XdmTestTags.PageHeader)"))
        assertTrue(primitives.contains("sizeIn(minWidth = XdmMinimumTouchTarget, minHeight = XdmMinimumTouchTarget)"))

        val primarySources = listOf(
            "ui/downloads/DownloadRow.kt",
            "ui/downloads/DownloadsScreen.kt",
            "ui/downloads/DownloadDetails.kt",
            "ui/intake/AddDownloadSurface.kt",
            "ui/media/MediaCaptureCard.kt",
            "ui/library/MediaLibraryScreen.kt",
            "ui/settings/SettingsScreen.kt",
            "Media3PlayerScreen.kt",
        ).map { File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/$it") }
        primarySources.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} still imports elevated Material Card", text.contains("import androidx.compose.material3.Card"))
            assertFalse("${file.name} still renders Material Card", Regex("(?<![A-Za-z0-9_])Card\\(").containsMatchIn(text))
            assertTrue("${file.name} should use flat XDM surfaces", listOf("XdmFlatCard(", "XdmListCard(", "XdmGroupedList(", "Surface(", "XdmMetricStrip(", "XdmAdaptiveSheet(").any(text::contains))
        }
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
