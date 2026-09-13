#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
OWNED = [*(f"S07-{i:02d}" for i in range(1, 16)), *(f"DS4-S07-{i:02d}" for i in range(1, 6)), "RERUN34-S07-01"]
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
    report = read("docs/remediation/XAR07-NATIVE-HTTP-PROTOCOL.md")
    missing = [cid for cid in OWNED if cid not in report]
    if missing:
        fail(f"report missing canonical IDs: {missing}")
    if "21/21" not in report:
        fail("report must explicitly claim 21/21 S07 closure")
    checks.append("21/21 S07 coverage")

def source_contracts() -> None:
    require(
        "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
        "supportsSelectiveRepair = false",
        "sanitizeRedirectCredentials",
        "listOf(\"Authorization\", \"Cookie\", \"Proxy-Authorization\", \"Referer\", \"Origin\")",
        "executeTracked(control, builder.build())",
        "return block(response)",
        "control.activeCalls += call",
        "remainingExpected",
        "Response body exceeded the declared native segment boundary",
        "range.totalLength ?: if (range.rangeSupported) null else length",
        "expectedLength = metadata.totalLength ?: request.expectedLength",
        "Complete response does not match the probed representation validator",
        "Content-Range total is unknown while the expected remote length is known",
        "Content-Range total is not greater than the end byte",
        "verifyCheckpointBeforePromotion",
        "Native finalization requires a persisted checkpoint integrity graph",
        "isTextualOrStructuredErrorContentType",
        "parseHttpDate(raw)?.toInstant()?.toEpochMilli()",
        "chargedHost",
        "NON_DOWNGRADABLE_STATES",
        "COMMIT_PROTECTED_STATES",
    )
    require(
        "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeTransferModels.kt",
        "val retryHost: String? = null",
    )
    require(
        "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeSelectiveRepairService.kt",
        "repairLocks",
        "computeIfAbsent(lockKey)",
        "UUID.randomUUID()",
        "verifyAllTrustedBlocks",
        "untouched trusted",
        "Selective repair requires the app request-security validator",
    )
    require(
        "transfer-native/src/test/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/Xar07NativeHttpProtocolContractTest.kt",
        "nativeHttpKeepsCallsTrackedThroughBodyReadAndBoundsWrites",
        "metadataAndRepresentationTruthAreFailClosed",
        "finalizationAndControlsCannotDowngradeCommittedOrQuarantinedWork",
        "redirectsRetryAndSelectiveRepairAreBoundedByProtocolTruth",
    )
    require("app/build.gradle.kts", "verifyXar07NativeHttpProtocol", "validate-xar07-native-http-protocol.py")
    require("tools/run-final-release-gate.sh", "tools/validate-xar07-native-http-protocol.py")

def manifest_contract() -> None:
    manifest = read("PROJECT_MANIFEST.json")
    for needle in ["xar07_native_http_protocol", "XAR07", "21", "tools/validate-xar07-native-http-protocol.py"]:
        if needle not in manifest:
            fail(f"PROJECT_MANIFEST missing {needle}")
    checks.append("manifest registered")

def main() -> int:
    coverage()
    source_contracts()
    manifest_contract()
    print(f"XAR07 native HTTP protocol contract passed: {len(checks)} checks; 21/21 S07 findings covered.")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"XAR07 validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
