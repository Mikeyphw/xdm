package com.mikeyphw.xdm.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

object XdmTestTags {
    const val PageHeader = "xdm_page_header"
    const val MetricStrip = "xdm_metric_strip"
    const val NoticeRow = "xdm_notice_row"
    const val GroupedList = "xdm_grouped_list"
    const val SegmentedControl = "xdm_segmented_control"
    const val ProgressLine = "xdm_progress_line"
    const val TechnicalDetails = "xdm_technical_details"
    const val AdaptiveSheet = "xdm_adaptive_sheet"
    const val EmptyState = "xdm_empty_state"
}

data class XdmMetric(val label: String, val value: String)

@Composable
fun XdmPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag(XdmTestTags.PageHeader)
            .xdmPane("$title page header")
            .padding(horizontal = XdmSpacing.ScreenPadding, vertical = 18.dp),
    ) {
        val stackActions = maxWidth < 420.dp
        if (stackActions) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

@Composable
fun XdmMetricStrip(metrics: List<XdmMetric>, modifier: Modifier = Modifier) {
    if (metrics.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(XdmTestTags.MetricStrip).semantics { stateDescription = metrics.joinToString { "${it.label}: ${it.value}" } },
        color = XdmTheme.extendedColors.groupedSurface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 520.dp) 2 else metrics.size.coerceAtMost(4)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                metrics.chunked(columns).forEach { rowMetrics ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        rowMetrics.forEach { metric ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(metric.value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(metric.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                        repeat(columns - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
fun XdmNoticeRow(
    text: String,
    modifier: Modifier = Modifier,
    tone: XdmStatusTone = XdmStatusTone.Info,
    icon: ImageVector = Icons.Rounded.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = xdmToneColors(tone)
    Surface(
        modifier = modifier.fillMaxWidth().xdmMinimumTouchTarget().testTag(XdmTestTags.NoticeRow).semantics { contentDescription = text },
        color = colors.container,
        contentColor = colors.content,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun XdmGroupedList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(XdmTestTags.GroupedList).semantics { isTraversalGroup = true },
        color = XdmTheme.extendedColors.groupedSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun XdmListSeparator(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 58.dp),
        thickness = 1.dp,
        color = XdmTheme.extendedColors.separator,
    )
}

@Composable
fun XdmListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = onClick != null) {
                if (supporting?.isNotBlank() == true) stateDescription = supporting
                if (onClick != null) role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            supporting?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> XdmSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(XdmTestTags.SegmentedControl).xdmPane("Segmented control"),
        color = XdmTheme.extendedColors.groupedSurface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    modifier = Modifier
                        .sizeIn(minWidth = XdmMinimumTouchTarget, minHeight = XdmMinimumTouchTarget)
                        .clickable(role = Role.RadioButton) { onSelected(option) }
                        .semantics {
                            this.selected = isSelected
                            stateDescription = if (isSelected) "${label(option)} selected" else "${label(option)} not selected"
                        },
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label(option), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun XdmFileTypeIcon(
    fileName: String,
    modifier: Modifier = Modifier,
    mimeType: String? = null,
    contentDescription: String? = null,
) {
    val lowerName = fileName.lowercase()
    val lowerMime = mimeType.orEmpty().lowercase()
    val icon = when {
        lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".webm") -> Icons.Rounded.Movie
        lowerMime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".flac") || lowerName.endsWith(".m4a") -> Icons.Rounded.AudioFile
        lowerMime.startsWith("image/") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".webp") -> Icons.Rounded.Image
        lowerName.endsWith(".zip") || lowerName.endsWith(".7z") || lowerName.endsWith(".tar") || lowerName.endsWith(".gz") -> Icons.Rounded.FolderZip
        lowerMime.startsWith("text/") || lowerName.endsWith(".pdf") || lowerName.endsWith(".txt") -> Icons.Rounded.Description
        fileName.isBlank() -> Icons.Rounded.Download
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
    Surface(
        modifier = modifier.size(40.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
fun XdmProgressLine(
    progress: Float?,
    modifier: Modifier = Modifier,
    stateLabel: String? = null,
) {
    val semanticModifier = modifier.fillMaxWidth().testTag(XdmTestTags.ProgressLine).semantics {
        stateDescription = stateLabel ?: "Transfer progress available visually"
        if (stateLabel != null) liveRegion = LiveRegionMode.Polite
    }
    if (progress == null) {
        LinearProgressIndicator(modifier = semanticModifier.height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = XdmTheme.extendedColors.groupedSurfaceStrong)
    } else {
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = semanticModifier.height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = XdmTheme.extendedColors.groupedSurfaceStrong)
    }
}

@Composable
fun XdmSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun XdmTechnicalDetails(
    modifier: Modifier = Modifier,
    label: String = "Technical details",
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth().testTag(XdmTestTags.TechnicalDetails).semantics { contentDescription = label }) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .xdmMinimumTouchTarget()
                .semantics { stateDescription = if (expanded) "$label expanded" else "$label collapsed" },
        ) {
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "Hide $label" else label)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XdmAdaptiveSheet(
    visible: Boolean,
    windowClass: XdmWindowClass,
    onDismissRequest: () -> Unit,
    title: String,
    scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    val focusRestorationController = LocalXdmFocusRestorationController.current
    val sheetFocusRequester = remember { FocusRequester() }
    fun dismissAndRestoreFocus() {
        onDismissRequest()
        focusRestorationController.restoreLastFocus()
    }
    LaunchedEffect(title, visible) {
        runCatching { sheetFocusRequester.requestFocus() }
    }
    if (windowClass == XdmWindowClass.Expanded) {
        Dialog(
            onDismissRequest = { dismissAndRestoreFocus() },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .sizeIn(maxWidth = 760.dp, maxHeight = 820.dp)
                    .testTag(XdmTestTags.AdaptiveSheet)
                    .focusRequester(sheetFocusRequester)
                    .focusable()
                    .xdmPane("$title dialog", traversal = XdmTraversalOrder.Dialog).semantics {
                        stateDescription = "Open"
                    },
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column {
                    XdmPageHeader(title = title)
                    val bodyModifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = LocalXdmWindowProfile.current.sheetMaxHeight)
                        .padding(bottom = 8.dp)
                        .let { if (scrollContent) it.verticalScroll(rememberScrollState()) else it }
                    Column(bodyModifier, content = content)
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { dismissAndRestoreFocus() },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            dragHandle = null,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(XdmTestTags.AdaptiveSheet)
                    .focusRequester(sheetFocusRequester)
                    .focusable()
                    .xdmPane("$title bottom sheet", traversal = XdmTraversalOrder.Sheet).semantics {
                        stateDescription = "Open"
                    },
            ) {
                XdmPageHeader(title = title)
                val bodyModifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalXdmWindowProfile.current.sheetMaxHeight)
                    .padding(bottom = 8.dp)
                    .let { if (scrollContent) it.verticalScroll(rememberScrollState()) else it }
                Column(bodyModifier, content = content)
            }
        }
    }
}

@Composable
fun XdmEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Download,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp).testTag(XdmTestTags.EmptyState).semantics { contentDescription = "$title. $description" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = XdmTheme.extendedColors.groupedSurface,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) Button(onClick = onAction, modifier = Modifier.xdmMinimumTouchTarget()) { Text(actionLabel) }
    }
}

@Composable
fun XdmFlatCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = XdmTheme.extendedColors.groupedSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(if (compact) XdmSpacing.CompactCardPadding else 0.dp),
            content = content,
        )
    }
}

private data class XdmToneColors(val container: Color, val content: Color)

@Composable
private fun xdmToneColors(tone: XdmStatusTone): XdmToneColors {
    val extended = XdmTheme.extendedColors
    val scheme = MaterialTheme.colorScheme
    val container = when (tone) {
        XdmStatusTone.Success -> extended.successContainer
        XdmStatusTone.Warning -> extended.warningContainer
        XdmStatusTone.Error -> scheme.errorContainer
        XdmStatusTone.Info -> scheme.primaryContainer
        XdmStatusTone.Neutral -> extended.groupedSurfaceStrong
    }
    val preferredContent = when (tone) {
        XdmStatusTone.Success -> extended.onSuccessContainer
        XdmStatusTone.Warning -> extended.onWarningContainer
        XdmStatusTone.Error -> scheme.onErrorContainer
        XdmStatusTone.Info -> scheme.onPrimaryContainer
        XdmStatusTone.Neutral -> scheme.onSurfaceVariant
    }
    val content = XdmContrastPolicy.ensureReadableContentColor(container, preferredContent, scheme.onSurface)
    return XdmToneColors(container, content)
}
