#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
checks = {
    "physical artifact model": (root / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "data class BackendArtifactIdentity"),
    "runtime identity model": (root / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "data class BackendRuntimeIdentity"),
    "backend preparation": (root / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt", "data class BackendPreparation"),
    "ownership reconciler": (root / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt", "class BackendOwnershipReconciler"),
    "generation-safe adoption": (root / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt", "override suspend fun adopt"),
    "schema v5 migration": (root / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", "Migration4To5"),
    "native physical artifacts": (root / "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt", "xdm-native-v1"),
    "startup reconciliation": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt", "reconcilePersistedOwnership"),
    "safe task detachment": (root / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt", "backend.detach(startedTask.taskId)"),
    "persistent backend instance": (root / "app/src/main/kotlin/com/mikeyphw/xdm/android/BackendRuntimeIdentityStore.kt", "backend-runtime-identities"),
    "architecture contract": (root / "docs/architecture/PHASE-6A-OWNERSHIP-HARDENING.md", "Unsafe or unknown claims are quarantined"),
    "attachment failure contract": (root / "docs/architecture/PHASE-6A-OWNERSHIP-HARDENING.md", "ownership is never released underneath a possible writer"),
}
errors = []
for name, (path, marker) in checks.items():
    if not path.is_file():
        errors.append(f"missing {name}: {path.relative_to(root)}")
        continue
    text = path.read_text(encoding="utf-8")
    if marker not in text:
        errors.append(f"{name} marker not found in {path.relative_to(root)}: {marker}")
    if "TODO(" in text or "TODO:" in text:
        errors.append(f"unfinished TODO in {path.relative_to(root)}")

required_tests = {
    "coordinator adoption tests": root / "transfer-api/src/test/kotlin/com/mikeyphw/xdm/android/transfer/BackendCoordinatorTest.kt",
    "Room ownership tests": root / "persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/BackendOwnershipStoreTest.kt",
    "migration test": root / "persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabaseMigrationTest.kt",
}
for name, path in required_tests.items():
    if not path.is_file():
        errors.append(f"missing {name}: {path.relative_to(root)}")

if errors:
    print("Ownership hardening validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("Ownership hardening validation passed: physical artifacts, runtime sessions, reconciliation, quarantine, and generation-safe adoption are present")
