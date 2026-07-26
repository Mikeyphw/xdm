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

val LocalXdmWindowClass = staticCompositionLocalOf { XdmWindowClass.Compact }
