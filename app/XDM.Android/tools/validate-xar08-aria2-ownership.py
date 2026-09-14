#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
OWNED = ['S08-01', 'S08-02', 'S08-03', 'S08-04', 'S08-05', 'S08-06', 'S08-07', 'S08-08', 'S08-09', 'S08-10', 'S08-11', 'S08-12', 'S08-13', 'S08-15', 'S08-16', 'S08-17', 'DS4-S08-01', 'DS4-S08-02', 'DS4-S08-03', 'DS4-S08-04', 'DS4-S08-05', 'DS4-S08-06', 'RERUN34-S08-01']
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
    report = read("docs/remediation/XAR08-ARIA2-OWNERSHIP.md")
    missing = [cid for cid in OWNED if cid not in report]
    if missing:
        fail(f"report missing canonical IDs: {missing}")
    if "23/23" not in report:
        fail("report must explicitly claim 23/23 S08 closure")
    checks.append("23/23 S08 coverage")

def source_contracts() -> None:
    require(
        "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
        "aria2OwnedProtocols",
        "lastKnownEnabledFeatures",
        "candidateUris.none(ExternalUrlPolicy::hasCredentialBearingQuery)",
        "magnet, sftp, torrent and provider-owned inputs require another backend",
        "commitMappingAndSession",
        "MAPPING_REMOVAL_PENDING",
        "ARIA2_RPC_UNAVAILABLE",
        "ARIA2_SAVE_SESSION_FAILED",
        "CANCEL_AT_COMPLETION_BOUNDARY",
        "verifiedCompleteReconciliation",
        "CompletionVerificationPending",
        "safeToResume = false",
        "RetiredForMigration",
        "FinalizationFailed",
        "updateMapping(mapping, MAPPING_COMPLETED)",
    )
    require(
        "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt",
        "cleanupTransientLaunchConfigurations()",
        "sanitizeSavedSessionToOwnedMetadata()",
        "rotateRuntimeLog()",
        "processId = process.processId",
        "ACTIVE_TASK_BATCH_SIZE",
        "lastKnownEnabledFeatures",
    )
    require(
        "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt",
        "sanitizeSavedSessionToOwnedMetadata",
        "rotateRuntimeLog",
        "runtime-owner.properties",
        "sessionFileSha256",
        "launch-",
    )
    require(
        "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RpcClient.kt",
        "purgeSavedSession",
    )
    require(
        "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2EventPoller.kt",
        "terminalBackoffMillis",
        "adaptiveDelay",
    )
    require(
        "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/EngineEscalationPlanner.kt",
        "aria2Eligible",
        "Use only for unsigned direct files",
    )
    require(
        "transfer-aria2/src/test/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Xar08Aria2OwnershipContractTest.kt",
        "xar08ContractNamesEveryFailClosedOwnershipBoundary",
        "recoveredCompleteMustBeVerifiedBeforeCompletionIsAcknowledged",
    )
    require("app/build.gradle.kts", "verifyXar08Aria2Ownership", "validate-xar08-aria2-ownership.py")
    require("tools/run-final-release-gate.sh", "tools/validate-xar08-aria2-ownership.py")

def manifest_contract() -> None:
    manifest = read("PROJECT_MANIFEST.json")
    for needle in ["xar08_aria2_ownership", "XAR08", "23", "tools/validate-xar08-aria2-ownership.py"]:
        if needle not in manifest:
            fail(f"PROJECT_MANIFEST missing {needle}")
    checks.append("manifest registered")

def main() -> int:
    coverage()
    source_contracts()
    manifest_contract()
    print(f"XAR08 aria2 ownership contract passed: {len(checks)} checks; 23/23 S08 findings covered.")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"XAR08 validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
