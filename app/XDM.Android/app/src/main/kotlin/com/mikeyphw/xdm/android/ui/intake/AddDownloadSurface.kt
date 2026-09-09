package com.mikeyphw.xdm.android

import android.os.Build
import android.os.Environment
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
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.BrowserSessionHealthReport
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.DownloadIntakeOrigin
import com.mikeyphw.xdm.android.model.DownloadReviewPlanner
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.EngineEscalationPlan
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.storage.DestinationUris
import com.mikeyphw.xdm.android.storage.PersonalDirectStorage
import com.mikeyphw.xdm.android.util.formatBytes

@Composable
@UiSurface(UiAudience.User, "Add a download with a single explicit action")
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
    admissionState: DownloadAdmissionUiState = DownloadAdmissionUiState(),
    onDismissAdmission: () -> Unit = {},
    onDuplicateDecision: (DuplicateUrlAction) -> Unit = {},
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
    var clipboardMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(externalDraftId) {
        if (externalDraftId != null) {
            url = initialUrl.orEmpty()
            name = initialFileName.orEmpty()
            backend = BackendType.Automatic
            allowFallback = true
            expectedChecksum = ""
            checksumAlgorithm = ChecksumAlgorithm.Sha256
            advancedExpanded = false
            clipboardMessage = null
        }
    }

    LaunchedEffect(
        url,
        name,
        backend,
        destinationUri,
        conflictPolicy,
        allowFallback,
        expectedChecksum,
        checksumAlgorithm,
    ) {
        onDismissAdmission()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onSafDestinationSelected(it.toString()) }
    }
    val directStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            onDestinationChanged(DestinationUris.DIRECT_DOWNLOADS)
            clipboardMessage = "Direct file access granted for Download/XDM"
        } else {
            clipboardMessage = "Direct file access was not granted; SAF and MediaStore remain available"
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
    val canDownload = review.canStartDirectly && recommendation?.compatible != false
    val canInspectMedia = review.canInspectAsMedia && (externalDraftId == null || externalCanInspectMedia || url != initialUrl)
    val preferMediaInspection = canInspectMedia && review.mediaInspectionRecommended && review.kind in setOf(
        DownloadIntakeKind.AdaptiveMedia,
        DownloadIntakeKind.PageOrUnknown,
    )
    val methodLabel = recommendation?.let { recommendationSummary(it, allowFallback) } ?: "Automatic • resumable"
    val fileLabel = name.ifBlank { inferredFileName(url) }
    val visibleSessionHealth = externalSessionHealth.takeIf { externalDraftId != null && url == initialUrl }
    val visibleEngineEscalation = externalEngineEscalationPlan.takeIf { externalDraftId != null && url == initialUrl }
    val sourceSummary = listOfNotNull(
        externalMimeType?.takeIf(String::isNotBlank),
        externalContentLength?.takeIf { it > 0L }?.formatBytes(),
        externalPageUrl?.takeIf(String::isNotBlank)?.let(::hostFromUrl),
        url.takeIf(String::isNotBlank)?.let(::hostFromUrl),
    ).distinct().joinToString(" • ")

    Column(Modifier.fillMaxSize().imePadding().xdmScreen(XdmScreenTags.AddDownload, "New download")) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (url.isNotBlank() || externalDraftId != null) {
                item {
                    XdmGroupedList {
                        XdmListRow(
                            headline = fileLabel,
                            supporting = sourceSummary.ifBlank {
                                externalPageTitle?.takeIf(String::isNotBlank)
                                    ?: externalKind?.externalLabel()
                                    ?: "Download"
                            },
                            leading = { XdmFileTypeIcon(fileLabel, mimeType = externalMimeType) },
                            trailing = if (visibleSessionHealth != null) ({
                                XdmStatusBadge("Browser context", tone = XdmStatusTone.Success)
                            }) else null,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Download link") },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    trailingIcon = {
                        TextButton(onClick = {
                            val candidate = firstDownloadUrlFromClipboard(context)
                            if (candidate != null) {
                                url = candidate
                                clipboardMessage = "Link pasted"
                            } else {
                                clipboardMessage = "No supported HTTP, HTTPS, or FTP URL found"
                            }
                        }) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                            Text("Paste")
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
                    onValueChange = { name = it },
                    label = { Text("File name") },
                    supportingText = { Text("Optional • XDM uses the server or link name when left empty.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item {
                XdmGroupedList {
                    XdmListRow(
                        headline = "Save to",
                        supporting = destinationUiLabel(destinationUri),
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
                                        if (choice.uri == DestinationUris.DIRECT_DOWNLOADS && !PersonalDirectStorage.isGranted(context)) {
                                            directStoragePermission.launch(PersonalDirectStorage.permissionIntent(context))
                                        } else {
                                            onDestinationChanged(choice.uri)
                                        }
                                    },
                                    label = { Text(choice.label) },
                                )
                            }
                            savedDestinations
                                .filter { it.persistedWrite && it.status == com.mikeyphw.xdm.android.model.DestinationHealthStatus.Healthy }
                                .take(5)
                                .forEach { destination ->
                                    FilterChip(
                                        selected = destinationUri == destination.uri,
                                        onClick = { onDestinationChanged(destination.uri) },
                                        label = { Text("${destination.displayName} · ${destination.type.uiLabel()}") },
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
                        supporting = listOfNotNull(
                            methodLabel,
                            visibleSessionHealth?.let { "Browser context attached" },
                        ).joinToString(" • "),
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
                                        onClick = { backend = value },
                                        label = { Text(if (value == BackendType.Automatic) "Automatic (recommended)" else value.uiLabel()) },
                                    )
                                }
                            }

                            XdmSectionLabel("File conflict")
                            XdmActionFlowRow {
                                FilenameConflictPolicy.entries.forEach { value ->
                                    FilterChip(
                                        selected = conflictPolicy == value,
                                        onClick = { onConflictPolicyChanged(value) },
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
                                    onCheckedChange = { allowFallback = it },
                                    modifier = Modifier.xdmStateDescription(
                                        if (allowFallback) "Compatible fallback enabled" else "Compatible fallback disabled",
                                    ),
                                )
                            }

                            OutlinedTextField(
                                value = expectedChecksum,
                                onValueChange = { expectedChecksum = it },
                                label = { Text("Checksum (optional)") },
                                supportingText = { Text("Verify the completed file before final success.") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            XdmActionFlowRow {
                                ChecksumAlgorithm.entries.forEach { value ->
                                    FilterChip(
                                        selected = checksumAlgorithm == value,
                                        onClick = { checksumAlgorithm = value },
                                        label = { Text(value.uiLabel()) },
                                    )
                                }
                            }

                            if (canInspectMedia && !preferMediaInspection) {
                                TextButton(onClick = { onInspectMedia(url, name) }) {
                                    Icon(Icons.Rounded.Movie, contentDescription = null)
                                    Text("Media options")
                                }
                            }
                            XdmMetadataText("Browser/session details and backend reasoning stay hidden unless you open Advanced.")
                        }
                    }
                }
            }

            visibleSessionHealth?.let { health ->
                item {
                    AnimatedVisibility(advancedExpanded) { BrowserSessionHealthCard(health) }
                }
            }
            visibleEngineEscalation?.let { plan ->
                item {
                    AnimatedVisibility(advancedExpanded) { EngineEscalationCard(plan) }
                }
            }

            if (preferMediaInspection) {
                item {
                    XdmNoticeRow(
                        text = review.mediaInspectionGuidance,
                        tone = XdmStatusTone.Info,
                        icon = Icons.Rounded.Movie,
                    )
                }
            }
        }

        XdmFlatCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        admissionState.message?.isNotBlank() == true -> admissionState.message.orEmpty()
                        preferMediaInspection -> "Choose media options before downloading this page or playlist."
                        !canDownload -> review.guidance
                        else -> "$methodLabel • ${destinationUiLabel(destinationUri)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (admissionState.awaitingDuplicateDecision) {
                    Text(
                        "Existing download: ${admissionState.duplicateFileName ?: "matching URL"}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onDuplicateDecision(DuplicateUrlAction.OpenExisting) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open existing") }
                        TextButton(
                            onClick = { onDuplicateDecision(DuplicateUrlAction.Skip) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Skip") }
                        Button(
                            onClick = { onDuplicateDecision(DuplicateUrlAction.AddAgain) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Add anyway") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = onCancel,
                            enabled = !admissionState.inFlight,
                            modifier = Modifier.weight(1f),
                        ) { Text("Cancel") }
                        Button(
                            onClick = {
                                if (preferMediaInspection) {
                                    onInspectMedia(url, name)
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
                            enabled = (if (preferMediaInspection) review.normalizedUrl != null else canDownload) && !admissionState.inFlight,
                            modifier = Modifier.weight(1.6f),
                        ) {
                            Text(
                                when {
                                    admissionState.inFlight -> "Adding…"
                                    preferMediaInspection -> "Inspect media"
                                    else -> "Download"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun BrowserSessionHealthCard(health: BrowserSessionHealthReport) {
    XdmGroupedList(
        modifier = Modifier.xdmScreen(XdmScreenTags.BrowserSessionHealth, "Browser session health"),
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
        modifier = Modifier.xdmScreen(XdmScreenTags.EngineEscalation, "Engine escalation planner"),
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
