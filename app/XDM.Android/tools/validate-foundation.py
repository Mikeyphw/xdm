#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
required_modules = {
    "app", "core-model", "core-utils", "persistence", "storage", "transfer-api",
    "transfer-native", "transfer-aria2", "scheduler", "media", "diagnostics",
    "browser-integration", "tasker-plugin", "protocol-test-lab",
}
settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
missing_modules = sorted(m for m in required_modules if f'":{m}"' not in settings)

entities = (root / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt").read_text(encoding="utf-8")
required_tables = {
    "downloads", "download_sources", "mirrors", "transfer_segments", "checkpoints",
    "checksum_expectations", "checksum_results", "queues", "schedule_rules", "backend_tasks",
    "recovery_records", "finalization_journals", "notification_records", "tags", "download_tags",
    "destination_permissions", "aria2_session_mappings", "destination_claims", "ownership_counters",
}
missing_tables = sorted(t for t in required_tables if f'tableName = "{t}"' not in entities)

routes = (root / "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").read_text(encoding="utf-8")
required_routes = {"Downloads", "Add", "Queues", "Scheduler", "Media", "Recovery", "Diagnostics", "Settings"}
missing_routes = sorted(r for r in required_routes if f'{r}("{r}"' not in routes)

errors = []
if missing_modules: errors.append(f"missing modules: {missing_modules}")
if missing_tables: errors.append(f"missing tables: {missing_tables}")
if missing_routes: errors.append(f"missing routes: {missing_routes}")
if not (root / ".github/workflows/android.yml").is_file(): errors.append("missing Android CI workflow")
if not (root / "persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabaseMigrationTest.kt").is_file(): errors.append("missing migration test")

if errors:
    print("Foundation validation failed:")
    for error in errors: print(f"- {error}")
    sys.exit(1)

phase23_files = [
    root / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt",
    root / "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
    root / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt",
]
missing_phase23 = [str(path.relative_to(root)) for path in phase23_files if not path.is_file()]
if missing_phase23:
    print(f"Foundation validation failed: missing Phase 2/3 files: {missing_phase23}")
    sys.exit(1)
print(f"Foundation validation passed: {len(required_modules)} modules, {len(required_tables)} tables, {len(required_routes)} routes; Phase 2/3 contracts present")
