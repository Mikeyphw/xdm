#!/usr/bin/env python3
"""Run the Gate-12 RM05 focused instrumentation precheck on one canonical device."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import sys

from gate12_android_device import DeviceResolutionError, resolve_unique_device

ROOT = Path(__file__).resolve().parents[2]
APP_APK = ROOT / "app/XDM.Android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/XDM.Android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
REPORT = ROOT / ".devtool/reports/xgo/gates/final-continue/android-rm05-residual-precheck.txt"
RUNNER = "com.mikeyphw.xdm.android.debug.test/androidx.test.runner.AndroidJUnitRunner"
FILTER = "com.mikeyphw.xdm.android.Phase9AccessibilityAdaptiveLayoutInstrumentedTest#adaptiveSheetRequestsFocusAndRestoresFocusToOpener"


def run(argv: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(argv, text=True, capture_output=True, check=False)


def main() -> int:
    adb = shutil.which("adb")
    if not adb:
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text("RM05 focus precheck: SKIPPED (adb unavailable; policy=when-device-present)\n", encoding="utf-8")
        print(REPORT.read_text(encoding="utf-8"), end="")
        return 0
    try:
        device = resolve_unique_device(adb=adb, required=False)
    except DeviceResolutionError as exc:
        raise SystemExit(f"RM05 focus precheck device resolution failed: {exc}") from exc

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    if device is None:
        REPORT.write_text("RM05 focus precheck: SKIPPED (no authorized device; policy=when-device-present)\n", encoding="utf-8")
        print(REPORT.read_text(encoding="utf-8"), end="")
        return 0
    for path in (APP_APK, TEST_APK):
        if not path.is_file() or path.stat().st_size <= 0:
            raise SystemExit(f"RM05 focus precheck missing APK: {path}")

    lines = [
        f"=== canonical device: {device.canonical_serial} ===",
        f"aliases: {', '.join(device.aliases)}",
        f"identity: {device.identity_key}",
    ]
    for apk in (APP_APK, TEST_APK):
        cp = run([adb, "-s", device.canonical_serial, "install", "-r", "-t", str(apk)])
        lines.extend([cp.stdout.rstrip(), cp.stderr.rstrip()])
        if cp.returncode != 0:
            REPORT.write_text("\n".join(item for item in lines if item) + "\n", encoding="utf-8")
            print(REPORT.read_text(encoding="utf-8"), end="")
            return cp.returncode or 1

    cp = run([
        adb, "-s", device.canonical_serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", FILTER, RUNNER,
    ])
    output = (cp.stdout or "") + (cp.stderr or "")
    lines.append(output.rstrip())
    failed_marker = any(token in output for token in (
        "Process crashed", "FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_STATUS_CODE: -2",
    ))
    passed = cp.returncode == 0 and "INSTRUMENTATION_CODE: -1" in output and not failed_marker
    lines.append("Gate-12 RM05 focus-restoration precheck: PASS" if passed else "Gate-12 RM05 focus-restoration precheck: FAIL")
    REPORT.write_text("\n".join(item for item in lines if item) + "\n", encoding="utf-8")
    print(REPORT.read_text(encoding="utf-8"), end="")
    return 0 if passed else (cp.returncode or 1)


if __name__ == "__main__":
    raise SystemExit(main())
