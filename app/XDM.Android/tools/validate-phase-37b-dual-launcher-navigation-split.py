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

manifest_json = json.loads(text("PROJECT_MANIFEST.json") or "{}")
manifest_xml = text("app/src/main/AndroidManifest.xml")
routes = text("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
app_shell = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
main_activity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
browser_activity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserActivity.kt")
screens = text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
strings = text("app/src/main/res/values/strings.xml")
contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
run_gate = text("tools/run-final-release-gate.sh")
workflow = text(".github/workflows/android.yml")
doc = text("docs/browser/PHASE-37B-DUAL-LAUNCHER-NAVIGATION-SPLIT.md")

if manifest_json.get("current_overlay") != "xdm_android_phase37b_dual_launcher_navigation_split_overlay.zip":
    errors.append("current_overlay must point at Phase 37B dual launcher/navigation split overlay")
if 37 not in manifest_json.get("project", {}).get("implemented_phases", []):
    errors.append("implemented_phases must include 37")
phase = manifest_json.get("phase37b_dual_launcher_navigation_split", {})
for key in [
    "browser_top_level_route",
    "browser_launcher_activity",
    "downloader_launcher_preserved",
    "external_add_receiver_preserved",
    "media_browser_chip_removed",
    "no_room_migration",
    "no_version_bump",
    "white_screen_fix_deferred_to_phase38",
]:
    if phase.get(key) is not True:
        errors.append(f"phase37b_dual_launcher_navigation_split.{key} must be true")

if 'Browser("Browser", Icons.Rounded.Public)' not in routes:
    errors.append("AppRoute must define Browser as a first-class route with browser icon")
if "private val primaryRoutes = listOf(AppRoute.Downloads, AppRoute.Browser, AppRoute.Media, AppRoute.Queues)" not in app_shell:
    errors.append("compact primary routes must include Browser between Downloads and Media")
if "AppRoute.Browser -> BrowserScreen(" not in app_shell:
    errors.append("XdmApp must render BrowserScreen from the top-level Browser route")
if "AppRoute.Browser" in app_shell.split("private val overflowRoutes", 1)[1].split(")", 1)[0]:
    errors.append("Browser must not live in the overflow route list")

if "class BrowserActivity : MainActivity()" not in browser_activity:
    errors.append("BrowserActivity must subclass MainActivity")
if "initialRoute(intent: Intent?): AppRoute? = AppRoute.Browser" not in browser_activity:
    errors.append("BrowserActivity must start on Browser route")
if "shouldHandleExternalIntent(intent: Intent): Boolean = false" not in browser_activity:
    errors.append("BrowserActivity must not reuse downloader external-intake handling in Phase 37B")
if "protected open fun initialRoute" not in main_activity or "protected open fun shouldHandleExternalIntent" not in main_activity:
    errors.append("MainActivity must expose safe startup hooks for BrowserActivity")

if '.BrowserActivity' not in manifest_xml or '@string/browser_activity_label' not in manifest_xml:
    errors.append("manifest must declare BrowserActivity with browser label")
if '@string/downloader_activity_label' not in manifest_xml:
    errors.append("manifest must label MainActivity as Downloader")
if manifest_xml.count('android.intent.category.LAUNCHER') < 2:
    errors.append("manifest must expose both downloader and browser launchers")
# Keep BrowserActivity launcher-only until Phase 38 reliability wires explicit URL loading.
match = re.search(r'<activity\s+[^>]*android:name="\.BrowserActivity"[\s\S]*?</activity>', manifest_xml)
if not match:
    errors.append("BrowserActivity manifest block missing")
elif "android.intent.action.VIEW" in match.group(0) or 'android:scheme="http"' in match.group(0) or 'android:scheme="https"' in match.group(0):
    errors.append("Phase 37B BrowserActivity must stay launcher-only; generic VIEW link handling belongs to Phase 38")

if "showBrowser" in screens or "switch to Browser" in screens or "FilterChip(selected = showBrowser" in screens:
    errors.append("Media screen must no longer hide Browser behind a local chip")
if "Use Browser to capture" not in screens:
    errors.append("Media screen must direct users to the first-class Browser route")
if "browser_activity_label" not in strings or "downloader_activity_label" not in strings:
    errors.append("strings must label browser and downloader launchers")
if "validate-phase-37b-dual-launcher-navigation-split.py" not in run_gate:
    errors.append("final release gate must include Phase 37B validator")
if "validate-phase-37b-dual-launcher-navigation-split.py" not in workflow:
    errors.append("Android CI must include Phase 37B validator")
if "phaseThirtySevenBDualLauncherNavigationSplitContractsArePresent" not in contract:
    errors.append("ArchitectureContractTest must cover Phase 37B")
if "Phase 38" not in doc or "white-screen" not in doc or "XDM Browser" not in doc or "XDM Downloader" not in doc:
    errors.append("Phase 37B doc must declare dual surfaces and defer white-screen reliability to Phase 38")

if 'versionName = "0.20.0-rc08"' not in text("app/build.gradle.kts") or 'versionCode = 21' not in text("app/build.gradle.kts"):
    errors.append("Phase 37B must not bump app version")
manifest_meta = manifest_json.get("database", {})
if manifest_meta.get("version") != 14:
    errors.append("Phase 37B must not bump schema metadata")

if errors:
    raise SystemExit("Phase 37B dual launcher/navigation split validation failed:\n" + "\n".join(errors))
print("Phase 37B dual launcher/navigation split validation passed")
