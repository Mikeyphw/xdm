#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        errors.append(f"Missing required file: {path}")
        return ""
    return file.read_text(encoding="utf-8")


def require(text: str, marker: str, context: str) -> None:
    if marker not in text:
        errors.append(f"{context} missing marker: {marker}")


inventory_path = ROOT / "docs/browser-removal/BROWSER-DOWNLOADER-BOUNDARY.json"
try:
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
except Exception as exc:
    errors.append(f"Boundary inventory is missing or invalid JSON: {exc}")
    inventory = {}

scope = inventory.get("phase_scope", {})
if scope.get("runtime_removal_started") is not False:
    errors.append("Phase 0/1 must record runtime_removal_started=false")
if scope.get("production_kotlin_modified") is not False:
    errors.append("Phase 0/1 must remain production-Kotlin neutral")
if scope.get("android_manifest_modified") is not False:
    errors.append("Phase 0/1 must remain AndroidManifest neutral")

ownership = inventory.get("ownership", {})
for category in (
    "remove_later_browser_runtime",
    "mixed_extract_before_delete",
    "preserve_external_handoff_despite_browser_naming",
    "preserve_downloader_runtime",
):
    if not ownership.get(category):
        errors.append(f"Boundary inventory missing ownership category: {category}")

manifest = read("app/src/main/AndroidManifest.xml")
match = re.search(
    r'<activity\s+[^>]*android:name="\.ExternalAddDownloadActivity"[\s\S]*?</activity>',
    manifest,
)
if not match:
    errors.append("ExternalAddDownloadActivity manifest block is missing")
    external_block = ""
else:
    external_block = match.group(0)
    for marker in (
        "android.intent.action.SEND",
        "android.intent.action.SEND_MULTIPLE",
        "android.intent.action.VIEW",
        'android:scheme="http"',
        'android:scheme="https"',
        'android:scheme="ftp"',
    ):
        require(external_block, marker, "ExternalAddDownloadActivity")
    for mime in re.findall(r'android:mimeType="([^"]+)"', external_block):
        if mime != mime.lower():
            errors.append(f"ExternalAddDownloadActivity MIME type is not lowercase: {mime}")

activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
for marker in (
    "Intent.EXTRA_TEXT",
    "Intent.EXTRA_SUBJECT",
    "intent.clipData",
    "item.uri?.toString()",
    "item.coerceToText(this@MainActivity)?.toString()",
    "AutomationCommandAction.PromptAddDownload",
    "AutomationCommandAction.CaptureMedia",
    "viewModel.ingestAutomationCommand(draft)",
):
    require(activity, marker, "MainActivity external handoff")
if "executionStarter.start" in activity or "viewModel.addDownload(" in activity:
    errors.append("MainActivity external handoff must remain review-first and must not start downloads directly")

receiver = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt")
require(receiver, "class ExternalAddDownloadActivity : MainActivity()", "External receiver")

integration_root = ROOT / "browser-integration/src/main/kotlin"
integration_source = "\n".join(
    path.read_text(encoding="utf-8")
    for path in integration_root.rglob("*.kt")
)
for forbidden in ("android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
    if forbidden in integration_source:
        errors.append(f"External browser-integration module must not contain {forbidden}")
for marker in ("object BrowserHandoffContract", "object SharedLinkParser"):
    require(integration_source, marker, "External browser-integration module")

protected = {
    "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt": "class NativeHttpDownloadBackend",
    "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt": "class EmbeddedAria2Backend",
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt": "class TransferExecutionRuntime",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt": "class MediaDownloadPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt": "class MediaExecutionDispatcher",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt": "class MediaExecutionLibraryPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaNativeDirectDownloadEngine.kt": "class MediaNativeDirectDownloadPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaOfflineLibraryV2.kt": "class MediaOfflineLibraryV2Planner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt": "class MediaPlayerDiagnosticsPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueActions.kt": "class MediaQueueActionPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueTelemetry.kt": "class MediaQueueTelemetryPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt": "class MediaTermuxRuntimeAdapter",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt": "class MediaWorkerBridgePlanner",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt": "fun Media3DirectPlayerCard",
}
for path, marker in protected.items():
    require(read(path), marker, path)

app_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPreservationContractTest.kt")
core_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloaderHandoffPreservationTest.kt")
parser_test = read("browser-integration/src/test/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParserTest.kt")
for marker in (
    "dedicatedExternalReceiverRemainsReviewFirst",
    "sharesheetIntakeInspectsTextSubjectAndClipData",
    "transferEnginesAndExecutionRuntimeRemainProtected",
    "mediaResolverQueueWorkerAndPlaybackRemainProtected",
):
    require(app_test, marker, "App preservation tests")
for marker in (
    "externalDownloadUrlsNormalizeSupportedSchemes",
    "sensitiveHeadersRemainRedactedWhileSafeHeadersSurvive",
    "alreadyRedactedPlaceholdersRemainSafeAndStable",
):
    require(core_test, marker, "Core-model preservation tests")
for marker in ("ignoresUnsafeAndNonShareSchemes", "acceptsNewlinesAndAngleBracketWrappedLinks"):
    require(parser_test, marker, "SharedLinkParser preservation tests")

project_manifest = read("PROJECT_MANIFEST.json")
require(project_manifest, '"browser_removal_phase0_1"', "Project manifest")
require(project_manifest, '"current_overlay": "xdm_android_browser_removal_phase0_1_baseline_preservation_overlay.zip"', "Project manifest")

workflow = read(".github/workflows/android.yml")
final_gate = read("tools/run-final-release-gate.sh")
validator_name = "tools/validate-browser-removal-phase-0-1.py"
require(workflow, validator_name, "Android CI")
require(final_gate, validator_name, "Final release gate")
for name in (
    "tools/validate-phase-49-browser-download-rules-file-type-interception.py",
    "tools/validate-phase-50-browser-downloader-ux-polish-seal.py",
):
    require(final_gate, name, "Final release gate")

if errors:
    for error in errors:
        print(error)
    raise SystemExit(1)

print("Browser removal Phase 0/1 validation passed: boundary inventory and downloader preservation contracts are present")
