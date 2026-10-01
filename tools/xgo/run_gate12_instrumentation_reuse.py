#!/usr/bin/env python3
"""Run Gate-12 instrumentation with the already-qualified APK pair and no Gradle rebuild.

The current Devtool instrumentation compatibility module still discovers APKs from
filesystem candidates when --skip-build is used. To prevent stale/copied candidates
from winning by mtime, this temporary XGO lane stages only the two hash-qualified
APKs into an isolated project output tree, then delegates execution/result parsing
to Devtool's instrumentation engine.
"""
from __future__ import annotations

from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys

from gate12_android_device import DeviceResolutionError, resolve_unique_device

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "app/XDM.Android"
APP_APK = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ANDROID / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
ATTEST = ROOT / ".devtool/reports/xgo/gates/final-continue/rm08-aria2-package.json"
CHECKPOINT = ROOT / ".devtool/reports/xgo/gates/final-continue/android-artifacts.sha256"
REPORT = ANDROID / ".devtool/reports/android-instrumentation.json"
REUSE_RECEIPT = ROOT / ".devtool/reports/xgo/gates/final-continue/android-instrumentation-reuse.json"
STAGE_ROOT = ANDROID / "app/build/devtool-gate12-instrumentation-reuse/project"
RUNNER = "com.mikeyphw.xdm.android.debug.test/androidx.test.runner.AndroidJUnitRunner"
TARGET_PACKAGE = "com.mikeyphw.xdm.android.debug"
TARGET = "xgo_rm08_aria2_device"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def load_qualified_pair() -> dict[str, str]:
    require(ATTEST.is_file(), f"missing RM08 package attestation: {ATTEST}")
    data = json.loads(ATTEST.read_text(encoding="utf-8"))
    require(data.get("status") == "pass", "RM08 package attestation is not passing")
    expected = {
        "app": str(data.get("app_apk_sha256") or ""),
        "test": str(data.get("android_test_apk_sha256") or ""),
    }
    require(all(expected.values()), "RM08 package attestation lacks APK hashes")
    require(APP_APK.is_file() and TEST_APK.is_file(), "qualified APK pair is missing")
    actual = {"app": sha256(APP_APK), "test": sha256(TEST_APK)}
    require(actual == expected, f"qualified APK hash drift: expected={expected} actual={actual}")

    require(CHECKPOINT.is_file(), f"missing Gate-12 artifact checkpoint: {CHECKPOINT}")
    checkpoint = CHECKPOINT.read_text(encoding="utf-8")
    for digest, path in ((actual["app"], APP_APK), (actual["test"], TEST_APK)):
        require(digest in checkpoint and str(path.relative_to(ROOT)) in checkpoint, f"checkpoint does not bind {path}")
    return actual


def stage_exact_pair(hashes: dict[str, str]) -> tuple[Path, Path]:
    if STAGE_ROOT.exists():
        shutil.rmtree(STAGE_ROOT)
    stage_app = STAGE_ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    stage_test = STAGE_ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    stage_app.parent.mkdir(parents=True, exist_ok=True)
    stage_test.parent.mkdir(parents=True, exist_ok=True)
    for source, dest in ((APP_APK, stage_app), (TEST_APK, stage_test)):
        try:
            os.link(source, dest)
        except OSError:
            shutil.copy2(source, dest)
    require(sha256(stage_app) == hashes["app"], "staged app APK hash mismatch")
    require(sha256(stage_test) == hashes["test"], "staged test APK hash mismatch")
    return stage_app, stage_test


def parse_result(lines: list[str]) -> dict[str, object] | None:
    prefix = "DEVTOOL_INSTRUMENTATION_RESULT "
    for line in reversed(lines):
        if line.startswith(prefix):
            try:
                value = json.loads(line[len(prefix):])
            except json.JSONDecodeError:
                return None
            return value if isinstance(value, dict) else None
    return None


def verify_result_pair(result: dict[str, object] | None, hashes: dict[str, str]) -> dict[str, str]:
    require(result is not None, "Devtool instrumentation did not emit DEVTOOL_INSTRUMENTATION_RESULT")
    paths = {
        "app": Path(str(result.get("app_apk") or "")),
        "test": Path(str(result.get("test_apk") or "")),
    }
    for kind, path in paths.items():
        require(path.is_file(), f"Devtool result {kind}_apk does not exist: {path}")
        require(sha256(path) == hashes[kind], f"Devtool selected the wrong {kind} APK: {path}")
    return {kind: str(path) for kind, path in paths.items()}


def main() -> int:
    receipt: dict[str, object] = {
        "schema_version": 1,
        "claim": "xgo-gate12-exact-apk-instrumentation-reuse",
        "status": "failed",
        "gradle_rebuild": False,
        "devtool_skip_build": True,
    }
    REUSE_RECEIPT.parent.mkdir(parents=True, exist_ok=True)
    try:
        hashes = load_qualified_pair()
        stage_app, stage_test = stage_exact_pair(hashes)
        adb = shutil.which("adb")
        require(bool(adb), "adb is required for Gate-12 required instrumentation")
        try:
            device = resolve_unique_device(adb=str(adb), required=True)
        except DeviceResolutionError as exc:
            raise RuntimeError(str(exc)) from exc
        assert device is not None

        REPORT.parent.mkdir(parents=True, exist_ok=True)
        command = [
            sys.executable, "-m", "devtool.android_instrumentation", str(STAGE_ROOT),
            "--variant", "debug",
            "--device-serial", device.canonical_serial,
            "--install-command", "sudo pm install -r -t",
            "--am-command", "sudo am instrument",
            "--canonical",
            "--target", TARGET,
            "--skip-build",
            "--policy", "required",
            "--device-policy", "exactly-one",
            "--aapt2-provider", "termux",
            "--runner", RUNNER,
            "--target-package", TARGET_PACKAGE,
            "--report", str(REPORT),
        ]
        print("Gate-12 exact APK reuse:")
        print(f"  app  {APP_APK.relative_to(ROOT)} {hashes['app']}")
        print(f"  test {TEST_APK.relative_to(ROOT)} {hashes['test']}")
        print(f"  device {device.canonical_serial} aliases={list(device.aliases)}")
        print("  Devtool instrumentation: --skip-build (isolated exact-pair stage)")

        proc = subprocess.Popen(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        lines: list[str] = []
        assert proc.stdout is not None
        for raw in proc.stdout:
            line = raw.rstrip("\n")
            lines.append(line)
            print(line, flush=True)
        returncode = proc.wait()
        result = parse_result(lines)
        selected = verify_result_pair(result, hashes)

        receipt.update({
            "status": "pass" if returncode == 0 and bool(result.get("ok")) else "failed",
            "returncode": returncode,
            "qualified_artifacts": {
                "app_apk": {"path": str(APP_APK.relative_to(ROOT)), "sha256": hashes["app"]},
                "test_apk": {"path": str(TEST_APK.relative_to(ROOT)), "sha256": hashes["test"]},
            },
            "isolated_stage": {
                "app_apk": str(stage_app.relative_to(ROOT)),
                "test_apk": str(stage_test.relative_to(ROOT)),
            },
            "devtool_selected_pair": selected,
            "device": {
                "canonical_serial": device.canonical_serial,
                "aliases": list(device.aliases),
                "identity_key": device.identity_key,
            },
            "devtool_result_ids": {
                key: result.get(key) for key in (
                    "intent_id", "plan_id", "receipt_id", "artifact_pair_id",
                    "device_lease_id", "execution_snapshot_id",
                ) if result.get(key) is not None
            },
            "test_counts": {key: result.get(key) for key in ("discovered", "selected", "executed", "passed", "failed", "skipped", "total")},
            "failed_ids": result.get("failed_ids") or [],
        })
        REUSE_RECEIPT.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return returncode
    except Exception as exc:
        receipt["error"] = str(exc)
        REUSE_RECEIPT.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"Gate-12 instrumentation reuse failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
