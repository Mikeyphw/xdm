#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
import tomllib
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
ROOT = ANDROID.parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def text(path: Path) -> str:
    require(path.is_file(), f"missing RM08 contract path: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def main() -> int:
    test_path = ANDROID / "app/src/androidTest/kotlin/com/mikeyphw/xdm/android/Aria2Rm08PackagedRuntimeInstrumentedTest.kt"
    test = text(test_path)
    trust = text(ANDROID / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2AndroidTrustBundleProvider.kt")
    store = text(ANDROID / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt")
    package_verifier = text(ROOT / "tools/xgo/verify_rm08_aria2_package.py")
    gate_audit = text(ROOT / "tools/xgo/audit_gate12.py")
    app_gradle = text(ANDROID / "app/build.gradle.kts")
    policy = json.loads(text(ROOT / "engine/testdata/seal/gate12-policy.json"))
    with (ROOT / ".devtool.toml").open("rb") as fh:
        config = tomllib.load(fh)

    rm08_gradle = re.search(r'val verifyRm08Aria2PackagedExecutionContract = tasks\.register<Exec>.*?\n}\n', app_gradle, re.S)
    require(rm08_gradle is not None, "RM08 Gradle verification task is missing")
    rm08_gradle_text = rm08_gradle.group(0)
    require("doFirst" not in rm08_gradle_text and "doLast" not in rm08_gradle_text,
            "RM08 Gradle verifier must remain configuration-cache-safe and must not mutate Exec command state in task actions")
    require('commandLine(' in rm08_gradle_text and 'validate-rm08-aria2-packaged-execution.py' in rm08_gradle_text,
            "RM08 Gradle task no longer executes the canonical static validator")

    require("fun interface Aria2TrustBundleProvider" in trust, "RM08 trust-provider seam is missing")
    require("Aria2AndroidTrustBundleProvider(context)" in store, "production trust default must remain AndroidCAStore-backed")
    for token in (
        "AndroidAria2CapabilityProbe", "attestationVerified", "nativeLibraryDir",
        "untrusted-server.p12", "Aria2TaskStatusValue.Error",
        "trusted-server.p12", "Aria2TaskStatusValue.Complete",
        "rpc.saveSession()", "manager.stop()", "manager.start()", "tellStatus(recoveryGid)",
        "xgo_rm08_aria2",
    ):
        require(token in test, f"RM08 instrumented lifecycle contract lost {token}")
    for asset in (
        ANDROID / "app/src/androidTest/assets/rm08/trusted-ca.pem",
        ANDROID / "app/src/androidTest/assets/rm08/trusted-server.p12",
        ANDROID / "app/src/androidTest/assets/rm08/untrusted-ca.pem",
        ANDROID / "app/src/androidTest/assets/rm08/untrusted-server.p12",
    ):
        require(asset.is_file() and asset.stat().st_size > 0, f"RM08 TLS fixture missing/empty: {asset.relative_to(ROOT)}")

    targets = config.get("targets") or {}
    contract = targets.get("xgo_rm08_aria2_contract") or {}
    require(contract.get("runner") == "android", "xgo_rm08_aria2_contract must use the Android runner")
    require(((contract.get("tasks") or {}).get("test") or []) == [":app:verifyRm08Aria2PackagedExecutionContract"],
            "RM08 overlay validation must stay bounded to its static contract")

    device = targets.get("xgo_rm08_aria2_device") or {}
    require(device.get("runner") == "android", "xgo_rm08_aria2_device must use the Android runner")
    ins = ((device.get("android") or {}).get("instrumentation") or {})
    require(ins.get("policy") == "required", "RM08 device qualification must be required, not when-device-present")
    require(ins.get("variant") == "debug" and ins.get("module") == "app", "RM08 instrumentation lane must be app/debug")
    require((((device.get("workflows") or {}).get("validate") or [{}])[0].get("ref") == "runner:android#instrumentation"),
            "RM08 device target must validate through Devtool's Android instrumentation runner")

    source_target = targets.get("xgo_rm08_aria2_source") or {}
    require(source_target.get("runner") == "android",
            "RM08 source materialization must use the Android runner so Devtool owns SDK/NDK bootstrap")
    source_tasks = source_target.get("tasks") or {}
    require(source_tasks.get("build") == [":transfer-aria2:buildPinnedAria2Runtime"],
            "RM08 source materialization must enter through the canonical Gradle buildPinnedAria2Runtime task")
    source_workflow = ((source_target.get("workflows") or {}).get("validate") or [])
    require(len(source_workflow) == 1 and source_workflow[0].get("ref") == "runner:android#build",
            "RM08 source validate workflow must execute through Devtool's Android build runner")
    require(not (source_target.get("jobs") or {}),
            "RM08 source target must not bypass Devtool Android bootstrap with a generic command job")

    package_target = targets.get("xgo_rm08_aria2_package") or {}
    package_nodes = ((package_target.get("workflows") or {}).get("validate") or [])
    require([node.get("id") for node in package_nodes] == ["source_runtime", "android_package", "package_attestation"],
            "RM08 package target lost source-build/package/attestation ordering")
    require([node.get("ref") for node in package_nodes] == [
        "target:xgo_rm08_aria2_source#validate",
        "target:xgo_rm08_aria2_android_package#validate",
        "target:xgo_rm08_aria2_attest#validate",
    ], "RM08 package target must compose only canonical #validate child targets")

    continuation_target = targets.get("xgo_gate_final_continue") or {}
    continuation_jobs = continuation_target.get("jobs") or {}
    require({
        "xgo-gate12-continue-android-topology",
        "xgo-gate12-continue-android-artifacts",
        "xgo-gate12-continue-android-rm05-precheck",
    }.issubset(set(continuation_jobs)), "Gate-12 continuation jobs must remain target-local")
    continuation = ((continuation_target.get("workflows") or {}).get("validate") or [])
    continuation_ids = [node.get("id") for node in continuation]
    require(continuation_ids == [
        "devtool_execution_policy",
        "android_topology_sanity",
        "android_package_refresh",
        "android_artifact_checkpoint",
        "android_rm05_residual_precheck",
        "android_lint_remaining",
        "android_instrumentation_remaining",
        "desktop_host_final",
        "wave12_final_gate",
    ], "Gate-12 continuation ordering drifted from the retained-checkpoint RM08 boundary")
    refs = {node.get("id"): node.get("ref") for node in continuation}
    require(refs.get("android_package_refresh") == "target:xgo_rm08_aria2_package#validate", "Gate-12 continuation must source-build/package/attest through RM08")
    require(refs.get("android_lint_remaining") == "target:xgo_rm08_aria2_lint#validate", "Gate-12 continuation lint must flow through a canonical #validate proxy")
    require(refs.get("android_instrumentation_remaining") == "target:xgo_rm08_aria2_device#validate", "Gate-12 continuation must require RM08 device execution")
    deps = {node.get("id"): node.get("depends_on") for node in continuation}
    require(deps.get("android_artifact_checkpoint") == ["android_package_refresh"], "Gate-12 continuation artifact checkpoint must follow RM08 package attestation")
    require(deps.get("android_instrumentation_remaining") == ["android_lint_remaining"], "Gate-12 continuation device proof must follow refreshed lint")
    require(deps.get("wave12_final_gate") == ["desktop_host_final"], "Gate-12 continuation final verifier must remain terminal")

    gate_nodes = (((targets.get("xgo_gate_final") or {}).get("workflows") or {}).get("validate") or [])
    gate_refs = {node.get("id"): node.get("ref") for node in gate_nodes}
    require(gate_refs.get("android_host_final") == "target:xgo_rm08_aria2#validate", "fresh Gate-12 must route Android host seal through RM08")

    host_evidence = policy.get("required_host_evidence") or []
    ids = {entry.get("id") for entry in host_evidence}
    require(ids == {"rm08_aria2_package", "rm08_android_instrumentation"}, "Gate-12 policy must require both RM08 host receipts")
    require("validate_host_evidence" in gate_audit, "Gate-12 auditor does not consume RM08 host evidence")
    require("binary_hash_matches_lock" in package_verifier and "source_build" in package_verifier, "RM08 package verifier lost source-built byte attestation")

    print("XGO-RM08 packaged aria2 execution contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
