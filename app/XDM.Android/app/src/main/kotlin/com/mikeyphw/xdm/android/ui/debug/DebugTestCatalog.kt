package com.mikeyphw.xdm.android.ui.debug

import android.util.Base64
import com.mikeyphw.xdm.android.AppContainer
import com.mikeyphw.xdm.android.BrowserBridgeSchemeState
import com.mikeyphw.xdm.android.BrowserCaptureEnvelopeManager
import com.mikeyphw.xdm.android.DebugWorkbenchRuntimeSelfTestSuite
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.RuntimeSelfTestLabels
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkContract
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DebugSeverity
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.model.QueueExecutionPolicy
import com.mikeyphw.xdm.android.model.QueueIntelligencePlanner
import com.mikeyphw.xdm.android.model.QueueRuntimeConditions
import com.mikeyphw.xdm.android.storage.DestinationUris
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

interface DebugTest {
    val id: String
    val group: DebugTestGroup
    val name: String
    val description: String

    suspend fun run(context: DebugTestContext): DebugTestResult

    fun definition(): DebugTestDefinition = DebugTestDefinition(
        id = id,
        group = group,
        name = name,
        description = description,
    )
}

data class DebugTestContext(
    val state: MainUiState,
    val privateRoot: File,
    val recorder: DebugEventRecorder,
    val appContainer: AppContainer?,
    val runId: String,
    val clock: () -> Long = System::currentTimeMillis,
    val isStopRequested: () -> Boolean = { false },
) {
    fun ensureNotStopped() {
        if (isStopRequested()) {
            throw kotlinx.coroutines.CancellationException("Debug Center run stopped")
        }
    }
}

object DebugTestRegistry {
    val tests: List<DebugTest> = listOf(
        AppRuntimeDebugTest,
        PrivateStorageDebugTest,
        SupportExportDebugTest,
        DownloadRepositoryRoundTripDebugTest,
        DownloadStateVisibilityDebugTest,
        QueuePolicyAcceptanceDebugTest,
        MediaCaptureLinkDebugTest,
        MediaDownloadTransactionDebugTest,
        MediaSnifferSmokeDebugTest,
        BrowserBridgeDebugTest,
        FirefoxSecureHandoffDebugTest,
        FirefoxEncryptedEnvelopeDecodeDebugTest,
        NativeBackendDebugTest,
        Aria2RuntimeDebugTest,
        TermuxRuntimeDebugTest,
        PrivacyRedactionDebugTest,
    )

    val definitions: List<DebugTestDefinition> = tests.map { it.definition() }

    fun defaultSelection(): Set<String> = tests
        .filter { it.group in setOf(DebugTestGroup.BasicHealth, DebugTestGroup.Downloads, DebugTestGroup.Media, DebugTestGroup.Browser, DebugTestGroup.Privacy) }
        .mapTo(linkedSetOf()) { it.id }

    fun groupIds(group: DebugTestGroup): Set<String> = tests
        .filter { it.group == group }
        .mapTo(linkedSetOf()) { it.id }

    fun find(id: String): DebugTest? = tests.firstOrNull { it.id == id }
}

private abstract class StateDebugTest(
    override val id: String,
    override val group: DebugTestGroup,
    override val name: String,
    override val description: String,
) : DebugTest {
    fun result(
        status: DebugTestStatus,
        context: DebugTestContext,
        startedAt: Long,
        summary: String,
        details: Map<String, String> = emptyMap(),
        errorCode: String? = null,
        suggestedAction: String? = null,
    ): DebugTestResult {
        val finishedAt = context.clock()
        val debugResult = DebugTestResult(
            testId = id,
            groupId = group.label,
            name = name,
            status = status,
            startedAtEpochMs = startedAt,
            durationMs = (finishedAt - startedAt).coerceAtLeast(0),
            summary = summary,
            details = details,
            errorCode = errorCode,
            suggestedAction = suggestedAction,
        )
        context.recorder.record(
            area = DebugArea.Validation,
            severity = when (status) {
                DebugTestStatus.Failed -> DebugSeverity.Error
                DebugTestStatus.Warning -> DebugSeverity.Warning
                else -> DebugSeverity.Info
            },
            action = "debug-test:$id",
            result = status.name.lowercase(),
            safeDetails = details + mapOf("summary" to summary, "test" to name),
            sessionId = context.runId,
            timestampMillis = startedAt,
            operationId = id,
            parentOperationId = context.runId,
        )
        return debugResult
    }

    fun missingRuntimeContext(context: DebugTestContext, startedAt: Long): DebugTestResult = result(
        status = DebugTestStatus.Skipped,
        context = context,
        startedAt = startedAt,
        summary = "The Android application container is not available in this context.",
        details = mapOf("container" to "missing"),
        errorCode = "runtime-container-unavailable",
        suggestedAction = "Run this test from the installed Android app, not from a static preview.",
    )
}

private object AppRuntimeDebugTest : StateDebugTest(
    id = "app-runtime",
    group = DebugTestGroup.BasicHealth,
    name = "App runtime",
    description = "Checks the Debug Workbench shell, recorder readiness, and runtime status signals.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val report = context.state.debugWorkbenchReport
        val status = if (report.failingChecks == 0) DebugTestStatus.Passed else DebugTestStatus.Failed
        return result(
            status = status,
            context = context,
            startedAt = startedAt,
            summary = report.overallLabel,
            details = mapOf(
                "session" to report.sessionLabel,
                "recorder" to report.recorderStorageLabel,
                "retention" to report.retentionLabel,
            ),
            errorCode = if (status == DebugTestStatus.Failed) "debug-workbench-shell-failed" else null,
            suggestedAction = if (status == DebugTestStatus.Failed) "Open Advanced Debug Workbench and copy the debug status." else "No action needed.",
        )
    }
}

private object PrivateStorageDebugTest : StateDebugTest(
    id = "private-storage",
    group = DebugTestGroup.Storage,
    name = "Private debug storage",
    description = "Creates and reads a private Debug Center probe file without touching user downloads.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val probe = File(context.privateRoot, "probe.txt")
        val outcome = runCatching {
            context.privateRoot.mkdirs()
            probe.writeText("debug-center-probe", Charsets.UTF_8)
            probe.readText(Charsets.UTF_8) == "debug-center-probe"
        }
        return if (outcome.getOrDefault(false)) {
            result(
                status = DebugTestStatus.Passed,
                context = context,
                startedAt = startedAt,
                summary = "Private Debug Center storage is writable and readable.",
                details = mapOf("path" to context.privateRoot.absolutePath),
                suggestedAction = "No action needed.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "Private Debug Center storage could not be written or read.",
                details = mapOf("path" to context.privateRoot.absolutePath, "error" to (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                errorCode = "debug-storage-unavailable",
                suggestedAction = "Check app storage state, then rerun Basic Health.",
            )
        }
    }
}

private object SupportExportDebugTest : StateDebugTest(
    id = "support-export",
    group = DebugTestGroup.BasicHealth,
    name = "Support export",
    description = "Verifies that a redacted support report is available to include beside Debug Center results.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val available = context.state.supportReportText.isNotBlank()
        return result(
            status = if (available) DebugTestStatus.Passed else DebugTestStatus.Warning,
            context = context,
            startedAt = startedAt,
            summary = if (available) "Redacted support report text is available." else "Support report text is empty for the current state.",
            details = mapOf("characters" to context.state.supportReportText.length.toString()),
            errorCode = if (available) null else "support-report-empty",
            suggestedAction = if (available) "Export Results to create a debug ZIP." else "Reproduce the issue, then rerun and export again.",
        )
    }
}

private object DownloadRepositoryRoundTripDebugTest : StateDebugTest(
    id = "download-repository-round-trip",
    group = DebugTestGroup.Downloads,
    name = "Download repository round trip",
    description = "Creates, reads, verifies, and deletes a temporary queued Download row.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val repository = context.appContainer?.repository ?: return missingRuntimeContext(context, startedAt)
        val id = "debug-center-download-$startedAt"
        val download = Download(
            id = id,
            fileName = "debug-center-probe.bin",
            sourceUrl = "https://example.invalid/xdm-debug-center-probe.bin",
            destinationUri = DestinationUris.APP_PRIVATE_DOWNLOADS,
            state = DownloadState.Queued,
            backend = BackendType.Native,
            bytesReceived = 0L,
            totalBytes = 1024L,
            speedBytesPerSecond = 0L,
            queueId = null,
            priority = 0,
            createdAtEpochMs = startedAt,
            updatedAtEpochMs = startedAt,
            userLabel = "Debug Center temporary probe",
            conflictPolicy = FilenameConflictPolicy.Rename,
            mimeType = "application/octet-stream",
            requestedBackend = BackendType.Native,
            backendSelectionReason = BackendSelectionReason.UserForced,
            backendSelectionExplanation = "Debug Center repository round-trip probe.",
        )
        val outcome = runCatching {
            check(repository.save(download)) { "Download save returned false" }
            val loaded = requireNotNull(repository.findDownload(id)) { "Saved Download could not be read back" }
            check(loaded.state == DownloadState.Queued) { "Expected Queued state but found ${loaded.state}" }
            check(loaded.fileName == download.fileName) { "Readback fileName mismatch" }
            loaded
        }
        runCatching { repository.deleteDownload(id) }
        return if (outcome.isSuccess) {
            result(
                status = DebugTestStatus.Passed,
                context = context,
                startedAt = startedAt,
                summary = "Temporary queued Download was created, read back, and cleaned up.",
                details = mapOf("downloadId" to id, "state" to DownloadState.Queued.name),
                suggestedAction = "No action needed.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "Download repository round trip failed.",
                details = mapOf("downloadId" to id, "error" to (outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                errorCode = "download-repository-round-trip-failed",
                suggestedAction = "Do not trust Add Download or media handoff until repository save/readback succeeds.",
            )
        }
    }
}

private object DownloadStateVisibilityDebugTest : StateDebugTest(
    id = "download-state-visibility",
    group = DebugTestGroup.Downloads,
    name = "Download state visibility",
    description = "Verifies that Queued, Waiting, Failed, and active states can be represented in diagnostics.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val visibleStates = setOf(
            DownloadState.Created,
            DownloadState.Queued,
            DownloadState.WaitingForNetwork,
            DownloadState.WaitingForPower,
            DownloadState.Connecting,
            DownloadState.Downloading,
            DownloadState.Failed,
        )
        val visible = context.state.downloads.count { it.state in visibleStates }
        return result(
            status = if (context.state.downloads.isEmpty()) DebugTestStatus.Warning else DebugTestStatus.Passed,
            context = context,
            startedAt = startedAt,
            summary = if (context.state.downloads.isEmpty()) "No Download rows are loaded." else "$visible diagnostic-visible row(s) among ${context.state.downloads.size} Download row(s).",
            details = context.state.downloads.groupingBy { it.state.name }.eachCount().mapValues { it.value.toString() },
            errorCode = if (context.state.downloads.isEmpty()) "download-list-empty" else null,
            suggestedAction = if (context.state.downloads.isEmpty()) "Create a download, then rerun this test." else "Use Downloads > All when tracking a newly queued or failed row.",
        )
    }
}


private object QueuePolicyAcceptanceDebugTest : StateDebugTest(
    id = "queue-policy-acceptance",
    group = DebugTestGroup.Downloads,
    name = "Queue policy acceptance",
    description = "Runs the pure queue-intelligence planner against synthetic ready and concurrency-limited conditions without launching a transfer.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val policy = QueueExecutionPolicy(maxConcurrent = 1)
        val readyConditions = QueueRuntimeConditions(
            connected = true,
            validated = true,
            unmetered = true,
            wifi = true,
            charging = true,
            batteryPercent = 100,
            availableStorageBytes = QueueExecutionPolicy.DEFAULT_STORAGE_RESERVE_BYTES * 2,
            nowEpochMs = startedAt,
        )
        val startDecision = QueueIntelligencePlanner.decision(
            policy = policy,
            conditions = readyConditions,
            queueEnabled = true,
            scheduleActive = true,
            activeCount = 0,
        )
        val concurrencyDecision = QueueIntelligencePlanner.decision(
            policy = policy,
            conditions = readyConditions,
            queueEnabled = true,
            scheduleActive = true,
            activeCount = 1,
        )
        val ok = startDecision.canStart && !concurrencyDecision.canStart
        return result(
            status = if (ok) DebugTestStatus.Passed else DebugTestStatus.Failed,
            context = context,
            startedAt = startedAt,
            summary = if (ok) {
                "Queue planner accepts a ready transfer and holds when concurrency is full."
            } else {
                "Queue planner did not produce the expected ready/hold decisions."
            },
            details = mapOf(
                "readyDisposition" to startDecision.disposition.name,
                "readyTitle" to startDecision.title,
                "concurrencyDisposition" to concurrencyDecision.disposition.name,
                "concurrencyReason" to (concurrencyDecision.reason?.name ?: "none"),
            ),
            errorCode = if (ok) null else "queue-policy-acceptance-failed",
            suggestedAction = if (ok) "No action needed." else "Inspect queue policy configuration before trusting automatic starts.",
        )
    }
}

private object MediaCaptureLinkDebugTest : StateDebugTest(
    id = "media-download-link",
    group = DebugTestGroup.Media,
    name = "Media to Download link",
    description = "Detects sniffed captures that were marked DownloadCreated but do not resolve to a Download row.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val downloadIds = context.state.downloads.mapTo(hashSetOf()) { it.id }
        val linkedCaptures = context.state.mediaCaptures.filter { it.status == MediaCaptureStatus.DownloadCreated }
        val orphans = linkedCaptures.count { capture -> capture.downloadId == null || capture.downloadId !in downloadIds }
        val status = when {
            orphans > 0 -> DebugTestStatus.Failed
            linkedCaptures.isNotEmpty() -> DebugTestStatus.Passed
            context.state.mediaCaptures.isNotEmpty() -> DebugTestStatus.Warning
            else -> DebugTestStatus.Skipped
        }
        return result(
            status = status,
            context = context,
            startedAt = startedAt,
            summary = when {
                orphans > 0 -> "$orphans media capture(s) reference a missing Download row."
                linkedCaptures.isNotEmpty() -> "All linked media captures resolve to Download rows."
                context.state.mediaCaptures.isNotEmpty() -> "Media captures exist, but none are linked to a Download yet."
                else -> "No media captures are currently loaded."
            },
            details = mapOf(
                "mediaCaptures" to context.state.mediaCaptures.size.toString(),
                "linkedCaptures" to linkedCaptures.size.toString(),
                "orphanLinks" to orphans.toString(),
            ),
            errorCode = if (orphans > 0) "media-download-orphan" else null,
            suggestedAction = when (status) {
                DebugTestStatus.Failed -> "Retry the media handoff; the capture should remain visible instead of disappearing."
                DebugTestStatus.Warning -> "Press Download on a candidate, then rerun this test."
                DebugTestStatus.Skipped -> "Sniff a page or receive a browser capture first."
                else -> "No action needed."
            },
        )
    }
}

private object MediaDownloadTransactionDebugTest : StateDebugTest(
    id = "media-download-transaction",
    group = DebugTestGroup.Media,
    name = "Media capture transaction",
    description = "Creates a temporary MediaCapture, converts it to a Download with the atomic repository API, verifies the link, and cleans up.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val repository = context.appContainer?.repository ?: return missingRuntimeContext(context, startedAt)
        val captureId = "debug-center-capture-$startedAt"
        val variantId = "debug-center-variant-$startedAt"
        val downloadId = "debug-center-media-download-$startedAt"
        val source = "https://example.invalid/xdm-debug-center-media.mp4"
        val capture = MediaCaptureRecord(
            id = captureId,
            sourceUrl = source,
            pageUrl = "https://example.invalid/debug-center-page",
            title = "Debug Center media probe",
            status = MediaCaptureStatus.MetadataReady,
            kind = MediaSourceKind.DirectFile,
            mimeType = "video/mp4",
            container = "mp4",
            codecs = null,
            durationMs = null,
            thumbnailUrl = null,
            fileName = "debug-center-media.mp4",
            variantCount = 1,
            downloadId = null,
            createdAtEpochMs = startedAt,
            updatedAtEpochMs = startedAt,
            selectedVariantId = variantId,
            selectedVariantUrl = source,
            resolutionStatus = MediaResolutionStatus.Resolved,
        )
        val variant = MediaVariant(
            id = variantId,
            captureId = captureId,
            url = source,
            kind = MediaVariantKind.Primary,
            mimeType = "video/mp4",
            position = 0,
            displayLabel = "Debug Center primary variant",
        )
        val download = Download(
            id = downloadId,
            fileName = "debug-center-media.mp4",
            sourceUrl = source,
            destinationUri = DestinationUris.APP_PRIVATE_DOWNLOADS,
            state = DownloadState.Queued,
            backend = BackendType.Native,
            bytesReceived = 0L,
            totalBytes = null,
            speedBytesPerSecond = 0L,
            queueId = null,
            priority = 0,
            createdAtEpochMs = startedAt,
            updatedAtEpochMs = startedAt,
            userLabel = "Debug Center media handoff probe",
            mimeType = "video/mp4",
            requestedBackend = BackendType.Native,
            backendSelectionReason = BackendSelectionReason.UserForced,
            backendSelectionExplanation = "Debug Center media-to-download transaction probe.",
        )
        val outcome = runCatching {
            repository.saveMediaCaptureWithVariants(capture, listOf(variant), startedAt)
            val created = repository.createDownloadFromMediaCapture(captureId, download, startedAt)
            check(created.isSuccess) { created.exceptionOrNull()?.message ?: "createDownloadFromMediaCapture returned failure" }
            val linkedCapture = requireNotNull(repository.findMediaCapture(captureId)) { "Linked capture could not be read back" }
            val linkedDownload = requireNotNull(repository.findDownload(downloadId)) { "Linked Download could not be read back" }
            check(linkedCapture.status == MediaCaptureStatus.DownloadCreated) { "Capture status was ${linkedCapture.status}" }
            check(linkedCapture.downloadId == downloadId) { "Capture linked to ${linkedCapture.downloadId}" }
            check(linkedDownload.state == DownloadState.Queued) { "Download state was ${linkedDownload.state}" }
            linkedCapture to linkedDownload
        }
        runCatching { repository.deleteMediaCapture(captureId) }
        runCatching { repository.deleteDownload(downloadId) }
        return if (outcome.isSuccess) {
            result(
                status = DebugTestStatus.Passed,
                context = context,
                startedAt = startedAt,
                summary = "MediaCapture to Download transaction committed, linked, read back, and cleaned up.",
                details = mapOf("captureId" to captureId, "downloadId" to downloadId, "state" to DownloadState.Queued.name),
                suggestedAction = "No action needed.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "MediaCapture to Download transaction failed.",
                details = mapOf("captureId" to captureId, "downloadId" to downloadId, "error" to (outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                errorCode = "media-download-transaction-failed",
                suggestedAction = "Do not trust sniffed media Download actions until this transaction passes.",
            )
        }
    }
}

private object MediaSnifferSmokeDebugTest : StateDebugTest(
    id = "media-sniffer-smoke",
    group = DebugTestGroup.Media,
    name = "Media sniffer smoke",
    description = "Runs the existing runtime self-test result for the media sniffer path.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult = runtimeSuiteResult(context, this, "media-sniffer")
}

private object PrivacyRedactionDebugTest : StateDebugTest(
    id = "privacy-redaction",
    group = DebugTestGroup.Privacy,
    name = "Privacy redaction",
    description = "Runs the existing runtime self-test result for redaction before copy/share/export.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult = runtimeSuiteResult(context, this, "redaction")
}

private object BrowserBridgeDebugTest : StateDebugTest(
    id = "browser-bridge",
    group = DebugTestGroup.Browser,
    name = "Browser bridge",
    description = "Checks the Android browser scheme and extension bridge readiness state.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val bridge = context.state.browserBridgeStatus
        val status = when {
            bridge.isReady -> DebugTestStatus.Passed
            bridge.schemeState == BrowserBridgeSchemeState.Ready -> DebugTestStatus.Warning
            else -> DebugTestStatus.Failed
        }
        return result(
            status = status,
            context = context,
            startedAt = startedAt,
            summary = "Scheme: ${bridge.schemeState.displayLabel}. Export access: ${bridge.safState.displayLabel}.",
            details = mapOf(
                "scheme" to bridge.schemeState.displayLabel,
                "export" to bridge.safState.displayLabel,
                "ready" to bridge.isReady.toString(),
            ),
            errorCode = if (status == DebugTestStatus.Failed) "browser-bridge-not-ready" else null,
            suggestedAction = if (bridge.isReady) "No action needed." else "Open Browser extension settings and regenerate or check the XPI.",
        )
    }
}

private object FirefoxSecureHandoffDebugTest : StateDebugTest(
    id = "firefox-secure-handoff",
    group = DebugTestGroup.Browser,
    name = "Firefox secure handoff key wrap",
    description = "Runs AndroidKeyStore RSA-OAEP key-wrap self-test with the same capture manager used by Firefox/IronFox handoff.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val manager = BrowserCaptureEnvelopeManager()
        val outcome = manager.selfTestKeyWrap()
        val current = context.state.browserExtension.isCurrent(
            appTheme = context.state.themeMode,
            appVersion = com.mikeyphw.xdm.android.BuildConfig.VERSION_NAME,
            applicationId = com.mikeyphw.xdm.android.BuildConfig.APPLICATION_ID,
            scheme = BrowserExtensionSourceContract.DefaultScheme,
        )
        return if (outcome.isSuccess) {
            result(
                status = if (current) DebugTestStatus.Passed else DebugTestStatus.Warning,
                context = context,
                startedAt = startedAt,
                summary = if (current) "AndroidKeyStore RSA-OAEP key-wrap self-test passed and the generated XPI is current." else "AndroidKeyStore RSA-OAEP self-test passed, but the generated XPI may be stale.",
                details = mapOf(
                    "oaepHash" to manager.captureOaepHash,
                    "expectedWrappedKeyBytes" to manager.expectedWrappedKeyBytes.toString(),
                    "keyIdPrefix" to manager.keyId.take(8),
                    "xpiCurrent" to current.toString(),
                ),
                errorCode = if (current) null else "firefox-xpi-stale",
                suggestedAction = if (current) "Capture a page again and verify Media receives it." else "Regenerate the Firefox/IronFox XPI before retesting secure capture.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "AndroidKeyStore RSA-OAEP key-wrap self-test failed.",
                details = mapOf("error" to (outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                errorCode = "firefox-secure-handoff-keywrap-failed",
                suggestedAction = "Regenerate the XPI after this test passes; if it keeps failing, capture the exported debug ZIP.",
            )
        }
    }
}

private object FirefoxEncryptedEnvelopeDecodeDebugTest : StateDebugTest(
    id = "firefox-encrypted-envelope-decode",
    group = DebugTestGroup.Browser,
    name = "Firefox encrypted envelope decode",
    description = "Builds a synthetic WebCrypto-compatible RSA-OAEP/AES-GCM envelope, decrypts it through BrowserCaptureEnvelopeManager, and verifies the decoded media candidate.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val manager = BrowserCaptureEnvelopeManager()
        val outcome = runCatching {
            val payload = syntheticEncryptedCapturePayload(manager, startedAt)
            val decoded = manager.decrypt(payload, nowEpochMs = startedAt).getOrThrow()
            check(decoded.sessionId == payload.captureSessionId) { "Decoded session id mismatch" }
            check(decoded.candidates.size == 1) { "Expected one candidate but decoded ${decoded.candidates.size}" }
            check(decoded.candidates.first().url == "https://example.invalid/debug-center-media.mp4") { "Decoded candidate URL mismatch" }
            decoded
        }
        return if (outcome.isSuccess) {
            val decoded = outcome.getOrThrow()
            result(
                status = DebugTestStatus.Passed,
                context = context,
                startedAt = startedAt,
                summary = "Synthetic encrypted Firefox capture envelope decrypted and decoded successfully.",
                details = mapOf(
                    "sessionId" to decoded.sessionId,
                    "candidates" to decoded.candidates.size.toString(),
                    "oaepHash" to manager.captureOaepHash,
                    "wrappedKeyBytes" to manager.expectedWrappedKeyBytes.toString(),
                ),
                suggestedAction = "Regenerate the XPI if real Firefox captures still fail.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "Synthetic encrypted Firefox capture envelope failed to decode.",
                details = mapOf("error" to (outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                errorCode = "firefox-encrypted-envelope-decode-failed",
                suggestedAction = "Do not trust extension capture until this Android-side decrypt/decode test passes.",
            )
        }
    }
}

private object NativeBackendDebugTest : StateDebugTest(
    id = "native-backend",
    group = DebugTestGroup.Backends,
    name = "Native backend",
    description = "Checks whether native backend capability rows are visible to diagnostics.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val rows = context.state.backendCapabilities
        val nativeRows = rows.filter { it.backend == BackendType.Native }
        return result(
            status = if (nativeRows.isNotEmpty()) DebugTestStatus.Passed else DebugTestStatus.Warning,
            context = context,
            startedAt = startedAt,
            summary = if (nativeRows.isNotEmpty()) "Native backend capability row is loaded." else "No Native backend capability row is loaded yet.",
            details = mapOf("capabilityRows" to rows.size.toString(), "nativeRows" to nativeRows.size.toString()),
            errorCode = if (nativeRows.isEmpty()) "native-backend-capability-empty" else null,
            suggestedAction = if (nativeRows.isNotEmpty()) "No action needed." else "Open Downloads or run Basic Health to refresh backend diagnostics.",
        )
    }
}

private object Aria2RuntimeDebugTest : StateDebugTest(
    id = "aria2-runtime",
    group = DebugTestGroup.Backends,
    name = "aria2 runtime",
    description = "Summarizes packaged aria2 status without starting downloads.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val diagnostics = context.state.aria2Diagnostics
        val status = when {
            diagnostics.status.contains("ready", ignoreCase = true) || diagnostics.status.contains("pass", ignoreCase = true) -> DebugTestStatus.Passed
            diagnostics.status.contains("fail", ignoreCase = true) -> DebugTestStatus.Failed
            else -> DebugTestStatus.Warning
        }
        return result(
            status = status,
            context = context,
            startedAt = startedAt,
            summary = diagnostics.status + ". " + diagnostics.detail,
            details = mapOf(
                "status" to diagnostics.status,
                "canRunSmokeTest" to diagnostics.canRunSmokeTest.toString(),
                "canRepair" to diagnostics.canRepair.toString(),
            ),
            errorCode = if (status == DebugTestStatus.Failed) "aria2-runtime-failed" else null,
            suggestedAction = "Use Storage doctor or aria2 repair only if this backend is required.",
        )
    }
}

private object TermuxRuntimeDebugTest : StateDebugTest(
    id = "termux-runtime",
    group = DebugTestGroup.Backends,
    name = "Termux runtime",
    description = "Summarizes Termux bridge/media-pipeline state without launching Termux work.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val bridge = context.state.termuxBridge
        val media = context.state.termuxMediaPipeline
        val summary = "Bridge: ${bridge.readinessLabel}. Media pipeline: ${media.readinessLabel}."
        val status = when {
            summary.contains("fail", ignoreCase = true) -> DebugTestStatus.Failed
            summary.contains("ready", ignoreCase = true) -> DebugTestStatus.Passed
            else -> DebugTestStatus.Warning
        }
        return result(
            status = status,
            context = context,
            startedAt = startedAt,
            summary = summary,
            details = mapOf(
                "bridge" to bridge.readinessLabel,
                "mediaPipeline" to media.readinessLabel,
            ),
            errorCode = if (status == DebugTestStatus.Failed) "termux-runtime-failed" else null,
            suggestedAction = "Keep this optional unless a selected media plan requires Termux/yt-dlp.",
        )
    }
}

private fun syntheticEncryptedCapturePayload(
    manager: BrowserCaptureEnvelopeManager,
    nowEpochMs: Long,
): XdmBrowserDeepLinkPayload {
    val sessionId = "debug-center-session-$nowEpochMs"
    val keyId = manager.keyId
    val clearKey = ByteArray(32).also(SecureRandom()::nextBytes)
    val iv = ByteArray(12).also(SecureRandom()::nextBytes)
    val clearJson = JSONObject()
        .put("v", 1)
        .put("sid", sessionId)
        .put("revision", 1L)
        .put("createdAt", nowEpochMs)
        .put("expiresAt", nowEpochMs + 60_000L)
        .put("pageUrl", "https://example.invalid/debug-center-page")
        .put("title", "Debug Center synthetic capture")
        .put("totalCandidateCount", 1)
        .put("truncated", false)
        .put(
            "candidates",
            JSONArray().put(
                JSONObject()
                    .put("url", "https://example.invalid/debug-center-media.mp4")
                    .put("pageUrl", "https://example.invalid/debug-center-page")
                    .put("title", "Debug Center media")
                    .put("contentType", "video/mp4")
                    .put("stableMediaId", "debug-center-media")
                    .put("sessionRevision", 1L)
                    .put("quality", "strong")
                    .put("reason", "debug-center-synthetic")
                    .put("streamKind", "video")
                    .put("manifest", false)
                    .put("playbackObserved", true)
                    .put("evidence", JSONArray().put("synthetic-debug-center")),
            ),
        )
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

    val aes = Cipher.getInstance("AES/GCM/NoPadding")
    aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(clearKey, "AES"), GCMParameterSpec(128, iv))
    aes.updateAAD("xdm-capture-v2|$sessionId|$keyId".toByteArray(StandardCharsets.UTF_8))
    val ciphertext = aes.doFinal(clearJson)

    val publicKey = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(decodeBase64Url(manager.publicKeySpkiBase64Url)),
    )
    val mgf1 = if (manager.captureOaepHash.equals("SHA-256", ignoreCase = true)) MGF1ParameterSpec.SHA256 else MGF1ParameterSpec.SHA1
    val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
    rsa.init(
        Cipher.ENCRYPT_MODE,
        publicKey,
        OAEPParameterSpec(manager.captureOaepHash, "MGF1", mgf1, PSource.PSpecified.DEFAULT),
    )
    val wrapped = rsa.doFinal(clearKey)

    return XdmBrowserDeepLinkPayload(
        version = XdmBrowserDeepLinkContract.CurrentVersion,
        action = AutomationCommandAction.CaptureMedia,
        captureSessionId = sessionId,
        captureKeyId = keyId,
        wrappedKey = base64Url(wrapped),
        envelopeIv = base64Url(iv),
        envelopeCiphertext = base64Url(ciphertext),
    )
}

private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun decodeBase64Url(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

private fun runtimeSuiteResult(
    context: DebugTestContext,
    test: StateDebugTest,
    checkId: String,
): DebugTestResult {
    val startedAt = context.clock()
    context.ensureNotStopped()
    val suite = DebugWorkbenchRuntimeSelfTestSuite.fromState(context.state)
    val check = suite.checks.firstOrNull { it.id == checkId }
    val status = when (check?.statusLabel) {
        RuntimeSelfTestLabels.Pass -> DebugTestStatus.Passed
        RuntimeSelfTestLabels.Fail -> DebugTestStatus.Failed
        RuntimeSelfTestLabels.Note -> DebugTestStatus.Warning
        else -> DebugTestStatus.Skipped
    }
    return test.result(
        status = status,
        context = context,
        startedAt = startedAt,
        summary = check?.detail ?: "Runtime self-test result was not available.",
        details = mapOf(
            "checkId" to checkId,
            "status" to (check?.statusLabel ?: "missing"),
        ),
        errorCode = if (status == DebugTestStatus.Failed) "runtime-self-test-failed" else null,
        suggestedAction = check?.fixHint ?: "Run the full runtime self-test suite.",
    )
}
