package com.mikeyphw.xdm.android

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.displayName
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.storage.DestinationUris
import com.mikeyphw.xdm.android.storage.PersonalDirectStorage

@Composable
@UiSurface(UiAudience.Advanced, "Configure download behavior without mixing network, external tools, post-processing, or backup settings")
internal fun AdvancedDownloadSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    var destinationRuleName by remember { mutableStateOf("") }
    var destinationRulePattern by remember { mutableStateOf("") }
    var destinationRuleDestination by remember(state.destinationUri) { mutableStateOf(state.destinationUri) }
    var destinationRuleMatch by remember { mutableStateOf(DestinationRuleMatch.Host) }
    var duplicateHost by remember { mutableStateOf("") }
    var duplicateAction by remember { mutableStateOf(DuplicateUrlAction.OpenExisting) }
    var showRuleDestinationPicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Download behavior", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmSupportingText(
                "Rules here decide how new downloads are named, routed, deduplicated, and admitted to queues. Network and external-tool settings live in their own categories.",
                maxLines = 4,
            )
        }

        item { XdmSectionHeader("Defaults") }
        item {
            XdmListCard {
                XdmCardTitle("File name conflicts")
                XdmSupportingText("Choose the default when a destination already contains a file with the requested name.", maxLines = 3)
                XdmActionFlowRow {
                    FilenameConflictPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = state.conflictPolicy == policy,
                            onClick = { viewModel.setConflictPolicy(policy) },
                            label = { Text(humanizeAdvancedName(policy.name)) },
                        )
                    }
                }
            }
        }
        item {
            XdmGroupedList {
                XdmListRow(
                    headline = "Smart queue",
                    supporting = queueSummary(state),
                    trailing = { Text("Manage", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                    onClick = { viewModel.navigateActivity(ActivityPanel.Queues) },
                )
            }
        }

        item { XdmSectionHeader("Destination rules") }
        item {
            XdmListCard {
                XdmCardTitle("Route downloads automatically")
                XdmSupportingText("Route new downloads by host, extension, MIME type, or fallback before they enter the queue.", maxLines = 3)
                XdmActionFlowRow {
                    DestinationRuleMatch.entries.forEach { match ->
                        FilterChip(
                            selected = destinationRuleMatch == match,
                            onClick = { destinationRuleMatch = match },
                            label = { Text(humanizeAdvancedName(match.name)) },
                        )
                    }
                }
                OutlinedTextField(
                    destinationRuleName,
                    { destinationRuleName = it },
                    label = { Text("Rule name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (destinationRuleMatch != DestinationRuleMatch.Fallback) {
                    OutlinedTextField(
                        destinationRulePattern,
                        { destinationRulePattern = it },
                        label = { Text("Pattern") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    XdmMetadataText("Fallback applies only when no host, extension, or MIME rule matches.")
                }
                XdmGroupedList {
                    XdmListRow(
                        headline = "Save matching downloads to",
                        supporting = destinationUiLabel(destinationRuleDestination),
                        trailing = { Text("Choose", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                        onClick = { showRuleDestinationPicker = true },
                    )
                }
                XdmMetadataText(destinationUiHint(destinationRuleDestination), maxLines = 3)
                if (state.developerOptionsEnabled) {
                    XdmMetadataText("Technical URI: $destinationRuleDestination", maxLines = 2)
                }
                Button(
                    onClick = {
                        viewModel.saveDestinationRule(destinationRuleName, destinationRuleMatch, destinationRulePattern, destinationRuleDestination)
                        destinationRuleName = ""
                        destinationRulePattern = ""
                    },
                    enabled = destinationRuleName.isNotBlank() && destinationRuleDestination.isNotBlank() &&
                        (destinationRuleMatch == DestinationRuleMatch.Fallback || destinationRulePattern.isNotBlank()),
                ) { Text("Save destination rule") }
                state.destinationRules.take(6).forEach { rule ->
                    XdmMetadataText(
                        "${rule.name}: ${humanizeAdvancedName(rule.match.name)} ${rule.pattern} → ${destinationUiLabel(rule.destinationUri)}",
                        maxLines = 2,
                    )
                }
            }
        }

        item { XdmSectionHeader("Duplicate URLs") }
        item {
            XdmListCard {
                XdmCardTitle("Duplicate handling")
                XdmSupportingText("Choose what XDM should do when a source URL is already in download history.", maxLines = 3)
                OutlinedTextField(
                    duplicateHost,
                    { duplicateHost = it },
                    label = { Text("Host pattern") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                XdmActionFlowRow {
                    DuplicateUrlAction.entries.forEach { action ->
                        FilterChip(
                            selected = duplicateAction == action,
                            onClick = { duplicateAction = action },
                            label = { Text(humanizeAdvancedName(action.name)) },
                        )
                    }
                }
                Button(
                    onClick = {
                        viewModel.saveDuplicateRule(duplicateHost, duplicateAction)
                        duplicateHost = ""
                    },
                    enabled = duplicateHost.isNotBlank(),
                ) { Text("Save duplicate rule") }
                state.duplicateRules.take(6).forEach { rule ->
                    XdmMetadataText("${rule.hostPattern}: ${humanizeAdvancedName(rule.action.name)}")
                }
            }
        }
    }

    if (showRuleDestinationPicker) {
        DestinationRulePickerDialog(
            state = state,
            selectedUri = destinationRuleDestination,
            onChoose = {
                destinationRuleDestination = it
                showRuleDestinationPicker = false
            },
            onDismiss = { showRuleDestinationPicker = false },
        )
    }
}

@Composable
internal fun StorageDestinationsSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let(viewModel::registerSafDestination)
    }
    val directStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (PersonalDirectStorage.isGranted(context)) viewModel.setDestination(DestinationUris.DIRECT_DOWNLOADS)
    }
    val directStorageSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val directStorageGranted = directStorageSupported && PersonalDirectStorage.isGranted(context)
    var showCustomDirectPath by remember { mutableStateOf(false) }
    var customDirectPath by remember { mutableStateOf(PersonalDirectStorage.downloadsDirectory().absolutePath) }
    var customDirectPathError by remember { mutableStateOf<String?>(null) }
    val currentPermission = state.destinationPermissions.firstOrNull { it.uri == state.destinationUri }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Storage & destinations", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Current save location")
                XdmSupportingText(destinationUiLabel(state.destinationUri), maxLines = 2)
                XdmMetadataText(destinationUiHint(state.destinationUri), maxLines = 3)
                XdmActionFlowRow {
                    StatusPill(
                        currentPermission?.status?.name?.let(::humanizeAdvancedName) ?: "Configured",
                        if (currentPermission?.persistedWrite == false) XdmStatusTone.Warning else XdmStatusTone.Success,
                    )
                    if (currentPermission?.persistedWrite == true) StatusPill("Writable", XdmStatusTone.Success)
                }
                if (state.developerOptionsEnabled) XdmMetadataText("Technical URI: ${state.destinationUri}", maxLines = 2)
            }
        }

        if (directStorageSupported) {
            item { XdmSectionHeader("Direct storage") }
            item {
                XdmGroupedList {
                    XdmListRow(
                        headline = "Direct file access",
                        supporting = if (directStorageGranted) {
                            "Granted. XDM can use ordinary shared-storage paths such as Download/XDM."
                        } else {
                            "Grant Android all-files access to use direct shared-storage paths in this personal build."
                        },
                        trailing = { Text(if (directStorageGranted) "Manage" else "Grant", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                        onClick = { directStorageLauncher.launch(PersonalDirectStorage.permissionIntent(context)) },
                    )
                    if (directStorageGranted) {
                        XdmListSeparator()
                        XdmListRow(
                            headline = "Download/XDM",
                            supporting = if (state.destinationUri == DestinationUris.DIRECT_DOWNLOADS) "Current default" else "Use the standard XDM direct-download folder.",
                            trailing = { Text(if (state.destinationUri == DestinationUris.DIRECT_DOWNLOADS) "Default" else "Use", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                            onClick = { viewModel.setDestination(DestinationUris.DIRECT_DOWNLOADS) },
                        )
                        XdmListSeparator()
                        XdmListRow(
                            headline = "Custom direct folder",
                            supporting = "Use another ordinary folder in shared storage. Android/data and Android/obb remain excluded.",
                            trailing = { Text("Set", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                            onClick = { showCustomDirectPath = true },
                        )
                    }
                }
            }
        }

        item { XdmSectionHeader("Android-managed storage") }
        item {
            XdmGroupedList {
                DestinationCatalog.available(Build.VERSION.SDK_INT)
                    .filterNot { it.uri == DestinationUris.DIRECT_DOWNLOADS }
                    .forEachIndexed { index, choice ->
                        XdmListRow(
                            headline = choice.label,
                            supporting = destinationUiHint(choice.uri),
                            trailing = if (choice.uri == state.destinationUri) ({ Text("Current", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }) else null,
                            onClick = { viewModel.setDestination(choice.uri) },
                        )
                        if (index != DestinationCatalog.available(Build.VERSION.SDK_INT).filterNot { it.uri == DestinationUris.DIRECT_DOWNLOADS }.lastIndex) XdmListSeparator()
                    }
            }
        }
        item {
            XdmGroupedList {
                XdmListRow(
                    headline = "Choose Android folder",
                    supporting = "Use Android's folder picker for a custom shared-storage location with persisted access.",
                    trailing = { Text("Choose", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                    onClick = { destinationPicker.launch(null) },
                )
            }
        }
        val saved = state.destinationPermissions
            .filter { it.persistedWrite && it.uri.startsWith("content://", ignoreCase = true) }
            .distinctBy { it.uri }
            .take(8)
        if (saved.isNotEmpty()) {
            item { XdmSectionHeader("Your folders") }
            item {
                XdmGroupedList {
                    saved.forEachIndexed { index, destination ->
                        XdmListRow(
                            headline = destination.displayName,
                            supporting = humanizeAdvancedName(destination.status.name),
                            trailing = if (destination.uri == state.destinationUri) ({ Text("Current", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }) else null,
                            onClick = { viewModel.setDestination(destination.uri) },
                        )
                        if (index != saved.lastIndex) XdmListSeparator()
                    }
                }
            }
        }

        item { XdmSectionHeader("Storage health") }
        item {
            XdmListCard {
                XdmCardTitle("Storage doctor")
                XdmSupportingText("Run one end-to-end check for destination permission, folder creation, write/fsync, rename, read, deletion, and backend storage access.", maxLines = 4)
                XdmMetadataText("${state.aria2Diagnostics.storageDoctor.status}. ${state.aria2Diagnostics.storageDoctor.detail}", maxLines = 5)
                Button(onClick = viewModel::runStorageDoctor, enabled = !state.aria2Diagnostics.storageDoctor.running) {
                    Text(if (state.aria2Diagnostics.storageDoctor.running) "Running…" else "Run storage doctor")
                }
            }
        }
    }

    if (showCustomDirectPath) {
        AlertDialog(
            onDismissRequest = { showCustomDirectPath = false },
            title = { Text("Custom direct folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter an absolute folder inside shared storage. XDM will use it as a directory, not as a document-provider URI.")
                    OutlinedTextField(
                        value = customDirectPath,
                        onValueChange = { customDirectPath = it; customDirectPathError = null },
                        label = { Text("Folder path") },
                        isError = customDirectPathError != null,
                        singleLine = true,
                    )
                    customDirectPathError?.let { XdmMetadataText(it, maxLines = 3) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { PersonalDirectStorage.customDirectoryUri(customDirectPath) }
                        .onSuccess { uri ->
                            viewModel.setDestination(uri)
                            customDirectPathError = null
                            showCustomDirectPath = false
                        }
                        .onFailure { customDirectPathError = it.message ?: "Invalid shared-storage path." }
                }) { Text("Use folder") }
            },
            dismissButton = { TextButton(onClick = { showCustomDirectPath = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun NetworkSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    var proxyEnabled by remember(state.proxySettings) { mutableStateOf(state.proxySettings.enabled) }
    var proxyHost by remember(state.proxySettings) { mutableStateOf(state.proxySettings.host) }
    var proxyPort by remember(state.proxySettings) { mutableStateOf(state.proxySettings.port?.toString().orEmpty()) }
    var proxyUsername by remember(state.proxySettings) { mutableStateOf(state.proxySettings.username) }
    var proxyAlias by remember(state.proxySettings) { mutableStateOf(state.proxySettings.credentialAlias) }
    val proxyDraft = ProxyCredentialSettings(
        enabled = proxyEnabled,
        host = proxyHost,
        port = proxyPort.toIntOrNull()?.takeIf { it in 1..65535 },
        username = proxyUsername,
        credentialAlias = proxyAlias,
    )
    val dirty = proxyDraft != state.proxySettings
    val portValid = proxyPort.isBlank() || proxyPort.toIntOrNull()?.let { it in 1..65535 } == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Network", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Proxy profile")
                        XdmMetadataText(state.proxySettings.redactedSummary)
                    }
                    StatusPill(if (dirty) "Unsaved" else "Saved", if (dirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    XdmSupportingText("Use proxy", modifier = Modifier.weight(1f))
                    Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                }
                OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = { proxyPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !portValid,
                    supportingText = { Text(if (portValid) "Optional. Use 1–65535." else "Port must be between 1 and 65535.") },
                )
                OutlinedTextField(proxyUsername, { proxyUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(proxyAlias, { proxyAlias = it }, label = { Text("Credential alias") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                XdmActionFlowRow {
                    Button(onClick = { viewModel.setProxySettings(proxyDraft) }, enabled = dirty && portValid) { Text("Save proxy profile") }
                    if (dirty) {
                        TextButton(onClick = {
                            proxyEnabled = state.proxySettings.enabled
                            proxyHost = state.proxySettings.host
                            proxyPort = state.proxySettings.port?.toString().orEmpty()
                            proxyUsername = state.proxySettings.username
                            proxyAlias = state.proxySettings.credentialAlias
                        }) { Text("Reset") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MediaCaptureSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Media & capture", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmGroupedList {
                XdmListRow(
                    headline = "Media inbox",
                    supporting = "${state.mediaCaptures.size} captured item${if (state.mediaCaptures.size == 1) "" else "s"}. Review captures, variants, and completed outputs.",
                    trailing = { Text("Open", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                    onClick = { viewModel.navigate(AppRoute.Media) },
                )
                XdmListSeparator()
                XdmListRow(
                    headline = "Live Locator",
                    supporting = "Open a page inside XDM's isolated locator and scan it for downloadable media.",
                    trailing = { Text("Open", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                    onClick = { context.startActivity(MediaLocatorActivity.intent(context)) },
                )
                XdmListSeparator()
                XdmListRow(
                    headline = "Browser integration",
                    supporting = browserExtensionSummary(state),
                    trailing = { Text("Configure", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                    onClick = { viewModel.selectSettingsPanel(SettingsPanel.BrowserExtension) },
                )
                XdmListSeparator()
                XdmListRow(
                    headline = "Post-processing",
                    supporting = state.postProcessingSettings.redactedSummary,
                    trailing = { Text("Configure", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                    onClick = { viewModel.selectSettingsPanel(SettingsPanel.PostProcessing) },
                )
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Capture privacy")
                XdmSupportingText("Browser cookies, authorization headers, and temporary request context remain private. Normal media cards show only user-facing capture state; raw evidence stays in Developer Center.", maxLines = 5)
            }
        }
    }
}

@Composable
internal fun ExternalToolsSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("External tools", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmSupportingText("Optional integrations live here so normal download and storage settings remain focused. Root actions still require the existing explicit authorization path.", maxLines = 4)
        }
        item {
            XdmListCard {
                XdmCardTitle("Integration status")
                XdmActionFlowRow {
                    XdmStatusBadge(
                        state.termuxBridge.readinessLabel,
                        tone = if (state.termuxBridge.canRunProbe) XdmStatusTone.Success else XdmStatusTone.Warning,
                    )
                    XdmStatusBadge(
                        state.termuxAria2.readinessLabel,
                        tone = if (state.termuxAria2.daemonState.name == "Running") XdmStatusTone.Success else XdmStatusTone.Neutral,
                    )
                }
                XdmSupportingText(state.termuxBridge.summary, maxLines = 3)
                XdmActionFlowRow {
                    Button(onClick = viewModel::runTermuxToolProbe, enabled = state.termuxBridge.canRunProbe) { Text("Check Termux") }
                    TextButton(onClick = viewModel::openTermux, enabled = state.termuxBridge.termuxInstalled) { Text("Open Termux") }
                }
            }
        }
        item { XdmSectionHeader("Termux") }
        item {
            TermuxBridgeSettingsCard(
                termux = state.termuxBridge,
                onRunProbe = viewModel::runTermuxToolProbe,
                onRunPrivacyAudit = viewModel::runTermuxPrivacyAudit,
                onOpenTermux = viewModel::openTermux,
                onRootModeChanged = viewModel::setTermuxRootMode,
                onRunRootProbe = viewModel::runTermuxRootProbe,
                onCollectRootDiagnostics = viewModel::collectTermuxRootProcessDiagnostics,
                onKillStuckAria2WithRoot = viewModel::killStuckTermuxAria2WithRoot,
                onFixDownloadPermissionsWithRoot = viewModel::fixTermuxDownloadPermissionsWithRoot,
            )
        }
        item { XdmSectionHeader("aria2") }
        item {
            TermuxAria2SettingsCard(
                aria2 = state.termuxAria2,
                onEnabledChanged = viewModel::setTermuxAria2Enabled,
                onRotateSecret = viewModel::rotateTermuxAria2Secret,
            )
        }
    }
}

@Composable
internal fun PostProcessingSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    var postEnabled by remember(state.postProcessingSettings) { mutableStateOf(state.postProcessingSettings.enabled) }
    var postPreset by remember(state.postProcessingSettings) { mutableStateOf(state.postProcessingSettings.preset) }
    var postLabel by remember(state.postProcessingSettings) { mutableStateOf(state.postProcessingSettings.customCommandLabel) }
    val postDraft = PostProcessingSettings(postEnabled, postPreset, postLabel)
    val dirty = postDraft != state.postProcessingSettings

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Post-processing", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("After-download pipeline")
                XdmSupportingText("XDM keeps safety-critical finalization first, then admits durable automation, then runs the optional transformation selected below.", maxLines = 4)
                XdmMetadataText("1. Finalize and publish file — always")
                XdmMetadataText("2. Durable automation — ${if (state.postProcessingAutomation.enabled) "enabled" else "disabled"}")
                XdmMetadataText("3. Transformation — ${if (postEnabled) postPreset.displayName() else "disabled"}")
            }
        }
        item {
            PostProcessingAutomationCard(
                automation = state.postProcessingAutomation,
                onEnabledChanged = viewModel::setPostProcessingAutomationEnabled,
                onRetryFailed = viewModel::retryFailedPostProcessing,
                onClearEvents = viewModel::clearPostProcessingEvents,
            )
        }
        item {
            XdmListCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Transformation")
                        XdmMetadataText(state.postProcessingSettings.redactedSummary)
                    }
                    StatusPill(if (dirty) "Unsaved" else "Saved", if (dirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    XdmSupportingText("Run after completion", modifier = Modifier.weight(1f))
                    Switch(checked = postEnabled, onCheckedChange = { postEnabled = it })
                }
                XdmActionFlowRow {
                    ConversionPreset.entries.forEach { preset ->
                        FilterChip(selected = postPreset == preset, onClick = { postPreset = preset }, label = { Text(preset.displayName()) })
                    }
                }
                if (postPreset == ConversionPreset.CustomCommand) {
                    OutlinedTextField(postLabel, { postLabel = it }, label = { Text("Custom label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                XdmActionFlowRow {
                    Button(onClick = { viewModel.setPostProcessingSettings(postDraft) }, enabled = dirty) { Text("Save post-processing") }
                    if (dirty) {
                        TextButton(onClick = {
                            postEnabled = state.postProcessingSettings.enabled
                            postPreset = state.postProcessingSettings.preset
                            postLabel = state.postProcessingSettings.customCommandLabel
                        }) { Text("Reset") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppearanceSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Appearance", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Theme")
                XdmSupportingText("Both themes stay dark and borderless. AMOLED black removes the remaining background glow.", maxLines = 3)
                XdmActionFlowRow {
                    XdmThemeMode.entries.forEach { mode ->
                        FilterChip(selected = state.themeMode == mode, onClick = { viewModel.setThemeMode(mode) }, label = { Text(mode.label) })
                    }
                }
            }
        }
        item {
            SettingsSwitchRow(
                title = "Compact rows",
                summary = "Fit more downloads on screen without hiding progress or primary actions.",
                checked = state.compactDensity,
                onCheckedChange = viewModel::setCompactDensity,
            )
        }
    }
}

@Composable
internal fun BackupRestoreSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Backup & restore", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Portable settings snapshot")
                XdmSupportingText("Copy a safe backup or paste one here. Passwords, transient browser secrets, and Developer mode are not exported.", maxLines = 4)
                XdmMetadataText(state.backupRestoreReport.summary)
                Button(onClick = { copyTextToClipboard(context, "XDM settings snapshot", state.settingsExportText) }) { Text("Copy export") }
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("Paste settings snapshot") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
                XdmActionFlowRow {
                    Button(
                        onClick = {
                            viewModel.importSettingsSnapshot(importText)
                            importText = ""
                        },
                        enabled = importText.isNotBlank(),
                    ) { Text("Import snapshot") }
                    if (importText.isNotBlank()) TextButton(onClick = { importText = "" }) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun DestinationRulePickerDialog(
    state: MainUiState,
    selectedUri: String,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val common = DestinationCatalog.available(Build.VERSION.SDK_INT)
    val saved = state.destinationPermissions
        .filter { it.persistedWrite }
        .distinctBy { it.uri }
        .filterNot { savedDestination -> common.any { it.uri == savedDestination.uri } }
        .take(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose rule destination") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { XdmSectionLabel("Common locations") }
                common.forEach { choice ->
                    item(key = choice.uri) {
                        XdmListRow(
                            headline = choice.label,
                            supporting = destinationUiHint(choice.uri),
                            trailing = if (choice.uri == selectedUri) ({ Text("Selected", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }) else null,
                            onClick = { onChoose(choice.uri) },
                        )
                    }
                }
                if (saved.isNotEmpty()) {
                    item { XdmSectionLabel("Your folders") }
                    saved.forEach { destination ->
                        item(key = destination.uri) {
                            XdmListRow(
                                headline = destination.displayName,
                                supporting = humanizeAdvancedName(destination.status.name),
                                trailing = if (destination.uri == selectedUri) ({ Text("Selected", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }) else null,
                                onClick = { onChoose(destination.uri) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun humanizeAdvancedName(value: String): String = value
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .lowercase()
    .replaceFirstChar(Char::titlecase)
