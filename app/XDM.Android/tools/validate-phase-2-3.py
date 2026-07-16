#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
checks = {
    "backend-neutral contract": (root / "transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt", "interface DownloadBackend"),
    "transactional destination claim": (root / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt", "withTransaction"),
    "native HTTP backend": (root / "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt", "class NativeHttpDownloadBackend"),
    "checkpoint persistence": (root / "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeCheckpointStore.kt", "class NativeCheckpointStore"),
    "range validation": (root / "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt", "Content-Range"),
    "atomic promotion": (root / "storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriter.kt", "StandardCopyOption.ATOMIC_MOVE"),
    "database schema v5": (root / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt", "version = 5"),
}
errors = []
for name, (path, needle) in checks.items():
    if not path.is_file():
        errors.append(f"missing {name}: {path.relative_to(root)}")
        continue
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        errors.append(f"{name} marker not found in {path.relative_to(root)}: {needle}")
    if "TODO(" in text or "TODO:" in text:
        errors.append(f"unfinished TODO in {path.relative_to(root)}")

native_tests = root / "transfer-native/src/test/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackendTest.kt"
ownership_tests = root / "transfer-api/src/test/kotlin/com/mikeyphw/xdm/android/transfer/BackendCoordinatorTest.kt"
for path in (native_tests, ownership_tests):
    if not path.is_file(): errors.append(f"missing tests: {path.relative_to(root)}")

if errors:
    print("Phase 2/3 validation failed:")
    for error in errors: print(f"- {error}")
    sys.exit(1)
print("Phase 2/3 validation passed: backend ownership, native HTTP, durable checkpoints, strict ranges, and atomic file promotion are present")
