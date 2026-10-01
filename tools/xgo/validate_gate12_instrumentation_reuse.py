#!/usr/bin/env python3
"""Static regression contract for Gate-12 exact APK instrumentation reuse."""
from __future__ import annotations

from pathlib import Path
import importlib.util
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".devtool.toml"
DEVICE = ROOT / "tools/xgo/gate12_android_device.py"
PRECHECK = ROOT / "tools/xgo/run_gate12_rm05_precheck.py"
REUSE = ROOT / "tools/xgo/run_gate12_instrumentation_reuse.py"
DOC = ROOT / "docs/xgo/XGO_GATE12_APK_PAIR_SIGNING_V15_REPORT.md"
SIGNER = ROOT / "tools/xgo/verify_gate12_apk_signers.py"


def fail(message: str) -> None:
    raise SystemExit(f"XGO Gate-12 instrumentation reuse contract: FAIL: {message}")


def main() -> None:
    data = tomllib.loads(CONFIG.read_text(encoding="utf-8"))
    targets = data.get("targets") or {}
    cont = targets.get("xgo_gate_final_continue") or {}
    workflows = cont.get("workflows") or {}
    bypass = workflows.get("registry_bypass")
    if not isinstance(bypass, list):
        fail("registry_bypass workflow missing")
    refs = {str(node.get("id")): str(node.get("ref")) for node in bypass if isinstance(node, dict)}
    if refs.get("android_instrumentation_remaining") != "job:xgo-gate12-continue-android-instrumentation-reuse":
        fail("temporary lane still delegates instrumentation to the rebuilding Android runner")
    if "target:xgo_rm08_aria2_device#validate" in refs.values():
        fail("temporary lane still invokes xgo_rm08_aria2_device and its outer Gradle build")

    canonical = workflows.get("validate")
    if not isinstance(canonical, list):
        fail("canonical validate workflow missing")
    canonical_refs = {str(node.get("id")): str(node.get("ref")) for node in canonical if isinstance(node, dict)}
    if canonical_refs.get("android_instrumentation_remaining") != "target:xgo_rm08_aria2_device#validate":
        fail("canonical validate instrumentation boundary changed")

    jobs = cont.get("jobs") or {}
    precheck = jobs.get("xgo-gate12-continue-android-rm05-precheck") or {}
    if precheck.get("command") != ["python3", "tools/xgo/run_gate12_rm05_precheck.py"]:
        fail("RM05 precheck is not using the canonical device-alias resolver")
    signer_job = jobs.get("xgo-gate12-continue-android-apk-signers") or {}
    if signer_job.get("command") != [
        "python3", "tools/xgo/verify_gate12_apk_signers.py",
        "--output", ".devtool/reports/xgo/gates/final-continue/android-apk-signers.json",
    ]:
        fail("APK signer compatibility job command is malformed")
    reuse_job = jobs.get("xgo-gate12-continue-android-instrumentation-reuse") or {}
    if reuse_job.get("command") != ["python3", "tools/xgo/run_gate12_instrumentation_reuse.py"]:
        fail("instrumentation reuse job command is malformed")

    text = REUSE.read_text(encoding="utf-8")
    for token in (
        "--skip-build", "devtool.android_instrumentation", "rm08-aria2-package.json",
        "android-artifacts.sha256", "android-apk-signers.json", "signer receipt",
        "stage_exact_pair", "verify_result_pair",
    ):
        if token not in text:
            fail(f"reuse runner missing required token: {token}")
    for forbidden in ("gradlew", "assembleDebug", "assembleDebugAndroidTest", "os.link"):
        if forbidden in text:
            fail(f"reuse runner contains forbidden rebuild/non-portable primitive: {forbidden}")
    if "shutil.copy2(source, dest)" not in text:
        fail("reuse runner does not use portable exact-pair staging copy")

    precheck_text = PRECHECK.read_text(encoding="utf-8")
    if "resolve_unique_device" not in precheck_text:
        fail("RM05 precheck does not use shared device alias resolution")
    if "for SERIAL in" in precheck_text:
        fail("RM05 precheck still iterates raw adb serial aliases")

    spec = importlib.util.spec_from_file_location("gate12_android_device", DEVICE)
    if spec is None or spec.loader is None:
        fail("cannot load device helper")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    records = [
        module.DeviceRecord("emulator-5554", "device:same", {"ro.serialno": "same"}, "local-emulator-adb:5555"),
        module.DeviceRecord("localhost:5555", "device:same", {"ro.serialno": "same"}, "local-emulator-adb:5555"),
    ]
    collapsed = module.collapse_device_aliases(records)
    if len(collapsed) != 1 or collapsed[0].canonical_serial != "emulator-5554":
        fail("local emulator alias collapse does not select one canonical emulator serial")
    if set(collapsed[0].aliases) != {"emulator-5554", "localhost:5555"}:
        fail("device alias evidence lost during collapse")


    signer_text = SIGNER.read_text(encoding="utf-8")
    for token in ("apksigner", "certificate SHA-256 digest", "app-debug.apk", "app-debug-androidTest.apk"):
        if token not in signer_text:
            fail(f"signer verifier missing required token: {token}")
    if "app_signers != test_signers" not in signer_text:
        fail("signer verifier does not fail closed on target/test certificate mismatch")

    refresh = targets.get("xgo_rm05_focus_test_refresh") or {}
    tasks = refresh.get("tasks") or {}
    if tasks.get("test") != [":app:assembleDebug", ":app:assembleDebugAndroidTest"]:
        fail("pair refresh is not a single target+androidTest producer invocation")

    if not DOC.is_file():
        fail("missing v15 APK pair/signing report")
    print("XGO Gate-12 instrumentation reuse contract: PASS")


if __name__ == "__main__":
    main()
