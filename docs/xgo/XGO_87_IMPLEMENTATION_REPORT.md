# XGO-87 ABI, Provenance and Release Metadata Seal — Implementation Report

XGO-87 makes build identity part of the executable engine contract rather than an informal packaging detail.

## Implemented

The canonical `engine/api/v1` metadata now carries a build provenance object with Go toolchain, Git revision/tree state, target OS/architecture and build mode. A canonical provenance document type and `xgo-provenance` generator/validator are shared by packaging validation. Engine schema metadata now reports SQLite schema version `1` instead of the previous placeholder `0`.

Android and Desktop both include their engine semantic-version/ABI/schema expectation in `xdm_engine_create` configuration. The Go decoder rejects mismatches before allocating/starting an engine. Both hosts still read `xdm_engine_metadata` and now validate the returned provenance, including the exact native target architecture, before marking startup complete or starting their frame/command flow.

The Desktop legacy-authority source audit now matches the Go `HostPlatform` field and `host_platform` JSON tag with a whitespace-tolerant semantic regex, so normal `gofmt` alignment cannot create a false failure.

Desktop native builders inject revision/tree-state/build-mode through Go linker variables and emit `xdmcore-provenance.json`. Prebuilt native inputs must carry a matching provenance document. Android's canonical build-plan model and executable builder both carry the same linker identity (`BuildRevision`, `BuildModified`, and `BuildMode`) and package the same provenance schema through its `assets/xdmcore` contract.

## Validation completed during overlay construction

- `go test ./engine/...` — pass.
- `python3 -m unittest discover -s tools/xgo/tests -p 'test_*.py'` — 17/17 pass.
- `python3 tools/xgo/audit_topology.py target-ready ... --target xgo_seal` — pass, 8 validation nodes.
- `python3 tools/xgo/audit_seal.py abi-provenance ...` — pass.
- real `tools/xgo/run_abi_harness.py` C shared-library build/harness — pass with all seven required symbols.
- real Unix `build-xgo-native.sh linux-x64` build — emitted a non-empty native library and canonical provenance file; the resulting library passed the C ABI harness.
- `go run ./engine/cmd/xgo-android-audit --mode android_engine_build` — pass with packaged provenance asset.

The construction environment does not provide the repository's .NET or Android Gradle toolchains. Those host compilers remain downstream validation surfaces; the authoritative XGO-87 `xgo_seal` job performs Go/C ABI behavior plus cross-host source-contract validation on the target host.

## Deliberately not included

XGO-88 dead-code/single-authority scanning and XGO-89 capability-ledger closure are not included. GATE-12 remains a later final-seal overlay.
