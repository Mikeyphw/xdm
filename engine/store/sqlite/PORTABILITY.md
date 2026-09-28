# SQLite backend portability contract

XGO uses SQLite through a small Go-owned cgo binding to SQLite's stable C ABI.
SQLite handles remain private to `engine/store/sqlite`; repository/schema/CAS
semantics do not depend on the host packaging strategy.

## Runtime linkage

Android uses the same embedded-amalgamation approach already proven by
chrootctl: `github.com/mattn/go-sqlite3` contributes its SQLite amalgamation to
the final Go/cgo link, while XGO's own binding continues to call the stable
`sqlite3_*` C API. Android therefore does **not** link `-lsqlite3`, does not use
Android hidden/private SQLite symbols, and does not require a separately
packaged `libsqlite3.so`.

Other hosts retain their existing external SQLite linkage contract:

- Linux: system or application-packaged `libsqlite3`.
- macOS: system or application-packaged SQLite according to release policy.
- Windows: application-packaged SQLite import library/DLL.

The Android native builder verifies that `libxdmcore.so` has no dynamic
`libsqlite3.so` dependency before it is accepted for application packaging.

## No-cgo behavior

`CGO_ENABLED=0` builds remain compilable and expose the same Go API, but `Open`
returns `ErrUnavailable`. This is deliberate: XDM must never silently switch to
a different persistence engine and split authoritative state.

## Required database settings

Every opened connection enforces:

- `PRAGMA foreign_keys=ON`
- `PRAGMA journal_mode=WAL`
- `PRAGMA synchronous=NORMAL`
- a finite busy timeout (default 5000 ms)
- SQLite extended result codes

Schema migration obtains an `IMMEDIATE` transaction before inspecting or
changing schema version, providing a writer lock for the migration critical
section.
