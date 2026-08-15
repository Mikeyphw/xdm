# XDM Android master remediation Overlay 13: privacy, quality, final gate

Target: `xdm_android`.

This is the final dependency-ordered remediation overlay. It is based on the successfully applied combined Overlay 11+12 v2 tree at commit `aef30699` and closes final M-036, M-046, M-048, M-055, and final M-001.

## Final validation is mandatory

Unlike every intermediate campaign artifact, this overlay does **not** allow deferred validation.

Required artifact validation tasks:

- `:app:finalRemediationStaticGate`
- `:app:compileDebugKotlin`
- `:core-model:test`
- `:core-utils:test`
- `:transfer-api:test`
- `:browser-integration:testDebugUnitTest`
- `:storage:testDebugUnitTest`
- `:transfer-native:testDebugUnitTest`
- `:transfer-aria2:test`
- `:scheduler:testDebugUnitTest`
- `:media:test`
- `:persistence:testDebugUnitTest`
- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:browser-extension:test`
- `:browser-extension:jsTest`
- `:browser-extension:validateFirefoxExtension`
- `:app:checkBrowserIntegration`
- `assembleDebug`
- `:app:assembleDebugAndroidTest`

`validation.allow_deferred` is `false`. Apply this overlay **without** `--no-validate`. Devtool must pause/roll back on validation failure rather than committing a source tree that has not passed the final campaign gate.

## M-036 — fail-closed release/readiness evidence

- Static validation, full Gradle validation, real-device smoke, and aria2 payload verification are explicit BuildConfig evidence inputs.
- All validation evidence defaults to `false`; normal/debug builds cannot self-certify release readiness.
- `MainViewModel` consumes actual release-security/install-readiness reports plus validation evidence instead of passing optimistic `true` constants.
- The final media dashboard exposes `releaseReady`, which is true only when blocker checks plus static/full evidence pass.
- Manual/device evidence remains independent and fail-closed; source validation does not pretend that physical-device smoke happened.

## M-046 — measurable real-filesystem privacy coverage

- Media privacy audit scans the actual app-private persistence roots used by secure browser envelopes, import journal, session index, and queue recovery.
- Filesystem inspection is canonical-path bounded by root count/depth/file count/file size/read bytes and also considers interrupted atomic-write artifacts (`.bak`, `.new`, `.tmp`, `.tmp-*`).
- Dashboard evidence reports filesystem roots/files scanned, coverage issues, and whether the requested roots were completely covered; oversized relevant files, traversal limits, read/enumeration failures, path escapes, and non-regular nodes make coverage incomplete.
- Final readiness requires all four known private roots to be covered and no durable secret blockers.
- Overlay 11's Termux-private/shared-staging privacy audit remains retained for FIFO/run-directory/`.xdm-*` surfaces.

## M-055 — exact request quality + structured diagnostics

- Capture duplicate grouping uses SHA-256 of the exact request URL instead of host/parent-path grouping, preserving signed/query-distinct request identity without displaying the URL.
- Session/credential detection uses `ExternalUrlPolicy.hasCredentialBearingQuery` instead of a literal `token` substring heuristic.
- Protected-media hints use boundary-aware structured container/codec markers; UI/display labels and arbitrary `widevine`/`protected` substrings are not protection authority.
- Media3 diagnostics classify from structured error-code/cause fields; execution failure categories use protection state, strategy, manifest freshness, `DownloadState`, and `BackendType`, not error-message substring parsing.

## M-048 / final M-001 — current final gate and validator harmony

- Media final validation now keys off current Overlay 13 + Room schema 20 rather than the obsolete media phase-18→33 ledger.
- Historical validators were rebaselined only where newer architecture superseded literal old tokens; their underlying ownership/security invariants remain enforced.
- `tools/run-final-release-gate.sh --ci` is executable from Gradle as `:app:finalRemediationStaticGate` and includes UIX R6, Debug Workbench D7, bug-hunt phases, current release seals, media validators, the 80-row Phase-11 matrix, and the Overlay-13 contract.
- `.devtool.toml` includes that static gate in the Android test/unit phase, and `tools/run-final-common-validation.sh` is the shared **keyless** non-device compile/module-test/lint/debug-package/browser-source/render matrix used by CI and signed publication.
- The signed publication entrypoint runs the Overlay-13 static and common gates before release assembly, then separately requires the key-bound Firefox Dark/AMOLED XPI package/verification tasks with `XDM_CAPTURE_KEY_ID`, `XDM_CAPTURE_PUBLIC_KEY_SPKI`, and `XDM_CAPTURE_OAEP_HASH`. Ordinary Devtool validation intentionally remains keyless.
- Signed CI explicitly wires the pinned aria2 archive SHA-256, verifies the trusted payload before release compilation, and retains post-build APK payload verification.
- `PROJECT_MANIFEST.json` current-state metadata is harmonized to Room schema 20, version `0.21.0`, versionCode 22, and fail-closed readiness evidence.

## Browser final-harmony repair found by the final gate

The direct browser JS gate exposed two cross-phase regressions not caught by the static validators:

1. the optional 1DM compatibility route called a removed `encodeSchemeData()` helper;
2. popup/page/manual/probe actions could no longer open XDM because generic `buildTargets()` had been made capture-empty during secure-v2 remediation.

The repair intentionally keeps two separate contracts:

- normal page/manual/probe Add uses `xdmdownload://add?v=1&url=...` and the optional `idmdownload:` route;
- detected media never uses that Add URL as a fallback and requires a prebuilt encrypted `xdmdownload://capture?v=2&sid=...&kid=...&ek=...&iv=...&ct=...` link.

A browser regression test now explicitly proves that automatic media fails closed without an encrypted link, then renders successfully with a prebuilt encrypted-v2 link.

## Evidence produced while building the artifact

Passed in the artifact work tree:

- `bash tools/run-final-release-gate.sh --ci`
- all seven repository-owned browser JavaScript tests
- fresh debug-extension rendering through `prepare_extension.py`
- rendered-extension `validate_extension.py`
- Python syntax and shell syntax checks (re-run during artifact seal)
- manifest/preimage/hash/inventory/apply simulation (re-run during artifact seal)

The container could not execute Gradle because Gradle 9.4.1 is not cached and `services.gradle.org` is unreachable. That is **not** recorded as a pass. The final artifact therefore requires those Gradle tasks on the target Devtool/Termux environment before commit.

## Apply

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlay \
  "/sdcard/Download/xdm_android_privacy_quality_final_gate_overlay_v2.zip"
```

Do not add `--no-validate` to the final campaign overlay.
