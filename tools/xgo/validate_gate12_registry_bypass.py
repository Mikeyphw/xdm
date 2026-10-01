#!/usr/bin/env python3
"""Static contract validator for the temporary Gate-12 registry bypass lane."""
from __future__ import annotations

from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".devtool.toml"
RECEIPT = ROOT / "tools/xgo/record_gate12_registry_bypass.py"
DOC = ROOT / "docs/xgo/XGO_GATE12_DEVTOOL_ARTIFACT_REGISTRY_BYPASS.md"

CANONICAL = [
    ("devtool_execution_policy", "target:xgo_devtool_policy#validate", []),
    ("android_topology_sanity", "job:xgo-gate12-continue-android-topology", ["devtool_execution_policy"]),
    ("android_package_refresh", "target:xgo_rm08_aria2_package#validate", ["android_topology_sanity"]),
    ("android_artifact_checkpoint", "job:xgo-gate12-continue-android-artifacts", ["android_package_refresh"]),
    ("android_rm05_residual_precheck", "job:xgo-gate12-continue-android-rm05-precheck", ["android_artifact_checkpoint"]),
    ("android_lint_remaining", "target:xgo_rm08_aria2_lint#validate", ["android_rm05_residual_precheck"]),
    ("android_instrumentation_remaining", "target:xgo_rm08_aria2_device#validate", ["android_lint_remaining"]),
    ("desktop_host_final", "target:xdm_modern#validate", ["android_instrumentation_remaining"]),
    ("wave12_final_gate", "target:xgo_final_wave12#validate", ["desktop_host_final"]),
]

BYPASS = [
    ("devtool_execution_policy", "target:xgo_devtool_policy#validate", []),
    ("android_topology_sanity", "job:xgo-gate12-continue-android-topology", ["devtool_execution_policy"]),
    ("android_pair_refresh", "target:xgo_rm05_focus_test_refresh#validate", ["android_topology_sanity"]),
    ("android_pair_signers", "job:xgo-gate12-continue-android-apk-signers", ["android_pair_refresh"]),
    ("devtool_registry_bypass_receipt", "job:xgo-gate12-continue-devtool-registry-bypass-receipt", ["android_pair_signers"]),
    ("package_attestation", "target:xgo_rm08_aria2_attest#validate", ["devtool_registry_bypass_receipt"]),
    ("android_artifact_checkpoint", "job:xgo-gate12-continue-android-artifacts", ["package_attestation"]),
    ("android_rm05_residual_precheck", "job:xgo-gate12-continue-android-rm05-precheck", ["android_artifact_checkpoint"]),
    ("android_lint_remaining", "target:xgo_rm08_aria2_lint#validate", ["android_rm05_residual_precheck"]),
    ("android_instrumentation_remaining", "job:xgo-gate12-continue-android-instrumentation-reuse", ["android_lint_remaining"]),
    ("desktop_host_final", "target:xdm_modern#validate", ["android_instrumentation_remaining"]),
    ("wave12_final_gate", "target:xgo_final_wave12#validate", ["desktop_host_final"]),
]


def fail(message: str) -> None:
    raise SystemExit(f"XGO Gate-12 registry bypass contract: FAIL: {message}")


def normalize(nodes: object) -> list[tuple[str, str, list[str]]]:
    if not isinstance(nodes, list):
        fail("workflow is not a node list")
    out: list[tuple[str, str, list[str]]] = []
    for node in nodes:
        if not isinstance(node, dict):
            fail("workflow node is not a table")
        out.append((str(node.get("id")), str(node.get("ref")), list(node.get("depends_on") or [])))
    return out


def main() -> None:
    data = tomllib.loads(CONFIG.read_text(encoding="utf-8"))
    targets = data.get("targets")
    if not isinstance(targets, dict):
        fail("missing targets")
    cont = targets.get("xgo_gate_final_continue")
    if not isinstance(cont, dict):
        fail("missing xgo_gate_final_continue")
    workflows = cont.get("workflows")
    if not isinstance(workflows, dict):
        fail("missing xgo_gate_final_continue workflows")

    if normalize(workflows.get("validate")) != CANONICAL:
        fail("canonical validate workflow changed; workaround must remain opt-in only")
    if normalize(workflows.get("registry_bypass")) != BYPASS:
        fail("registry_bypass workflow does not match the narrow approved graph")

    refs = {ref for _, ref, _ in BYPASS}
    forbidden = {
        "target:xgo_rm08_aria2_package#validate",
        "target:xgo_rm08_aria2_android_package#validate",
        "target:xdm_android#validate",
    }
    leaked = sorted(refs & forbidden)
    if leaked:
        fail("bypass lane still enters broken package/host boundary: " + ", ".join(leaked))
    if "target:xgo_rm08_aria2_attest#validate" not in refs:
        fail("bypass lane must retain exact RM08 packaged-byte attestation")
    if "target:xgo_rm08_aria2_device#validate" in refs:
        fail("bypass lane must not re-enter rebuilding Android instrumentation target")
    if "job:xgo-gate12-continue-android-instrumentation-reuse" not in refs:
        fail("bypass lane must consume the exact qualified APK pair through reuse job")

    refresh = targets.get("xgo_rm05_focus_test_refresh")
    if not isinstance(refresh, dict):
        fail("missing RM05 focused androidTest refresh target")
    refresh_workflows = refresh.get("workflows") or {}
    if normalize(refresh_workflows.get("validate")) != [
        ("android_test_apk_refresh", "runner:android#test", [])
    ]:
        fail("RM05/Gate-12 APK pair refresh workflow is malformed")
    refresh_tasks = refresh.get("tasks") or {}
    if refresh_tasks.get("test") != [":app:assembleDebug", ":app:assembleDebugAndroidTest"]:
        fail("Gate-12 refresh must build target and androidTest APKs together in one Devtool Gradle invocation")

    jobs = cont.get("jobs")
    if not isinstance(jobs, dict):
        fail("missing continuation jobs")
    signer_job = jobs.get("xgo-gate12-continue-android-apk-signers")
    if not isinstance(signer_job, dict):
        fail("missing APK signer compatibility job")
    if signer_job.get("command") != [
        "python3", "tools/xgo/verify_gate12_apk_signers.py",
        "--output", ".devtool/reports/xgo/gates/final-continue/android-apk-signers.json",
    ]:
        fail("APK signer compatibility job command is malformed")
    receipt_job = jobs.get("xgo-gate12-continue-devtool-registry-bypass-receipt")
    if not isinstance(receipt_job, dict):
        fail("missing explicit bypass receipt job")
    command = receipt_job.get("command")
    expected = [
        "python3", "tools/xgo/record_gate12_registry_bypass.py",
        "--output", ".devtool/reports/xgo/gates/final-continue/devtool-artifact-registry-bypass.json",
    ]
    if command != expected:
        fail("bypass receipt job command is not canonical")

    receipt_text = RECEIPT.read_text(encoding="utf-8")
    for token in (
        "verify-aria2-runtime.py",
        "verify-ffmpeg-runtime.py",
        "libxdmcore.so",
        "libaria2c.so",
        "libxdm_ffmpeg.so",
        "libxdm_ffprobe.so",
        "temporary_bypass",
    ):
        if token not in receipt_text:
            fail(f"receipt script missing required evidence token: {token}")
    lowered = receipt_text.lower()
    if "gradlew" in lowered or "subprocess.run([\"gradle\"" in lowered:
        fail("receipt script must not rebuild through Gradle")
    if not DOC.is_file():
        fail("missing workaround documentation")

    contract = targets.get("xgo_gate12_registry_bypass_contract")
    if not isinstance(contract, dict):
        fail("missing static bypass contract target")
    cflows = contract.get("workflows") or {}
    if normalize(cflows.get("validate")) != [("contract", "job:contract", [])]:
        fail("static bypass contract target is malformed")

    print("XGO Gate-12 registry bypass contract: PASS")


if __name__ == "__main__":
    main()
