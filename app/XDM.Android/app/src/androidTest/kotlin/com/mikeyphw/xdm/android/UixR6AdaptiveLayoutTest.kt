package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class UixR6AdaptiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactUsesBottomNavigationWithAccessibleNewDownloadAction() {
        composeRule.setContent { shell(XdmWindowClass.Compact) }
        assertBottomNavigationShell(XdmScreenTags.ShellCompact)
    }

    @Test
    fun mediumUsesBottomNavigationWithAccessibleNewDownloadAction() {
        composeRule.setContent { shell(XdmWindowClass.Medium) }
        assertBottomNavigationShell(XdmScreenTags.ShellMedium)
    }

    @Test
    fun expandedUsesPersistentSidebarAndBoundedContentCanvas() {
        composeRule.setContent { shell(XdmWindowClass.Expanded) }
        composeRule.onNodeWithTag(XdmScreenTags.ShellExpanded).assertExists()
        composeRule.onNodeWithTag(XdmScreenTags.NavigationSidebar).assertIsDisplayed()
        composeRule.onNodeWithTag(XdmScreenTags.BottomNavigation).assertDoesNotExist()
        composeRule.onNodeWithTag(XdmScreenTags.ContentCanvas).assertExists()
    }

    @Test
    fun adaptiveAddSurfaceHasDistinctPhoneAndExpandedSemantics() {
        composeRule.setContent {
            XdmTheme {
                XdmAdaptiveSheet(
                    visible = true,
                    windowClass = XdmWindowClass.Compact,
                    onDismissRequest = {},
                    title = "New download",
                ) { Text("Review download") }
            }
        }
        composeRule.onNodeWithTag(XdmTestTags.AdaptiveSheet).assertExists()
        composeRule.onAllNodesWithContentDescription("New download bottom sheet").onFirst().assertExists()
    }

    @Test
    fun sharedLayoutsRemainReadableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                XdmTheme {
                    Column(Modifier.fillMaxSize()) {
                        XdmPageHeader(
                            title = "Downloads with a deliberately long translated heading",
                            subtitle = "A long subtitle must wrap instead of colliding with actions or disappearing.",
                            actions = { Text("Action") },
                        )
                        XdmMetricStrip(
                            listOf(
                                XdmMetric("Active transfers", "12"),
                                XdmMetric("Combined speed", "125.4 MB/s"),
                                XdmMetric("Time remaining", "2 hours 18 minutes"),
                                XdmMetric("Queued work", "37"),
                            ),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(XdmTestTags.PageHeader).assertIsDisplayed()
        composeRule.onNodeWithTag(XdmTestTags.MetricStrip).assertIsDisplayed()
    }


    @Test
    fun emptyAndErrorStatesExposeReadableSemantics() {
        composeRule.setContent {
            XdmTheme {
                Column(Modifier.fillMaxSize()) {
                    XdmEmptyState(
                        title = "Nothing queued",
                        description = "Add a link to start your first download.",
                    )
                    XdmNoticeRow(
                        text = "The destination is unavailable. Choose another folder.",
                        tone = XdmStatusTone.Error,
                        icon = Icons.Rounded.Warning,
                    )
                }
            }
        }
        composeRule.onNodeWithTag(XdmTestTags.EmptyState).assertIsDisplayed()
        composeRule.onNodeWithTag(XdmTestTags.NoticeRow).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("The destination is unavailable. Choose another folder.")
            .onFirst()
            .assertExists()
    }

    private fun assertBottomNavigationShell(shellTag: String) {
        composeRule.onNodeWithTag(shellTag).assertExists()
        composeRule.onNodeWithTag(XdmScreenTags.BottomNavigation).assertIsDisplayed()
        composeRule.onNodeWithTag(XdmScreenTags.NavigationSidebar).assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription("New download").onFirst()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Composable
    private fun shell(windowClass: XdmWindowClass) {
        XdmTheme {
            XdmAdaptiveShell(
                windowClass = windowClass,
                selectedRoute = AppRoute.Downloads,
                destinations = listOf(AppRoute.Downloads, AppRoute.Media, AppRoute.Library, AppRoute.Activity, AppRoute.Settings),
                activeTransferCount = 2,
                queuedTransferCount = 4,
                runtimeLabel = "Native engine ready",
                onNavigate = {},
                onAddDownload = {},
            ) { Text("Downloads content") }
        }
    }
}
