# XDM Android XAR16 Signed Release Evidence Seal

Merged roadmap overlay **7 of 8** / Android overlay **16 of 17**.

XAR16 closes the signed-release, APK-set, real-device, publication, native-runtime, lint and same-run evidence roots:
S16-01, S16-02, S16-03, S16-04, S16-05, S16-06, S16-07, S16-08, S16-09, S16-10, S16-11, DS8-S16-01, DS8-S16-02, DS8-S16-04, DS8-S16-05, DS8-S16-06, DS8-S16-07.

The overlay does not touch the production Android Firefox extension. XFE01 remains the single-extension authority and desktop must adapt around the Android-owned implementation.

## Gate behavior

- Normal Devtool application runs the Android validation path and static XAR16 validator.
- The full signed-release matrix is explicit: `XDM_XAR16_FULL_RELEASE_GATE=1 bash tools/run-final-release-gate.sh --ci` or `bash tools/run-xar16-signed-release-gate.sh --ci`.
- Publication metadata is generated only after signed APK/AAB/APKS artifacts, split APK-set install, previous-release upgrade/reboot/launch, signed-release journeys, aria2 evidence, FFmpeg evidence, lint and native-symbol/16 KiB evidence exist for the same run.

## New evidence tools

- `tools/build-xar16-release-artifacts.sh`
- `tools/run-xar16-install-upgrade-matrix.sh`
- `tools/run-xar16-signed-release-journeys.sh`
- `tools/generate-xar16-publication-bundle.sh`
- `tools/verify-xar16-release-evidence.py`
- `tools/run-xar16-signed-release-gate.sh`
- `tools/validate-xar16-release-evidence-seal.py`


### Validation graph repair

The XAR16 Gradle graph keeps validation enabled but leaves `finalRemediationStaticGate` edge-free with respect to individual XAR validator dependencies. Devtool invokes those validators explicitly, while the final shell gate remains the canonical aggregate checker. This avoids the retained XAR05 ⇄ final-gate cycle without weakening validation.


## v8 validation hotfix

XAR16 v8 keeps validation enabled and repairs the failures observed in the v5 Devtool log:

- Runtime lock `schemaVersion` validation now compares against the pinned runtime manifest instead of a stale hard-coded value.
- Gradle `Exec` validators run with `PYTHONDONTWRITEBYTECODE=1`, and the XAR16 static validator purges stale cache artifacts left by failed validation attempts before checking repository cleanliness.
- `EngineEscalationPlanner` propagates `aria2Eligible` to the helper methods whose signatures already require it.
- The canonical Android Firefox extension keeps the same capture protocol while making `browserHandoff` normalization idempotent, so retained privileged proposed headers such as `Accept-Language` survive candidate re-merge.

## v9 validation compile/gate repair

XAR16 v9 keeps validation enabled and repairs the v8 validation failures: retained post-UX13 successor acceptance now handles compact XAR overlay names and the XAR17 next phase; XAR15 contract tests use project-standard JUnit 4 imports; XAR15/XAR16 Kotlin compile drift is fixed by restoring bounded ClipData/WebView bridge constants, adding the browser request-fingerprint contract constant, keeping direct-capture private approval scopes as durable strings, typing native-HLS startup recovery as `Result<Unit>`, preserving Termux media CAS checks while returning a Boolean accepted flag, and fixing clipboard URL text joining. The canonical Android Firefox extension capture logic remains unchanged.


## v10 retained validation repair

XAR16 v10 keeps validation enabled and repairs the v9 apply failures. The retained Phase61 validator now accepts the compact `XAR17_313_root_closure_audit_final_gate` next-phase marker used by the merged roadmap. Devtool validation also stops invoking `:app:testDebugUnitTest` during XAR16 apply because that task resolved uncached external test artifacts (`kotlinx-coroutines-test` and Turbine) from `dl.google.com` in the offline Termux validation environment. XAR16 still validates static gates, retained XAR validators, browser extension validation, module tests, lint, and debug assembly; full app-unit-test dependency validation remains part of the final XAR17/online cache gate.
## v12 validation repair

XAR16 v12 keeps validation enabled and repairs the three failures surfaced by the v11 full Devtool graph. `checkBrowserIntegration` now runs the browser-integration module tests instead of reintroducing the full app unit-test suite that XAR16 deliberately defers to XAR17; the retained Phase64 validator accepts the merged-roadmap `XAR17_313_root_closure_audit_final_gate` successor marker; and the FFmpeg runtime verification tasks now declare `mustRunAfter(installPinnedFfmpegRuntime)` so Gradle 9.7 has an explicit ordering relationship whenever native install and verification coexist in one graph. The canonical Android Firefox extension production logic is unchanged.

## v13 retained Phase63/static-matrix successor repair

XAR16 v13 keeps validation enabled and repairs the stale retained-gate successor checks exposed after v12. Phase63 now accepts the already-authoritative merged-roadmap `XAR17_313_root_closure_audit_final_gate` successor marker, and the retained Phase7/Phase10/Phase11 static matrix validators now accept the superseding XAR16 signed-release path and XAR17 final-closure handoff without weakening their original evidence checks. No production Android Firefox extension logic is changed, and the v12 browser-integration scope and Gradle 9.7 FFmpeg ordering repairs remain intact.
