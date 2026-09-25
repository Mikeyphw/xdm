# XGO-11+12+13 overlay contract

## Covered roadmap items

- XGO-11 — SQLite driver and transactional storage foundation
- XGO-12 — canonical schema v1 and migration mechanism
- XGO-13 — revision-based compare-and-swap

## Devtool ownership

- target: `xgo_store`
- validation: required
- failure action: pause
- `validation.tasks`: absent; `xgo_store#validate` is authoritative

## Capabilities closed

- `XGO-CAP-STORE-001`
- `XGO-CAP-STORE-002`
- `XGO-CAP-STORE-003`

## Files / interfaces introduced

- `engine/store/sqlite`: SQLite C-ABI binding, schema/migration, repository/CAS
- `engine/cmd/xgo-store-audit`: machine-readable platform/schema/CAS audit
- `tools/xgo/run_sqlite_platform_smoke.py`: native + no-cgo portability smoke
- `tools/xgo/audit_store_contract.py`: shared capability/fixture closure audit

## New invariants

1. SQLite is configured with foreign keys, WAL, NORMAL synchronous mode and a
   finite busy timeout on every successful native open.
2. `CGO_ENABLED=0` never silently falls back to another store; `Open` returns
   `ErrUnavailable`.
3. Schema migration owns an IMMEDIATE transaction and rejects future schema
   versions rather than downgrading them.
4. Canonical mutable tables contain a positive integer `revision`.
5. Repository mutations require an explicit expected revision.
6. Exactly one writer using a given current revision can succeed; all later
   writers with that revision change zero rows and receive `ErrStaleWrite`.
7. Stale writes are not automatically re-read/retried by the repository.
8. `store/*` may depend on typed domain identities but not runtime/backend/media
   or platform/UI implementations.

## Deliberately deferred

- attempt-generation reservation/fencing (XGO-14)
- backend ownership activation protocol (XGO-15)
- durable checkpoint ledger (XGO-16)
- verification journal (XGO-17)
- publication saga (XGO-18)
- recovery coordinator (XGO-19)
- final Android/Desktop SQLite library packaging (host reconnection waves)

## Required validation DAG

```text
runner:go#validate
  -> store_contract_audit
  -> sqlite_platform_smoke
  -> schema_audit
  -> cas_stress
```

The native Termux validation does not use `go test -race`; Android/arm64 does
not support the Go race detector. Concurrency correctness here is exercised by
repeatable multi-connection CAS stress. Supported-host race qualification
remains a later seal activity.
