# XGO-RM02 Android Generated Native Source Ownership Report

## Trigger

The authoritative `xgo_gate_final` invocation correctly entered the full
Android host seal after completing `xgo_seal`, but Gradle 9.7 rejected two app
source-set merge tasks:

- `:app:mergeDebugJniLibFolders`
- `:app:mergeDebugAssets`

Both tasks consumed files produced by `:app:buildXgoNativeCore` under
`app/src/main/jniLibs` and `app/src/main/assets` without a producer dependency.

The existing wiring only attached `buildXgoNativeCore` to
`merge*NativeLibs`. That is too late in AGP's graph: `merge*JniLibFolders` and
`merge*Assets` are earlier consumers and Gradle's work validation correctly
fails them independently.

## Resolution

Keep the current XGO packaging contract and generated paths intact, but bind
`buildXgoNativeCore` to every app merge task whose name ends in:

- `JniLibFolders`
- `Assets`
- `NativeLibs`

This covers debug, androidTest, developmentUnsigned, release, and future
variants without enumerating variant names.

## Why not move outputs in this remediation

The fixed source-tree locations are currently part of XGO-87/XGO-89
provenance, Android packaging, audit and manifest contracts. Moving the native
library/provenance into `build/generated` is a worthwhile future cleanup, but
would widen this Gate-12 repair into a packaging-contract migration. RM02 keeps
the established paths and repairs only Gradle producer ownership.

## Regression evidence

`tools/xgo/tests/test_build_android_xdmcore.py` now locks all three consumer
families and the single `buildXgoNativeCore` dependency binding.

## Gate position

`xgo_gate_final` is authoritative. The failed run proved that it executed the
Wave-12 seal and then entered the Android package lane. RM02 must be applied
with `--no-validate`; then rerun `xgo_gate_final` in full. Desktop/final-wave
verification must not be claimed from the failed run because Android packaging
terminated the gate first.
