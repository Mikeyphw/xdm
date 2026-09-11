#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []
HOTFIX = "xdm_android_post_ux13_roadmap_completion_hotfix_v1.zip"
PARITY01_OVERLAY = "xdm_media_parity01_runtime_truth_diagnostics_backend_reliability_v2.zip"
PARITY02_OVERLAY = "xdm_media_parity02_logical_media_capture_browser_convergence_v2.zip"
PARITY03_OVERLAY = "xdm_media_parity03_native_hls_execution_admission_integrity_v2.zip"
PARITY04_OVERLAY = "xdm_media_parity04_browser_ux_userscripts_notifications_release_seal_v1.zip"
UX13 = "xdm_android_ux13_end_to_end_ui_ux_release_seal_v1.zip"


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)


def forbid(haystack: str, needle: str, message: str) -> None:
    require(needle not in haystack, message)


manifest = json.loads(text("PROJECT_MANIFEST.json") or "{}")
report = text("XDM_POST_UX13_ROADMAP_COMPLETION_HOTFIX_REPORT.md")
external = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt")
main_activity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
media_inbox = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
view_model = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
downloads_screen = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
downloads_workspace = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt")
download_details = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
ui_labels = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmUiLabels.kt")
developer = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
readme = text("README.md")
final_gate = text("tools/run-final-release-gate.sh")
ux13_validator = text("tools/validate-ux13-end-to-end-ui-ux-release-seal.py")
strings_xml = text("app/src/main/res/values/strings.xml")

# Final-authority truth.
require(manifest.get("current_overlay") in {HOTFIX, PARITY01_OVERLAY, PARITY02_OVERLAY, PARITY03_OVERLAY, PARITY04_OVERLAY}, "PROJECT_MANIFEST current_overlay must point to the post-UX13 hotfix or an accepted media parity successor")
require(manifest.get("next_phase") in {"complete", "media_parity04_browser_ux_userscripts_feedback_final_release_seal", None}, "roadmap must remain complete")
require(manifest.get("current_release_authority") in {"post_ux13_roadmap_completion_hotfix", "media_parity01_runtime_truth_diagnostics_backend_reliability", "media_parity02_logical_media_capture_browser_convergence", "media_parity03_native_hls_execution_admission_integrity", "media_parity04_browser_ux_userscripts_notifications_release_seal"}, "legacy current_release_authority must point to the final hotfix or accepted media parity successor")
require(manifest.get("database", {}).get("version", 0) >= 21, "later schema evolution must retain at least the Room 21 hotfix baseline")
phase = manifest.get("post_ux13_roadmap_completion_hotfix", {})
require(phase.get("status") == "implemented_final_hotfix", "post-UX13 hotfix manifest block must be final")
for key in (
    "direct_browser_media_review_dialog_removed",
    "direct_browser_capture_routes_internal_without_interstitial",
    "media_intake_technical_diagnostics_collapsed",
    "media_intake_internal_jargon_removed",
    "download_recovery_vocabulary_normalized",
    "downloads_zero_activity_metrics_hidden",
    "developer_center_duplicate_intro_title_removed",
    "readme_release_authority_reconciled",
    "legacy_current_release_authority_reconciled",
    "roadmap_reaudit_complete",
):
    require(phase.get(key) is True, f"hotfix manifest must seal {key}")
require(phase.get("roadmap_remaining_gaps") == [], "hotfix manifest must record no remaining roadmap gaps")
require(phase.get("room_schema_unchanged") == 21, "hotfix manifest block must retain schema 21")
require(phase.get("validation_deferred") is False, "final hotfix may not defer validation")
require(phase.get("validation_repair_revision") == "v5", "post-UX13 hotfix must identify validation repair revision v5")
require(phase.get("lint_unused_media_locator_resources_removed") is True,
        "hotfix manifest must seal removal of obsolete Live Locator resources")
require(phase.get("lint_unused_resource_static_preflight") is True,
        "hotfix manifest must seal the unused-resource static preflight")
for obsolete_name in ("media_locator_locate", "media_locator_rescan"):
    forbid(strings_xml, f'name="{obsolete_name}"',
           f"obsolete Live Locator resource must remain removed: {obsolete_name}")

# Direct browser-media capture: no review AlertDialog/interstitial; retain internal-only routing.
require("payload.hasDirectCaptureSession" in external, "direct browser capture detection must remain")
require("routeDirectBrowserCapture(draft)" in external, "direct browser capture must route immediately")
require("private fun routeDirectBrowserCapture" in external, "direct browser capture routing helper missing")
require("ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT" in external, "external boundary must emit internal direct-capture action")
require("EXTRA_INTERNAL_BROWSER_DIRECT_CAPTURE_URI" in external, "external boundary must pass direct-capture URI through internal extra")
require("EXTRA_INTERNAL_BROWSER_DIRECT_PRIVATE_NETWORK_APPROVED" in external, "exact-target private-network approval handoff must remain")
forbid(external, 'setTitle("Open browser media in XDM")', "direct browser-media confirmation title must be removed")
forbid(external, "reviewDirectBrowserCapture", "direct browser-media review helper must be removed")
forbid(external, "bounded candidate set", "direct browser-media dialog implementation copy must be removed")
forbid(external, "candidate(s)", "direct browser-media dialog candidate grammar must be removed")
route_start = external.find("private fun routeDirectBrowserCapture")
route_end = external.find("private fun reviewEncryptedBrowserCapture", route_start)
route_body = external[route_start:route_end] if route_start >= 0 and route_end > route_start else ""
require(route_body != "", "unable to isolate direct browser capture routing helper")
forbid(route_body, "AlertDialog.Builder", "direct browser capture routing helper must not show a dialog")
require("ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT" in main_activity and "ingestDirectBrowserCaptureSession" in main_activity,
        "MainActivity must retain internal direct-capture consumption")
# Generic external command and legacy encrypted boundaries are intentionally preserved.
require('setTitle("Open in XDM")' in external and "ExternalCommandAuthorization.UserConfirmed" in external,
        "generic external-command review boundary must remain")
require("reviewEncryptedBrowserCapture" in external, "legacy encrypted-capture recovery boundary must remain")

# Media intake hierarchy and language.
require('XdmTechnicalDetails(label = "Technical details")' in media_inbox, "Media intake diagnostics must be collapsed")
require("XdmTechnicalText(diagnostic)" in media_inbox, "Media intake diagnostics must use selectable technical text")
forbid(media_inbox, "XdmMetadataText(diagnostic)", "Media intake diagnostics must not render inline as normal metadata")
for token in (
    'XdmStatusBadge("Running"',
    'XdmStatusBadge("Ready"',
    'XdmStatusBadge("Needs action"',
    'XdmStatusBadge("Failed"',
    'XdmStatusBadge("Completed"',
):
    require(token in media_inbox, f"Media intake normalized status missing {token}")
for old_copy in (
    "Fetching a bounded page prefix and checking it for media candidates.",
    "reviewable media item(s)",
    "No reviewable media found",
    "reviewable media candidates",
    "durable revision of this browser capture session",
    "durable request handoffs",
    "media candidate(s) for review",
):
    forbid(view_model, old_copy, f"normal Media feedback must not expose implementation copy: {old_copy}")
require("Checking this page for downloadable media." in view_model, "page-media feedback must be task-oriented")
require("mediaItemCountLabel" in view_model, "Media feedback must use human singular/plural item counts")

# Downloads screenshot polish + terminology seal.
require("val showMetrics = downloadingCount > 0 || waitingCount > 0 || queuedCount > 0" in downloads_screen,
        "Downloads must derive whether overview metrics contain useful activity")
require("if (showMetrics)" in downloads_screen and "XdmMetricStrip(metrics" in downloads_screen,
        "Downloads must hide the all-zero/Idle overview strip")
for source_name, source in (
    ("DownloadsWorkspace", downloads_workspace),
    ("DownloadDetails", download_details),
    ("XdmUiLabels", ui_labels),
):
    forbid(source, "Needs recovery", f"{source_name} must use normalized Needs action vocabulary")

# Developer hierarchy and source-truth documentation.
forbid(developer, 'XdmCardTitle("Developer Center")', "Developer Center must not repeat its page title in an intro card")
require("Technical controls are separated from normal app settings" in developer, "Developer Center lightweight intro copy must remain")
require("Media Parity01 remains the runtime-truth/diagnostics/backend-reliability baseline" in readme and "Media Parity02 is the current logical-media capture and Firefox/WebView convergence authority" in readme,
        "README must retain Parity01 runtime truth and identify Parity02 as current capture authority")
require("post-UX13 roadmap-completion hotfix remains the UI release baseline" in readme,
        "README must retain the post-UX13 UI release baseline")
require("UX13 is the end-to-end UI/UX baseline" in readme, "README must retain UX13 as baseline")
require("Live Locator uses a constrained in-app WebView" in readme and "does not register as a general browser" in readme,
        "README must truthfully describe the constrained Live Locator runtime")

# Carry-forward and final validation ownership.
require(HOTFIX in ux13_validator, "UX13 carry-forward validator must accept the final hotfix authority")
require("validate-post-ux13-roadmap-completion-hotfix.py" in final_gate,
        "canonical final gate must execute the post-UX13 hotfix validator")
require("run-bug-hunt-phase11-validation-matrix.sh --static-only --ci" in final_gate,
        "final gate must retain the 80-row Phase11 static matrix")
require(HOTFIX in report and UX13 in report and "No other unresolved UX01–UX13 product promise was found" in report,
        "hotfix report must document the re-audit and UX13 baseline")

# Test-framework consistency. All current unit/instrumentation Kotlin tests use JUnit 4;
# kotlin.test/JUnit Jupiter are not declared by these Android/JVM module contracts.
test_sources = sorted(
    p for p in ROOT.rglob("*.kt")
    if "/src/test/" in p.as_posix() or "/src/androidTest/" in p.as_posix()
)
require(bool(test_sources), "test-framework preflight must discover Kotlin test sources")
for source_path in test_sources:
    source_text = source_path.read_text(encoding="utf-8")
    relative = source_path.relative_to(ROOT)
    import_lines = [line.strip() for line in source_text.splitlines() if line.lstrip().startswith("import ")]
    require(not any(line.startswith("import kotlin.test") for line in import_lines),
            f"unsupported kotlin.test import in {relative}; use project-standard JUnit 4")
    require(not any(line.startswith("import org.junit.jupiter") for line in import_lines),
            f"unsupported JUnit Jupiter import in {relative}; use project-standard JUnit 4")
require('testImplementation(libs.junit)' in text("app/build.gradle.kts"),
        "app unit-test dependency must retain JUnit 4")
require(phase.get("test_framework_consistency_repaired") is True,
        "hotfix manifest must seal test-framework consistency repair")
require(phase.get("test_framework_static_preflight") is True,
        "hotfix manifest must seal test-framework static preflight")
require(phase.get("test_source_root_resolution_repaired") is True,
        "hotfix manifest must seal test source-root resolution repair")
require(phase.get("test_source_root_static_preflight") is True,
        "hotfix manifest must seal test source-root static preflight")

# Source-contract tests must resolve repository sources from a discovered Android root,
# never from the Gradle test process working directory.
app_contract_sources = sorted((ROOT / "app/src/test").rglob("*.kt"))
for source_path in app_contract_sources:
    source_text = source_path.read_text(encoding="utf-8")
    relative = source_path.relative_to(ROOT)
    for unsafe in ('File("app/src/', 'File("core-model/src/'):
        require(unsafe not in source_text,
                f"working-directory-dependent source read in {relative}: {unsafe}")

require("Validation repair revision v3" in report,
        "hotfix report must document validation repair revision v3")
require("Validation repair revision v4" in report,
        "hotfix report must document validation repair revision v4")
require("Validation repair revision v5" in report,
        "hotfix report must document validation repair revision v5")

if ERRORS:
    print("Post-UX13 roadmap-completion hotfix validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)
print("Post-UX13 roadmap-completion hotfix validation passed")
