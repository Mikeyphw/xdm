#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing file: {relative}")
        return ""
    return path.read_text()

manifest = json.loads(text("PROJECT_MANIFEST.json") or "{}")
manifest_xml = text("app/src/main/AndroidManifest.xml")
browser = text("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
main_activity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
browser_activity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserActivity.kt")
view_model = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
app_shell = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
run_gate = text("tools/run-final-release-gate.sh")
workflow = text(".github/workflows/android.yml")
doc = text("docs/browser/PHASE-38-BROWSER-RELIABILITY-FOUNDATION.md")

if manifest.get("current_overlay") not in {"xdm_android_phase38_browser_reliability_foundation_overlay.zip", "xdm_android_phase39_browser_chrome_navigation_overlay.zip", "xdm_android_phase40_browser_tabs_session_ux_overlay.zip", "xdm_android_phase41_browser_download_bridge_overlay.zip", "xdm_android_phase42_browser_media_capture_cockpit_overlay.zip",
    "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase44_browser_settings_privacy_controls_overlay.zip", "xdm_android_phase45_browser_visual_polish_adaptive_layout_overlay.zip", "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip", "xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip", "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase48_browser_resource_inspector_overlay.zip", "xdm_android_phase49_50_download_rules_ux_polish_overlay.zip"}:
    errors.append("current_overlay must point at the Phase 38 browser reliability overlay or approved later Phase 39 browser chrome/navigation overlay or approved later Phase 40 tabs/session overlay")
if 38 not in manifest.get("project", {}).get("implemented_phases", []):
    errors.append("implemented_phases must include 38")
phase = manifest.get("phase38_browser_reliability_foundation", {})
for key in [
    "browser_start_page",
    "white_webview_void_removed",
    "loading_progress_state",
    "http_error_state",
    "webview_error_state",
    "ssl_error_cancelled_and_visible",
    "blank_page_detector",
    "retry_open_external_add_url_actions",
    "browser_activity_view_link_handling",
    "browser_start_url_state",
    "generic_browser_links_auto_verify_false",
    "no_room_migration",
    "no_version_bump",
]:
    if phase.get(key) is not True:
        errors.append(f"phase38_browser_reliability_foundation.{key} must be true")

for required in [
    "BrowserStartPage",
    "BrowserLoadState",
    "BrowserLoadOverlay",
    "BrowserReliabilityCard",
    "onProgressChanged",
    "onReceivedError",
    "onReceivedHttpError",
    "onReceivedSslError",
    "handler?.cancel()",
    "BlankPageProbeScript",
    "BlankPageProbeDelayMs",
    "Open externally",
    "Add URL",
    "Page did not load",
    "Blank page detected",
]:
    if required not in browser:
        errors.append(f"BrowserScreen missing reliability marker: {required}")

if "if (loadRequest.isNullOrBlank())" not in browser or "BrowserStartPage(" not in browser:
    errors.append("Browser must render a start page instead of a raw blank WebView when no URL is loaded")
if "evaluateJavascript(BlankPageProbeScript)" not in browser:
    errors.append("Browser must run the blank-page probe after page finish")
if "SSL error blocked" not in browser:
    errors.append("SSL failures must be visible and must not proceed")

activity_block = re.search(r'<activity\s+[^>]*android:name="\.BrowserActivity"[\s\S]*?</activity>', manifest_xml)
if not activity_block:
    errors.append("BrowserActivity manifest block missing")
else:
    block = activity_block.group(0)
    if '<intent-filter android:autoVerify="false">' not in block or 'android.intent.action.VIEW' not in block:
        errors.append("BrowserActivity must expose lint-safe generic VIEW filters")
    if 'android:scheme="http"' not in block or 'android:scheme="https"' not in block:
        errors.append("BrowserActivity must accept http and https links")
    if 'android:scheme="ftp"' in block:
        errors.append("BrowserActivity must not take ftp links from the downloader handoff surface")

if "shouldOpenBrowserUrl" not in main_activity or "openBrowserUrlFromIntent" not in main_activity:
    errors.append("MainActivity must expose BrowserActivity URL startup hooks")
if "override fun shouldOpenBrowserUrl(intent: Intent): Boolean = intent.action == Intent.ACTION_VIEW" not in browser_activity:
    errors.append("BrowserActivity must opt into VIEW link startup only")
if "browserStartUrl" not in view_model or "openBrowserUrl(url: String)" not in view_model or "consumeBrowserStartUrl" not in view_model:
    errors.append("MainViewModel must hold and consume browser startup URLs")
if "initialUrl = state.browserStartUrl" not in app_shell or "onInitialUrlConsumed = viewModel::consumeBrowserStartUrl" not in app_shell:
    errors.append("XdmApp must pass browser startup URLs into BrowserScreen")
if "initialUrl: String? = null" not in browser or "LaunchedEffect(initialUrl)" not in browser:
    errors.append("BrowserScreen must consume initial BrowserActivity URLs")


# Compose lint: modifier must be the first optional parameter in BrowserScreen.
signature_match = re.search(r"fun\s+BrowserScreen\s*\((?P<body>[\s\S]*?)\)\s*\{", browser)
if not signature_match:
    errors.append("BrowserScreen composable signature missing")
else:
    signature = signature_match.group("body")
    modifier_index = signature.find("modifier: Modifier = Modifier")
    initial_url_index = signature.find("initialUrl: String? = null")
    if modifier_index == -1:
        errors.append("BrowserScreen must expose a Modifier default parameter")
    elif initial_url_index != -1 and modifier_index > initial_url_index:
        errors.append("BrowserScreen Modifier parameter must be the first optional parameter for Compose lint")

if "validate-phase-38-browser-reliability-foundation.py" not in run_gate:
    errors.append("final release gate must include Phase 38 validator")
if "validate-phase-38-browser-reliability-foundation.py" not in workflow:
    errors.append("Android CI must include Phase 38 validator")
if "phaseThirtyEightBrowserReliabilityFoundationContractsArePresent" not in contract:
    errors.append("ArchitectureContractTest must cover Phase 38")
if "white-screen" not in doc or "BrowserActivity" not in doc or "SSL" not in doc:
    errors.append("Phase 38 doc must describe the white-screen, link handoff, and SSL reliability posture")

if 'versionName = "0.20.0-rc08"' not in text("app/build.gradle.kts") or 'versionCode = 21' not in text("app/build.gradle.kts"):
    errors.append("Phase 38 must not bump app version")
if manifest.get("database", {}).get("version") != 14:
    errors.append("Phase 38 must not bump schema metadata")


architecture = text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
if 'assertEquals(listOf("Downloads", "Browser", "Add", "Queues", "Scheduler", "Media", "Recovery", "Diagnostics", "Settings"), labels)' not in architecture:
    errors.append("ArchitectureContractTest must accept Browser as the Phase 37B top-level route")
if '"History", "Browser", "Clipboard", "Backup", "Updater"' in architecture:
    errors.append("Legacy Phase 12-14 route guard must not ban the Phase 37B Browser route")
if 'it.label == "Player" || it.label == "Resolver" || it.label == "Browser"' in architecture:
    errors.append("Legacy resolver/player route guard must not ban the Phase 37B Browser route")

if errors:
    raise SystemExit("Phase 38 browser reliability validation failed:\n" + "\n".join(errors))
print("Phase 38 browser reliability validation passed")
