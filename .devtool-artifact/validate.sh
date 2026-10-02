#!/usr/bin/env sh
set -eu
python3 tools/xgo/validate_gate12_registry_bypass.py
python3 tools/xgo/validate_gate12_source_runtime_lineage_v19.py
python3 tools/xgo/validate_rm08_contract_reconciliation_v20.py
python3 tools/xgo/validate_rm08_ndk_api_library_precedence_v22.py
python3 tools/xgo/validate_rm08_direct_contract_boundary_v23.py
