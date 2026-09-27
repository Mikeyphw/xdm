# XGO-88 dead-code and single-authority architecture seal

## Result

XGO-88 converts the Android/Desktop authority cutovers from convention into an enforceable architecture boundary.

The legacy Desktop donor engines had intentionally remained in the compiled source tree through XGO-81 so differential behavior could still be classified. That prerequisite is now closed. XGO-88 retains the source files as historical evidence but removes the known authoritative implementations from normal `XDM.DownloadEngine` and `XDM.Media` compilation. Their execution-specific donor tests are likewise retired from normal test compilation.

The compile quarantine covers the old DownloadManager, recovery coordinator, retry policy, segmented executor, backend selector/ownership helper, queue scheduler/dependency evaluator, stateful aria2 owner, and the old media/HLS/DASH download execution services. Compatibility DTOs/interfaces, stateless aria2 RPC mechanism, UI/catalog/conversion functionality, and the narrow Go-authorized host brokers remain compiled.

## Single-authority scanner

`audit_seal.py single-authority` is policy-driven by `engine/testdata/seal/single-authority-policy.json`. It verifies:

- every quarantined donor file still exists as explicit historical evidence and is removed from the corresponding production project compilation;
- every retired donor test is removed from the normal test project compilation;
- active Desktop source has no legacy download/recovery/queue authority construction and no retry/backend/queue/segmented execution policy references;
- active Desktop App/Platform code contains no direct canonical SQLite download-state writer;
- selected Android application/scheduler authority surfaces contain no legacy scheduler/retry/start authority;
- Go contains the canonical HLS/DASH parsers, retry decision engine, backend selector, scheduler arbitration and store schema;
- the existing Android legacy-authority audit and Desktop legacy-authority/e2e audits remain green.

The scanner also runs deliberate negative fixtures for a reintroduced `DownloadManager`, Desktop retry policy, C# HLS execution, direct Desktop SQLite canonical-state access and Android queue-start authority. XGO-88 fails if any negative fixture is not rejected, proving the scanner itself is active rather than merely reporting success.

## Validation performed in the implementation tree

- `python3 tools/xgo/audit_topology.py target-ready --target xgo_seal`: pass, 9 validation nodes.
- `python3 tools/xgo/audit_seal.py single-authority`: pass.
- active Desktop C# files scanned: 201.
- Android authority surfaces scanned: 7.
- compile-quarantined authority sources: 15.
- retired donor execution tests: 15.
- deliberate prohibited fixtures rejected: 5/5.
- `go test ./engine/... -count=1`: pass.
- `go vet ./engine/...`: pass.
- Android `android_legacy_authority_scan`: pass.
- Desktop `legacy-authority`: pass.
- Desktop `desktop-e2e`: pass.

Complete Android package/Gradle and Desktop .NET build/test requalification remains a GATE-12 host qualification responsibility. The XGO-88 specialized node does not fabricate those measurements in an environment where those host builds are not portable.

## Scope boundary

XGO-89 capability-ledger closure and the final audit/remediation loop are not included in this overlay.
