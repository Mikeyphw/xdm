# Phase 17: Final public release gate

Phase 17 closes the Android modernization sequence with a release-candidate gate. It is intentionally schema-free and route-free: the existing Diagnostics and Settings routes expose the state, while CI and local scripts enforce the gate.

## Scope

- Bump app metadata to `0.17.0-rc01` with `versionCode = 18`.
- Keep the base package identity `com.mikeyphw.xdm.android` stable.
- Keep Room at schema v13. No migration is introduced in this phase.
- Require full devtool validation for the final public release, not a medium selected-task gate.
- Keep privacy-safe diagnostics and install/update readiness active.
- Verify that the aria2 payload gate is still available for publishable aria2-enabled artifacts.
- Document signed release verification and artifact checksum expectations.
- Do not add a new top-level route.

## Required release flow

1. Apply the Phase 17 overlay with full `devtool --validate`.
2. Run the static final gate with `tools/run-final-release-gate.sh` from `app/XDM.Android`.
3. Produce artifacts with the root `build-release-apk.sh` helper.
4. Verify signed release APKs with `apksigner` when release signing inputs are available.
5. Save SHA-256 checksums for every APK placed under `dist/android/`.
6. Confirm Diagnostics copy/export surfaces still use redacted summaries only.

## Validation contract

The final gate passes only when:

- all phase validators through Phase 17 are present and wired into CI;
- `PROJECT_MANIFEST.json` lists implemented phase 17 and `next_phase` is `complete`;
- the public package ID remains `com.mikeyphw.xdm.android`;
- app metadata is at least `0.17.0-rc01` / `versionCode = 18`;
- Room schema v13 exists and has no unsupported top-level `version` key;
- Diagnostics and Settings expose the final release gate inside existing routes;
- release, install/update, payload, diagnostic-redaction, and route-topography checks remain active.

## Non-goals

- No database migration.
- No new feature route.
- No new background service.
- No silent relaxation of lint, privacy redaction, or package identity checks.
