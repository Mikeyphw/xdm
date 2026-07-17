#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "checksum models": (ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "data class TrustedBlockManifest"),
    "verification service": (ROOT / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/ChecksumVerification.kt", "class ChecksumVerificationService"),
    "trusted block planner": (ROOT / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/ChecksumVerification.kt", "fun planRepair"),
    "completion gate": (ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletionVerificationCoordinator.kt", "snapshot.state != DownloadState.Completed"),
    "runtime completion verifier": (ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt", "completionVerifier.complete"),
    "room checksum store": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomChecksumWorkflowStore.kt", "class RoomChecksumWorkflowStore"),
    "checksum dao": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/ChecksumDao.kt", "interface ChecksumDao"),
    "schema migration": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", "Migration7To8"),
    "schema": (ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/8.json", '"version": 8'),
    "add checksum ui": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Expected checksum"),
    "download verification ui": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Verification:"),
    "architecture": (ROOT / "docs/architecture/PHASE-8-CHECKSUMS-VERIFICATION-REPAIR.md", "A mismatch never produces a completed state"),
}
errors = []
for label, (path, needle) in checks.items():
    if not path.is_file():
        errors.append(f"missing {label}: {path.relative_to(ROOT)}")
        continue
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        errors.append(f"{label} marker missing: {needle}")
    if "TODO(" in text or "TODO:" in text:
        errors.append(f"unfinished TODO in {path.relative_to(ROOT)}")
manifest = json.loads((ROOT / "PROJECT_MANIFEST.json").read_text())
if 8 not in manifest["project"]["implemented_phases"]:
    errors.append("project manifest does not declare Phase 8")
if manifest["database"]["version"] < 8:
    errors.append("project manifest regressed below database v8")
app_database = (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").read_text()
match = re.search(r"version\s*=\s*(\d+)", app_database)
if match is None or int(match.group(1)) < 8:
    errors.append("Room database regressed below v8")
screens = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").read_text()
if "onClick = {}" in screens:
    errors.append("Phase 8 introduced an inert UI action")
if "AppRoute.Verification" in screens or "AppRoute.Repair" in screens:
    errors.append("Phase 8 introduced a forbidden top-level route")
if errors:
    print("Phase 8 validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("Phase 8 validation passed: checksum expectations, completion verification, persisted results, trusted block manifests, and selective repair planning are present")
