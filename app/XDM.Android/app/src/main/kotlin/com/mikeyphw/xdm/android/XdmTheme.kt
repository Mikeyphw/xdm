package com.mikeyphw.xdm.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val XdmBackground = Color(0xFF090B0F)
private val XdmSurface = Color(0xFF101318)
private val XdmSurfaceRaised = Color(0xFF161A21)
private val XdmSurfaceStrong = Color(0xFF1C212A)
private val XdmBlue = Color(0xFF7DB8FF)
private val XdmBlueContainer = Color(0xFF183B59)
private val XdmGreen = Color(0xFF79D49A)
private val XdmGreenContainer = Color(0xFF153D29)
private val XdmAmber = Color(0xFFF1C36D)
private val XdmAmberContainer = Color(0xFF453510)
private val XdmRose = Color(0xFFFFB4AB)
private val XdmRoseContainer = Color(0xFF5A1F22)
private val XdmText = Color(0xFFE8EDF5)
private val XdmTextMuted = Color(0xFFABB4C2)
private val XdmOutline = Color(0xFF333A46)

private val XdmDarkColorScheme = darkColorScheme(
    primary = XdmBlue,
    onPrimary = Color(0xFF003259),
    primaryContainer = XdmBlueContainer,
    onPrimaryContainer = Color(0xFFD2E8FF),
    inversePrimary = Color(0xFF285F8B),
    secondary = Color(0xFFAEC9E6),
    onSecondary = Color(0xFF163149),
    secondaryContainer = Color(0xFF243C53),
    onSecondaryContainer = Color(0xFFD8E9FA),
    tertiary = XdmAmber,
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = XdmAmberContainer,
    onTertiaryContainer = Color(0xFFFFE3A7),
    background = XdmBackground,
    onBackground = XdmText,
    surface = XdmSurface,
    onSurface = XdmText,
    surfaceVariant = XdmSurfaceRaised,
    onSurfaceVariant = XdmTextMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE4E8EF),
    inverseOnSurface = Color(0xFF25282E),
    error = XdmRose,
    onError = Color(0xFF690005),
    errorContainer = XdmRoseContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = XdmOutline,
    outlineVariant = Color(0xFF262C35),
    scrim = Color.Black,
)

private val XdmAmoledColorScheme = XdmDarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF0B0D10),
    outlineVariant = Color(0xFF1B1E24),
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

private val XdmDarkExtendedColors = XdmExtendedColors(
    success = XdmGreen,
    onSuccess = Color(0xFF003919),
    successContainer = XdmGreenContainer,
    onSuccessContainer = Color(0xFFB5F3C8),
    warning = XdmAmber,
    onWarning = Color(0xFF3E2E00),
    warningContainer = XdmAmberContainer,
    onWarningContainer = Color(0xFFFFE3A7),
    info = XdmBlue,
    onInfo = Color(0xFF003259),
    groupedSurface = XdmSurfaceRaised,
    groupedSurfaceStrong = XdmSurfaceStrong,
    separator = Color(0xFF252B35),
)

private val XdmAmoledExtendedColors = XdmDarkExtendedColors.copy(
    groupedSurface = Color(0xFF0B0D10),
    groupedSurfaceStrong = Color(0xFF12151A),
    separator = Color(0xFF191C22),
)

private val LocalXdmExtendedColors = staticCompositionLocalOf { XdmDarkExtendedColors }

object XdmTheme {
    val extendedColors: XdmExtendedColors
        @Composable get() = LocalXdmExtendedColors.current
}

@Composable
fun XdmTheme(
    mode: XdmThemeMode = XdmThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val colors = if (mode == XdmThemeMode.Amoled) XdmAmoledColorScheme else XdmDarkColorScheme
    val extended = if (mode == XdmThemeMode.Amoled) XdmAmoledExtendedColors else XdmDarkExtendedColors
    androidx.compose.runtime.CompositionLocalProvider(LocalXdmExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colors,
            typography = XdmTypography,
            shapes = XdmShapes,
            content = content,
        )
    }
}
