#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST = ROOT / "app/XDM.Android/app/src/androidTest/kotlin/com/mikeyphw/xdm/android/Phase9AccessibilityAdaptiveLayoutInstrumentedTest.kt"
CONFIG = ROOT / ".devtool.toml"

def require(data: str, needle: str, where: str, errors: list[str]) -> None:
    if needle not in data:
        errors.append(f"{where}: missing {needle!r}")

errors: list[str] = []
test = TEST.read_text(encoding="utf-8")
config = CONFIG.read_text(encoding="utf-8")

for needle in (
    "LocalInputModeManager",
    "InputMode.Keyboard",
    "inputModeManager.requestInputMode(InputMode.Keyboard)",
    "opener.requestFocus()",
    "opener.assertIsFocused()",
    "waitUntil(timeoutMillis = 5_000L)",
):
    require(test, needle, TEST.relative_to(ROOT).as_posix(), errors)

mode_pos = test.find("inputModeManager.requestInputMode(InputMode.Keyboard)")
focus_pos = test.find("opener.requestFocus()")
if mode_pos < 0 or focus_pos < 0 or mode_pos > focus_pos:
    errors.append("RM05 focus test must enter keyboard input mode before requesting opener focus")

refresh_target = "[targets.xgo_rm05_focus_test_refresh]"
require(config, refresh_target, ".devtool.toml", errors)
require(config, 'test = [":app:assembleDebugAndroidTest"]', ".devtool.toml", errors)
require(config, 'ref = "runner:android#test"', ".devtool.toml", errors)

start = config.find("registry_bypass = [")
end = config.find("\n]", start)
if start < 0 or end < 0:
    errors.append(".devtool.toml: registry_bypass workflow not found")
else:
    bypass = config[start:end]
    refresh = bypass.find('id = "android_test_refresh"')
    receipt = bypass.find('id = "devtool_registry_bypass_receipt"')
    precheck = bypass.find('id = "android_rm05_residual_precheck"')
    if min(refresh, receipt, precheck) < 0:
        errors.append(".devtool.toml: registry_bypass is missing refresh/receipt/precheck nodes")
    elif not (refresh < receipt < precheck):
        errors.append(".devtool.toml: registry_bypass must refresh androidTest before receipt and RM05 precheck")
    if 'target:xgo_rm05_focus_test_refresh#validate' not in bypass:
        errors.append(".devtool.toml: registry_bypass does not use the Devtool-owned RM05 test refresh target")

canonical_start = config.find("validate = [", config.find("[targets.xgo_gate_final_continue.workflows]"))
canonical_end = config.find("\n]", canonical_start)
if canonical_start >= 0 and canonical_end >= 0:
    canonical = config[canonical_start:canonical_end]
    if "xgo_rm05_focus_test_refresh" in canonical:
        errors.append(".devtool.toml: temporary focus refresh must not alter canonical validate workflow")

if errors:
    raise SystemExit("XGO RM05 focus input-mode validation failed:\n" + "\n".join(errors))

print("XGO RM05 keyboard-focus input-mode contract: PASS")
