#!/usr/bin/env sh
set -eu
python3 tools/xgo/validate_gate12_registry_bypass.py
python3 app/XDM.Android/tools/validate-bug-hunt-phase9-accessibility-adaptive-layout.py
python3 tools/xgo/validate_rm05_focus_input_mode.py
python3 tools/xgo/validate_gate12_instrumentation_reuse.py
python3 tools/xgo/validate_rm08_packaged_runtime_instrumentation_v16.py
python3 tools/xgo/validate_rm08_android_process_environment_v17.py
