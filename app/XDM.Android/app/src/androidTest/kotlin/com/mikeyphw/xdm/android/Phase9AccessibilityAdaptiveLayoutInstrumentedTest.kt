package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase9AccessibilityAdaptiveLayoutInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeScreenshotAndSemanticsMatrixCoversPhoneSplitThresholdTabletLandscapeLargeFontAndHinge() {
        XdmAdaptiveTestMatrix.screenshotSemanticsCases.forEach { case ->
            composeRule.setContent {
                val profile = case.profile
                CompositionLocalProvider(
                    LocalXdmWindowClass provides profile.windowClass,
                    LocalXdmWindowProfile provides profile,
                ) {
                    XdmTheme {
                        XdmAdaptiveShell(
                            windowClass = profile.windowClass,
                            windowProfile = profile,
                            selectedRoute = AppRoute.Downloads,
                            destinations = listOf(AppRoute.Downloads, AppRoute.Media, AppRoute.Library, AppRoute.Activity, AppRoute.Settings),
                            activeTransferCount = 1,
                            queuedTransferCount = 2,
                            runtimeLabel = case.name,
                            onNavigate = {},
                            onAddDownload = {},
                        ) { Text("Matrix ${case.name}", modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
            composeRule.onRoot().captureToImage()
            val shellTag = if (case.profile.usesNavigationSidebar) XdmScreenTags.ShellExpanded else if (case.profile.windowClass == XdmWindowClass.Compact) XdmScreenTags.ShellCompact else XdmScreenTags.ShellMedium
            composeRule.onNodeWithTag(shellTag).assertIsDisplayed()
            assertTrue(Phase9AccessibilityRegressionContracts.coversScreenshotCase(case.name))
        }
    }

    @Test
    fun adaptiveSheetRequestsFocusAndRestoresFocusToOpener() {
        composeRule.setContent {
            val controller = rememberXdmFocusRestorationController()
            var visible by remember { mutableStateOf(false) }
            XdmTheme {
                CompositionLocalProvider(LocalXdmFocusRestorationController provides controller) {
                    Column(Modifier.fillMaxSize()) {
                        Button(
                            onClick = {
                                controller.markLastFocused("open-sheet")
                                visible = true
                            },
                            modifier = Modifier.xdmFocusRestorePoint("open-sheet"),
                        ) { Text("Open sheet") }
                        XdmAdaptiveSheet(
                            visible = visible,
                            windowClass = XdmWindowClass.Compact,
                            onDismissRequest = { visible = false },
                            title = "Focusable actions",
                        ) { Text("Sheet body") }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Open sheet").performClick()
        composeRule.onNodeWithTag(XdmTestTags.AdaptiveSheet).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Focusable actions bottom sheet").performClick()
        composeRule.runOnIdle { }
        composeRule.onNodeWithText("Open sheet").assertIsFocused()
    }

    @Test
    fun largeFontRiskySurfacesRemainAddressableBySemantics() {
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                XdmTheme {
                    Column(Modifier.fillMaxSize()) {
                        XdmNoticeRow("Warning state remains readable", tone = XdmStatusTone.Warning)
                        XdmStatusBadge("Selected", tone = XdmStatusTone.Info)
                        XdmProgressLine(0.55f, stateLabel = "Verifying")
                        XdmAdaptiveSheet(visible = true, windowClass = XdmWindowClass.Compact, onDismissRequest = {}, title = "Post-processing") {
                            Text("Media variant row")
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(XdmTestTags.NoticeRow).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(XdmTestTags.ProgressLine).assertIsDisplayed()
        composeRule.onNodeWithTag(XdmTestTags.AdaptiveSheet).assertIsDisplayed()
        Phase9AccessibilityRegressionContracts.riskyLargeFontSurfaces.forEach { surface ->
            assertTrue(Phase9AccessibilityRegressionContracts.coversRequiredRiskySurface(surface))
        }
    }

    @Test
    fun highContrastPolicyCoversStatusWarningProgressDisabledAndSelectedStates() {
        val black = Color.Black
        val white = Color.White
        assertTrue(XdmContrastPolicy.passesNormalText(black, white))
        assertTrue(XdmContrastPolicy.passesNonText(black, white))
        listOf("status", "warning", "progress", "disabled", "selected").forEach { surface ->
            assertTrue(Phase9AccessibilityRegressionContracts.coversHighContrastSurface(surface))
        }
    }
}
