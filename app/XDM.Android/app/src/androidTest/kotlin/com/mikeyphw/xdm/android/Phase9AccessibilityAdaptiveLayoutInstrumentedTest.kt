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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase9AccessibilityAdaptiveLayoutInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeScreenshotAndSemanticsMatrixCoversPhoneSplitThresholdTabletLandscapeLargeFontAndHinge() {
        val cases = XdmAdaptiveTestMatrix.screenshotSemanticsCases
        val activeCase = mutableStateOf(cases.first())

        composeRule.setContent {
            val case = activeCase.value
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

        cases.forEach { case ->
            composeRule.runOnIdle { activeCase.value = case }
            composeRule.waitForIdle()
            composeRule.onRoot().captureToImage()
            val shellTag = when (case.profile.windowClass) {
                XdmWindowClass.Compact -> XdmScreenTags.ShellCompact
                XdmWindowClass.Medium -> XdmScreenTags.ShellMedium
                XdmWindowClass.Expanded -> XdmScreenTags.ShellExpanded
            }
            composeRule.onNodeWithTag(shellTag).assertIsDisplayed()
            assertTrue(Phase9AccessibilityRegressionContracts.coversScreenshotCase(case.name))
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun adaptiveSheetRequestsFocusAndRestoresFocusToOpener() {
        lateinit var controller: XdmFocusRestorationController
        lateinit var inputModeManager: InputModeManager

        composeRule.setContent {
            controller = rememberXdmFocusRestorationController()
            inputModeManager = LocalInputModeManager.current
            var visible by remember { mutableStateOf(false) }
            XdmTheme {
                CompositionLocalProvider(LocalXdmFocusRestorationController provides controller) {
                    Column(Modifier.fillMaxSize()) {
                        Button(
                            onClick = { visible = true },
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

        val opener = composeRule.onNodeWithText("Open sheet")

        // This is a keyboard/DPAD/switch-access focus contract. Material clickables
        // are not keyboard-focusable while Android reports touch input mode, so make
        // the test input modality explicit before requesting focus. Do not force this
        // mode in production: touch users should remain in touch mode.
        composeRule.runOnIdle {
            assertTrue(
                "instrumentation could not enter keyboard input mode",
                inputModeManager.requestInputMode(InputMode.Keyboard),
            )
        }
        composeRule.waitForIdle()

        // Model the actual accessibility contract: the opener really owns focus
        // before the modal is shown. xdmFocusRestorePoint observes that focus and
        // records it as the restoration target; the test must not fake controller
        // state with markLastFocused().
        opener.requestFocus()
        opener.assertIsFocused()
        composeRule.runOnIdle {
            assertTrue(
                "focus restoration controller did not observe the real opener focus: ${controller.debugSummary()}",
                controller.hasFocusedRestoreTarget("open-sheet"),
            )
        }
        opener.performClick()

        composeRule.onNodeWithTag(XdmTestTags.AdaptiveSheet).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Focusable actions bottom sheet").assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitUntilDoesNotExist(
            hasTestTag(XdmTestTags.AdaptiveSheet),
            timeoutMillis = 5_000L,
        )
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(
                "sheet dismissal did not publish a focus restoration transaction: ${controller.debugSummary()}",
                controller.restoreRequestGeneration > 0L,
            )
        }
        try {
            // The controller intentionally crosses Android host-view traversal
            // boundaries after the modal window is gone. Wait for the observable
            // focus condition instead of assuming waitForIdle owns View.postOnAnimation.
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                runCatching { opener.assertIsFocused() }.isSuccess
            }
            opener.assertIsFocused()
        } catch (failure: Throwable) {
            val diagnostics = composeRule.runOnIdle { controller.debugSummary() }
            throw AssertionError("opener focus restoration failed; $diagnostics", failure)
        }
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
