# XGO-RM08 — Packaged aria2 Android execution qualification

Date: 2026-09-30
Gate: GATE-12 remediation
Status: implementation complete; authoritative package/device evidence is produced when Gate-12 resumes.

## Outcome

RM08 closes the final aria2 embedding gap left after RM06 runtime/recovery convergence and RM07 source/provenance qualification. Gate-12 can no longer close from source contracts alone: it must materialize XGO's pinned source-built aria2 runtime, package those exact attested bytes into the Android APK, and execute the packaged daemon on a real Android instrumentation target.

## Implemented contract

- `xgo_rm08_aria2_contract` owns bounded apply-time validation through `:app:verifyRm08Aria2PackagedExecutionContract`; it does not trigger the expensive source build or device run.
- `xgo_rm08_aria2_package` owns source materialization, Android packaging, and exact APK-byte attestation.
- `xgo_rm08_aria2_device` is a dedicated Android target with `policy = "required"` instrumentation.
- `xgo_rm08_aria2` is the fresh Gate-12 aggregate Android host authority; gate composition remains canonical because every XGO cross-target edge references `#validate`.
- Gate-12 package execution builds aria2 from the RM07 pinned source recipe before APK packaging.
- `tools/xgo/verify_rm08_aria2_package.py` requires schema-v3 `source-build` provenance, runs the strict aria2 ELF/APK verifier, and proves the APK's `lib/arm64-v8a/libaria2c.so` and embedded runtime lock match the source-tree attestation byte-for-byte.
- Gate-12 device execution uses Devtool's Android instrumentation runner with a required-device policy rather than an ad-hoc ADB shell gate.
- The instrumented test launches the actual executable from `ApplicationInfo.nativeLibraryDir`, verifies runtime-lock attestation and Android dependency policy, verifies authenticated RPC startup, rejects a TLS server signed by an untrusted test CA, downloads successfully from a loopback HTTPS server signed by the injected trusted test CA, checkpoints the aria2 session, performs clean daemon shutdown, restarts the packaged daemon, and proves an XDM-owned paused GID is recovered from the saved session.
- Production still defaults to `AndroidAria2TrustBundleProvider`; RM08 adds only a narrow `Aria2TrustBundleProvider` seam so instrumentation can provide deterministic test CA trust without weakening production certificate validation.

## Gate-12 integration

The checkpoint continuation now routes:

1. Android package refresh through `target:xgo_rm08_aria2_package#validate`.
2. Package attestation into `.devtool/reports/xgo/gates/final-continue/rm08-aria2-package.json`.
3. The retained `gate-final-continue` path is refreshed so its Android resume boundary is: Devtool policy -> topology -> RM08 source build/package/attestation -> APK checkpoint -> RM05 residual precheck -> canonical lint proxy -> required RM08 device execution -> desktop -> final Wave-12 verifier.
4. Its checkpoint/precheck jobs are target-local to `xgo_gate_final_continue`, and all cross-target edges use canonical `target:<name>#validate` refs.
5. Remaining Android instrumentation runs through `target:xgo_rm08_aria2_device#validate`, where a missing device is a failure rather than a skip.
6. The final Wave-12 verifier requires both the RM08 package receipt and a successful non-empty Android instrumentation receipt.

A fresh `xgo_gate_final` run routes its Android host node through `target:xgo_rm08_aria2#validate`.

## Local construction evidence

- RM08 static contract validator: PASS.
- Full XGO topology audit: PASS.
- `xgo_gate_final_continue` target-ready topology audit: PASS.
- Gate-12 auditor regressions: 5 passed.
- Python compile checks for the new/changed Gate-12 verification scripts: PASS.

The actual source build, APK-byte receipt, and Android packaged-runtime execution are intentionally deferred to the existing authoritative Gate-12 host boundary.
