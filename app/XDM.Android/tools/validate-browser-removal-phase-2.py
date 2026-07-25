#!/usr/bin/env python3
from __future__ import annotations

import json
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


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase = manifest.get("browser_removal_phase2", {})
for key in (
    "neutral_url_policy",
    "neutral_download_intake_draft",
    "neutral_download_intake_planner",
    "neutral_media_capture_intake",
    "browser_emits_neutral_contracts",
    "view_model_browser_neutral_entrypoints",
    "review_first_preserved",
    "browser_runtime_still_present",
    "android_manifest_unchanged",
    "room_schema_unchanged",
    "transfer_engines_unchanged",
):
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase2.{key} must be true")

expected_overlay = "xdm_android_browser_removal_phase2_neutral_intake_extraction_overlay.zip"
current_overlay = str(manifest.get("current_overlay", ""))
if current_overlay != expected_overlay and not current_overlay.startswith("xdm_android_browser_removal_phase"):
    errors.append(f"current_overlay must be {expected_overlay} or a browser-removal successor")

intake = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt")
for marker in (
    "enum class DownloadIntakeOrigin",
    "data class DownloadIntakeDraft",
    "class DownloadIntakePlanner",
    "fromBuiltInBrowserPage",
    "fromBuiltInBrowserDownload",
    "fromExternal",
    "ExternalUrlPolicy.normalizedUrl",
):
    require(intake, marker, "Neutral download intake")
for forbidden in (
    "DownloadRepository",
    "executionStarter",
    "TransferExecution",
    "android.webkit",
    "android.content",
    "WebView",
):
    if forbidden in intake:
        errors.append(f"Neutral download intake must not depend on {forbidden}")

models = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt")
require(models, "object ExternalUrlPolicy", "Neutral URL policy")
require(models, '@Deprecated("Use ExternalUrlPolicy"', "Legacy BrowserHandoffPolicy compatibility facade")
require(models, "object BrowserHandoffPolicy", "Legacy BrowserHandoffPolicy compatibility facade")

desktop_parity = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt")
if "BrowserHandoffPolicy." in desktop_parity:
    errors.append("New core-model production code must use ExternalUrlPolicy instead of BrowserHandoffPolicy")

parser = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt")
require(parser, "ExternalUrlPolicy.urlsInText", "SharedLinkParser")
if "android.webkit" in parser or "WebView" in parser:
    errors.append("External browser integration must remain WebKit-free")

media_intake = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureIntake.kt")
for marker in (
    "data class MediaCaptureIntake",
    "class MediaCaptureIntakePlanner",
    "fun plan(facts: MediaRequestFacts)",
    "captureService.candidateFor",
    "captureService.recordFor",
):
    require(media_intake, marker, "Neutral media intake")
for forbidden in ("DownloadRepository", "executionStarter", "WebView", "android.webkit"):
    if forbidden in media_intake:
        errors.append(f"Neutral media intake must not depend on {forbidden}")

browser = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
for marker in (
    "onOpenDownloadReview: (DownloadIntakeDraft) -> Unit",
    "onMediaRequest: (MediaRequestFacts) -> Unit",
    "fromBuiltInBrowserPage",
    "fromBuiltInBrowserDownload",
    "val intake: DownloadIntakeDraft",
    "headers = request?.requestHeaders.orEmpty()",
):
    require(browser, marker, "Browser neutral output seam")
for stale in ("onBrowserDownloadRequest",):
    if stale in browser:
        errors.append(f"BrowserScreen still exposes stale callback: {stale}")
if "executionStarter.start" in browser or "addDownload(" in browser:
    errors.append("BrowserScreen must remain review-first and execution-free")

view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
for marker in (
    "val externalAddDraft: DownloadIntakeDraft?",
    "fun captureMediaRequest(facts: MediaRequestFacts)",
    "fun openDownloadReview(draft: DownloadIntakeDraft)",
    "mediaCaptureIntakePlanner.plan(facts)",
    "downloadIntakePlanner.fromExternal",
):
    require(view_model, marker, "ViewModel neutral intake")
for stale in ("fun captureBrowserMediaUrl", "fun openAddFromBrowser", "fun openBrowserDownload", "data class ExternalAddDraft"):
    if stale in view_model:
        errors.append(f"ViewModel retains stale browser-shaped intake: {stale}")
review_block = view_model.split("fun openDownloadReview", 1)[1].split("fun ", 1)[0] if "fun openDownloadReview" in view_model else ""
for forbidden in ("executionStarter.start", "repository.save(", "Download("):
    if forbidden in review_block:
        errors.append(f"openDownloadReview must not perform {forbidden}")

shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
require(shell, "onOpenDownloadReview = viewModel::openDownloadReview", "App shell")
require(shell, "onMediaRequest = viewModel::captureMediaRequest", "App shell")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for stale in ("onBrowserMediaRequest", "onOpenAddForBrowserUrl"):
    if stale in screens:
        errors.append(f"MediaInboxScreen retains unused browser callback: {stale}")

android_manifest = read("app/src/main/AndroidManifest.xml")
require(android_manifest, 'android:name=".BrowserActivity"', "Phase 2 browser-runtime deferral")
require(browser, "WebView(context)", "Phase 2 browser-runtime deferral")

for path in (
    "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadIntakePlannerTest.kt",
    "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureIntakePlannerTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase2ContractTest.kt",
    "docs/browser-removal/PHASE-2-NEUTRAL-INTAKE-EXTRACTION.md",
):
    read(path)

workflow = read(".github/workflows/android.yml")
final_gate = read("tools/run-final-release-gate.sh")
validator = "tools/validate-browser-removal-phase-2.py"
require(workflow, validator, "Android CI")
require(final_gate, validator, "Final release gate")

if errors:
    print("Browser removal Phase 2 validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("Browser removal Phase 2 validation passed: downloader intake and media classification are browser-neutral and review-first")
