#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(text: str, needle: str, owner: str) -> None:
    if needle not in text:
        errors.append(f"{owner} is missing required contract: {needle}")


project_manifest_text = read("PROJECT_MANIFEST.json")
try:
    project_manifest = json.loads(project_manifest_text)
except json.JSONDecodeError as exc:
    errors.append(f"PROJECT_MANIFEST.json is invalid JSON: {exc}")
    project_manifest = {}

phase = project_manifest.get("browser_removal_phase7", {})
for key in (
    "program_complete",
    "downloader_only_product_identity",
    "general_browser_intent_absent",
    "package_manager_non_browser_contract",
    "external_handoff_preserved",
    "review_first_intake_preserved",
    "all_download_engines_preserved",
    "media_resolver_library_player_preserved",
    "browser_runtime_and_persistence_absent",
    "room_schema_14_sealed",
    "release_gate_sealed",
):
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase7.{key} must be true")

expected_overlay = "xdm_android_browser_removal_phase7_final_downloader_release_seal_overlay.zip"
current_overlay = str(project_manifest.get("current_overlay", ""))
if current_overlay != expected_overlay and not current_overlay.startswith("xdm_android_browser_removal_phase8"):
    errors.append("current_overlay must identify the Phase 7 seal or an approved downloader-experience successor")
if project_manifest.get("next_phase") != "complete":
    errors.append("top-level next_phase must be complete")

contract = read("docs/architecture/DOWNLOADER_PRODUCT_CONTRACT.md")
require(contract, "XDM Android is a download manager", "Downloader product contract")
require(contract, "is not a general web browser", "Downloader product contract")
require(contract, "ordinary `ACTION_VIEW` intent", "Downloader product contract")
phase_doc = read("docs/browser-removal/PHASE-7-FINAL-DOWNLOADER-RELEASE-SEAL.md")
require(phase_doc, "browser-removal roadmap is complete", "Phase 7 document")

for removed in (
    "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserActivity.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt",
):
    if (ROOT / removed).exists():
        errors.append(f"Removed browser runtime file returned: {removed}")

production_roots = (
    "app/src/main",
    "core-model/src/main",
    "core-utils/src/main",
    "media/src/main",
    "persistence/src/main",
    "scheduler/src/main",
    "storage/src/main",
    "transfer-api/src/main",
    "transfer-native/src/main",
    "transfer-aria2/src/main",
)
production_text = []
for relative in production_roots:
    directory = ROOT / relative
    if directory.exists():
        for path in directory.rglob("*"):
            if path.is_file() and path.suffix in {".kt", ".xml", ".kts"}:
                production_text.append(path.read_text(encoding="utf-8"))
joined_production = "\n".join(production_text)
for forbidden in (
    "BrowserActivity",
    "BrowserScreen",
    "AppRoute.Browser",
    "android.webkit",
    "WebViewClient",
    "WebChromeClient",
):
    if forbidden in joined_production:
        errors.append(f"Forbidden browser runtime token returned to production: {forbidden}")

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
try:
    manifest_root = ET.parse(manifest_path).getroot()
except (ET.ParseError, OSError) as exc:
    errors.append(f"AndroidManifest.xml cannot be parsed: {exc}")
    manifest_root = None

launcher_count = 0
external_activity_found = False
share_found = False
typed_http_found = False
browser_download_action_found = False
if manifest_root is not None:
    for activity in manifest_root.findall("./application/activity"):
        name = activity.get(ANDROID_NS + "name", "")
        if name.endswith("BrowserActivity"):
            errors.append("AndroidManifest registers BrowserActivity")
        if name.endswith("ExternalAddDownloadActivity"):
            external_activity_found = True
        for intent_filter in activity.findall("intent-filter"):
            actions = {item.get(ANDROID_NS + "name", "") for item in intent_filter.findall("action")}
            categories = {item.get(ANDROID_NS + "name", "") for item in intent_filter.findall("category")}
            data_items = intent_filter.findall("data")
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                launcher_count += 1
            if "android.intent.action.SEND" in actions:
                share_found = True
            if "com.android.browser.action.DOWNLOAD" in actions:
                browser_download_action_found = True
            if "android.intent.action.VIEW" in actions and "android.intent.category.BROWSABLE" in categories:
                for data in data_items:
                    scheme = data.get(ANDROID_NS + "scheme", "")
                    mime = data.get(ANDROID_NS + "mimeType", "")
                    path_contract = any(data.get(ANDROID_NS + key, "") for key in ("path", "pathPrefix", "pathPattern"))
                    if scheme in {"http", "https"}:
                        if not mime and not path_contract:
                            errors.append(f"Generic {scheme} browsing claim remains in AndroidManifest")
                        if mime:
                            typed_http_found = True
    if manifest_root.findall("./application/activity-alias"):
        errors.append("Browser-removal seal permits no activity aliases")

if launcher_count != 1:
    errors.append(f"Expected exactly one MAIN/LAUNCHER activity, found {launcher_count}")
if not external_activity_found:
    errors.append("ExternalAddDownloadActivity is missing")
if not share_found:
    errors.append("ACTION_SEND external handoff is missing")
if not typed_http_found:
    errors.append("Typed HTTP/HTTPS download VIEW handling is missing")
if not browser_download_action_found:
    errors.append("Android browser download-manager action handling is missing")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    require(routes, f'{route}("{route}"', "AppRoute")
for forbidden in ("Browser(", "Queues(", "Scheduler(", "Recovery(", "Diagnostics("):
    if forbidden in routes:
        errors.append(f"Retired top-level route returned: {forbidden}")

preferences = read("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt").lower()
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Room schema must remain 14")
for forbidden in ("browser_tab", "browser_history", "bookmark", "private_session", "browser_profile"):
    if forbidden in (preferences + database.lower()):
        errors.append(f"Browser persistence contract returned: {forbidden}")

schema_path = ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/14.json"
if not schema_path.is_file():
    errors.append("Exported Room schema 14 is missing")
else:
    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        table_names = [entity.get("tableName", "").lower() for entity in schema.get("database", {}).get("entities", [])]
        for table in table_names:
            if any(token in table for token in ("browser", "bookmark", "browsing_history", "private_session", "browser_profile")):
                errors.append(f"Browser table remains in Room schema 14: {table}")
    except json.JSONDecodeError as exc:
        errors.append(f"Room schema 14 is invalid JSON: {exc}")

browser_integration = ROOT / "browser-integration"
for path in browser_integration.rglob("*"):
    if path.is_file() and path.suffix in {".kt", ".xml", ".kts"}:
        text = path.read_text(encoding="utf-8")
        for forbidden in ("android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
            if forbidden in text:
                errors.append(f"browser-integration must stay WebKit-free: {path.relative_to(ROOT)} contains {forbidden}")

for preserved in (
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaOfflineLibraryV2.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt",
    "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
    "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
):
    if not (ROOT / preserved).is_file():
        errors.append(f"Preserved downloader implementation is missing: {preserved}")

build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 7 must not change app version")

unit_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase7ContractTest.kt")
instrumentation_contract = read("app/src/androidTest/kotlin/com/mikeyphw/xdm/android/BrowserRemovalFinalManifestTest.kt")
require(unit_contract, "ordinaryWebNavigationIsNotClaimedButExplicitDownloadHandoffRemains", "Phase 7 JVM contract")
require(instrumentation_contract, "ordinaryHttpsNavigationDoesNotResolveToXdm", "PackageManager contract")
require(instrumentation_contract, "typedApkDownloadStillResolvesToExternalAddDownload", "PackageManager contract")

validator = "tools/validate-browser-removal-phase-7.py"
final_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
require(final_gate, validator, "Final release gate")
require(workflow, validator, "Portable Android CI")
require(final_gate, ":app:assembleDebugAndroidTest", "Final Gradle gate")
require(workflow, ":app:assembleDebugAndroidTest", "Portable Android CI")
repository_workflow_path = ROOT.parent.parent / ".github/workflows/android.yml"
if repository_workflow_path.is_file():
    repository_workflow = repository_workflow_path.read_text(encoding="utf-8")
    require(repository_workflow, "bash tools/run-final-release-gate.sh --ci", "Repository Android CI")
    require(repository_workflow, ":app:assembleDebugAndroidTest", "Repository Android CI")

if errors:
    print("Browser removal Phase 7 validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Browser removal Phase 7 validation passed: downloader-only product identity, manifest ownership, persistence, execution, and release gates are sealed")
