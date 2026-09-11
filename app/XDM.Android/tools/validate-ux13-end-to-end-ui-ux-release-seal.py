#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []
OVERLAY = "xdm_android_ux13_end_to_end_ui_ux_release_seal_v1.zip"
POST_UX13_HOTFIX = "xdm_android_post_ux13_roadmap_completion_hotfix_v1.zip"
PARITY01_OVERLAY = "xdm_media_parity01_runtime_truth_diagnostics_backend_reliability_v2.zip"
PARITY02_OVERLAY = "xdm_media_parity02_logical_media_capture_browser_convergence_v2.zip"
PARITY03_OVERLAY = "xdm_media_parity03_native_hls_execution_admission_integrity_v2.zip"
PARITY04_OVERLAY = "xdm_media_parity04_browser_ux_userscripts_notifications_release_seal_v1.zip"


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)


manifest = json.loads(text("PROJECT_MANIFEST.json") or "{}")
report = text("XDM_UX13_END_TO_END_UI_UX_RELEASE_SEAL_REPORT.md")
final_gate = text("tools/run-final-release-gate.sh")
queue_reader = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidQueueConditionsReader.kt")
queue_policy = text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligence.kt")
downloads = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt")
download_details = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
media_card = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
media_workspace = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")
locator = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
library = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
activity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt") + "\n" + text("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt") + "\n" + text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityWorkspace.kt") + "\n" + text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
settings = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
developer = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperSettingsScreen.kt") + "\n" + text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
design = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmDesignSystem.kt")
shell = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt")
ux12_contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/Ux12AccessibilityTerminologyVisualSealContractTest.kt")

require(manifest.get("current_overlay") in {OVERLAY, POST_UX13_HOTFIX, PARITY01_OVERLAY, PARITY02_OVERLAY, PARITY03_OVERLAY, PARITY04_OVERLAY}, "PROJECT_MANIFEST current_overlay must point to UX13 or an accepted successor through Parity04")
require(manifest.get("next_phase") in {"complete", "media_parity04_browser_ux_userscripts_feedback_final_release_seal", None}, "UX13 must close the historical UX roadmap or point to the accepted Parity04 successor")
require(manifest.get("database", {}).get("version", 0) >= 21, "later schema evolution must retain at least the UX13 Room 21 baseline")
phase = manifest.get("ux13_end_to_end_ui_ux_release_seal", {})
require(phase.get("status") == "implemented_final_seal", "UX13 manifest block must be final-seal status")
require(phase.get("roadmap_complete") is True, "UX13 manifest must mark the UX roadmap complete")
require(phase.get("validation_deferred") is False, "UX13 may not defer validation")
for key in (
    "cross_screen_state_consistency",
    "storage_truth_consistency",
    "download_state_consistency",
    "media_state_consistency",
    "developer_mode_single_center",
    "legacy_debug_surface_duplication_forbidden",
    "accessibility_seal_required",
    "full_static_gate_required",
    "app_unit_tests_required",
    "lint_required",
):
    require(phase.get(key) is True, f"UX13 manifest must require {key}")

# Storage truth: the scheduler must not grow another URI parser.
require("destinationWriter.health(raw)" in queue_reader, "queue storage probe must use shared DestinationWriter.health")
require("DestinationSpaceState.Known" in queue_reader and "DestinationSpaceState.Unknown" in queue_reader and "DestinationSpaceState.Unavailable" in queue_reader, "queue destination space must distinguish known/unknown/unavailable")
require("public-downloads://" not in queue_reader and "app-private://" not in queue_reader, "queue reader must not revive destination URI parsing")
require("destinationSpaceState" in queue_policy and "DestinationSpaceState.Unknown" in queue_policy, "queue policy must reason about destination-space state")

# Download truth.
for literal in ('All("All")', 'Downloading("Downloading")', 'Waiting("Waiting")', 'Finished("Finished")'):
    require(literal in downloads, f"Downloads primary filter missing {literal}")
require("private val waitingStates = queuedStates + DownloadState.Paused" in downloads, "Waiting filter must include paused work")
require("Waiting — $policy" in downloads, "download rows must use concise waiting policy copy")
for label in ("What happened:", "What XDM will do:", "What you can do:", "Technical details"):
    require(label in download_details, f"download details error hierarchy missing {label}")

# Media truth.
require("MediaConsumerState.Downloaded" in media_card and 'Text("Open")' in media_card and 'Text(if (downloadInFlight) "Adding…" else "Download again")' in media_card, "downloaded media must offer Open then Download again")
for state in ("Captured", "Ready", "Downloading", "Downloaded", "RefreshNeeded", "Unavailable"):
    require(f"MediaConsumerState.{state}" in media_card or f"MediaConsumerState.{state}" in media_workspace, f"media consumer lifecycle missing {state}")

# Locator / Library / Activity.
require("onRenderProcessGone" in locator and "webViewDisposed = true" in locator and "intent.putExtra(EXTRA_URL, normalized)" in locator and "recreate()" in locator, "Live Locator must retain renderer-loss recovery")
require("R.string.media_locator_title" in locator and "R.string.media_locator_scan" in locator, "Live Locator must retain native locator controls")
require("No media yet" in library and "Find media" in library and "Share" in library and "Delete saved file" in library, "Library must retain empty/open/share/delete product flows")
require("Queue & recovery" in activity and "Retry storage check" in activity, "Activity must retain queue/recovery-first actions")
require("similar events" in activity.lower() or "similarEvents" in activity, "Activity must retain grouped repeated incidents")

# Settings / developer boundary.
for label in ("Storage & destinations", "Download behavior", "Network", "Media & capture", "External tools", "Post-processing", "Appearance", "Backup & restore", "Diagnostics & support", "Developer mode", "Developer Center"):
    require(label in settings, f"Settings information architecture missing {label}")
require(settings.count('"Developer Center"') == 1, "Settings must expose exactly one visible Developer Center entry")
for legacy in ('"Debug Workbench"', '"Advanced Debug Workbench"', '"Developer tools"'):
    require(legacy not in settings, f"legacy duplicated developer surface returned to Settings: {legacy}")
require("Developer mode is off" in developer and "Enable Developer mode" in developer, "Developer Center must remain gated by Developer mode")

# Accessibility / visual seal.
require("stateDescription" in design and "accessibilityLabel()" in design, "status meaning must remain exposed through semantics")
require("SelectionContainer" in design and "FontFamily.Monospace" in design, "technical text must remain selectable monospace")
require("alwaysShowLabel = fontScale < 1.60f || selected" in shell, "bottom navigation must remain large-text adaptive")
require("Ux12AccessibilityTerminologyVisualSealContractTest" in ux12_contract, "UX12 accessibility regression contract must remain present")

# Final validation ownership.
require("validate-ux13-end-to-end-ui-ux-release-seal.py" in final_gate, "canonical final static gate must execute UX13 validator")
require("run-bug-hunt-phase11-validation-matrix.sh --static-only --ci" in final_gate, "final gate must retain Phase11 80-row static matrix")
require("UX13" in report and "Room schema: 21" in report and "documentation" in report.lower(), "UX13 report must document final validation ownership and schema")

if ERRORS:
    print("UX13 end-to-end UI/UX release seal validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)
print("UX13 end-to-end UI/UX release seal validation passed")
