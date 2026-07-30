package com.mikeyphw.xdm.android

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.BackendRecommendation
import com.mikeyphw.xdm.android.model.BrowserSessionHealthReport
import com.mikeyphw.xdm.android.model.EngineEscalationPlan
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.DownloadIntakeOrigin
import com.mikeyphw.xdm.android.model.DownloadReviewPlanner
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.util.formatBytes

@Composable
@UiSurface(UiAudience.User, "Review and add a download")
fun AddDownloadScreen(
    destinationUri: String,
    conflictPolicy: FilenameConflictPolicy,
    savedDestinations: List<DestinationPermission>,
    externalDraftId: String? = null,
    initialUrl: String? = null,
    initialFileName: String? = null,
    externalSourceLabel: String? = null,
    externalKind: DownloadIntakeKind? = null,
    externalOrigin: DownloadIntakeOrigin? = null,
    externalPageTitle: String? = null,
    externalPageUrl: String? = null,
    externalMimeType: String? = null,
    externalContentLength: Long? = null,
    externalCanInspectMedia: Boolean = false,
    externalSessionHealth: BrowserSessionHealthReport? = null,
    externalEngineEscalationPlan: EngineEscalationPlan? = null,
    onInspectMedia: (String, String) -> Unit = { _, _ -> },
    onCancel: () -> Unit,
    onDestinationChanged: (String) -> Unit,
    onSafDestinationSelected: (String) -> Unit,
    onConflictPolicyChanged: (FilenameConflictPolicy) -> Unit,
    onAdd: (String, String, BackendType, String, FilenameConflictPolicy, Boolean, String, ChecksumAlgorithm) -> Unit,
    recommend: (String, String, BackendType, String, FilenameConflictPolicy, Boolean) -> BackendRecommendation,
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(initialFileName.orEmpty()) }
    var backend by rememberSaveable { mutableStateOf(BackendType.Automatic) }
    var allowFallback by rememberSaveable { mutableStateOf(true) }
    var expectedChecksum by rememberSaveable { mutableStateOf("") }
    var checksumAlgorithm by rememberSaveable { mutableStateOf(ChecksumAlgorithm.Sha256) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var reviewConfirmed by rememberSaveable { mutableStateOf(false) }
    var clipboardMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(externalDraftId) {
        if (externalDraftId != null) {
            url = initialUrl.orEmpty()
            name = initialFileName.orEmpty()
            reviewConfirmed = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            reviewConfirmed = false
            onSafDestinationSelected(it.toString())
        }
    }
    val recommendation = url.takeIf(String::isNotBlank)?.let {
        recommend(url, name, backend, destinationUri, conflictPolicy, allowFallback)
    }
    val review = DownloadReviewPlanner.plan(
        url = url,
        fileName = name,
        mimeType = externalMimeType.takeIf { url == initialUrl },
        destinationUri = destinationUri,
        origin = if (externalDraftId != null && url == initialUrl) externalOrigin ?: DownloadIntakeOrigin.ExternalView else DownloadIntakeOrigin.ManualEntry,
    )
    val canReview = review.canStartDirectly && recommendation?.compatible != false
    val canInspectMedia = review.canInspectAsMedia && (externalDraftId == null || externalCanInspectMedia || url != initialUrl)
    val methodLabel = recommendation?.let { recommendationSummary(it, allowFallback) } ?: "Automatic • resumable"
    val fileLabel = name.ifBlank { inferredFileName(url) }
    val visibleSessionHealth = externalSessionHealth.takeIf { externalDraftId != null && url == initialUrl }
    val visibleEngineEscalation = externalEngineEscalationPlan.takeIf { externalDraftId != null && url == initialUrl }

    Column(Modifier.fillMaxSize().imePadding().xdmScreen(XdmScreenTags.AddDownload, "New download")) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (externalDraftId != null) {
                item {
                    XdmNoticeRow(
                        text = "Link received from ${externalSourceLabel ?: "another app"}. Review it before anything enters the queue.",
                        tone = XdmStatusTone.Info,
                        icon = Icons.Rounded.Link,
                    )
                }
                item {
                    XdmGroupedList {
                        XdmListRow(
                            headline = externalPageTitle?.takeIf(String::isNotBlank) ?: externalKind?.externalLabel() ?: "External download",
                            supporting = listOfNotNull(
                                externalKind?.externalLabel(),
                                externalMimeType?.takeIf(String::isNotBlank),
                                externalContentLength?.takeIf { it > 0L }?.formatBytes(),
                                externalPageUrl?.takeIf { it.isNotBlank() && it != initialUrl }?.let { "Page context available" },
                            ).joinToString(" • ").ifBlank { externalIntakeGuidance(externalKind) },
                            leading = { Icon(Icons.Rounded.Link, contentDescription = null) },
                        )
                    }
                }
            }


            visibleSessionHealth?.let { health ->
                item {
                    BrowserSessionHealthCard(health)
                }
            }

            visibleEngineEscalation?.let { plan ->
                item {
                    EngineEscalationCard(plan)
                }
            }

            item {
                Text(
                    "Paste a link. XDM chooses safe defaults and keeps session details backstage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        reviewConfirmed = false
                    },
                    label = { Text("Download link") },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    trailingIcon = {
                        TextButton(onClick = {
                            val candidate = firstDownloadUrlFromClipboard(context)
                            if (candidate != null) {
                                url = candidate
                                reviewConfirmed = false
                                clipboardMessage = "Link pasted from clipboard"
                            } else {
                                clipboardMessage = "No supported HTTP, HTTPS, or FTP URL found"
                            }
                        }) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                            Text("Paste detected URL")
                        }
                    },
                    supportingText = { clipboardMessage?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        reviewConfirmed = false
                    },
                    label = { Text("File name") },
                    supportingText = { Text("Optional. XDM infers a name from the link when left empty.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item {
                XdmGroupedList {
                    XdmListRow(
                        headline = "Save to",
                        supporting = destinationUri.ifBlank { "Choose where completed files should be saved." },
                        leading = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                        trailing = { TextButton(onClick = { folderPicker.launch(null) }) { Text("Choose") } },
                    )
                    XdmListSeparator()
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        XdmActionFlowRow {
                            DestinationCatalog.available(Build.VERSION.SDK_INT).forEach { choice ->
                                FilterChip(
                                    selected = destinationUri == choice.uri,
                                    onClick = {
                                        reviewConfirmed = false
                                        onDestinationChanged(choice.uri)
                                    },
                                    label = { Text(choice.label) },
                                )
                            }
                            savedDestinations.take(5).forEach { destination ->
                                FilterChip(
                                    selected = destinationUri == destination.uri,
                                    onClick = {
                                        reviewConfirmed = false
                                        onDestinationChanged(destination.uri)
                                    },
                                    label = { Text(destination.displayName) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                XdmGroupedList {
                    XdmListRow(
                        headline = "Advanced options",
                        supporting = "Engine override, filename conflict, fallback, and checksum.",
                        onClick = { advancedExpanded = !advancedExpanded },
                        trailing = { Text(if (advancedExpanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary) },
                    )
                    AnimatedVisibility(advancedExpanded) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            XdmSectionLabel("Download engine")
                            XdmActionFlowRow {
                                BackendType.entries.forEach { value ->
                                    FilterChip(
                                        selected = backend == value,
                                        onClick = {
                                            backend = value
                                            reviewConfirmed = false
                                        },
                                        label = { Text(if (value == BackendType.Automatic) "Automatic (recommended)" else value.uiLabel()) },
                                    )
                                }
                            }

                            XdmSectionLabel("File conflict")
                            XdmActionFlowRow {
                                FilenameConflictPolicy.entries.forEach { value ->
                                    FilterChip(
                                        selected = conflictPolicy == value,
                                        onClick = {
                                            reviewConfirmed = false
                                            onConflictPolicyChanged(value)
                                        },
                                        label = { Text(value.uiLabel()) },
                                    )
                                }
                            }

                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Compatible fallback", style = MaterialTheme.typography.bodyMedium)
                                    XdmMetadataText("Used only before a backend owns the destination.")
                                }
                                Switch(
                                    checked = allowFallback,
                                    onCheckedChange = {
                                        allowFallback = it
                                        reviewConfirmed = false
                                    },
                                    modifier = Modifier.xdmStateDescription(
                                        if (allowFallback) "Compatible fallback enabled" else "Compatible fallback disabled",
                                    ),
                                )
                            }

                            OutlinedTextField(
                                value = expectedChecksum,
                                onValueChange = {
                                    expectedChecksum = it
                                    reviewConfirmed = false
                                },
                                label = { Text("Checksum (optional)") },
                                supportingText = { Text("Used to verify the completed file before final success.") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            XdmActionFlowRow {
                                ChecksumAlgorithm.entries.forEach { value ->
                                    FilterChip(
                                        selected = checksumAlgorithm == value,
                                        onClick = {
                                            checksumAlgorithm = value
                                            reviewConfirmed = false
                                        },
                                        label = { Text(value.uiLabel()) },
                                    )
                                }
                            }
                            XdmMetadataText("Private browser context, backend probes, and fallback internals are intentionally hidden from the normal Add flow.")
                        }
                    }
                }
            }

            item {
                XdmGroupedList(
                    modifier = Modifier.xdmScreen(XdmScreenTags.AddReview, "Download review summary"),
                ) {
                    XdmListRow(
                        headline = if (reviewConfirmed) "Review confirmed" else review.title,
                        supporting = if (reviewConfirmed) "Nothing has been queued yet. Add it only when the summary below is correct." else review.guidance,
                        leading = {
                            Icon(
                                if (reviewConfirmed) Icons.Rounded.CheckCircle else Icons.Rounded.Link,
                                contentDescription = null,
                                tint = if (reviewConfirmed) XdmTheme.extendedColors.success else MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                    XdmListSeparator()
                    ReviewSummaryRow("File", fileLabel)
                    XdmListSeparator()
                    ReviewSummaryRow("Destination", destinationUri.ifBlank { "Not selected" })
                    XdmListSeparator()
                    ReviewSummaryRow("Method", methodLabel)
                }
            }

            if (canInspectMedia) {
                item {
                    Button(
                        onClick = { onInspectMedia(url, name) },
                        enabled = review.normalizedUrl != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Movie, contentDescription = null)
                        Text(review.mediaInspectionActionLabel)
                    }
                }
                item {
                    XdmMetadataText(review.mediaInspectionGuidance)
                }
                item {
                    XdmMetadataText("Inspect media uses a review-first path and never creates a transfer automatically.")
                }
            }
        }

        XdmFlatCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        !canReview -> review.guidance
                        !reviewConfirmed -> "Step 1 of 2 • Review the download summary."
                        else -> "Step 2 of 2 • Add the reviewed request to the queue."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = {
                            if (!reviewConfirmed) {
                                reviewConfirmed = true
                            } else {
                                onAdd(
                                    url,
                                    name,
                                    backend,
                                    destinationUri,
                                    conflictPolicy,
                                    allowFallback,
                                    expectedChecksum,
                                    checksumAlgorithm,
                                )
                            }
                        },
                        enabled = canReview,
                        modifier = Modifier.weight(1.6f),
                    ) {
                        Text(if (reviewConfirmed) "Add to queue" else "Review download")
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserSessionHealthCard(health: BrowserSessionHealthReport) {
    XdmGroupedList(
        modifier = Modifier.xdmScreen(XdmScreenTags.AddReview, "Browser session health"),
    ) {
        XdmListRow(
            headline = "Browser session health",
            supporting = health.guidance,
            leading = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
            trailing = { Text(health.primaryActionLabel, color = MaterialTheme.colorScheme.primary) },
        )
        XdmListSeparator()
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            XdmMetricStrip(
                listOf(
                    XdmMetric("Context", health.browserContextLabel),
                    XdmMetric("Sign-in", health.protectedRequestLabel),
                    XdmMetric("Expiry risk", health.expiryRiskLabel),
                    XdmMetric("Method", health.suggestedMethodLabel),
                ),
            )
            health.signals.forEach { signal ->
                ReviewSummaryRow(signal.label, "${signal.value} • ${signal.guidance}")
            }
            XdmMetadataText("Private browser values are never shown here. Refresh from the browser if the server asks for sign-in again.")
        }
    }
}


@Composable
private fun EngineEscalationCard(plan: EngineEscalationPlan) {
    XdmGroupedList(
        modifier = Modifier.xdmScreen(XdmScreenTags.AddReview, "Engine escalation planner"),
    ) {
        XdmListRow(
            headline = plan.title,
            supporting = plan.guidance,
            leading = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
            trailing = { Text(plan.nextActionLabel, color = MaterialTheme.colorScheme.primary) },
        )
        XdmListSeparator()
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            XdmMetricStrip(
                listOf(
                    XdmMetric("Method", plan.recommendedMethodLabel),
                    XdmMetric("Reason", plan.reasonLabel),
                ),
            )
            plan.steps.forEach { step ->
                ReviewSummaryRow(step.label, "${step.status} • ${step.guidance}")
            }
            if (plan.hasAlternatives) {
                XdmSectionLabel("Safe alternatives")
                plan.alternatives.forEach { alternative ->
                    ReviewSummaryRow(alternative.methodLabel, alternative.whenToUse)
                }
            }
            XdmMetadataText("This planner chooses only the next review action. It does not start a transfer or expose private browser values.")
        }
    }
}

@Composable
private fun ReviewSummaryRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.32f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.68f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun recommendationSummary(recommendation: BackendRecommendation, allowFallback: Boolean): String = buildString {
    append(recommendation.backend.uiLabel())
    if (recommendation.compatible) append(" • resumable")
    if (allowFallback && recommendation.fallbackAllowed && recommendation.fallbackBackend != null) append(" • safe fallback")
}

private fun inferredFileName(url: String): String = runCatching {
    url.substringBefore('?').substringBefore('#').substringAfterLast('/').takeIf(String::isNotBlank)
}.getOrNull() ?: "Name inferred when queued"

internal fun DownloadIntakeKind.externalLabel(): String = when (this) {
    DownloadIntakeKind.DirectFile -> "Direct file"
    DownloadIntakeKind.DirectMedia -> "Direct media"
    DownloadIntakeKind.AdaptiveMedia -> "HLS / DASH"
    DownloadIntakeKind.Torrent -> "Torrent"
    DownloadIntakeKind.PageOrUnknown -> "Page or unknown"
}

internal fun externalIntakeGuidance(kind: DownloadIntakeKind?): String = when (kind) {
    DownloadIntakeKind.DirectFile -> "A downloadable file was shared with XDM. Confirm its name and destination before queueing."
    DownloadIntakeKind.DirectMedia -> "A direct audio or video URL was shared. Download it directly or inspect it first."
    DownloadIntakeKind.AdaptiveMedia -> "An HLS or DASH playlist was shared. Inspect it to choose variants, audio, and subtitles."
    DownloadIntakeKind.Torrent -> "A torrent handoff was detected. Review the destination and compatible method."
    DownloadIntakeKind.PageOrUnknown -> "This may be a webpage rather than a file. Inspect it as media when that is the safer choice."
    null -> "Review the link, destination, and method before queueing."
}
