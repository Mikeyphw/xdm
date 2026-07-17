#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "selection policy": (ROOT / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt", "fun rankedRecommendations"),
    "pre-start fallback boundary": (ROOT / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt", "Fallback before task creation"),
    "migration journal model": (ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "enum class BackendMigrationStage"),
    "migration store": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendMigrationStore.kt", "class RoomBackendMigrationStore"),
    "transactional ownership transfer": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt", "override suspend fun transfer"),
    "migration coordinator": (ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt", "class BackendMigrationCoordinator"),
    "source retirement": (ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt", "retireForMigration"),
    "cross-backend reuse guard": (ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt", "cannot be silently reinterpreted"),
    "capability UI": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Backend strategy"),
    "migration UI compatibility gate": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "targetCompatible"),
    "add UI compatibility gate": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "recommendation?.compatible != false"),
    "architecture": (ROOT / "docs/architecture/PHASE-7-BACKEND-STRATEGY-MIGRATION.md", "Migration is a journaled transaction"),
    "strategy tests": (ROOT / "transfer-api/src/test/kotlin/com/mikeyphw/xdm/android/transfer/BackendCoordinatorTest.kt", "backendFailureAfterTaskCreationNeverFallsBack"),
    "migration tests": (ROOT / "scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinatorTest.kt", "emptySourceTransfersGenerationBeforeTargetActivation"),
    "schema": (ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/7.json", '"version": 7'),
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
if 7 not in manifest["project"]["implemented_phases"]:
    errors.append("project manifest does not declare Phase 7")
if manifest["database"]["version"] < 7:
    errors.append("project manifest does not declare at least database v7")
app_database = (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").read_text()
match = re.search(r"version\s*=\s*(\d+)", app_database)
if match is None or int(match.group(1)) < 7:
    errors.append("Room database is older than v7")

screens = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").read_text()
if "onClick = {}" in screens:
    errors.append("Phase 7 introduced an inert UI action")
if "AppRoute.Backend" in screens or "AppRoute.Migration" in screens:
    errors.append("Phase 7 introduced a forbidden top-level route")

if errors:
    print("Phase 7 validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("Phase 7 validation passed: explainable selection, pre-start fallback, journaled migration, ownership transfer, preserved source artifacts, and compatible UI actions are present")
