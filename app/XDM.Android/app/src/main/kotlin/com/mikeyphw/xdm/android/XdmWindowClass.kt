package com.mikeyphw.xdm.android

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class XdmWindowClass {
    Compact,
    Medium,
    Expanded;

    val usesBottomNavigation: Boolean get() = this != Expanded
    val usesNavigationSidebar: Boolean get() = this == Expanded
    val contentHorizontalPadding: Dp
        get() = when (this) {
            Compact -> 16.dp
            Medium -> 20.dp
            Expanded -> 28.dp
        }

    companion object {
        fun fromWidth(width: Dp): XdmWindowClass = fromWidthDp(width.value)

        fun fromWidthDp(widthDp: Float): XdmWindowClass = when {
            widthDp < 600f -> Compact
            widthDp < 840f -> Medium
            else -> Expanded
        }
    }
}

enum class XdmFoldPosture {
    Flat,
    Book,
    Tabletop,
    SeparatingHinge,
}

data class XdmWindowProfile(
    val width: Dp,
    val height: Dp,
    val windowClass: XdmWindowClass = XdmWindowClass.fromWidth(width),
    val foldPosture: XdmFoldPosture = XdmFoldPosture.Flat,
    val fontScale: Float = 1f,
) {
    val isShort: Boolean get() = height < 560.dp
    val isLandscapeCompactHeight: Boolean get() = width > height && height < 520.dp
    val isLargeFont: Boolean get() = fontScale >= 1.30f
    val isVeryLargeFont: Boolean get() = fontScale >= 1.85f
    val hasSeparatingFold: Boolean get() = foldPosture == XdmFoldPosture.SeparatingHinge

    val allowsExpandedShell: Boolean
        get() = windowClass == XdmWindowClass.Expanded && !isLandscapeCompactHeight

    val usesNavigationSidebar: Boolean
        get() = allowsExpandedShell && !isVeryLargeFont

    val usesBottomNavigation: Boolean
        get() = !usesNavigationSidebar

    val downloadsListMinWidth: Dp
        get() = when {
            isVeryLargeFont -> 460.dp
            isLargeFont -> 420.dp
            else -> 360.dp
        }

    val downloadsDetailMinWidth: Dp
        get() = when {
            isVeryLargeFont -> 420.dp
            isLargeFont -> 380.dp
            else -> 340.dp
        }

    val minimumPaneGap: Dp get() = if (hasSeparatingFold) 28.dp else 16.dp

    val requiredDownloadsPaneWidth: Dp
        get() = downloadsListMinWidth + downloadsDetailMinWidth + minimumPaneGap + 40.dp

    fun allowsTwoPaneDownloadsFor(availablePaneWidth: Dp): Boolean {
        if (!allowsExpandedShell || isShort || isVeryLargeFont || hasSeparatingFold) return false
        return availablePaneWidth >= requiredDownloadsPaneWidth && availablePaneWidth >= 900.dp
    }

    val allowsTwoPaneDownloads: Boolean
        get() = allowsTwoPaneDownloadsFor(width)

    fun withAvailablePaneWidth(availablePaneWidth: Dp): XdmWindowProfile = copy(width = availablePaneWidth)

    val maxContentWidth: Dp
        get() = when {
            hasSeparatingFold -> 1260.dp
            isLargeFont -> 1180.dp
            else -> 1480.dp
        }

    val sheetMaxHeight: Dp
        get() = when {
            isLandscapeCompactHeight -> 420.dp
            isShort -> 520.dp
            isLargeFont -> 720.dp
            else -> 820.dp
        }

    val playerHeight: Dp
        get() = when {
            isLandscapeCompactHeight -> 180.dp
            isShort -> 190.dp
            isLargeFont -> 240.dp
            else -> 220.dp
        }
}

object XdmAdaptiveLayoutPolicy {
    fun profileFor(widthDp: Float, heightDp: Float, fontScale: Float = 1f, foldPosture: XdmFoldPosture = XdmFoldPosture.Flat): XdmWindowProfile = XdmWindowProfile(
        width = widthDp.dp,
        height = heightDp.dp,
        fontScale = fontScale,
        foldPosture = foldPosture,
    )

    fun twoPaneDownloadsAllowed(widthDp: Float, heightDp: Float, fontScale: Float = 1f, foldPosture: XdmFoldPosture = XdmFoldPosture.Flat): Boolean =
        profileFor(widthDp, heightDp, fontScale, foldPosture).allowsTwoPaneDownloads
}

data class XdmAdaptiveMatrixCase(
    val name: String,
    val widthDp: Float,
    val heightDp: Float,
    val fontScale: Float = 1f,
    val foldPosture: XdmFoldPosture = XdmFoldPosture.Flat,
) {
    val profile: XdmWindowProfile get() = XdmAdaptiveLayoutPolicy.profileFor(widthDp, heightDp, fontScale, foldPosture)
}

object XdmAdaptiveTestMatrix {
    val phone = XdmAdaptiveMatrixCase("phone", 393f, 851f)
    val splitScreen = XdmAdaptiveMatrixCase("split-screen", 600f, 640f)
    val threshold840 = XdmAdaptiveMatrixCase("840dp-threshold", 840f, 900f)
    val tablet = XdmAdaptiveMatrixCase("tablet", 1280f, 900f)
    val compactLandscape = XdmAdaptiveMatrixCase("compact-height-landscape", 840f, 430f)
    val largeFont = XdmAdaptiveMatrixCase("large-font-200-percent", 1180f, 820f, fontScale = 2f)
    val separatingHinge = XdmAdaptiveMatrixCase("separating-hinge", 1320f, 900f, foldPosture = XdmFoldPosture.SeparatingHinge)

    val screenshotSemanticsCases: List<XdmAdaptiveMatrixCase> = listOf(
        phone, splitScreen, threshold840, tablet, compactLandscape, largeFont, separatingHinge,
    )
}

val LocalXdmWindowClass = staticCompositionLocalOf { XdmWindowClass.Compact }
val LocalXdmWindowProfile = staticCompositionLocalOf { XdmAdaptiveLayoutPolicy.profileFor(393f, 851f) }
