#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[2]
launcher = (root / "app/XDM.Android/transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessLauncher.kt").read_text()
manager = (root / "app/XDM.Android/transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt").read_text()
test = (root / "app/XDM.Android/transfer-aria2/src/test/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessLauncherTest.kt").read_text()

required_launcher = [
    '.directory(plan.workingDirectory)',
    'remove("OLDPWD")',
    'putAll(plan.privateRuntimeEnvironment())',
    '"HOME" to root.absolutePath',
    '"PWD" to root.absolutePath',
    '"TMPDIR" to temporary.absolutePath',
    '"XDG_CONFIG_HOME" to config.absolutePath',
    '"XDG_CACHE_HOME" to cache.absolutePath',
    '"XDG_DATA_HOME" to data.absolutePath',
    '"XDG_STATE_HOME" to state.absolutePath',
    '"XDG_RUNTIME_DIR" to runtime.absolutePath',
]
for token in required_launcher:
    if token not in launcher:
        raise SystemExit(f"missing launcher isolation contract: {token}")

exit_pos = manager.find('exitCode != null -> Aria2StartupFailureKind.ProcessExited')
refused_pos = manager.find('error is ConnectException -> Aria2StartupFailureKind.ConnectionRefused')
if exit_pos < 0 or refused_pos < 0 or exit_pos > refused_pos:
    raise SystemExit("native process exit must outrank follow-on RPC connection refusal")

required_test = [
    'privateRuntimeEnvironmentNeverInheritsShellTestPaths',
    'assertEquals(canonicalRoot, environment.getValue("HOME"))',
    'assertFalse(environment.values.any { it.startsWith("/data/local/tests") })',
]
for token in required_test:
    if token not in test:
        raise SystemExit(f"missing process-environment regression assertion: {token}")

if 'environment().clear()' in launcher:
    raise SystemExit("launcher must not clear Android runtime environment wholesale")

print("XGO RM08 Android private process environment v17: PASS")
