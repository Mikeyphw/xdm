# GATE-12 Android native core / SQLite runtime hotfix

## Failure that triggered this remediation

Android instrumentation reached the real app process but crashed before test execution:

```text
java.lang.UnsatisfiedLinkError: dlopen failed: library "libxdmcore.so" not found
```

The existing XGO Android host contract could audit the native build plan without making
`libxdmcore.so` an authoritative APK build prerequisite. A manual native build then exposed
a second packaging gap: `engine/store/sqlite/db_cgo.go` linked Android with `-lsqlite3`, so the
cross-link required a separately supplied Android `libsqlite3.so`.

## Chrootctl precedent

Chrootctl already solves the same SQLite/cgo portability problem with
`github.com/mattn/go-sqlite3`: its SQLite amalgamation is compiled into the Android/arm64 Go
runtime instead of depending on a system `libsqlite3.so`. XGO now follows that model only for
Android while preserving XGO's existing Go-owned SQLite C-ABI wrapper and database semantics.

## Changes

- Android no longer declares `#cgo android LDFLAGS: -lsqlite3`.
- Android blank-imports `github.com/mattn/go-sqlite3` v1.14.50 so its amalgamation contributes
  the `sqlite3_*` symbols used by XGO's existing cgo binding.
- `build_android_xdmcore.py` now resolves/probes a real Android/arm64 compiler lane:
  Termux-native clang first on Termux, NDK target clang on ordinary build hosts, and a probed
  Termux+NDK-sysroot compatibility lane only as a fallback.
- Linker-only flags stay in `CGO_LDFLAGS`; compiler wrappers never inject them into cgo's
  `-Werror` compile probes.
- `libxdmcore.so` is linked for 16 KiB pages and verified as AArch64 with no dynamic
  `libsqlite3.so` dependency.
- A stale manually copied `libsqlite3.so` is deleted before the Android core build.
- Gradle `merge*NativeLibs` depends on `buildXgoNativeCore`, so APK/package/instrumentation
  paths cannot omit the native core.
- The Devtool `xgo_android` workflow now has an explicit native runtime build node before its
  existing Android-engine audit, with generated JNI/provenance outputs declared in the
  transaction write set.
- The `xdm_android` target declares the engine/native-builder inputs and generated JNI outputs,
  making resume/affected execution sensitive to native-core changes.

## Validation strategy

This is implementation remediation discovered during GATE-12 closure. Keep the implementation
artifact separate from the final GATE-12 qualification. The overlay itself should be applied
without replaying the full Android seal; after application, rebuild the debug app/androidTest
APKs and rerun the missing instrumentation boundary. Once that is green, run the normal
resumable Android seal and then the separate GATE-12 verifier.

## Pre-overlay runtime confirmation

A manual Termux `libsqlite3.so` package proved why an external Android SQLite payload is not acceptable:
`libsqlite3.so` carried a versioned `libz.so.1` dependency, which Android's namespace could not resolve.
The native verifier therefore rejects both dynamic `libsqlite3.so` and versioned `*.so.N` dependencies.


## V4 recovery-safe rebase

V4 is sealed against the 2026-09-28 fresh XGO snapshot after the failed v2
transaction. It preserves the authoritative full Android validation phase
matrix and intentionally does not absorb unrelated local Devtool-wrapper work.
The SQLite/JNI remediation remains a focused post-GATE-12 implementation
artifact; final qualification stays separate.
