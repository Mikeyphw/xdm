#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

manifest_path = ROOT / "PROJECT_MANIFEST.json"
manifest = json.loads(manifest_path.read_text())
manifest_text = manifest_path.read_text()
roadmap = ROOT / "docs/browser/DUAL_BROWSER_DOWNLOADER_ROADMAP.md"
study = ROOT / "docs/browser/REFERENCE_STUDY_1DM_SUPERX.md"
run_gate = (ROOT / "tools/run-final-release-gate.sh").read_text()
workflow = (ROOT / ".github/workflows/android.yml").read_text()
contract = (ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt").read_text()

allowed_current = {
    "xdm_android_phase37a_browser_downloader_roadmap_overlay.zip",
    "xdm_android_phase37b_dual_launcher_navigation_split_overlay.zip",
}
if manifest.get("current_overlay") not in allowed_current:
    errors.append("current_overlay must point at Phase 37A or an approved later Phase 37B overlay")

if 37 not in manifest.get("project", {}).get("implemented_phases", []):
    errors.append("project implemented_phases must include 37")

phase = manifest.get("phase37a_browser_downloader_roadmap", {})
required_true = [
    "dual_surface_direction",
    "browser_first_class_required",
    "downloader_surface_preserved",
    "roadmap_docs_added",
    "reference_study_recorded",
    "no_runtime_changes",
    "no_proprietary_1dm_code",
    "superx_reference_only",
    "no_silent_auto_queue_default",
    "privacy_redaction_required",
]
for key in required_true:
    if phase.get(key) is not True:
        errors.append(f"phase37a_browser_downloader_roadmap.{key} must be true")

for path, required in [
    (roadmap, ["XDM Downloader", "XDM Browser", "Phase 37B", "Phase 38", "No copied proprietary 1DM"]),
    (study, ["1DM", "SuperX", "topology reference", "open-source media-capture reference", "white-screen"]),
]:
    if not path.is_file():
        errors.append(f"missing document: {path.relative_to(ROOT)}")
        continue
    text = path.read_text()
    for needle in required:
        if needle not in text:
            errors.append(f"{path.relative_to(ROOT)} must mention {needle!r}")

if "validate-phase-37a-browser-downloader-roadmap.py" not in run_gate:
    errors.append("final release gate must include Phase 37A validator")
if "validate-phase-37a-browser-downloader-roadmap.py" not in workflow:
    errors.append("Android CI must include Phase 37A validator")
if "phaseThirtySevenABrowserDownloaderRoadmapContractsArePresent" not in contract:
    errors.append("ArchitectureContractTest must cover Phase 37A")
if "BrowserActivity" in manifest_text or "AppRoute.Browser" in contract.split("phaseThirtySevenABrowserDownloaderRoadmapContractsArePresent", 1)[0]:
    errors.append("Phase 37A must remain roadmap-only; runtime BrowserActivity/AppRoute work belongs to Phase 37B")

if errors:
    raise SystemExit("Phase 37A browser/downloader roadmap validation failed:\n" + "\n".join(errors))
print("Phase 37A browser/downloader roadmap validation passed")
