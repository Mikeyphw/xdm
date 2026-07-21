#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
required_files = [
    'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxCommandRunner.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxResultService.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxPaths.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxRunStore.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt',
    'docs/architecture/PHASE-7-TERMUX-COMMAND-RUNNER.md',
]
missing = [path for path in required_files if not (root / path).is_file()]
if missing:
    raise SystemExit(f'Missing Termux bridge files: {missing}')

manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
for required in [
    'com.termux.permission.RUN_COMMAND',
    '<package android:name="com.termux"',
    '<package android:name="com.termux.api"',
    '.termux.TermuxResultService',
]:
    if required not in manifest:
        raise SystemExit(f'Manifest missing {required}')

runner = (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxCommandRunner.kt').read_text()
for required in [
    'com.termux.app.RunCommandService',
    'com.termux.RUN_COMMAND',
    'RUN_COMMAND_PENDING_INTENT',
    'XdmTermuxCommand',
]:
    if required not in runner:
        raise SystemExit(f'Runner missing {required}')

models = (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt').read_text()
for required in ['sealed class XdmTermuxCommand', 'TermuxRootMode', 'sealed class XdmRootAction', 'Off("Off"']:
    if required not in models:
        raise SystemExit(f'Models missing {required}')

screens = (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt').read_text()
for required in ['Termux bridge', 'Termux backend', 'Optional root mode', 'Copy Termux diagnostics']:
    if required not in screens:
        raise SystemExit(f'UI missing {required}')

if 'raw shell' in screens.lower() and 'never exposes a raw root shell endpoint' not in screens:
    raise SystemExit('UI appears to expose raw shell wording unexpectedly')

print('Termux bridge validation passed')
