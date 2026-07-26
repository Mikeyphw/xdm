package com.mikeyphw.xdm.android.browserextension

/**
 * Platform-neutral XDM visual tokens shared by Compose and the generated Firefox extension.
 *
 * Keep concrete palette values here. Android UI code converts ARGB values to Compose [Color],
 * while [XdmThemeCssGenerator] renders the same values into extension CSS and JavaScript.
 */
data class XdmThemeTokens(
    val mode: BrowserExtensionSourceContract.ThemeMode,
    val background: Long,
    val surface: Long,
    val raisedSurface: Long,
    val strongSurface: Long,
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val text: Long,
    val mutedText: Long,
    val outline: Long,
    val outlineVariant: Long,
    val separator: Long,
    val success: Long,
    val onSuccess: Long,
    val successContainer: Long,
    val onSuccessContainer: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val fabSizePx: Int = 56,
    val fabCornerRadiusPx: Int = 18,
    val fabEdgeInsetPx: Int = 16,
    val fabActionGapPx: Int = 10,
    val motionFastMs: Int = 140,
    val motionStandardMs: Int = 220,
) {
    init {
        val colors = listOf(
            background, surface, raisedSurface, strongSurface, primary, onPrimary,
            primaryContainer, onPrimaryContainer, secondary, onSecondary,
            secondaryContainer, onSecondaryContainer, tertiary, onTertiary,
            tertiaryContainer, onTertiaryContainer, text, mutedText, outline,
            outlineVariant, separator, success, onSuccess, successContainer,
            onSuccessContainer, error, onError, errorContainer, onErrorContainer,
        )
        require(colors.all { it in 0L..0xFFFF_FFFF }) { "Theme colors must be 32-bit ARGB values" }
        require(fabSizePx >= 48) { "The extension FAB must preserve a minimum touch target" }
        require(fabCornerRadiusPx in 0..fabSizePx / 2) { "Invalid FAB corner radius" }
        require(fabEdgeInsetPx >= 8 && fabActionGapPx >= 4) { "Invalid FAB spacing" }
        require(motionFastMs in 0..1000 && motionStandardMs in motionFastMs..1500) { "Invalid motion durations" }
    }
}

object XdmThemeTokenCatalog {
    val Dark: XdmThemeTokens = XdmThemeTokens(
        mode = BrowserExtensionSourceContract.ThemeMode.Dark,
        background = 0xFF090B0F,
        surface = 0xFF101318,
        raisedSurface = 0xFF161A21,
        strongSurface = 0xFF1C212A,
        primary = 0xFF7DB8FF,
        onPrimary = 0xFF003259,
        primaryContainer = 0xFF183B59,
        onPrimaryContainer = 0xFFD2E8FF,
        secondary = 0xFFAEC9E6,
        onSecondary = 0xFF163149,
        secondaryContainer = 0xFF243C53,
        onSecondaryContainer = 0xFFD8E9FA,
        tertiary = 0xFFF1C36D,
        onTertiary = 0xFF3E2E00,
        tertiaryContainer = 0xFF453510,
        onTertiaryContainer = 0xFFFFE3A7,
        text = 0xFFE8EDF5,
        mutedText = 0xFFABB4C2,
        outline = 0xFF333A46,
        outlineVariant = 0xFF262C35,
        separator = 0xFF252B35,
        success = 0xFF79D49A,
        onSuccess = 0xFF003919,
        successContainer = 0xFF153D29,
        onSuccessContainer = 0xFFB5F3C8,
        error = 0xFFFFB4AB,
        onError = 0xFF690005,
        errorContainer = 0xFF5A1F22,
        onErrorContainer = 0xFFFFDAD6,
        fabSizePx = 56,
        fabCornerRadiusPx = 18,
        fabEdgeInsetPx = 16,
        fabActionGapPx = 10,
        motionFastMs = 140,
        motionStandardMs = 220,
    )

    val Amoled: XdmThemeTokens = XdmThemeTokens(
        mode = BrowserExtensionSourceContract.ThemeMode.Amoled,
        background = 0xFF000000,
        surface = 0xFF000000,
        raisedSurface = 0xFF0B0D10,
        strongSurface = 0xFF12151A,
        primary = 0xFF7DB8FF,
        onPrimary = 0xFF003259,
        primaryContainer = 0xFF183B59,
        onPrimaryContainer = 0xFFD2E8FF,
        secondary = 0xFFAEC9E6,
        onSecondary = 0xFF163149,
        secondaryContainer = 0xFF243C53,
        onSecondaryContainer = 0xFFD8E9FA,
        tertiary = 0xFFF1C36D,
        onTertiary = 0xFF3E2E00,
        tertiaryContainer = 0xFF453510,
        onTertiaryContainer = 0xFFFFE3A7,
        text = 0xFFE8EDF5,
        mutedText = 0xFFABB4C2,
        outline = 0xFF333A46,
        outlineVariant = 0xFF1B1E24,
        separator = 0xFF191C22,
        success = 0xFF79D49A,
        onSuccess = 0xFF003919,
        successContainer = 0xFF153D29,
        onSuccessContainer = 0xFFB5F3C8,
        error = 0xFFFFB4AB,
        onError = 0xFF690005,
        errorContainer = 0xFF5A1F22,
        onErrorContainer = 0xFFFFDAD6,
        fabSizePx = 56,
        fabCornerRadiusPx = 18,
        fabEdgeInsetPx = 16,
        fabActionGapPx = 10,
        motionFastMs = 140,
        motionStandardMs = 220,
    )

    fun forMode(mode: BrowserExtensionSourceContract.ThemeMode): XdmThemeTokens = when (mode) {
        BrowserExtensionSourceContract.ThemeMode.Dark -> Dark
        BrowserExtensionSourceContract.ThemeMode.Amoled -> Amoled
    }
}
