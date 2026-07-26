package com.mikeyphw.xdm.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
        composeRule.openDownloads()
        composeRule.onNodeWithTag(XdmScreenTags.Downloads).assertIsDisplayed()
    }

    @Test
    fun addDownloadIsReachableAndBackReturnsToDownloads() {
        composeRule.openDownloads()
        composeRule.openAddDownload()
        composeRule.onNodeWithText("New download").assertIsDisplayed()
        composeRule.onNodeWithTag(XdmScreenTags.AddDownload).assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onNodeWithTag(XdmScreenTags.Downloads).assertIsDisplayed()
    }

    @Test
    fun settingsIsAVisiblePrimaryDestination() {
        composeRule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
        composeRule.onNodeWithTag(XdmScreenTags.Settings).assertIsDisplayed()
        composeRule.onAllNodesWithText("Settings").onFirst().assertIsDisplayed()
    }

    @Test
    fun addDownloadAcceptsUrlWithoutManualFilename() {
        composeRule.openDownloads()
        composeRule.openAddDownload()

        composeRule.onNode(hasText("Download link") and hasSetTextAction()).performTextInput("https://example.com/releases/app.apk")
        composeRule.onNodeWithText("Optional. XDM infers a name from the link when left empty.").assertIsDisplayed()
        composeRule.onNodeWithText("Review download").assertIsEnabled()
    }

    private fun ComposeTestRule.openDownloads() {
        onAllNodesWithContentDescription("Downloads").onFirst().performClick()
    }

    private fun ComposeTestRule.openAddDownload() {
        onAllNodesWithContentDescription("New download").onFirst().performClick()
    }
}
