# XGO-88 overlay contract — dead-code and single-authority architecture seal

Overlay ID: `XGO-88`
Wave: `12`
Prerequisites: `XGO-81` through `XGO-87` complete; XGO-87 ABI/provenance seal green.
Target: `xgo_seal`
Validation required: yes
Failure action: `pause`
Validation task override: absent; `xgo_seal#validate` remains authoritative.

## Outcome

After XGO-88, normal Android/Desktop host code cannot reintroduce a second authoritative download/media/scheduler engine without failing `xgo_seal#validate`. Historical Desktop donor implementations remain in the repository only as source evidence; the known transfer/recovery/retry/backend/queue/media execution implementations are explicitly excluded from normal production compilation. Go remains the only canonical HLS/DASH execution, retry, backend-selection, queue-eligibility and canonical-state authority.

## Capability IDs changed

No capability semantics change. XGO-88 seals the already-implemented authority dispositions represented by the capability ledger, especially the Android/Desktop, HLS/DASH, retry, backend, scheduler, store and runtime capability families.

## Donor files/tests studied

- `docs/xgo/XGO_74_IMPLEMENTATION_REPORT.md`
- `docs/xgo/XGO_75_IMPLEMENTATION_REPORT.md`
- `docs/xgo/XGO_79_IMPLEMENTATION_REPORT.md`
- `docs/xgo/XGO_80_IMPLEMENTATION_REPORT.md`
- `app/XDM/src/XDM.DownloadEngine/DownloadManager.cs`
- `app/XDM/src/XDM.DownloadEngine/DownloadRecoveryCoordinator.cs`
- `app/XDM/src/XDM.DownloadEngine/DownloadRetryPolicy.cs`
- `app/XDM/src/XDM.DownloadEngine/SegmentedDownloadExecutor.cs`
- `app/XDM/src/XDM.DownloadEngine/Queues/QueueSchedulerRuntime.cs`
- `app/XDM/src/XDM.DownloadEngine/Queues/QueueDependencyEvaluator.cs`
- `app/XDM/src/XDM.DownloadEngine/Backends/DownloadBackendAdvisor.cs`
- `app/XDM/src/XDM.DownloadEngine/Aria2/Aria2Service.cs`
- `app/XDM/src/XDM.Media/MediaDownloadService.cs`
- `app/XDM/src/XDM.Media/HlsDownloader.cs`
- `app/XDM/src/XDM.Media/DashDownloader.cs`
- corresponding legacy donor tests under `XDM.DownloadEngine.Tests` and `XDM.BrowserMedia.Tests`
- Android authority surfaces validated by `android_legacy_authority_scan`
- Desktop authority surfaces validated by `legacy-authority` and `desktop-e2e`

## Exact files added

- `engine/testdata/seal/single-authority-policy.json`
- `docs/xgo/XGO_88_OVERLAY_CONTRACT.md`
- `docs/xgo/XGO_88_IMPLEMENTATION_REPORT.md`

## Exact files modified

- `.devtool.toml`
- `tools/xgo/audit_seal.py`
- `app/XDM/src/XDM.DownloadEngine/XDM.DownloadEngine.csproj`
- `app/XDM/src/XDM.DownloadEngine.Tests/XDM.DownloadEngine.Tests.csproj`
- `app/XDM/src/XDM.Media/XDM.Media.csproj`
- `app/XDM/src/XDM.BrowserMedia.Tests/XDM.BrowserMedia.Tests.csproj`

## Exact files deleted

None. Historical donor source is retained as audit evidence but compile-quarantined.

## Schema migration number

None.

## Wire protocol change

None.

## New invariants

1. `INV-XGO-SINGLE-AUTHORITY-001`: normal Desktop production compilation excludes the retired C# DownloadManager/recovery/retry/backend/queue/stateful-aria2 authority sources.
2. `INV-XGO-SINGLE-AUTHORITY-002`: normal Desktop media compilation excludes the retired C# HLS/DASH/media download execution sources.
3. `INV-XGO-SINGLE-AUTHORITY-003`: active Desktop production code contains no legacy retry, backend-selection, queue-eligibility, segmented-transfer or HLS/DASH execution authority reference.
4. `INV-XGO-SINGLE-AUTHORITY-004`: Android application/scheduler control surfaces remain Go-command/wake driven and contain no legacy planner/retry-ledger/start authority.
5. `INV-XGO-SINGLE-AUTHORITY-005`: Android/Desktop host surfaces do not directly write the canonical Go SQLite download state.
6. `INV-XGO-SINGLE-AUTHORITY-006`: canonical HLS and DASH execution parsers remain in Go.
7. `INV-XGO-SINGLE-AUTHORITY-007`: canonical retry decision, backend selection and queue eligibility remain in Go.
8. `INV-XGO-SINGLE-AUTHORITY-008`: the architecture scanner must reject deliberate prohibited-reference fixtures; a scanner that silently accepts them fails the seal.

## Preserved invariants

- XGO-74 Android one-time Room import remains the only legacy persistence ingress.
- XGO-75 Android platform execution broker remains a narrow Go-authorized side-effect adapter.
- XGO-80 Desktop compatibility interfaces and physical transfer broker remain usable without reviving policy authority.
- XGO-81 differential evidence remains reproducible from retained donor source text and recorded classification evidence.
- XGO-87 ABI/provenance compatibility remains unchanged.

## Known intentional breakage

The retired donor engine implementations and their execution-specific tests are no longer part of normal Desktop compilation. They remain as source-only historical evidence. This is intentional: XGO-81 differential classification is complete and production binaries must not carry a dormant second policy/execution engine.

## Devtool target

`xgo_seal`

## Target-local jobs added/changed

Added `single_authority_scan`:

```text
python3 tools/xgo/audit_seal.py single-authority \
  --output .devtool/reports/xgo/seal/single-authority.json
```

## Workflow DAG nodes/dependencies changed

`xgo_seal#validate` appends:

```text
abi_provenance_audit -> single_authority_scan
```

The resulting target has nine ordered validation nodes.

## Cross-target workflow references

None added.

## Execution-environment requirements

- native Termux/Linux-compatible Python 3
- Go toolchain
- repository source tree
- no .NET or Gradle build is claimed by this specialized node; complete packaged host builds remain part of GATE-12 final qualification.

## Domain evidence outputs

- `.devtool/reports/xgo/seal/single-authority.json`

The report contains production-source scan counts, exact compile quarantine inventory/hashes, canonical Go authority checks, host audit results, negative scanner fixture results, and full Go test/vet command evidence.

## Exit criteria

- `xgo_seal` topology reports nine validation nodes.
- all declared legacy authority source files are excluded from normal production compilation.
- retired donor execution tests are excluded from normal test compilation.
- active Desktop and Android authority surfaces contain zero prohibited authority references.
- Go canonical HLS/DASH, retry, backend, scheduler and store authority symbols are present.
- all deliberate prohibited fixtures are rejected by the scanner.
- `go test ./engine/... -count=1` passes.
- `go vet ./engine/...` passes.
- Android legacy-authority audit passes.
- Desktop legacy-authority and desktop-e2e audits pass.
