# XGO-62..64 implementation report — operational settings, secrets, and diagnostics

## Scope

This overlay implements the first Wave 9 operational-core boundary:

- XGO-62 engine settings schema and ownership classification.
- XGO-63 SecretRef broker and structural redaction.
- XGO-64 structured diagnostics, retention, health, and support snapshot.

XGO-65 is intentionally left for a later overlay because idempotent legacy import requires a durable import job journal and crash/retry semantics distinct from the settings/secret/diagnostics surface.

## Delivered behavior

- `engine/ops` owns versioned engine settings with deterministic defaults, legacy v0 migration, explicit future-version handling, and validation for impossible combinations.
- Legacy settings are classified as engine, Android host, Desktop host, or presentation-only so shared behavior moves to Go without stealing platform/UI preferences.
- Secrets use canonical `SecretRef` values and a bounded in-memory broker; structural redaction is applied to headers, cookies, signed query parameters, proxy credentials, body-shaped context and support data before persistence/export.
- Diagnostics use typed event fields, bounded retention by age/count/bytes, subsystem health states, redacted external logs, and bounded support snapshots.

## Validation

- `go test ./engine/ops`
- `go run ./engine/cmd/xgo-ops-audit --mode settings_audit`
- `go run ./engine/cmd/xgo-ops-audit --mode secret_scan`
- `go run ./engine/cmd/xgo-ops-audit --mode diagnostics_stress`
- fixture lint and fixture secret scan
- capability ledger audit
- `go test ./engine/...`
- `go vet ./engine/...`
