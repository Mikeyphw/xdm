package com.mikeyphw.xdm.android.browserextension

/** Renders extension templates from the same token catalog used by the Android Compose theme. */
object XdmThemeCssGenerator {
    fun replacements(mode: BrowserExtensionSourceContract.ThemeMode): Map<String, String> {
        val tokens = XdmThemeTokenCatalog.forMode(mode)
        return linkedMapOf(
            "@@THEME_MODE@@" to mode.wireValue,
            "@@BACKGROUND@@" to tokens.background.toCssHex(),
            "@@SURFACE@@" to tokens.surface.toCssHex(),
            "@@RAISED@@" to tokens.raisedSurface.toCssHex(),
            "@@STRONG_SURFACE@@" to tokens.strongSurface.toCssHex(),
            "@@TEXT@@" to tokens.text.toCssHex(),
            "@@MUTED@@" to tokens.mutedText.toCssHex(),
            "@@PRIMARY@@" to tokens.primary.toCssHex(),
            "@@ON_PRIMARY@@" to tokens.onPrimary.toCssHex(),
            "@@PRIMARY_CONTAINER@@" to tokens.primaryContainer.toCssHex(),
            "@@ON_PRIMARY_CONTAINER@@" to tokens.onPrimaryContainer.toCssHex(),
            "@@OUTLINE@@" to tokens.outline.toCssHex(),
            "@@OUTLINE_VARIANT@@" to tokens.outlineVariant.toCssHex(),
            "@@SEPARATOR@@" to tokens.separator.toCssHex(),
            "@@SUCCESS@@" to tokens.success.toCssHex(),
            "@@SUCCESS_CONTAINER@@" to tokens.successContainer.toCssHex(),
            "@@ERROR@@" to tokens.error.toCssHex(),
            "@@ERROR_CONTAINER@@" to tokens.errorContainer.toCssHex(),
            "@@FAB_SIZE@@" to tokens.fabSizePx.toString(),
            "@@FAB_RADIUS@@" to tokens.fabCornerRadiusPx.toString(),
            "@@FAB_EDGE_INSET@@" to tokens.fabEdgeInsetPx.toString(),
            "@@FAB_ACTION_GAP@@" to tokens.fabActionGapPx.toString(),
            "@@MOTION_FAST@@" to tokens.motionFastMs.toString(),
            "@@MOTION_STANDARD@@" to tokens.motionStandardMs.toString(),
        )
    }

    fun render(template: String, mode: BrowserExtensionSourceContract.ThemeMode): String {
        val rendered = replacements(mode).entries.fold(template) { value, (token, replacement) ->
            value.replace(token, replacement)
        }
        require("@@" !in rendered) { "Unresolved shared XDM theme token" }
        return rendered
    }

    private fun Long.toCssHex(): String = "#%06X".format(this and 0x00FF_FFFF)
}
