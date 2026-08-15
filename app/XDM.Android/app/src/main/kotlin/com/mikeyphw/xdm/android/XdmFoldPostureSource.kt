package com.mikeyphw.xdm.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

data class XdmFoldLayoutInfo(
    val posture: XdmFoldPosture = XdmFoldPosture.Flat,
    val isSeparating: Boolean = false,
    val isVertical: Boolean = false,
    val hingeWidth: Dp = 0.dp,
    val hingeHeight: Dp = 0.dp,
    val hingeLeft: Dp = 0.dp,
    val hingeTop: Dp = 0.dp,
    val hingeRight: Dp = 0.dp,
    val hingeBottom: Dp = 0.dp,
)

@Composable
fun rememberXdmFoldLayoutInfo(): XdmFoldLayoutInfo {
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = context.findActivity()
    val layoutInfo by produceState(initialValue = XdmFoldLayoutInfo(), activity, density) {
        val owner = activity ?: return@produceState
        WindowInfoTracker.getOrCreate(owner)
            .windowLayoutInfo(owner)
            .collect { info ->
                val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                value = if (feature == null) {
                    XdmFoldLayoutInfo()
                } else {
                    val bounds = feature.bounds
                    XdmFoldLayoutInfo(
                        posture = feature.toXdmFoldPosture(),
                        isSeparating = feature.isSeparating,
                        isVertical = feature.orientation == FoldingFeature.Orientation.VERTICAL,
                        hingeWidth = with(density) { bounds.width().toDp() },
                        hingeHeight = with(density) { bounds.height().toDp() },
                        hingeLeft = with(density) { bounds.left.toDp() },
                        hingeTop = with(density) { bounds.top.toDp() },
                        hingeRight = with(density) { bounds.right.toDp() },
                        hingeBottom = with(density) { bounds.bottom.toDp() },
                    )
                }
            }
    }
    return layoutInfo
}

@Composable
fun rememberXdmFoldPosture(): XdmFoldPosture = rememberXdmFoldLayoutInfo().posture

internal fun FoldingFeature.toXdmFoldPosture(): XdmFoldPosture = when {
    orientation == FoldingFeature.Orientation.VERTICAL && state == FoldingFeature.State.HALF_OPENED -> XdmFoldPosture.Book
    orientation == FoldingFeature.Orientation.HORIZONTAL && state == FoldingFeature.State.HALF_OPENED -> XdmFoldPosture.Tabletop
    isSeparating -> XdmFoldPosture.SeparatingHinge
    else -> XdmFoldPosture.Flat
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
