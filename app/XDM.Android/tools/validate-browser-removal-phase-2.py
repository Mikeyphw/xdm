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
    "view_model_browser_neutral_entrypoints",
    "review_first_preserved",
    "room_schema_unchanged",
    "transfer_engines_unchanged",
):
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase2.{key} must be true")
if str(manifest.get("current_overlay", "")) not in {"xdm_android_phase61_final_gate_validator_harmony_overlay.zip", "xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip"} and not str(manifest.get("current_overlay", "")).startswith("xdm_android_browser_removal_phase"):
    errors.append("current_overlay must be a browser-removal phase")

intake = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt")
for marker in (
    "data class DownloadIntakeDraft",
    "enum class DownloadIntakeOrigin",
    "enum class DownloadIntakeKind",
    "object DownloadIntakeClassifier",
    "class DownloadIntakePlanner",
    "ExternalUrlPolicy.normalizedUrl",
    "fromExternal",
):
    require(intake, marker, "Neutral download intake")
for forbidden in ("DownloadRepository", "executionStarter", "TransferExecution", "WebView", "android.webkit", "android.content"):
    if forbidden in intake:
        errors.append(f"Neutral download intake must not depend on {forbidden}")

media_contract = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt")
media_intake = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureIntake.kt")
require(media_contract, "data class MediaRequestFacts", "Neutral media request facts")
for marker in ("class MediaCaptureIntakePlanner", "MediaCaptureService", "facts: MediaRequestFacts"):
    require(media_intake, marker, "Neutral media capture intake")

view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
for marker in (
    "fun openDownloadReview(draft: DownloadIntakeDraft)",
    "fun captureMediaRequest(facts: MediaRequestFacts)",
    "externalAddDraft.value = draft",
):
    require(view_model, marker, "MainViewModel neutral entry points")
for forbidden in ("fun openAddFromBrowser", "fun openBrowserDownload", "fun captureBrowserMediaUrl"):
    if forbidden in view_model:
        errors.append(f"Browser-shaped ViewModel method remains: {forbidden}")

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

print("Browser removal Phase 2 validation passed: browser-neutral intake contracts remain intact")
