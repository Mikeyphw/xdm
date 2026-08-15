package com.mikeyphw.xdm.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase9AccessibilityGapClosurePolicyTest {
    @Test
    fun matrixNamesCoverEveryRoadmapConfiguration() {
        assertEquals(
            setOf("phone", "split-screen", "840dp-threshold", "tablet", "compact-height-landscape", "large-font-200-percent", "separating-hinge"),
            Phase9AccessibilityRegressionContracts.composeScreenshotSemanticsMatrix.toSet(),
        )
    }

    @Test
    fun verticalSeparatingHingeUsesRealGapAndCanKeepTwoPaneDownloads() {
        val profile = XdmAdaptiveTestMatrix.separatingHinge.profile
        assertTrue(profile.hasSeparatingFold)
        assertTrue(profile.hasVerticalSeparatingFold)
        assertEquals(64f, profile.minimumPaneGap.value, 0.01f)
        assertTrue(profile.allowsTwoPaneDownloadsFor(profile.width))
        val split = profile.verticalHingeSplitFor(containerLeftInWindow = 0f.dp, containerWidth = profile.width)
        assertTrue(split != null)
        assertEquals(512f, split!!.leftPaneWidth.value, 0.01f)
        assertEquals(64f, split.hingeGap.value, 0.01f)
        assertEquals(744f, split.rightPaneWidth.value, 0.01f)
        assertEquals(XdmFoldSafePaneEdge.End, profile.preferredFoldSafePane()?.edge)
    }

    @Test
    fun tabletopPostureAvoidsSideBySideDownloadPanes() {
        val profile = XdmAdaptiveLayoutPolicy.profileFor(
            widthDp = 1320f,
            heightDp = 900f,
            foldPosture = XdmFoldPosture.Tabletop,
            foldIsSeparating = true,
            foldIsVertical = false,
            foldHingeHeightDp = 42f,
            foldHingeTopDp = 420f,
            foldHingeBottomDp = 462f,
            foldHingeRightDp = 1320f,
        )
        assertTrue(profile.hasHorizontalSeparatingFold)
        assertFalse(profile.allowsTwoPaneDownloadsFor(profile.width))
        assertEquals(XdmFoldSafePaneEdge.Bottom, profile.preferredFoldSafePane()?.edge)
    }

    @Test
    fun measuredPaneWidthControlsTwoPaneEligibility() {
        val tablet = XdmAdaptiveTestMatrix.tablet.profile
        assertTrue(tablet.allowsTwoPaneDownloadsFor(1280f.dp))
        assertFalse(tablet.allowsTwoPaneDownloadsFor(620f.dp))
    }

    @Test
    fun traversalOrderIsExplicitForKeyboardDpadAndSwitchAccess() {
        assertEquals(
            listOf("navigation", "content", "download-list", "download-detail", "sheet-or-dialog", "player-controls"),
            XdmTraversalOrder.keyboardDpadSwitchAccessOrder,
        )
        assertTrue(XdmTraversalOrder.Navigation < XdmTraversalOrder.Content)
        assertTrue(XdmTraversalOrder.List < XdmTraversalOrder.Detail)
        assertTrue(XdmTraversalOrder.Sheet < XdmTraversalOrder.PlayerControls)
    }

    @Test
    fun contrastGateCoversRoadmapSurfaces() {
        assertEquals(setOf("status", "warning", "progress", "disabled", "selected"), XdmContrastPolicy.requiredSurfaceNames())
        assertTrue(XdmContrastPolicy.passesNormalText(Color.Black, Color.White))
        assertFalse(XdmContrastPolicy.passesNormalText(Color(0xFF777777), Color(0xFF888888)))
    }
}
