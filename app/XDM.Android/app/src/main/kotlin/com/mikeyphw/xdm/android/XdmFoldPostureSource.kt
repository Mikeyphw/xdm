package com.mikeyphw.xdm.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

@Composable
fun rememberXdmFoldPosture(): XdmFoldPosture {
    val context = LocalContext.current
    val activity = context.findActivity()
    val posture by produceState(initialValue = XdmFoldPosture.Flat, activity) {
        val owner = activity ?: return@produceState
        WindowInfoTracker.getOrCreate(owner)
            .windowLayoutInfo(owner)
            .collect { info ->
                value = info.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()
                    ?.toXdmFoldPosture()
                    ?: XdmFoldPosture.Flat
            }
    }
    return posture
}

internal fun FoldingFeature.toXdmFoldPosture(): XdmFoldPosture = when {
    isSeparating -> XdmFoldPosture.SeparatingHinge
    orientation == FoldingFeature.Orientation.VERTICAL && state == FoldingFeature.State.HALF_OPENED -> XdmFoldPosture.Book
    orientation == FoldingFeature.Orientation.HORIZONTAL && state == FoldingFeature.State.HALF_OPENED -> XdmFoldPosture.Tabletop
    else -> XdmFoldPosture.Flat
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
