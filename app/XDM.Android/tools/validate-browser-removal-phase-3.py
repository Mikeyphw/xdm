#!/usr/bin/env python3
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(body: str, marker: str, label: str) -> None:
    if marker not in body:
        errors.append(f"{label} missing {marker!r}")

manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase = manifest.get("browser_removal_phase3", {})
expected_overlay = "xdm_android_browser_removal_phase3_external_handoff_add_download_overlay.zip"
if manifest.get("current_overlay") != expected_overlay:
    errors.append(f"current_overlay must be {expected_overlay}")
for key in (
    "external_handoff_classification",
    "mime_content_length_page_context_preserved",
    "safe_source_label_preserved",
    "add_download_type_guidance",
    "explicit_inspect_as_media",
    "external_media_review_planner",
    "page_probe_review_first",
    "browser_runtime_still_present",
    "android_manifest_unchanged",
    "room_schema_unchanged",
    "transfer_engines_unchanged",
):
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase3.{key} must be true")
for key in ("auto_probe", "auto_queue"):
    if phase.get(key) is not False:
        errors.append(f"browser_removal_phase3.{key} must be false")
if phase.get("version_name_unchanged") != "0.20.0-rc08" or phase.get("version_code_unchanged") != 21:
    errors.append("Phase 3 must not change Android version metadata")

intake = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt")
for marker in (
    "enum class DownloadIntakeKind",
    "DirectFile",
    "DirectMedia",
    "AdaptiveMedia",
    "Torrent",
    "PageOrUnknown",
    "object DownloadIntakeClassifier",
    "val kind: DownloadIntakeKind",
    "val canInspectAsMedia: Boolean",
):
    require(intake, marker, "Download intake classification")
for forbidden in ("DownloadRepository", "executionStarter", "android.webkit", "WebView"):
    if forbidden in intake:
        errors.append(f"Neutral download intake must not depend on {forbidden}")

automation = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt")
require(automation, "val mimeType: String? = null", "Automation handoff metadata")
require(automation, "val contentLength: Long? = null", "Automation handoff metadata")

handoff_contract = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt")
for marker in ("ExtraContentLength", "ExtraPageUrl", "ExtraPageTitle", "ExtraMimeType"):
    require(handoff_contract, marker, "External handoff contract")
if "android.webkit" in handoff_contract or "WebView" in handoff_contract:
    errors.append("External browser integration must remain WebKit-free")

activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
for marker in (
    "handoffMimeType",
    "handoffContentLength",
    "handoffPageUrl",
    "mimeType = mimeType",
    "contentLength = contentLength",
    "Intent.EXTRA_TEXT",
    "clipData",
):
    require(activity, marker, "External intent intake")

media_review = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt")
for marker in (
    "data class ExternalMediaReviewIntake",
    "class ExternalMediaReviewPlanner",
    "if (!draft.canInspectAsMedia) return null",
    "captureService.candidateFor",
    "MediaSourceKind.Unknown",
    "isPageProbe = true",
):
    require(media_review, marker, "External media review planner")
for forbidden in ("DownloadRepository", "executionStarter", "TermuxCommandRunner", "android.webkit", "WebView"):
    if forbidden in media_review:
        errors.append(f"External media review planner must not depend on {forbidden}")

view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
for marker in (
    "private val externalMediaReviewPlanner",
    "mimeType = draft.mimeType",
    "contentLength = draft.contentLength",
    "fun inspectExternalMedia(draft: DownloadIntakeDraft)",
    "externalMediaReviewPlanner.plan(draft)",
    "repository.saveMediaCapture",
    "navigate(AppRoute.Media)",
):
    require(view_model, marker, "ViewModel external media review")
review_block = view_model.split("fun inspectExternalMedia", 1)[1].split("fun ", 1)[0] if "fun inspectExternalMedia" in view_model else ""
for forbidden in ("executionStarter.start", "addDownload(", "termuxMediaPipelineManager.extractMetadata", "downloadWithYtDlp"):
    if forbidden in review_block:
        errors.append(f"inspectExternalMedia must not perform {forbidden}")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for marker in (
    "externalKind: DownloadIntakeKind?",
    "externalCanInspectMedia",
    "Inspect as media",
    "Opens the media resolver; it does not queue a download.",
    "Start direct download",
    "XDM never auto-queues external handoffs",
):
    require(screens, marker, "Add Download replacement UX")

shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
for marker in (
    "externalKind = state.externalAddDraft?.kind",
    "externalMimeType = state.externalAddDraft?.mimeType",
    "externalContentLength = state.externalAddDraft?.contentLength",
    "externalCanInspectMedia = state.externalAddDraft?.canInspectAsMedia == true",
    "state.externalAddDraft?.let(viewModel::inspectExternalMedia)",
):
    require(shell, marker, "App shell")

android_manifest = read("app/src/main/AndroidManifest.xml")
browser = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
require(android_manifest, 'android:name=".BrowserActivity"', "Phase 3 browser-runtime deferral")
require(android_manifest, 'android:name=".ExternalAddDownloadActivity"', "External download receiver")
require(browser, "WebView(context)", "Phase 3 browser-runtime deferral")

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
    errors.append("Phase 3 must not bump app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 3 must keep Room schema 14")

if errors:
    print("Browser removal Phase 3 validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("Browser removal Phase 3 validation passed: external handoffs are classified, review-first, and connected to the existing media resolver without auto-queue")
