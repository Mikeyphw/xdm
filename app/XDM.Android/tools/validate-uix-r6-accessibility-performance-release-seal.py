#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def require(relative: str, *needles: str) -> str:
    text = read(relative)
    for needle in needles:
        if needle not in text:
            ERRORS.append(f"{relative} missing {needle!r}")
    return text


accessibility = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAccessibility.kt",
    "XdmMinimumTouchTarget = 48.dp",
    "ShellCompact", "ShellMedium", "ShellExpanded",
    "BottomNavigation", "NavigationSidebar", "ContentCanvas",
    "DownloadsList", "DownloadsDetail", "AddReview",
    "MediaCapture", "MediaTrackSheet", "LibraryList", "LibraryGrid",
    "DeveloperTools",
    "fun Modifier.xdmMinimumTouchTarget()",
)
primitives = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt",
    "BoxWithConstraints",
    "sizeIn(minWidth = XdmMinimumTouchTarget, minHeight = XdmMinimumTouchTarget)",
    'stateDescription = if (expanded) "$label expanded" else "$label collapsed"',
    'xdmPane("$title bottom sheet", traversal = XdmTraversalOrder.Sheet)',
    'xdmPane("$title dialog", traversal = XdmTraversalOrder.Dialog)',
)
shell = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt",
    "XdmScreenTags.ShellCompact",
    "XdmScreenTags.ShellMedium",
    "XdmScreenTags.ShellExpanded",
    "XdmScreenTags.BottomNavigation",
    "XdmScreenTags.NavigationSidebar",
    "XdmScreenTags.ContentCanvas",
    "defaultMinSize(minHeight = 60.dp)",
    "WindowInsets.safeDrawing",
    "imePadding()",
)
app = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
    "rememberSaveable",
    "BackHandler(enabled = state.route == AppRoute.Add)",
    "XdmAdaptiveSheet(",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "XdmScreenTags.Downloads",
    "XdmScreenTags.DownloadsList",
    "rememberSaveable",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt",
    "XdmScreenTags.DownloadsDetail",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt",
    "XdmScreenTags.AddDownload",
    "XdmScreenTags.AddReview",
    "rememberSaveable",
    "imePadding()",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt",
    "XdmScreenTags.Media",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt",
    "XdmScreenTags.MediaCapture",
    "XdmScreenTags.MediaTrackSheet",
    "rememberSaveable",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt",
    "XdmScreenTags.Library",
    "XdmScreenTags.LibraryList",
    "XdmScreenTags.LibraryGrid",
    "rememberSaveable",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt",
    "XdmScreenTags.Activity",
    "Needs attention selected",
    "Recent selected",
    "XdmScreenTags.ActivityAttention",
    "XdmScreenTags.ActivityRecent",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt",
    "XdmScreenTags.Settings",
    "Developer options enabled",
    "Developer options disabled",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperSettingsScreen.kt",
    "DeveloperWorkspacePolicy.shouldCompose",
    "XdmScreenTags.DeveloperTools",
)
policy = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperWorkspacePolicy.kt",
    "developerOptionsEnabled && settingsPanel == SettingsPanel.DeveloperTools",
)
for forbidden in ("androidx.compose", "android.content", "MediaFinalValidationGatePlanner", "MediaDispatchPlanner"):
    if forbidden in policy:
        ERRORS.append(f"Developer workspace policy must remain pure: {forbidden}")

# Semantics-based layout and source contracts.
require(
    "app/src/androidTest/kotlin/com/mikeyphw/xdm/android/UixR6AdaptiveLayoutTest.kt",
    "compactUsesBottomNavigationWithAccessibleNewDownloadAction",
    "mediumUsesBottomNavigationWithAccessibleNewDownloadAction",
    "expandedUsesPersistentSidebarAndBoundedContentCanvas",
    "adaptiveAddSurfaceHasDistinctPhoneAndExpandedSemantics",
    "sharedLayoutsRemainReadableAtTwoHundredPercentFontScale",
    "emptyAndErrorStatesExposeReadableSemantics",
    "assertWidthIsAtLeast(48.dp)",
    "assertHeightIsAtLeast(48.dp)",
)
require(
    "app/src/androidTest/kotlin/com/mikeyphw/xdm/android/AppSmokeTest.kt",
    "addDownloadIsReachableAndBackReturnsToDownloads",
    "settingsIsAVisiblePrimaryDestination",
    "XdmScreenTags.AddDownload",
    "Review download",
)
require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/UixR6ReleaseSealContractTest.kt",
    "everyPrimaryWorkflowHasStableSemanticsTags",
    "touchTargetsLargeTextImeAndRestorableStateAreSealed",
    "normalUiCannotRenderArchitectureNoiseSecretsOrRawMachineValues",
    "developerDiagnosticsStayLazyAndTheProductBoundaryDoesNotMove",
)
require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/UixR6DeveloperWorkspacePolicyTest.kt",
    "expensiveDeveloperWorkspaceRequiresBothThePersistedGateAndActivePanel",
)

# Normal UI must remain consumer-facing. Parsing JSON for typed queue/schedule forms is
# allowed; rendering raw JSON, enum names, commands, secret fields, or full URLs is not.
ui_root = ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui"
user_files = sorted(
    p for p in ui_root.rglob("*.kt")
    if "/ui/developer/" not in p.as_posix()
)
user_files += [
    ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt",
    ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
    ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt",
]
architecture_noise = (
    "control tower", "telemetry deck", "worker bridge", "runtime adapter",
    "dispatch runbook", "validation gate", "privacy audit", "sidecar diagnostics",
)
render_call = re.compile(r"\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(")
for path in user_files:
    text = path.read_text(encoding="utf-8", errors="replace")
    lower = text.lower()
    for token in architecture_noise:
        if token in lower:
            ERRORS.append(f"Normal UI exposes internal architecture phrase {token!r}: {path.relative_to(ROOT)}")
    for line_number, line in enumerate(text.splitlines(), 1):
        renders = bool(render_call.search(line)) or "headline =" in line or "supporting =" in line
        if not renders:
            continue
        if ".name" in line and not any(allowed in line for allowed in ("humanize", "tag.name", "search.name", "queue.name", "rule.name", "queues.firstOrNull")):
            ERRORS.append(f"Raw enum/name rendering at {path.relative_to(ROOT)}:{line_number}")
        if any(token in line for token in ("rawJson", "JSONObject", "JSONArray", "rawHeaders", ".cookies", ".authorization", ".command")):
            ERRORS.append(f"Raw machine or secret-bearing rendering at {path.relative_to(ROOT)}:{line_number}")
        if "constraintsJson" in line and not any(helper in line for helper in ("nextRunSummary", "scheduleConstraintSummary")):
            ERRORS.append(f"Unparsed schedule constraints rendered at {path.relative_to(ROOT)}:{line_number}")
        if ".url" in line and "hostFromUrl" not in line and "redactUrl" not in line:
            ERRORS.append(f"Full URL rendering at {path.relative_to(ROOT)}:{line_number}")

# Expensive planners can exist only under the developer package.
normal_source = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in user_files)
developer_source = "\n".join(
    path.read_text(encoding="utf-8", errors="replace")
    for path in (ui_root / "developer").glob("*.kt")
)
for planner in (
    "MediaFinalValidationGatePlanner",
    "MediaWorkerBridgePlanner",
    "MediaSessionPrivacyAuditPlanner",
):
    constructor = re.compile(rf"\b{planner}\s*\(")
    if constructor.search(normal_source):
        ERRORS.append(f"Normal UI constructs developer planner: {planner}")
    if not constructor.search(developer_source):
        ERRORS.append(f"Developer diagnostics lost planner construction: {planner}")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    if f'{route}("{route}"' not in routes:
        ERRORS.append(f"Stable route missing: {route}")
if len(re.findall(r'^    [A-Z][A-Za-z]+\("', routes, re.MULTILINE)) != 6:
    ERRORS.append("UIX R6 must not add a top-level route")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*17\b", database):
    ERRORS.append("Room schema must remain 17 after Phase 7 publication journaling")
build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    ERRORS.append("App version must remain 0.20.0-rc08 / 21")
if "warningsAsErrors = true" not in build:
    ERRORS.append("Android lint warnings must remain errors")

manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
r6 = manifest.get("uix_r6_accessibility_performance_release_seal", {})
expected = {
    "program": "android_uix_redesign",
    "minimum_touch_target_dp": 48,
    "font_scale_qualified": 2.0,
    "window_classes": ["Compact", "Medium", "Expanded"],
    "semantics_layout_contracts": True,
    "developer_planners_lazy": True,
    "normal_ui_debug_language_banned": True,
    "normal_ui_raw_machine_values_banned": True,
    "manual_clean_install_upgrade_checklist": True,
    "external_handoff_manual_gate": True,
    "full_validation_required": True,
    "room_schema_current": 17,
    "version_name_unchanged": "0.20.0-rc08",
    "version_code_unchanged": 21,
    "depends_on": "uix_r5_activity_settings_developer_boundary",
    "next_overlay": "complete",
}
for key, value in expected.items():
    if r6.get(key) != value:
        ERRORS.append(f"PROJECT_MANIFEST uix_r6_accessibility_performance_release_seal.{key} must equal {value!r}")
if manifest.get("current_uix_overlay") not in {"xdm_android_uix_r6_accessibility_performance_release_seal_overlay.zip", "xdm_android_bug_hunt_phase9_accessibility_adaptive_layout_full_overlay.zip"}:
    ERRORS.append("current_uix_overlay must identify UIX R6 or the Phase 9 accessibility closure")

require(
    "docs/architecture/UIX-R6-ACCESSIBILITY-PERFORMANCE-RELEASE-SEAL.md",
    "48 dp",
    "200%",
    "Compact, Medium, and Expanded",
    "Developer planners",
    "Clean install",
    "External handoff",
    "zero warnings and zero errors",
)
require(
    "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md",
    "UIX R6 Accessibility, Performance, and Release Seal",
)
require(
    "docs/architecture/DOWNLOADER_PRODUCT_CONTRACT.md",
    "UIX R6 final release boundary",
)
require(
    "tools/run-uix-device-smoke.sh",
    "connectedDebugAndroidTest",
    "UixR6AdaptiveLayoutTest",
    "AppSmokeTest",
)


devtool_config = ROOT.parents[1] / ".devtool.toml"
if not devtool_config.is_file():
    ERRORS.append("Repository .devtool.toml is missing")
else:
    devtool_text = devtool_config.read_text(encoding="utf-8", errors="replace")
    for task in (
        '"assembleDebug"', '":app:assembleDebugAndroidTest"',
        '":core-model:test"', '":core-utils:test"', '":transfer-aria2:test"',
        '":media:test"', '":app:testDebugUnitTest"', '"lintDebug"',
    ):
        if task not in devtool_text:
            ERRORS.append(f"Devtool final Android gate missing {task}")

validator = "tools/validate-uix-r6-accessibility-performance-release-seal.py"
for gate in ("tools/run-final-release-gate.sh", ".github/workflows/android.yml"):
    if validator not in read(gate):
        ERRORS.append(f"{gate} does not run {validator}")
root_workflow = (ROOT.parents[1] / ".github/workflows/android.yml")
if root_workflow.is_file() and validator not in root_workflow.read_text(encoding="utf-8", errors="replace"):
    ERRORS.append("Repository Android workflow does not run the UIX R6 validator")

if ERRORS:
    print("UIX R6 accessibility, performance, and release seal validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)
print("UIX R6 accessibility, performance, and release seal validation passed")
