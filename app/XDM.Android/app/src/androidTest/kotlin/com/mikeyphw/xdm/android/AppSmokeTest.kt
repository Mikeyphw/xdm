package com.mikeyphw.xdm.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun downloadsShellIsVisible() {
        composeRule.onAllNodesWithText("Downloads").onFirst().assertIsDisplayed()
    }
}
