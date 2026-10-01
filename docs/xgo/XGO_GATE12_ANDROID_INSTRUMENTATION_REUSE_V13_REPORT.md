# XGO Gate-12 Android instrumentation exact-reuse remediation — v13

## Scope

This remediation is confined to the explicit temporary `xgo_gate_final_continue#registry_bypass` lane. The canonical `validate` workflow and the canonical `xgo_rm08_aria2_device` Android runner remain unchanged.

The v12 continuation log exposed three related instrumentation-boundary defects in the temporary lane:

1. `:app:assembleDebugAndroidTest` was built by the RM05 refresh and then built again, together with `:app:assembleDebug`, by the later Android instrumentation runner. The second pass was mostly `UP-TO-DATE` but still cost about 91 seconds before device execution.
2. RM05 enumerated raw `adb devices` serials and therefore executed twice when one emulator was reachable as both `emulator-5554` and `localhost:5555`.
3. Devtool's skip-build instrumentation path selected `xdmandroid-android-test-debug.apk` as `app_apk`, even though Gate-12 had already hash-qualified `app-debug.apk` as the application APK. This is consistent with the known Devtool instrumentation artifact-lineage gap where filesystem candidates can still be selected by naming/mtime rather than an exact producer receipt.

The remaining `Aria2Rm08PackagedRuntimeInstrumentedTest.packagedRuntimeEnforcesTlsAndRecoversSavedOwnedSession` failure is not modified by this overlay. This remediation removes execution ambiguity first so the next failure evidence comes from the intended APK pair and one device identity.

## Online platform study

Android's current command-line test documentation defines the device-test lifecycle as: build/rebuild the main app and test package, install both APKs, then run `am instrument`. A second build immediately before instrumentation is not a platform requirement when an already-built compatible pair is intentionally reused.

Android's current ADB documentation also documents the first emulator as console port 5554 with ADB port 5555, and explicitly states that the emulator connected on ADB port 5555 is the same emulator whose console is on 5554. The temporary lane therefore collapses a local `emulator-5554` / `localhost:5555` transport alias when on-device identity agrees instead of treating them as two devices.

References:

- https://developer.android.com/studio/test/command-line
- https://developer.android.com/tools/adb
- https://developer.android.com/studio/run/emulator-commandline

## Remediation

### Exact APK reuse

`tools/xgo/run_gate12_instrumentation_reuse.py`:

- reads the passing RM08 packaged-byte attestation;
- verifies the current app/test APK SHA-256 values against that attestation and the Gate-12 artifact checkpoint;
- stages only those two exact APKs into an isolated `build/` tree;
- invokes Devtool's existing `devtool.android_instrumentation` engine with `--skip-build` and an explicit device serial;
- verifies the `DEVTOOL_INSTRUMENTATION_RESULT` selected APK bytes have the same hashes as the qualified source pair;
- records a separate XGO reuse receipt including Devtool plan/receipt/artifact-pair/device IDs where emitted.

The isolated tree is deliberate. The installed Devtool compatibility path does not expose explicit `--app-apk` / `--test-apk` arguments and still discovers candidates under `build/outputs/apk`; presenting exactly one qualified app/test pair prevents stale copied outputs from winning by mtime while preserving Devtool's installation, AndroidJUnitRunner execution, parsing and result semantics.

### No second Gradle build

Inside `registry_bypass`, `android_instrumentation_remaining` now references the command-owned exact-reuse job rather than `target:xgo_rm08_aria2_device#validate`. The canonical workflow still references the normal Android target.

Expected temporary-lane sequence:

```text
androidTest refresh (one Gradle producer pass)
→ packaged-byte/hash qualification
→ RM05 focused device precheck
→ lint
→ exact-pair Devtool instrumentation --skip-build
→ desktop
→ final Wave-12
```

There is no second outer `assembleDebug` / `assembleDebugAndroidTest` build in the temporary instrumentation node.

### Device alias convergence

`tools/xgo/gate12_android_device.py` resolves one physical/emulated device from authorized ADB transports. It probes stable on-device identity and also understands the documented local emulator console/ADB port relationship. Multiple aliases of one emulator collapse to one device, preferring the standard `emulator-N` serial. Multiple genuinely distinct devices still fail the exactly-one-device policy.

The RM05 focused precheck and the full instrumentation reuse job consume the same resolver, so they cannot silently choose different aliases for the same emulator.

## Removal

All v13 reuse/alias code remains temporary and should be removed when Devtool's Android instrumentation campaign provides canonical exact `ArtifactPair` reuse and `DeviceLease` alias convergence directly.
