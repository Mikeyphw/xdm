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

enum class XdmFoldSafePaneEdge { Start, End, Top, Bottom }

data class XdmVerticalHingeSplit(
    val leftPaneWidth: Dp,
    val hingeGap: Dp,
    val rightPaneWidth: Dp,
)

data class XdmFoldSafePane(
    val edge: XdmFoldSafePaneEdge,
    val maxWidth: Dp,
    val maxHeight: Dp,
)

data class XdmWindowProfile(
    val width: Dp,
    val height: Dp,
    val windowClass: XdmWindowClass = XdmWindowClass.fromWidth(width),
    val foldPosture: XdmFoldPosture = XdmFoldPosture.Flat,
    val fontScale: Float = 1f,
    val foldIsSeparating: Boolean = foldPosture == XdmFoldPosture.SeparatingHinge,
    val foldIsVertical: Boolean = foldPosture == XdmFoldPosture.Book || foldPosture == XdmFoldPosture.SeparatingHinge,
    val foldHingeWidth: Dp = 0.dp,
    val foldHingeHeight: Dp = 0.dp,
    val foldHingeLeft: Dp = 0.dp,
    val foldHingeTop: Dp = 0.dp,
    val foldHingeRight: Dp = 0.dp,
    val foldHingeBottom: Dp = 0.dp,
) {
    val isShort: Boolean get() = height < 560.dp
    val isLandscapeCompactHeight: Boolean get() = width > height && height < 520.dp
    val isLargeFont: Boolean get() = fontScale >= 1.30f
    val isVeryLargeFont: Boolean get() = fontScale >= 1.85f
    val hasSeparatingFold: Boolean get() = foldIsSeparating
    val hasVerticalSeparatingFold: Boolean get() = hasSeparatingFold && foldIsVertical
    val hasHorizontalSeparatingFold: Boolean get() = hasSeparatingFold && !foldIsVertical

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

    val minimumPaneGap: Dp
        get() = if (hasVerticalSeparatingFold) maxOf(28.dp, foldHingeWidth + 16.dp) else 16.dp

    val requiredDownloadsPaneWidth: Dp
        get() = downloadsListMinWidth + downloadsDetailMinWidth + minimumPaneGap + 40.dp

    fun allowsTwoPaneDownloadsFor(availablePaneWidth: Dp): Boolean {
        if (!allowsExpandedShell || isShort || isVeryLargeFont) return false
        if (foldPosture == XdmFoldPosture.Tabletop || hasHorizontalSeparatingFold) return false
        if (hasVerticalSeparatingFold && (foldHingeRight <= foldHingeLeft || foldHingeWidth <= 0.dp)) return false
        return availablePaneWidth >= requiredDownloadsPaneWidth && availablePaneWidth >= 900.dp
    }

    /**
     * Resolve a vertical separating hinge against a measured Compose pane in window coordinates.
     * Returning null is fail-closed: the caller must use the single-pane layout rather than guess
     * where a physical hinge sits.
     */
    fun verticalHingeSplitFor(containerLeftInWindow: Dp, containerWidth: Dp): XdmVerticalHingeSplit? {
        if (!hasVerticalSeparatingFold || foldHingeRight <= foldHingeLeft || containerWidth <= 0.dp) return null
        val containerRight = containerLeftInWindow + containerWidth
        if (foldHingeLeft <= containerLeftInWindow || foldHingeRight >= containerRight) return null
        val clearance = 8.dp
        val left = (foldHingeLeft - containerLeftInWindow - clearance).coerceAtLeast(0.dp)
        val right = (containerRight - foldHingeRight - clearance).coerceAtLeast(0.dp)
        val gap = (foldHingeRight - foldHingeLeft + clearance * 2).coerceAtLeast(foldHingeWidth)
        if (left < downloadsListMinWidth || right < downloadsDetailMinWidth) return null
        return XdmVerticalHingeSplit(left, gap, right)
    }

    /** A separating fold constrains modal content to one physical pane instead of straddling it. */
    fun preferredFoldSafePane(): XdmFoldSafePane? {
        if (!hasSeparatingFold) return null
        val margin = 16.dp
        return if (foldIsVertical) {
            if (foldHingeRight <= foldHingeLeft) return null
            val leftWidth = (foldHingeLeft - margin * 2).coerceAtLeast(0.dp)
            val rightWidth = (width - foldHingeRight - margin * 2).coerceAtLeast(0.dp)
            if (leftWidth <= 0.dp && rightWidth <= 0.dp) return null
            XdmFoldSafePane(
                edge = if (rightWidth > leftWidth) XdmFoldSafePaneEdge.End else XdmFoldSafePaneEdge.Start,
                maxWidth = maxOf(leftWidth, rightWidth),
                maxHeight = (height - margin * 2).coerceAtLeast(0.dp),
            )
        } else {
            if (foldHingeBottom <= foldHingeTop) return null
            val topHeight = (foldHingeTop - margin * 2).coerceAtLeast(0.dp)
            val bottomHeight = (height - foldHingeBottom - margin * 2).coerceAtLeast(0.dp)
            if (topHeight <= 0.dp && bottomHeight <= 0.dp) return null
            XdmFoldSafePane(
                edge = if (bottomHeight > topHeight) XdmFoldSafePaneEdge.Bottom else XdmFoldSafePaneEdge.Top,
                maxWidth = (width - margin * 2).coerceAtLeast(0.dp),
                maxHeight = maxOf(topHeight, bottomHeight),
            )
        }
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
    fun profileFor(
        widthDp: Float,
        heightDp: Float,
        fontScale: Float = 1f,
        foldPosture: XdmFoldPosture = XdmFoldPosture.Flat,
        foldIsSeparating: Boolean = foldPosture == XdmFoldPosture.SeparatingHinge,
        foldIsVertical: Boolean = foldPosture == XdmFoldPosture.Book || foldPosture == XdmFoldPosture.SeparatingHinge,
        foldHingeWidthDp: Float = 0f,
        foldHingeHeightDp: Float = 0f,
        foldHingeLeftDp: Float = 0f,
        foldHingeTopDp: Float = 0f,
        foldHingeRightDp: Float = 0f,
        foldHingeBottomDp: Float = 0f,
    ): XdmWindowProfile = XdmWindowProfile(
        width = widthDp.dp,
        height = heightDp.dp,
        fontScale = fontScale,
        foldPosture = foldPosture,
        foldIsSeparating = foldIsSeparating,
        foldIsVertical = foldIsVertical,
        foldHingeWidth = foldHingeWidthDp.dp,
        foldHingeHeight = foldHingeHeightDp.dp,
        foldHingeLeft = foldHingeLeftDp.dp,
        foldHingeTop = foldHingeTopDp.dp,
        foldHingeRight = foldHingeRightDp.dp,
        foldHingeBottom = foldHingeBottomDp.dp,
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
    val foldIsSeparating: Boolean = foldPosture == XdmFoldPosture.SeparatingHinge,
    val foldIsVertical: Boolean = foldPosture == XdmFoldPosture.Book || foldPosture == XdmFoldPosture.SeparatingHinge,
    val foldHingeWidthDp: Float = 0f,
    val foldHingeHeightDp: Float = 0f,
    val foldHingeLeftDp: Float = 0f,
    val foldHingeTopDp: Float = 0f,
    val foldHingeRightDp: Float = 0f,
    val foldHingeBottomDp: Float = 0f,
) {
    val profile: XdmWindowProfile get() = XdmAdaptiveLayoutPolicy.profileFor(
        widthDp, heightDp, fontScale, foldPosture, foldIsSeparating, foldIsVertical,
        foldHingeWidthDp, foldHingeHeightDp, foldHingeLeftDp, foldHingeTopDp, foldHingeRightDp, foldHingeBottomDp,
    )
}

object XdmAdaptiveTestMatrix {
    val phone = XdmAdaptiveMatrixCase("phone", 393f, 851f)
    val splitScreen = XdmAdaptiveMatrixCase("split-screen", 600f, 640f)
    val threshold840 = XdmAdaptiveMatrixCase("840dp-threshold", 840f, 900f)
    val tablet = XdmAdaptiveMatrixCase("tablet", 1280f, 900f)
    val compactLandscape = XdmAdaptiveMatrixCase("compact-height-landscape", 840f, 430f)
    val largeFont = XdmAdaptiveMatrixCase("large-font-200-percent", 1180f, 820f, fontScale = 2f)
    val separatingHinge = XdmAdaptiveMatrixCase(
        "separating-hinge", 1320f, 900f,
        foldPosture = XdmFoldPosture.SeparatingHinge,
        foldIsSeparating = true,
        foldIsVertical = true,
        foldHingeWidthDp = 48f,
        foldHingeLeftDp = 520f,
        foldHingeRightDp = 568f,
        foldHingeBottomDp = 900f,
    )

    val screenshotSemanticsCases: List<XdmAdaptiveMatrixCase> = listOf(
        phone, splitScreen, threshold840, tablet, compactLandscape, largeFont, separatingHinge,
    )
}

val LocalXdmWindowClass = staticCompositionLocalOf { XdmWindowClass.Compact }
val LocalXdmWindowProfile = staticCompositionLocalOf { XdmAdaptiveLayoutPolicy.profileFor(393f, 851f) }
