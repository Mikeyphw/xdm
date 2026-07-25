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


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase = manifest.get("browser_removal_phase3", {})
for key in (
    "external_handoff_classification",
    "mime_content_length_page_context_preserved",
    "safe_source_label_preserved",
    "add_download_type_guidance",
    "explicit_inspect_as_media",
    "external_media_review_planner",
    "page_probe_review_first",
):
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase3.{key} must be true")
if phase.get("auto_probe") is not False or phase.get("auto_queue") is not False:
    errors.append("Phase 3 must remain explicit and review-first")

activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
for marker in (
    "handoffMimeType",
    "handoffContentLength",
    "handoffPageUrl",
    "mimeType = mimeType",
    "contentLength = contentLength",
):
    require(activity, marker, "External handoff metadata")

intake = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt")
for marker in ("enum class DownloadIntakeKind", "object DownloadIntakeClassifier", "canInspectAsMedia"):
    require(intake, marker, "Download intake classification")

planner = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt")
for marker in ("class ExternalMediaReviewPlanner", "ExternalMediaReviewIntake", "isPageProbe"):
    require(planner, marker, "External media review planner")

view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
for marker in (
    "fun inspectExternalMedia(draft: DownloadIntakeDraft)",
    "externalMediaReviewPlanner.plan(draft)",
    "repository.saveMediaCapture",
    "navigate(AppRoute.Media)",
):
    require(view_model, marker, "Explicit media inspection")
block = view_model.partition("fun inspectExternalMedia")[2].partition("fun ")[0]
if "executionStarter.start" in block or "addDownload(" in block:
    errors.append("Inspect as media must not auto-start or auto-queue a download")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for marker in ("Inspect as media", "Start direct download", "XDM never auto-queues external handoffs"):
    require(screens, marker, "Add Download replacement UX")

shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
for marker in (
    "externalKind = state.externalAddDraft?.kind",
    "externalMimeType = state.externalAddDraft?.mimeType",
    "externalContentLength = state.externalAddDraft?.contentLength",
    "state.externalAddDraft?.let(viewModel::inspectExternalMedia)",
):
    require(shell, marker, "App shell")

android_manifest = read("app/src/main/AndroidManifest.xml")
require(android_manifest, 'android:name=".ExternalAddDownloadActivity"', "External download receiver")

for path in (
    "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadIntakePlannerTest.kt",
    "media/src/test/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewPlannerTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase3ContractTest.kt",
    "docs/browser-removal/PHASE-3-EXTERNAL-HANDOFF-ADD-DOWNLOAD.md",
):
    read(path)

workflow = read(".github/workflows/android.yml")
final_gate = read("tools/run-final-release-gate.sh")
validator = "tools/validate-browser-removal-phase-3.py"
require(workflow, validator, "Android CI")
require(final_gate, validator, "Final release gate")

build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 3/4 must not bump app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 3/4 must keep Room schema 14")

if errors:
    print("Browser removal Phase 3 validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("Browser removal Phase 3 validation passed: external review and media-inspection replacement paths remain intact")
