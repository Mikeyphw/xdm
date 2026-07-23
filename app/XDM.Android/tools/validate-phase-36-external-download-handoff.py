#!/usr/bin/env python3
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
errors = []


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(rel: str, token: str) -> None:
    body = text(rel)
    if token not in body:
        errors.append(f"{rel} missing {token!r}")

manifest_json = json.loads(text("PROJECT_MANIFEST.json") or "{}")
if 36 not in manifest_json.get("project", {}).get("implemented_phases", []):
    errors.append("project.implemented_phases missing 36")
if 36 not in manifest_json.get("implemented_phases", []):
    errors.append("root implemented_phases missing 36")
if manifest_json.get("next_phase") != "complete":
    errors.append("next_phase must remain complete")
if manifest_json.get("current_overlay") != "xdm_android_phase36_external_download_handoff_overlay.zip":
    errors.append("current_overlay must point at Phase 36 external download handoff overlay")
phase = manifest_json.get("phase36_external_download_handoff", {})
for key in [
    "phase35_landed",
    "dedicated_external_add_activity",
    "sharesheet_text_plain_text_any_intake",
    "browser_view_http_https_ftp_intake",
    "external_handoffs_prompt_add_first",
    "media_capture_requires_explicit_capture_action",
    "no_auto_queue_for_external_browser_share",
    "ftp_url_normalization",
    "request_headers_redacted",
    "run_final_release_gate_included",
    "ci_static_contract_included",
]:
    if phase.get(key) is not True:
        errors.append(f"phase36_external_download_handoff.{key} must be true")
for key in ["raw_shell_exposed", "root_required", "room_schema_migration", "top_level_route_added"]:
    if phase.get(key) is not False:
        errors.append(f"phase36_external_download_handoff.{key} must be false")
if phase.get("version_name_unchanged") != "0.20.0-rc08" or phase.get("version_code_unchanged") != 21:
    errors.append("Phase 36 must not bump version metadata")

manifest = text("app/src/main/AndroidManifest.xml")
for token in [
    'android:name=".ExternalAddDownloadActivity"',
    'android:label="@string/external_add_download_label"',
    'android.intent.action.SEND',
    'android.intent.action.SEND_MULTIPLE',
    'android.intent.action.VIEW',
    'android.intent.action.DOWNLOAD',
    'com.android.browser.action.DOWNLOAD',
    'com.android.browser.intent.action.DOWNLOAD',
    'android:mimeType="text/plain"',
    'android:mimeType="text/*"',
    'android:mimeType="*/*"',
    'android:scheme="http"',
    'android:scheme="https"',
    'android:scheme="ftp"',
    'android:host="*" android:pathPattern=".*\\\\.zip"',
    'android:host="*" android:pathPattern=".*\\\\.apk"',
    'android:host="*" android:pathPattern=".*\\\\.mp4"',
]:
    if token not in manifest:
        errors.append(f"manifest missing {token}")

main_activity_block = re.search(r'<activity\s+android:name="\.MainActivity"[\s\S]*?</activity>', manifest)
if not main_activity_block:
    errors.append("MainActivity manifest block missing")
elif "android.intent.action.MAIN" not in main_activity_block.group(0) or "com.mikeyphw.xdm.android.ADD_URL" not in main_activity_block.group(0):
    errors.append("MainActivity must remain launcher and custom command surface")

external_activity_block = re.search(r'<activity\s+android:name="\.ExternalAddDownloadActivity"[\s\S]*?</activity>', manifest)
if not external_activity_block:
    errors.append("ExternalAddDownloadActivity manifest block missing")
else:
    block = external_activity_block.group(0)
    for token in ["android.intent.action.SEND", "android.intent.action.VIEW", "com.android.browser.action.DOWNLOAD", "android:scheme=\"ftp\"", "android:mimeType=\"*/*\""]:
        if token not in block:
            errors.append(f"ExternalAddDownloadActivity block missing {token}")

# Lint-safe path filters: Android ignores path attributes unless a host is
# present, and Gradle lint flags multi-data path filters as ambiguous. Keep
# each extension in its own filter with an explicit wildcard host.
hostless_path = re.findall(r'<data\s+android:scheme="https?"\s+android:pathPattern=', manifest)
if hostless_path:
    errors.append('downloadable pathPattern filters must include android:host="*"')

for filter_block in re.findall(r'<intent-filter(?:\s+[^>]*)?>[\s\S]*?</intent-filter>', manifest):
    if 'android:pathPattern=' in filter_block and filter_block.count('<data ') > 1:
        errors.append('downloadable pathPattern filters must be split into one data tag per intent-filter')
    if (
        'android.intent.category.BROWSABLE' in filter_block
        and ('android:scheme="http"' in filter_block or 'android:scheme="https"' in filter_block)
        and 'android:autoVerify="false"' not in filter_block.split('>', 1)[0]
    ):
        errors.append('download-manager web intent filters must set android:autoVerify="false" to opt out of verified App Links')

require("app/src/main/res/values/strings.xml", '<string name="external_add_download_label">Add to XDM</string>')
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt", "class ExternalAddDownloadActivity : MainActivity()")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt", "open class MainActivity")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt", "shouldOpenExternalAddPrompt")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt", "AutomationCommandAction.PromptAddDownload")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "External download opened Add Download prompt")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "AutomationCommandAction.PromptAddDownload -> openExternalAddDraft")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", "PromptAddDownload")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", "externalUrlPattern")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", "clipboardUrlPattern")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", "(?:https?|ftp)://")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", "https?://")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", "urlsInText(text: String): List<String> = clipboardUrlPattern.findAll(text)")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", 'scheme != "http" && scheme != "https" && scheme != "ftp"')
require("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "externalSourceLabel")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "XDM never auto-queues external handoffs")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt", "externalSourceLabel = state.externalAddDraft?.sourceLabel")

for rel, token in [
    ("docs/architecture/PHASE-36-EXTERNAL-DOWNLOAD-HANDOFF.md", "Phase 36: External Download Handoff"),
    ("docs/architecture/PHASE-36-EXTERNAL-DOWNLOAD-HANDOFF.md", "Add to XDM"),
    ("tools/run-final-release-gate.sh", "validate-phase-36-external-download-handoff.py"),
    (".github/workflows/android.yml", "validate-phase-36-external-download-handoff.py"),
    ("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "phaseThirtySixExternalDownloadHandoffContractsArePresent"),
]:
    require(rel, token)

build = text("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 36 must not bump app version")

screens = text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for forbidden in ['label = "External"', 'label = "Handoff"', 'label = "IronFox"', 'label = "Browser Download"']:
    if forbidden in screens:
        errors.append(f"forbidden Phase 36 top-level route label: {forbidden}")

if errors:
    raise SystemExit("Phase 36 external download handoff validation failed:\n" + "\n".join(errors))
print("Phase 36 external download handoff validation passed")
