package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object XdmSpacing {
    val ScreenPadding = 20.dp
    val ListPadding = 16.dp
    val CardPadding = 16.dp
    val CompactCardPadding = 10.dp
    val SectionGap = 16.dp
    val ItemGap = 8.dp
    val TightGap = 4.dp
    val BadgeHorizontalPadding = 10.dp
    val BadgeVerticalPadding = 6.dp
    val MinimumTouchTarget = 48.dp
}

val XdmTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3f).sp),
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2f).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1f).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.25.sp),
)

enum class XdmStatusTone { Neutral, Success, Warning, Error, Info }

@Composable
fun XdmSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun XdmCardTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.titleMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun XdmSupportingText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun XdmMetadataText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun XdmMetricText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.titleSmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun XdmActionFlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(XdmSpacing.ItemGap),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(XdmSpacing.TightGap),
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
fun XdmStatusBadge(text: String, modifier: Modifier = Modifier, tone: XdmStatusTone = XdmStatusTone.Neutral) {
    val scheme = MaterialTheme.colorScheme
    val extended = XdmTheme.extendedColors
    val background = when (tone) {
        XdmStatusTone.Success -> extended.successContainer
        XdmStatusTone.Warning -> extended.warningContainer
        XdmStatusTone.Error -> scheme.errorContainer
        XdmStatusTone.Info -> scheme.primaryContainer
        XdmStatusTone.Neutral -> extended.groupedSurfaceStrong
    }
    val preferredForeground = when (tone) {
        XdmStatusTone.Success -> extended.onSuccessContainer
        XdmStatusTone.Warning -> extended.onWarningContainer
        XdmStatusTone.Error -> scheme.onErrorContainer
        XdmStatusTone.Info -> scheme.onPrimaryContainer
        XdmStatusTone.Neutral -> scheme.onSurfaceVariant
    }
    val foreground = XdmContrastPolicy.ensureReadableContentColor(background, preferredForeground, scheme.onSurface)
    Surface(
        modifier = modifier,
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text,
            Modifier
                .sizeIn(minHeight = 32.dp)
                .padding(horizontal = XdmSpacing.BadgeHorizontalPadding, vertical = XdmSpacing.BadgeVerticalPadding),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun XdmListCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = XdmTheme.extendedColors.groupedSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(if (compact) XdmSpacing.CompactCardPadding else XdmSpacing.CardPadding),
            verticalArrangement = Arrangement.spacedBy(XdmSpacing.ItemGap),
            content = content,
        )
    }
}

fun xdmListPadding(horizontal: Boolean = true): PaddingValues = if (horizontal) {
    PaddingValues(XdmSpacing.ListPadding)
} else {
    PaddingValues(vertical = XdmSpacing.ListPadding)
}
