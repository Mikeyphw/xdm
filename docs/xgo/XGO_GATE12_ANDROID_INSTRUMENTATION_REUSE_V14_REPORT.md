# XGO Gate-12 Android instrumentation exact-reuse v14

## Scope

This is a narrow follow-up to v13. The workflow graph and exact APK-pair reuse design are unchanged. v14 fixes the isolated staging implementation for native Termux Python.

## Runtime finding

The v13 continuation reached `tools/xgo/run_gate12_instrumentation_reuse.py` without launching the former second `assembleDebug` + `assembleDebugAndroidTest` instrumentation build. It then failed before device instrumentation with:

```text
Gate-12 instrumentation reuse failed: module 'os' has no attribute 'link'
```

Termux has a documented history of Python builds where `os.link` is unavailable. Android Python also has platform-specific standard-library omissions/restrictions. Hard links are not required for the Gate-12 contract.

## Remediation

`stage_exact_pair()` now uses `shutil.copy2()` unconditionally for the two already-qualified APKs. The staging boundary remains fail-closed:

1. load the passing RM08 package attestation;
2. SHA-256 verify the retained app and androidTest APKs;
3. bind them to the Gate-12 checkpoint;
4. copy exactly those files into the isolated instrumentation tree;
5. SHA-256 verify the staged bytes again;
6. run Devtool instrumentation with `--skip-build`;
7. verify the APK paths selected by Devtool hash back to the qualified pair.

The copy is temporary/rebuildable staging, not an additional source of truth.

## Device-policy note

The RM05 precheck remains `when-device-present`; therefore `SKIPPED (no authorized device)` is a valid precheck outcome when ADB inventory is empty at that instant. Required full instrumentation remains fail-closed and still requires exactly one resolved device. v14 does not add an XGO-owned ADB/root bootstrap path.

## Regression contract

The validator now rejects `os.link` in the reuse runner and requires the portable `shutil.copy2(source, dest)` staging path. It continues to prove that the temporary bypass lane does not invoke the rebuilding `xgo_rm08_aria2_device` target and that the canonical Gate-12 validate workflow is unchanged.
