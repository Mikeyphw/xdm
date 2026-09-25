# SQLite backend portability contract

XGO uses SQLite through a small Go-owned cgo binding to SQLite's stable C ABI.
The Go engine does not depend on a third-party Go SQLite module and does not
expose SQLite handles outside `engine/store/sqlite`.

## Runtime linkage

The cgo implementation links `-lsqlite3`. The packaging layer for each host is
responsible for making a compatible SQLite library available:

- Termux/native Android development: Termux `libsqlite3`.
- Android application packaging: ship/link an app-owned SQLite library through
  the NDK build; do not depend on Android hidden/private SQLite symbols.
- Linux: system or application-packaged `libsqlite3`.
- macOS: system or application-packaged SQLite according to release policy.
- Windows: application-packaged SQLite import library/DLL.

Those packaging adapters are intentionally later host work. The authoritative
schema, migration semantics, repository API and CAS behavior are independent of
how the host supplies the SQLite C library.

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
