package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
    val SectionGap = 12.dp
    val ItemGap = 8.dp
    val TightGap = 4.dp
    val BadgeHorizontalPadding = 10.dp
    val BadgeVerticalPadding = 6.dp
}

val XdmTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

enum class XdmStatusTone { Neutral, Success, Warning, Error, Info }

@Composable
fun XdmSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
fun XdmCardTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun XdmSupportingText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
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
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
fun XdmStatusBadge(text: String, modifier: Modifier = Modifier, tone: XdmStatusTone = XdmStatusTone.Neutral) {
    val colorScheme = MaterialTheme.colorScheme
    val background = when (tone) {
        XdmStatusTone.Success -> colorScheme.primaryContainer
        XdmStatusTone.Warning -> colorScheme.tertiaryContainer
        XdmStatusTone.Error -> colorScheme.errorContainer
        XdmStatusTone.Info -> colorScheme.secondaryContainer
        XdmStatusTone.Neutral -> colorScheme.surfaceVariant
    }
    val foreground = when (tone) {
        XdmStatusTone.Success -> colorScheme.onPrimaryContainer
        XdmStatusTone.Warning -> colorScheme.onTertiaryContainer
        XdmStatusTone.Error -> colorScheme.onErrorContainer
        XdmStatusTone.Info -> colorScheme.onSecondaryContainer
        XdmStatusTone.Neutral -> colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = XdmSpacing.BadgeHorizontalPadding, vertical = XdmSpacing.BadgeVerticalPadding),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun XdmListCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
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
