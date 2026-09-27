# XGO-87 Overlay Contract

## Identity

- Overlay ID: `XGO-87`
- Wave: 12 — Differential audit, adversarial testing, and final removal seal
- Outcome: every distributed `xdmcore` exposes a canonical compatibility/provenance tuple and hosts reject incompatible engines before issuing commands.
- Owning Devtool target: `xgo_seal`
- Specialized target-local job: `abi_provenance_audit`
- Prerequisite: XGO-84/85/86 is committed and `xgo_seal#validate` is green through `performance_budget`.
- Validation: the target workflow remains authoritative; the overlay requires validation with `failure_action = pause`; `validation.tasks` is intentionally absent.

## Capability IDs changed

No donor capability disposition changes. XGO-87 seals the already-migrated runtime/ABI/platform capability surface, principally `XGO-CAP-ABI-001` and the Android/Desktop host compatibility contracts.

## Exact files modified

- `.devtool.toml`
- `app/XDM/eng/build-xgo-native.sh`
- `app/XDM/eng/build-xgo-native.ps1`
- `app/XDM/src/XDM.Core.Tests/DesktopGoHostTests.cs`
- `app/XDM/src/XDM.Platform/DesktopGoEngineHost.cs`
- `app/XDM.Android/app/src/main/assets/xdmcore/manifest.json`
- `app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineProcessAuthority.kt`
- `engine/androidhost/packaging.go`
- `engine/androidhost/packaging_test.go`
- `engine/api/v1/wire.go`
- `engine/bridge/cabi/harness/abi_harness.c`
- `engine/bridge/cabi/main.go`
- `engine/cmd/xgo-android-audit/main.go`
- `tools/xgo/audit_cross_host.py`
- `tools/xgo/audit_desktop.py`
- `tools/xgo/audit_seal.py`
- `tools/xgo/build_android_xdmcore.py`
- `tools/xgo/tests/test_audit_cross_host.py`

## Exact files added

- `docs/xgo/XGO_87_OVERLAY_CONTRACT.md`
- `docs/xgo/XGO_87_IMPLEMENTATION_REPORT.md`
- `app/XDM.Android/app/src/main/assets/xdmcore/xdmcore-provenance.json`
- `engine/api/v1/provenance.go`
- `engine/api/v1/provenance_test.go`
- `engine/cmd/xgo-provenance/main.go`

## Exact files deleted

None.

## Schema / wire / ABI change

- Engine semantic version remains `0.1.0-dev`.
- Wire protocol remains `1.0`; no command/event envelope break is introduced.
- C ABI major remains `1`; the exported symbol inventory remains exactly seven symbols.
- Canonical engine schema metadata changes from placeholder `0` to authoritative SQLite schema `1`.
- Host create configuration gains optional `compatibility` expectations containing engine version, ABI major and schema version. Android and Desktop now always send this tuple, and both reject runtime provenance whose target OS/architecture does not match the host process.
- Runtime metadata gains a `provenance` object. Existing top-level compatibility fields remain stable.

## New invariants

1. `INV-XGO-PROVENANCE-001`: runtime metadata always exposes engine semantic version, wire protocol, ABI major, engine schema version, Go toolchain, Git revision/tree state, target OS/arch and build mode.
2. `INV-XGO-PROVENANCE-002`: `engine/api/v1.SchemaVersion` equals `engine/store/sqlite.CurrentSchemaVersion`; placeholder schema metadata is forbidden.
3. `INV-XGO-PROVENANCE-003`: Android and Desktop send their expected engine/ABI/schema tuple at engine creation and validate returned metadata before any command is issued.
4. `INV-XGO-PROVENANCE-004`: wrong protocol, engine version, ABI major or schema expectation fails creation/decoding with a compatibility/protocol error.
5. `INV-XGO-PROVENANCE-005`: native release build entrypoints inject revision/tree-state/build-mode into `xdmcore` and emit `xdmcore-provenance.json` adjacent to Desktop native output or as the Android packaged provenance asset.
6. `INV-XGO-PROVENANCE-006`: prebuilt Desktop `xdmcore` is rejected unless a provenance document is supplied and validates for the requested target OS/arch and `c-shared` build mode.
7. `INV-XGO-ABI-001`: C ABI symbol inventory remains exactly `xdm_engine_create`, `xdm_engine_command`, `xdm_engine_next_frame`, `xdm_engine_platform_reply`, `xdm_engine_metadata`, `xdm_engine_shutdown`, `xdm_buffer_free`.
8. `INV-XGO-SEAL-DAG-002`: `abi_provenance_audit` runs only after XGO-86 `performance_budget`.
9. `INV-XGO-AUDIT-ROBUSTNESS-001`: Desktop ABI host-discriminator validation matches the Go field/tag semantically and is insensitive to `gofmt` alignment whitespace.

## Packaging contract

`xdmcore-provenance.json` schema 1 contains:

- engine semantic version;
- wire protocol major/minor;
- ABI major;
- authoritative engine schema version;
- Go toolchain version;
- Git revision;
- Git modified flag;
- target OS;
- target architecture;
- build mode.

The Unix and Windows Desktop native builders emit the document beside `libxdmcore.so`/`xdmcore.dll`. Prebuilt native directories must provide the same file. Android build planning and execution bind the same revision/modified/build-mode linker identity and expose the same document at `app/src/main/assets/xdmcore/xdmcore-provenance.json`.

## Devtool workflow change

`xgo_seal#validate` becomes:

`bootstrap_contract → differential_runner → state_machine_fuzz → full_crash_matrix → adversarial_matrix → fuzz_campaign → performance_budget → abi_provenance_audit`

The new job produces `.devtool/reports/xgo/seal/abi-provenance.json`.

## Exit criteria

- authoritative store schema and runtime schema metadata are equal;
- wrong protocol and wrong schema expectations are behaviorally rejected;
- Go API/provenance and Android packaging tests pass;
- cross-host metadata audit passes with create-time compatibility and runtime provenance checks;
- C shared-library ABI harness passes and exact exported symbols remain stable;
- Android build plan binds semantic/wire/schema version, Go version, Git revision, OS/arch and `c-shared` mode;
- generated Android/Linux/Windows provenance documents contain the canonical tuple;
- `xgo_seal` topology recognizes eight ordered nodes;
- XGO-88 single-authority/dead-code work remains outside this overlay.
