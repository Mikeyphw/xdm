# XGO-11+12+13 implementation report

## Storage backend

The engine now owns a thin cgo binding to SQLite's stable C ABI. The binding is
kept inside `engine/store/sqlite`; no third-party Go SQLite module is required.
The package has an explicit no-cgo implementation that preserves API/build
shape while returning `ErrUnavailable` for runtime access.

Native connection policy:

- foreign keys: ON
- journal mode: WAL
- synchronous: NORMAL
- busy timeout: 5000 ms by default
- extended SQLite result codes: enabled

Errors preserve numeric SQLite result/extended codes while keeping SQL arguments
out of error text. Busy/locked, constraint and corruption/not-a-database classes
have stable predicates.

## Schema v1

Schema version 1 creates 20 canonical tables and 8 explicit indexes. It includes
normalized records for requests/downloads/attempts, backend ownership/tasks,
artifacts/publication, transfer segments/checkpoint blocks, verification,
queues/schedules, media graph entities and diagnostics.

Migration is protected by `BEGIN IMMEDIATE`, records a SHA-256 checksum of the
schema, sets `PRAGMA user_version=1`, is idempotent at version 1 and rejects a
newer database with `ErrSchemaTooNew`.

## Revision CAS

`Repository.UpdateDownload` accepts `(DownloadID, expected Revision, patch)`.
The SQL mutation contains `WHERE download_id=? AND revision=?` and writes
`revision=expected+1`. Zero affected rows are distinguished as not-found or
`ErrStaleWrite`; the repository does not perform a hidden read/retry loop.

The stress audit executes 64 fresh-database rounds with 12 concurrent writers
sharing revision 1. Each round requires exactly one success and eleven stale
writes.

## Portability boundary

The current native build links `-lsqlite3`. Future Android/Desktop packaging is
responsible for supplying an app-owned or supported SQLite library without
changing the Go store API/schema. The no-cgo contract is compile-checked for
Android/arm64, Linux/arm64, Windows/amd64 and macOS/arm64 so unsupported builds
fail explicitly rather than accidentally selecting a different state store.
