# XGO-65 Implementation Report — Idempotent Legacy Import Framework

## Scope

XGO-65 implements the Go-owned legacy import framework for Android Room and Desktop JSON sources without introducing a permanent dual-write architecture.

## Delivered behavior

- Legacy import source descriptor with source kind, version, content hash and stable idempotency key.
- Import job journal with pending, staged, committed and failed states.
- Source kind/version/hash/status/commit counts preserved in the journal.
- Staging-before-final-commit behavior so crash-after-stage does not mutate authoritative state.
- Restart recovery that commits already staged objects without re-running the adapter.
- Duplicate import handling that returns the committed result without creating new authoritative objects.
- Unsupported newer source version rejection before journal or authoritative mutation.
- Malformed input and partial source handling with typed errors and no authoritative mutation.
- Retry-after-failure path for the same idempotency key.
- Initial Android Room and Desktop JSON adapters for later host-specific import overlays.

## Validation

- `import_faults`: success, duplicate import, crash mid-import, malformed input, partial source, unsupported newer version and retry after failure.
- `settings_audit`: existing XGO-62 settings promises remain closed.
- `secret_scan`: existing XGO-63 redaction promises remain closed.
- `diagnostics_stress`: existing XGO-64 diagnostics promises remain closed.
- `fixture lint` and `fixture secret scan` include the now-detailed XGO-CAP-OPS-005 fixture.
- `xgo_ops` topology includes the new `import_faults` validation node.
- Full `go test ./engine/...` and `go vet ./engine/...` pass.

## Gate status

XGO-65 completes the implementation portion of Wave 9. GATE-09 remains separate and should audit the full operational core target after this overlay is applied.
