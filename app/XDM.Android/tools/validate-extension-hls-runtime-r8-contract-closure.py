#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(path: str) -> str:
    candidate = ROOT / path
    if not candidate.exists():
        errors.append(f"missing {path}")
        return ""
    return candidate.read_text(errors="replace")


def compact(path: str) -> str:
    return re.sub(r"\s+", " ", text(path))


def require(path: str, needle: str, label: str) -> None:
    if needle not in text(path):
        errors.append(f"{label}: missing {needle!r} in {path}")


def require_compact(path: str, needle: str, label: str) -> None:
    normalized = re.sub(r"\s+", " ", needle).strip()
    if normalized not in compact(path):
        errors.append(f"{label}: missing compact snippet in {path}: {needle!r}")


def forbid(path: str, needle: str, label: str) -> None:
    if needle in text(path):
        errors.append(f"{label}: forbidden {needle!r} in {path}")


def require_regex(path: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text(path), re.S):
        errors.append(f"{label}: pattern not found in {path}: {pattern}")


# Keyboard and sheet regressions.
require_compact('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt', 'title = "New download", scrollContent = false,', 'Add Download sheet owns its own scroll')
require_compact('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt', 'title = "Manage activity", scrollContent = false,', 'Manage Activity sheet owns its own scroll')
forbid('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt', 'Box(Modifier.fillMaxSize().padding(padding).imePadding())', 'Shell must not double-apply IME padding')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt', 'Column(Modifier.fillMaxSize().imePadding()', 'Add Download must own keyboard-safe IME padding inside the sheet')
require_compact('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt', 'LazyColumn( modifier = Modifier.weight(1f),', 'Add Download fields remain in a bounded LazyColumn')
require_compact('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt', 'title = "Actions for ${download.fileName}", scrollContent = false,', 'Three-dot action sheet avoids parent scroll constraint crash')
require_compact('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt', 'title = "Organize downloads", scrollContent = false,', 'Organize sheet avoids nested scroll crash')
require_compact('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt', 'title = "Download details", scrollContent = false,', 'Details sheet avoids nested scroll crash')

# Browser extension metadata and absent-thumbnail regression.
require('browser-extension/src/main/extension/xdm-firefox/handoff.js', 'const raw = String(value || "").trim();', 'Safe URL helper must not resolve blank optional values against the page URL')
require('browser-extension/src/main/extension/xdm-firefox/handoff.js', 'if (!raw) return "";', 'Blank optional thumbnail/poster must be omitted')
require('browser-extension/src/main/extension/xdm-firefox/handoff.js', 'params.set("length", String(Math.floor(length)))', 'Extension forwards content length metadata')
require('browser-extension/src/main/extension/xdm-firefox/handoff.js', 'params.set("durationMs", String(Math.floor(durationMs)))', 'Extension forwards duration metadata')
require('browser-extension/src/main/extension/xdm-firefox/handoff.js', 'params.set("thumbnail", thumbnail)', 'Extension forwards safe thumbnail metadata')
require('browser-extension/tests/test_release_gate.js', 'missing thumbnail must not resolve to the page URL', 'Release gate covers absent-thumbnail regression')
require('browser-extension/tests/test_release_gate.js', 'thumbnailUrl: "https://cdn.example/poster.jpg"', 'Release gate covers explicit thumbnail propagation')
require('browser-extension/src/main/extension/xdm-firefox/frame-bridge.js', 'function elementMetadata(video)', 'Frame bridge extracts HTML video metadata')
require('browser-extension/src/main/extension/xdm-firefox/frame-bridge.js', 'contentLength: input.contentLength', 'Frame bridge passes content length into handoff')
require('browser-extension/src/main/extension/xdm-firefox/frame-bridge.js', 'thumbnailUrl: input.thumbnailUrl', 'Frame bridge passes thumbnail into handoff')
require('browser-extension/src/main/extension/xdm-firefox/network-observer.js', 'contentLength: candidate.contentLength || 0', 'Background candidate dispatch preserves size metadata')

# Parser/model pipeline.
require('browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt', 'const val ContentLengthParameter = "length"', 'Deep-link contract accepts length')
require('browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt', 'const val ThumbnailUrlParameter = "thumbnail"', 'Deep-link contract accepts thumbnail')
require('browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt', 'this[name.lowercase(Locale.US)]', 'Deep-link parser normalizes requested parameter names')
require('browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt', 'thumbnailUrl = thumbnailUrl,', 'Deep-link parser populates thumbnail')
require('browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkPayload.kt', 'durationMs = durationMs,', 'Deep-link payload forwards duration')
require('browser-integration/src/test/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParserTest.kt', 'extensionCaptureParsesOptionalSizeDurationAndThumbnailMetadata', 'Parser regression test covers optional metadata')
require('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt', 'val thumbnailUrl: String? = null,', 'Automation draft carries thumbnail metadata')
require('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt', 'val thumbnailUrl: String? = null,', 'Add Download draft preserves thumbnail metadata')
require('media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt', 'thumbnailUrl = input.thumbnailUrl,', 'Sniffing engine forwards thumbnail')
require('media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt', 'thumbnailUrl = thumbnailUrl?.takeIf(String::isNotBlank),', 'Media records store thumbnail metadata')

# Add Video failure visibility and r4-r6 compile closures.
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'if (!recommendation.compatible) {', 'Incompatible media queue path is explicit')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'resolutionStatus = MediaResolutionStatus.Failed', 'Incompatible queue path marks capture failed')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'navigate(AppRoute.Media)', 'Failed media add returns to visible Media state')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'durationMs = draft.durationMs', 'Extension capture carries duration metadata')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'thumbnailUrl = draft.thumbnailUrl', 'Extension capture carries thumbnail metadata')
require_regex(
    'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt',
    r'fun captureSharedText\([^)]*\) \{.*?viewModelScope\.launch\(Dispatchers\.IO\) \{\s*val now = System\.currentTimeMillis\(\).*?repository\.saveMediaCapturesWithVariants\(merged, sniffingPlan\.variants, now\)',
    'Shared-text capture declares the transactional persistence timestamp',
)
require_compact(
    'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt',
    'record.copy( createdAtEpochMs = existing?.createdAtEpochMs ?: record.createdAtEpochMs, updatedAtEpochMs = now, )',
    'New shared-text captures get a deterministic update timestamp',
)

# r8: source contracts must follow the exact-candidate and atomic repository APIs.
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'exactUrl: String,', 'Manifest refresh accepts the exact captured URL')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'mediaPageProbe.probePage(exactUrl', 'Manifest refresh probes the exact HLS/DASH candidate')
forbid('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'mediaPageProbe.probePage(probeUrl', 'Obsolete retry-local name must not return')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'repository.saveMediaCapturesWithVariants(merged, plan.variants, now)', 'Batch media persistence is transactional')
forbid('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'repository.replaceMediaVariants', 'Batch flow must not return to two-step variant persistence')
require('app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase5BrowserHandoffMediaContractTest.kt', 'mediaPageProbe.probePage(exactUrl', 'Browser handoff contract follows exact candidate naming')
require('app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase5BrowserHandoffMediaContractTest.kt', 'resolver.contains("mediaPageProbe.probePage(probeUrl")', 'Browser handoff contract rejects stale probeUrl spelling')
require('app/src/test/kotlin/com/mikeyphw/xdm/android/MediaBatchPhase46ContractTest.kt', 'repository.saveMediaCapturesWithVariants(merged, plan.variants, now)', 'Batch contract follows atomic persistence')
require('app/src/test/kotlin/com/mikeyphw/xdm/android/MediaBatchPhase46ContractTest.kt', 'batchFlow.contains("repository.replaceMediaVariants")', 'Batch contract rejects deleted two-step persistence')


# r8 closes the persistence contract that still demanded the retired two-call API.
require(
    'persistence/src/test/kotlin/com/mikeyphw/xdm/android/persistence/BugHuntPhase6DatabaseIntegrityContractTest.kt',
    'repository.contains("saveMediaCapturesWithVariants") && repository.contains("database.withTransaction")',
    'Persistence contract requires the atomic repository helper',
)
require(
    'persistence/src/test/kotlin/com/mikeyphw/xdm/android/persistence/BugHuntPhase6DatabaseIntegrityContractTest.kt',
    'batchFlow.contains("repository.saveMediaCapturesWithVariants(merged, plan.variants, now)")',
    'Persistence contract verifies atomic batch wiring',
)
forbid(
    'persistence/src/test/kotlin/com/mikeyphw/xdm/android/persistence/BugHuntPhase6DatabaseIntegrityContractTest.kt',
    'assertTrue(viewModel.contains("repository.replaceMediaVariants"))',
    'Persistence contract must not require the retired two-call API',
)
require(
    'app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase5BrowserHandoffMediaContractTest.kt',
    'private fun androidRoot(): File',
    'Browser handoff contract locates the Android root robustly',
)
require(
    'app/src/test/kotlin/com/mikeyphw/xdm/android/MediaBatchPhase46ContractTest.kt',
    'val batchFlow = source',
    'Batch contract scopes assertions to the batch function',
)

# Mirror the exact source-contract predicates so a validator cannot pass while JUnit fails.
vm = text('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
resolver = vm.split('private suspend fun resolveCapturedPlaylistIfPossible(', 1)[-1].split('private fun MediaVariant.rekeyForCapture', 1)[0]
page_capture = vm.split('fun capturePageUrl(pageUrl: String', 1)[-1].split('fun captureSharedText', 1)[0]
batch_flow = vm.split('fun captureMediaBatchInput(text: String)', 1)[-1].split('fun openDownloadReview', 1)[0]
repository_source = text('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt')
dao_source = text('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt')

mirrored_checks = {
    'page capture probes normalized page URL': 'mediaPageProbe.probePage(normalized' in page_capture,
    'resolver accepts exact URL': 'exactUrl: String' in resolver,
    'resolver probes exact URL': 'mediaPageProbe.probePage(exactUrl' in resolver,
    'resolver rejects stale probeUrl': 'mediaPageProbe.probePage(probeUrl' not in resolver,
    'batch uses atomic repository call': 'repository.saveMediaCapturesWithVariants(merged, plan.variants, now)' in batch_flow,
    'batch omits separate capture save': 'repository.saveMediaCaptures(merged)' not in batch_flow,
    'batch omits separate variant replacement': 'repository.replaceMediaVariants' not in batch_flow,
    'repository helper uses Room transaction': 'saveMediaCapturesWithVariants' in repository_source and 'database.withTransaction' in repository_source,
    'DAO replacement remains transactional': '@Transaction' in dao_source and 'replaceMediaVariantsForCaptures' in dao_source,
    'DAO deletes old variants': 'deleteMediaVariantsForCaptures(captureIds)' in dao_source,
    'DAO reconciles capture metadata': 'reconcileCaptureAfterVariantReplacement' in dao_source,
}
for label, passed in mirrored_checks.items():
    if not passed:
        errors.append(f'mirrored JUnit contract failed: {label}')

# r8: warning cleanup remains explicit and reviewable.
for path in (
    'app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase5BrowserHandoffMediaContractTest.kt',
    'app/src/test/kotlin/com/mikeyphw/xdm/android/MediaBatchPhase46ContractTest.kt',
    'app/src/test/kotlin/com/mikeyphw/xdm/android/Phase65DiagnosticExportDownloadActionContractTest.kt',
    'app/src/test/kotlin/com/mikeyphw/xdm/android/PostProcessingPhase7ContractTest.kt',
    'media/src/test/kotlin/com/mikeyphw/xdm/android/media/BugHuntPhase5MediaSniffingContractTest.kt',
    'persistence/src/test/kotlin/com/mikeyphw/xdm/android/persistence/BugHuntPhase6DatabaseIntegrityContractTest.kt',
):
    forbid(path, 'File(System.getProperty("user.dir"))', 'Nullable user.dir Java-platform warning must stay fixed')
require('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase65DiagnosticExportDownloadActionContractTest.kt', 'requireNotNull(nestedProject.parentFile)', 'Located project parent is made non-null explicitly')
require('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferActionReceiver.kt', '@Suppress("DEPRECATION")', 'Legacy mute compatibility read has scoped deprecation suppression')

if errors:
    print('XDM extension HLS runtime r8 validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('XDM extension HLS runtime r8 validator passed')
