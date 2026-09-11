package com.mikeyphw.xdm.android.ui.debug

import com.mikeyphw.xdm.android.AppContainer
import com.mikeyphw.xdm.android.BrowserBridgeSchemeState
import com.mikeyphw.xdm.android.DebugWorkbenchRuntimeSelfTestSuite
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.RuntimeSelfTestLabels
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkContract
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParser
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DiagnosticExportIntegrity
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
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessState
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
            throw kotlinx.coroutines.CancellationException("Diagnostics run stopped")
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
        FirefoxDirectV3HandoffDebugTest,
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
    description = "Checks the diagnostic health surface, recorder readiness, and runtime status signals.",
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
            suggestedAction = if (status == DebugTestStatus.Failed) "Open Advanced Diagnostics & support and copy the debug status." else "No action needed.",
        )
    }
}

private object PrivateStorageDebugTest : StateDebugTest(
    id = "private-storage",
    group = DebugTestGroup.Storage,
    name = "Private debug storage",
    description = "Creates and reads a private Diagnostics probe file without touching user downloads.",
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
                summary = "Private Diagnostics storage is writable and readable.",
                details = mapOf("path" to context.privateRoot.absolutePath),
                suggestedAction = "No action needed.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "Private Diagnostics storage could not be written or read.",
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
    description = "Verifies that a redacted support report is available to include beside Diagnostics results.",
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
            userLabel = "Diagnostics temporary probe",
            conflictPolicy = FilenameConflictPolicy.Rename,
            mimeType = "application/octet-stream",
            requestedBackend = BackendType.Native,
            backendSelectionReason = BackendSelectionReason.UserForced,
            backendSelectionExplanation = "Diagnostics repository round-trip probe.",
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
            title = "Diagnostics media probe",
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
            displayLabel = "Diagnostics primary variant",
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
            userLabel = "Diagnostics media handoff probe",
            mimeType = "video/mp4",
            requestedBackend = BackendType.Native,
            backendSelectionReason = BackendSelectionReason.UserForced,
            backendSelectionExplanation = "Diagnostics media-to-download transaction probe.",
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
    name = "Final ZIP privacy & integrity",
    description = "Exports a real diagnostics ZIP through the production exporter and scans the exact final artifact for malformed JSONL, manifest drift, and credential leaks.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val probeRoot = File(context.privateRoot, "privacy-final-zip-probe")
        val probeStore = DebugTestStore(probeRoot, retainedRuns = 1)
        val probeRun = DebugTestRun(
            id = "privacy-probe-${context.runId}",
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = startedAt,
            selectedTestIds = listOf("privacy-probe"),
            results = listOf(
                DebugTestResult(
                    testId = "privacy-probe",
                    groupId = DebugTestGroup.Privacy.label,
                    name = "Signed media export probe",
                    status = DebugTestStatus.Passed,
                    startedAtEpochMs = startedAt,
                    durationMs = 0L,
                    summary = "Exporter must redact signed-media request material.",
                ),
            ),
        )
        val rawJsonl = """{"safeDetails":{"url":"https://cdn.example.test/master.m3u8?md5=MD5_SECRET&sess=SESSION_SECRET&token=TOKEN_SECRET","nested":"Authorization: Bearer abcdefghijklmnopqrstuvwxyz"}}"""
        val outcome = runCatching {
            val zip = probeStore.exportRunZip(
                run = probeRun,
                supportReportText = "Cookie: sid=COOKIE_SECRET\nhttps://cdn.example.test/video?signature=SIGNATURE_SECRET",
                debugTimelineJsonl = rawJsonl,
            )
            val scan = probeStore.scanExport(zip)
            check(scan.safe) { scan.summary }
            check(scan.manifestVerified) { "Final diagnostic manifest did not verify" }
            check(DiagnosticExportIntegrity.contractSelfTest()) { "Secret-marker scanner self-test failed" }
            scan
        }
        probeRoot.deleteRecursively()
        return if (outcome.isSuccess) {
            val scan = outcome.getOrThrow()
            result(
                status = DebugTestStatus.Passed,
                context = context,
                startedAt = startedAt,
                summary = "Production diagnostics exporter produced a parseable, manifest-consistent, secret-safe final ZIP.",
                details = mapOf(
                    "entries" to scan.entryCount.toString(),
                    "manifestVerified" to scan.manifestVerified.toString(),
                    "finalArtifactScan" to "passed",
                ),
                suggestedAction = "No action needed. Every real export is rescanned again before sharing.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "Final diagnostics ZIP privacy/integrity verification failed.",
                details = mapOf("error" to (outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                errorCode = "diagnostic-final-zip-unsafe",
                suggestedAction = "Do not share diagnostics ZIPs until this test passes; the exporter blocks unsafe final artifacts.",
            )
        }
    }
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

private object FirefoxDirectV3HandoffDebugTest : StateDebugTest(
    id = "firefox-direct-v3-handoff",
    group = DebugTestGroup.Browser,
    name = "Firefox direct v3 handoff",
    description = "Exercises the production keyless v3 capture parser with a bounded multi-candidate payload. Legacy RSA/encrypted-envelope compatibility is not a release blocker.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val scheme = com.mikeyphw.xdm.android.BuildConfig.XDM_BROWSER_SCHEME
        val sessionId = "debug-v3-${startedAt}"
        val mediaUrl = "https://cdn.example.test/master.m3u8?md5=opaque&sess=opaque"
        val candidates = """[{"url":"https://cdn.example.test/master.m3u8","contentType":"application/vnd.apple.mpegurl","stableMediaId":"debug-hls"},{"url":"https://cdn.example.test/video-720.mp4","contentType":"video/mp4","stableMediaId":"debug-mp4"}]"""
        fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        val deepLink = "$scheme://${XdmBrowserDeepLinkContract.CaptureHost}?v=${XdmBrowserDeepLinkContract.CurrentVersion}" +
            "&url=${enc(mediaUrl)}&page=${enc("https://example.test/watch")}" +
            "&sid=${enc(sessionId)}&sessionRevision=7&candidateCount=2&candidates=${enc(candidates)}"
        val outcome = runCatching {
            val payload = requireNotNull(XdmBrowserDeepLinkParser.parse(deepLink, scheme)) { "v3 capture parser rejected the current direct handoff" }
            check(payload.version == XdmBrowserDeepLinkContract.CurrentVersion)
            check(payload.hasDirectCaptureSession)
            check(!payload.hasEncryptedCaptureEnvelope)
            check(payload.captureSessionId == sessionId)
            check(payload.sessionRevision == 7L)
            check(payload.totalCandidateCount == 2)
            check(payload.directCandidatesJson == candidates)
            payload
        }
        return if (outcome.isSuccess) {
            result(
                status = DebugTestStatus.Passed,
                context = context,
                startedAt = startedAt,
                summary = "Current Firefox keyless v3 capture handoff parsed a two-candidate session without legacy crypto.",
                details = mapOf(
                    "contractVersion" to XdmBrowserDeepLinkContract.CurrentVersion.toString(),
                    "candidateCount" to "2",
                    "legacyEncryptedBlocker" to "retired",
                ),
                suggestedAction = "If a real capture fails, regenerate/check the current XPI and export Diagnostics; do not use legacy crypto tests as a release gate.",
            )
        } else {
            result(
                status = DebugTestStatus.Failed,
                context = context,
                startedAt = startedAt,
                summary = "Current Firefox direct v3 capture handoff failed.",
                details = mapOf("error" to (outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                errorCode = "firefox-direct-v3-handoff-failed",
                suggestedAction = "Repair the v3 direct capture contract before trusting Firefox media handoff.",
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
    name = "aria2 runtime lifecycle",
    description = "Runs the real packaged aria2 lifecycle: executable probe, launch, authenticated loopback RPC, local transfer, pause/resume, save-session, cleanup, and shutdown.",
) {
    override suspend fun run(context: DebugTestContext): DebugTestResult {
        val startedAt = context.clock()
        context.ensureNotStopped()
        val manager = context.appContainer?.aria2ProcessManager ?: return missingRuntimeContext(context, startedAt)
        val capability = manager.probe()
        if (!capability.isAvailable) {
            return result(
                status = DebugTestStatus.Warning,
                context = context,
                startedAt = startedAt,
                summary = "Optional packaged aria2 runtime is unavailable: ${capability.summary}",
                details = mapOf("availability" to capability.availability.name, "nativeBackendAffected" to "false"),
                errorCode = "aria2-runtime-unavailable",
                suggestedAction = "Use Repair aria2 if you need the optional aria2 backend. Native downloads remain independently usable.",
            )
        }
        val outcome = runCatching { manager.smokeTest() }
        val smoke = outcome.getOrNull()
        val passed = smoke?.successful == true
        val runtimeState = manager.state.value
        val failure = (runtimeState as? Aria2ProcessState.Failed)?.diagnostic
        return result(
            status = if (passed) DebugTestStatus.Passed else DebugTestStatus.Failed,
            context = context,
            startedAt = startedAt,
            summary = smoke?.summary ?: "aria2 runtime lifecycle threw ${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "an unknown error"}.",
            details = buildMap {
                put("availability", capability.availability.name)
                put("version", smoke?.version?.version ?: "unknown")
                put("authenticatedRpc", passed.toString())
                put("lifecycleSmoke", passed.toString())
                put("runtimeState", runtimeState::class.java.simpleName)
                failure?.let { diagnostic ->
                    put("failureKind", diagnostic.kind.name)
                    diagnostic.exitCode?.let { put("exitCode", it.toString()) }
                    put("failureDetail", diagnostic.detail)
                    diagnostic.logTail?.takeIf { it.isNotBlank() }?.let { put("runtimeLogTail", it) }
                }
            },
            errorCode = if (passed) null else "aria2-runtime-lifecycle-failed",
            suggestedAction = if (passed) "No action needed." else "Use Repair aria2, rerun this lifecycle test, and export the verified diagnostics ZIP if it still fails. The failure kind, exit code, and redacted runtime log are included when available.",
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
