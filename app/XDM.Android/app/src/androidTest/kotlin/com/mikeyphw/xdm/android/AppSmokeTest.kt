package com.mikeyphw.xdm.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun downloadsShellIsVisible() {
        composeRule.onAllNodesWithText("Downloads").onFirst().assertIsDisplayed()
    }

    @Test
    fun addDownloadIsReachableAndBackReturnsToDownloads() {
        composeRule.openAddDownload()
        composeRule.onNodeWithText("New download").assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onAllNodesWithText("Downloads").onFirst().assertIsDisplayed()
    }

    @Test
    fun overflowRouteShowsSelectedState() {
        composeRule.onNodeWithContentDescription("More sections").performClick()
        composeRule.onNodeWithText("Settings").performClick()

        composeRule.onAllNodesWithText("Settings").onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More sections, Settings selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More sections, Settings selected").performClick()
        composeRule.onNodeWithText("Settings selected").assertIsDisplayed()
    }

    @Test
    fun addDownloadAcceptsUrlWithoutManualFilename() {
        composeRule.openAddDownload()

        composeRule.onNode(hasText("URL") and hasSetTextAction()).performTextInput("https://example.com/releases/app.apk")
        composeRule.onNodeWithText("Optional. XDM will infer a name from the URL when this is empty.").assertIsDisplayed()
        composeRule.onNodeWithText("Add to Default queue").assertIsEnabled()
    }

    private fun ComposeTestRule.openAddDownload() {
        onNodeWithContentDescription("Add download").performClick()
    }
}
