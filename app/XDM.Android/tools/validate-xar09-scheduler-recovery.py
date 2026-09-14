#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
OWNED = [
    "S09-01", "S09-02", "S09-03", "S09-04", "S09-05", "S09-06", "S09-07", "S09-08", "S09-09", "S09-10", "S09-11", "S09-12", "S09-13", "S09-14", "S09-15",
    "DS5-S09-01", "DS5-S09-02", "DS5-S09-03", "DS5-S09-04", "DS5-S09-05", "RERUN56-S09-01",
]
checks: list[str] = []

def fail(message: str) -> None:
    raise AssertionError(message)

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.exists():
        fail(f"missing {rel}")
    return path.read_text(encoding="utf-8")

def require(rel: str, *needles: str) -> str:
    text = read(rel)
    missing = [needle for needle in needles if needle not in text]
    if missing:
        fail(f"{rel} missing {missing}")
    checks.extend(f"{rel}:{needle}" for needle in needles)
    return text

def coverage() -> None:
    report = read("docs/remediation/XAR09-SCHEDULER-RECOVERY.md")
    missing = [cid for cid in OWNED if cid not in report]
    if missing:
        fail(f"report missing canonical IDs: {missing}")
    if "21/21" not in report:
        fail("report must explicitly claim 21/21 S09 closure")
    checks.append("21/21 S09 coverage")

def source_contracts() -> None:
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/SchedulerRecoveryLeaseCoordinator.kt",
        "class SchedulerRecoveryLeaseCoordinator",
        "tryAcquire",
        "LEASE_TTL_MS",
        "release(lease",
        "activeLeaseToken",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferRestoreWorker.kt",
        "SchedulerRecoveryLeaseCoordinator",
        "tryAcquire(\"restore-worker\")",
        "if (recovery.admissionSafe)",
        "notifyRestored(recovery.restoredCount)",
        "leaseCoordinator.release",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt",
        "SchedulerRecoveryLeaseCoordinator",
        "tryAcquire(\"application-startup\")",
        "nativeHlsRecovery: Result<Int>",
        "monitor.isSuccess",
        "migration.isSuccess && recovery.admissionSafe && nativeHlsRecovery.isSuccess",
        "recoveryLeaseCoordinator.release",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt",
        "consumeImmediateReevaluations(now)",
        "schedulePrecisionWakeup",
        "releaseFailedExecutionOwner",
        "retireAndroidSystemId",
        "QueueIntelligenceWorker.enqueueImmediate(appContext)",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt",
        "consumeImmediateReevaluation",
        "reevaluate-consumed",
        "compactLogIfNeededLocked",
        "MAX_LOG_LINES",
        "record.key.requestIdentity",
        "pendingImmediateReevaluationsLocked",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt",
        "releaseFailedExecutionOwner",
        "requestIdentity = event.requestIdentity",
        "markTerminalDispatched(event.downloadId, event.attemptGeneration, event.state, event.requestIdentity)",
        "schedulePrecisionWakeup",
        "PRECISION_WAKEUP_TAG",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt",
        "initial UIDT notification happens only after the durable queue claim is authorized",
        "setNotification(",
        "releaseFailedExecutionOwner(downloadId, queueClaimToken",
        "requestIdentity = requestIdentity",
        "markTerminalDispatched(downloadId, result?.attemptGeneration ?: 0L, state, requestIdentity)",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt",
        "if (!startForeground())",
        "private fun startForeground(): Boolean",
        "releaseFailedExecutionOwner(id, queueClaimToken",
        "requestIdentity = event.requestIdentity",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt",
        "resetStopIntentForNewExecution",
        "clearForNewExecution",
        "terminalRequestIdentity",
        "requestIdentity = terminalRequestIdentity",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueRetryLedger.kt",
        "secure-context requirement belongs to this exact failed request/backend/source identity",
        "download.sourceUrl",
        "download.backend.name",
        "putBoolean(prefix + \"secureRequired\", secureContextPresent)",
    )
    require(
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferSystemIdRegistry.kt",
        "fun retire(downloadId: String)",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
        "retireAndroidSystemId",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt",
        "clearing a finished Download must hide/delete retryable Termux-owned media",
        "repository.hideMediaOutput(\"media-output:termuxjob:${job.id}:${job.attemptGeneration}\")",
    )
    require(
        "scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/Xar09SchedulerRecoveryContractTest.kt",
        "xar09NamesEverySchedulerRecoveryBoundary",
        "terminalNotificationsAreFencedByRequestIdentity",
        "retryLedgerIdentityIncludesRequestAndBackend",
    )
    require("app/build.gradle.kts", "verifyXar09SchedulerRecovery", "validate-xar09-scheduler-recovery.py")
    require("tools/run-final-release-gate.sh", "tools/validate-xar09-scheduler-recovery.py")

def manifest_contract() -> None:
    manifest = read("PROJECT_MANIFEST.json")
    for needle in ["xar09_scheduler_recovery", "XAR09", "21", "tools/validate-xar09-scheduler-recovery.py", "xdm_android_xar09_scheduler_recovery_v1.tar.gz"]:
        if needle not in manifest:
            fail(f"PROJECT_MANIFEST missing {needle}")
    checks.append("manifest registered")

def main() -> int:
    coverage()
    source_contracts()
    manifest_contract()
    print(f"XAR09 scheduler recovery contract passed: {len(checks)} checks; 21/21 S09 findings covered.")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"XAR09 validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
