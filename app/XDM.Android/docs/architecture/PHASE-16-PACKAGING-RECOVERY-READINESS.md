# Phase 16: Packaging, recovery and install/update readiness

Phase 16 prepares XDM Android for beta packaging and update testing without changing the app's route topography or Room schema.

## Scope

- Keep Room at schema v13. Phase 16 is an install/update readiness slice, not a persistence migration.
- Advance Android metadata to `0.16.0-alpha01` with monotonic `versionCode = 17`.
- Keep the base package identity stable as `com.mikeyphw.xdm.android` while retaining debug and beta suffixes for side-by-side testing.
- Surface install/update readiness inside Diagnostics and Settings, not as a new top-level route.
- Keep recovery and finalization surfaces available before update testing.
- Retain the aria2 runtime payload gate for beta packaging.
- Keep support diagnostics privacy-safe and redacted.
- Replace the deprecated Compose clipboard manager with the Android `ClipboardManager` service.

## Runtime contract

The app evaluates an `InstallUpdateReadinessReport` from the current build metadata and runtime readiness signals. Blocking checks prevent a build from being treated as beta-ready when package identity, version code, schema version, recovery readiness, diagnostics redaction, or payload verification are not in the expected state.

## Packaging contract

The beta build remains installable as a distinct package through the `.beta` suffix. Release updates keep the base package identity stable so Android can treat later release artifacts as upgrades. Publishable release builds still require external signing values; local unsigned release builds remain allowed for development but are reported as warnings.

## Recovery contract

Phase 16 does not move recovery into a new route. Interrupted downloads, finalization journals, orphan artifacts, and backend migration records continue to surface through the existing Recovery, Diagnostics, Downloads and Settings routes.

## Validation

`tools/validate-phase-16.py` checks version metadata, schema stability, route topography, readiness models, clipboard API migration, CI/static gate coverage, and architecture tests.
