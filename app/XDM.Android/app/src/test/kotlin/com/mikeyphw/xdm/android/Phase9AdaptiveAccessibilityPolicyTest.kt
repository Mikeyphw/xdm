package com.mikeyphw.xdm.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase9AdaptiveAccessibilityPolicyTest {
    @Test
    fun twoPaneDownloadsRequireEnoughWidthHeightAndNoSeparatingHinge() {
        assertTrue(XdmAdaptiveLayoutPolicy.twoPaneDownloadsAllowed(widthDp = 1180f, heightDp = 820f))
        assertFalse(XdmAdaptiveLayoutPolicy.twoPaneDownloadsAllowed(widthDp = 840f, heightDp = 820f))
        assertFalse(XdmAdaptiveLayoutPolicy.twoPaneDownloadsAllowed(widthDp = 1180f, heightDp = 480f))
        assertFalse(XdmAdaptiveLayoutPolicy.twoPaneDownloadsAllowed(widthDp = 1180f, heightDp = 820f, foldPosture = XdmFoldPosture.SeparatingHinge))
        assertFalse(XdmAdaptiveLayoutPolicy.twoPaneDownloadsAllowed(widthDp = 1180f, heightDp = 820f, fontScale = 2.0f))
    }

    @Test
    fun accessibilityPolicyPreventsTinyTargetsAndByteSpam() {
        assertTrue(XdmAccessibilityPolicy.touchTargetPasses(48, 48))
        assertFalse(XdmAccessibilityPolicy.touchTargetPasses(44, 48))
        assertTrue(XdmAccessibilityPolicy.shouldUsePoliteLiveRegion("Downloading", "Completed"))
        assertFalse(XdmAccessibilityPolicy.shouldUsePoliteLiveRegion("Downloading", "1234 bytes downloaded"))
        assertTrue(XdmAccessibilityPolicy.bottomNavigationSurvivesLargeFont(itemCount = 5, fontScale = 2.0f))
        assertFalse(XdmAccessibilityPolicy.bottomNavigationSurvivesLargeFont(itemCount = 6, fontScale = 2.0f))
    }
}
