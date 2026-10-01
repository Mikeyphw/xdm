# XGO Gate-12 v15 — compatible target/androidTest APK pair

## Incident

The Gate-12 registry-bypass lane refreshed only `:app:assembleDebugAndroidTest` while retaining an older `app-debug.apk`. Both APKs installed successfully because they are separate packages, but Android rejected `am instrument` with:

```text
Permission Denial: ... package com.mikeyphw.xdm.android.debug.test does not have a signature matching the target com.mikeyphw.xdm.android.debug
```

The retained app APK SHA-256 remained stable while the androidTest APK SHA-256 changed after test-source rebuilds. This proved the bypass was no longer consuming a signing-compatible producer pair.

## Remediation

The temporary `registry_bypass` lane now refreshes both APKs in one Devtool-owned Android test runner invocation:

```text
:app:assembleDebug
:app:assembleDebugAndroidTest
```

This is one Gradle invocation at the producer boundary, not a return to the previously duplicated later instrumentation build. The later exact-reuse job remains `--skip-build` and contains no Gradle/assemble invocation.

Immediately after the pair refresh, `tools/xgo/verify_gate12_apk_signers.py`:

1. resolves the Android SDK `apksigner` tool;
2. verifies both APK signatures;
3. extracts every signer certificate SHA-256 digest;
4. requires the target APK and androidTest APK signer sets to be identical;
5. records both APK SHA-256 hashes and signer digest evidence in `android-apk-signers.json`.

The signer receipt is checked again by the exact-reuse runner against the later RM08 package attestation and artifact checkpoint, so stale or replaced APK bytes invalidate reuse before installation.

## Workflow

```text
policy
→ topology
→ one target + androidTest pair refresh
→ signer compatibility receipt
→ Devtool-registry bypass receipt
→ RM08 packaged-byte attestation
→ artifact SHA-256 checkpoint
→ RM05 focused precheck
→ lint
→ exact-pair Devtool instrumentation --skip-build
→ desktop
→ Wave-12 final
```

The canonical `xgo_gate_final_continue#validate` workflow remains unchanged. This remains a temporary lane until Devtool's Android artifact publication/registry defect is repaired.

## Separate Devtool defect

The `cannot finalize execution run with nonterminal node(s)` error is a Devtool workflow-finalization defect exposed after a failed dependency. v15 does not weaken or hide that defect; it removes the XGO signing mismatch that triggered this particular failed dependency.
