# XGO RM08 — Effective Android Compiler + Dynamic Bionic Link Contract v21

## Finding

The v19 source-lineage boundary now correctly runs before APK refresh.  The first
real failure is the v18 pre-build C++/`getaddrinfo` link-model probe itself:
`DT_NEEDED=[]`.

The ELF reader parses `PT_DYNAMIC` directly; this is not a `readelf` formatting
failure.  LLVM's Android driver normally adds `-lc`, so a `getaddrinfo` probe with
no `DT_NEEDED` indicates that the effective hybrid compiler/link path resolved
Bionic statically or inherited an equivalent static/default configuration.

## Remediation

* Apply `--no-default-config` to the effective compiler pair returned by
  `prepare_ffmpeg_compilers()`, including the `ndk-clang-driver+termux-utils`
  hybrid path that v20 did not cover.
* Keep static libc++ via `-static-libstdc++`.
* Explicitly restore linker dynamic mode before platform libc and retain
  `libc.so` with `-Wl,-Bdynamic -Wl,--no-as-needed -lc`.
* Continue to require `DT_NEEDED libc.so` and reject `libc++_shared.so`.
* On probe failure, emit Clang `-###` output so the actual linker job is visible.
* Bump the aria2 native recipe marker to schema 5.

No test weakening, Gate-12 ordering change, or instrumentation bypass is added.
