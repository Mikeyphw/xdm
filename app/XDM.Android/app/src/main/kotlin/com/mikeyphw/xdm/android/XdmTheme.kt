package com.mikeyphw.xdm.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import com.mikeyphw.xdm.android.browserextension.XdmThemeTokenCatalog
import com.mikeyphw.xdm.android.browserextension.XdmThemeTokens

private fun XdmThemeMode.extensionMode(): BrowserExtensionSourceContract.ThemeMode = when (this) {
    XdmThemeMode.Dark -> BrowserExtensionSourceContract.ThemeMode.Dark
    XdmThemeMode.Amoled -> BrowserExtensionSourceContract.ThemeMode.Amoled
}

private fun themeTokens(mode: XdmThemeMode): XdmThemeTokens = XdmThemeTokenCatalog.forMode(mode.extensionMode())

private fun colorScheme(tokens: XdmThemeTokens) = darkColorScheme(
    primary = Color(tokens.primary),
    onPrimary = Color(tokens.onPrimary),
    primaryContainer = Color(tokens.primaryContainer),
    onPrimaryContainer = Color(tokens.onPrimaryContainer),
    inversePrimary = Color(0xFF285F8B),
    secondary = Color(tokens.secondary),
    onSecondary = Color(tokens.onSecondary),
    secondaryContainer = Color(tokens.secondaryContainer),
    onSecondaryContainer = Color(tokens.onSecondaryContainer),
    tertiary = Color(tokens.tertiary),
    onTertiary = Color(tokens.onTertiary),
    tertiaryContainer = Color(tokens.tertiaryContainer),
    onTertiaryContainer = Color(tokens.onTertiaryContainer),
    background = Color(tokens.background),
    onBackground = Color(tokens.text),
    surface = Color(tokens.surface),
    onSurface = Color(tokens.text),
    surfaceVariant = Color(tokens.raisedSurface),
    onSurfaceVariant = Color(tokens.mutedText),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE4E8EF),
    inverseOnSurface = Color(0xFF25282E),
    error = Color(tokens.error),
    onError = Color(tokens.onError),
    errorContainer = Color(tokens.errorContainer),
    onErrorContainer = Color(tokens.onErrorContainer),
    outline = Color(tokens.outline),
    outlineVariant = Color(tokens.outlineVariant),
    scrim = Color.Black,
)

private val XdmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Immutable
data class XdmExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val groupedSurface: Color,
    val groupedSurfaceStrong: Color,
    val separator: Color,
)

private fun extendedColors(tokens: XdmThemeTokens) = XdmExtendedColors(
    success = Color(tokens.success),
    onSuccess = Color(tokens.onSuccess),
    successContainer = Color(tokens.successContainer),
    onSuccessContainer = Color(tokens.onSuccessContainer),
    warning = Color(tokens.tertiary),
    onWarning = Color(tokens.onTertiary),
    warningContainer = Color(tokens.tertiaryContainer),
    onWarningContainer = Color(tokens.onTertiaryContainer),
    info = Color(tokens.primary),
    onInfo = Color(tokens.onPrimary),
    groupedSurface = Color(tokens.raisedSurface),
    groupedSurfaceStrong = Color(tokens.strongSurface),
    separator = Color(tokens.separator),
)

private val LocalXdmExtendedColors = staticCompositionLocalOf { extendedColors(XdmThemeTokenCatalog.Dark) }

object XdmTheme {
    val extendedColors: XdmExtendedColors
        @Composable get() = LocalXdmExtendedColors.current
}

@Composable
fun XdmTheme(
    mode: XdmThemeMode = XdmThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val tokens = themeTokens(mode)
    androidx.compose.runtime.CompositionLocalProvider(LocalXdmExtendedColors provides extendedColors(tokens)) {
        MaterialTheme(
            colorScheme = colorScheme(tokens),
            typography = XdmTypography,
            shapes = XdmShapes,
            content = content,
        )
    }
}
