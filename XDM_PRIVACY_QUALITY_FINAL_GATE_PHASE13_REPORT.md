# XDM Android Overlay 13 — Privacy / Quality / Final Gate Report

Baseline: successful combined Overlay 11+12 v2 application, Git commit `aef30699`.

Scope: final M-036, M-046, M-048, M-055, and final M-001 closure.

## Implemented closure

### Release/readiness truth

Validation state is now evidence, not configuration optimism. Static/full/device/aria2 evidence is fail-closed in BuildConfig and is consumed by the release-security, install/update, support-bundle, and final-gate models. No runtime path may promote release safety, install readiness, static-validator completion, or full validation by passing a literal `true`.

### Real privacy audit

The media privacy dashboard receives the four real app-private persistence roots and performs bounded canonical filesystem inspection. Coverage counts, explicit coverage issues, and coverage completeness are evidence consumed by the final release gate. Oversized relevant files, depth/file-count truncation, unreadable paths, path escapes, and non-regular nodes fail coverage closed rather than being silently skipped. Existing Termux-private/shared-staging scans from Overlay 11 remain part of the broader privacy boundary.

### Quality/diagnostics

Signed/query-distinct media requests no longer collapse merely because they share host/path. Quality grouping fingerprints the exact request URL with SHA-256. Credential-query detection goes through the central URL policy. Protected-media planning now accepts only boundary-aware structured container/codec markers; display labels and arbitrary substrings are not authoritative. Playback diagnostics use structured Media3 error-code/cause inputs, and execution failure kinds use structured protection/strategy/state/backend inputs.

### Final validator harmony

The final static gate now targets current schema 20/Overlay 13 and contains the active bug-hunt, release-seal, UIX, Debug Workbench D7, media, and Phase-11 matrix checks. The current manifest database/public-release blocks are harmonized to schema 20, `0.21.0`, versionCode 22, and fail-closed readiness evidence. Historical assertions that incorrectly froze older Room schema versions or old ownership APIs were updated to retain their invariant rather than their obsolete implementation token. A shared `run-final-common-validation.sh` now drives the complete **keyless** non-device module-test/lint/debug-package/browser-source/render matrix. Signed publication must run both Overlay-13 static and common gates before release assembly and then pass the key-bound Firefox release-artifact tasks with the Android capture key/SPKI/OAEP inputs.
The signed GitHub job now exposes the required `XDM_ARIA2_ARCHIVE_SHA256` plus `XDM_CAPTURE_KEY_ID`, `XDM_CAPTURE_PUBLIC_KEY_SPKI`, and `XDM_CAPTURE_OAEP_HASH`; it fails closed if the browser capture inputs are absent, verifies the pinned aria2 payload before release compilation, and still verifies the exact packaged APK payload afterward.

### Browser final-gate corrections

Direct execution of the browser JS gate found two additional regressions and fixed both:

- restored the URL encoder required by the optional 1DM custom-scheme compatibility route;
- restored the separate XDM direct-Add v1 builder for page/manual/probe UI while keeping detected media encrypted-v2 only.

Automatic media without a prebuilt encrypted XDM capture now has an explicit fail-closed JS regression case. No plaintext `capture?v=1` path was restored.

## Validation evidence available in the build environment used to prepare this overlay

Passed:

- final static release gate (`run-final-release-gate.sh --ci`), including the 80-row static matrix;
- all seven Firefox bridge JavaScript tests;
- fresh keyless-debug extension render;
- rendered Firefox extension validator;
- Overlay-13 dedicated contract validator.

Not executed here:

- Gradle compile/unit/lint/browser Gradle tasks, because the required Gradle 9.4.1 wrapper distribution is not cached and this container cannot reach `services.gradle.org`.

This missing environment capability is deliberately not converted into release evidence. The Devtool artifact is configured with `allow_deferred=false`, so the target environment must execute the final static gate, Android compile, all current JVM/Android unit modules, lint, debug packaging/Android-test assembly, browser tests/integration, and keyless rendered-extension validation before Devtool commits the overlay. Key-bound Firefox release-artifact verification is intentionally reserved for signed publication, where the capture key/SPKI/OAEP inputs exist.

## Manual/device evidence

Physical-device operational smoke remains a separate explicit evidence input and defaults false. Source/Gradle success is not equivalent to completing browser-device, reboot/recovery, SAF/MediaStore, Termux, notification, provider-revocation, TalkBack, or IME smoke scenarios.

> **Superseded browser-capture note (XPI v3 R2):** the signed Firefox extension path is now direct-v3/keyless. `XDM_CAPTURE_KEY_ID`, `XDM_CAPTURE_PUBLIC_KEY_SPKI`, and `XDM_CAPTURE_OAEP_HASH` are no longer release inputs. Encrypted-v2 key handling remains only for reading legacy captures.
