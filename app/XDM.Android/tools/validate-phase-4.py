#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
checks = {
    "UIDT job": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt", "setNotification("),
    "UIDT scheduling": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionStarter.kt", ".setUserInitiated(true)"),
    "foreground service": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt", "FOREGROUND_SERVICE_TYPE_DATA_SYNC"),
    "timeout pause": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt", "runtime.pauseAll()"),
    "notification actions": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt", "ACTION_PAUSE_ALL"),
    "boot restore worker": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferBootReceiver.kt", "enqueueUniqueWork"),
    "interrupted state restore": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt", "INTERRUPTED_STATES"),
    "Room runtime adapter": (root / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferDownloadStore.kt", "RepositoryTransferDownloadStore"),
    "runtime recovery test": (root / "scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntimeTest.kt", "restorePausesOnlyInterruptedStates"),
    "launch policy tests": (root / "scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/TransferLaunchPolicyTest.kt", "android14VisibleLaunchUsesUidt"),
    "phase documentation": (root / "docs/architecture/PHASE-4.md", "Android 14 and newer"),
}
errors = []
for label, (path, needle) in checks.items():
    if not path.is_file():
        errors.append(f"missing {label}: {path.relative_to(root)}")
    elif needle not in path.read_text(encoding="utf-8"):
        errors.append(f"missing {label} invariant: {needle}")

manifest = root / "scheduler/src/main/AndroidManifest.xml"
try:
    ET.parse(manifest)
except Exception as error:
    errors.append(f"invalid scheduler manifest: {error}")
else:
    text = manifest.read_text(encoding="utf-8")
    for needle in ("FOREGROUND_SERVICE_DATA_SYNC", "RUN_USER_INITIATED_JOBS", "RECEIVE_BOOT_COMPLETED", 'foregroundServiceType="dataSync"'):
        if needle not in text:
            errors.append(f"scheduler manifest missing {needle}")
    if "LOCKED_BOOT_COMPLETED" in text:
        errors.append("credential-protected Room restore must not run from LOCKED_BOOT_COMPLETED")

catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
if 'work = "2.11.2"' not in catalog:
    errors.append("WorkManager 2.11.2 is not pinned")

for path in root.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    if "TODO(" in text or "TODO:" in text:
        errors.append(f"unfinished TODO in {path.relative_to(root)}")

if errors:
    print("Phase 4 validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print(f"Phase 4 validation passed: {len(checks)} execution and lifecycle invariants")
