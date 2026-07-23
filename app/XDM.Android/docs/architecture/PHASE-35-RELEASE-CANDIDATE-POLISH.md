# Phase 35: Release Candidate Polish

Phase 35 is a stabilization-only release-candidate polish phase. It does not change runtime behavior. It turns the Phase 33 final validation gate and the Phase 34 release handoff into a concise ship/no-ship contract for the Android release candidate.

## Status carried forward

- Phase 33 media final validation gate is landed.
- Phase 33 devtool result: 149 passed, 0 failed, 0 skipped.
- Phase 33 diagnostics: 0 warnings, 0 errors.
- Phase 34 stabilization release handoff is landed.
- `next_phase` remains `complete` because this overlay is a release polish layer, not a feature branch.

## Release-candidate scope

Phase 35 is limited to docs, manifest metadata, CI/static-gate wiring, and architecture contract tests.

It intentionally avoids:

- runtime code changes;
- new top-level routes;
- Room schema migration;
- app version bump;
- raw shell exposure;
- root requirement;
- DRM bypass;
- durable cookie/header/token/session persistence;
- changes to media execution, browser capture, download scheduling, player behavior, or storage finalization.

## Android release-readiness anchors

The release-candidate gate tracks these Android release expectations:

- Package identity must remain stable: `com.mikeyphw.xdm.android`.
- Release artifacts must be signed before distribution.
- Release artifacts must emit checksums.
- Debug package identity remains isolated through `.debug`.
- Beta package identity remains isolated through `.beta`.
- Release builds must not be debug-only artifacts.
- The aria2 payload gate remains required for publishable artifacts.
- User-started long transfers remain modeled as user-visible, resumable work with notification controls and persisted state.
- The validation matrix must include the latest Android target, compact phone, tablet, and foldable smoke coverage before public release.
- Static validation remains a zero-warning gate.

## Ship/no-ship gate

Ship is allowed only when all of the following are true:

1. Static validators pass with zero warnings and zero errors.
2. Gradle build, lint, unit tests, and packaging tasks pass for the release-candidate tree.
3. Signed release or beta artifacts are produced by the documented release helper.
4. APK checksums are written next to every produced artifact.
5. The packaged aria2 runtime is verified when producing publishable artifacts.
6. No raw cookies, authorization headers, bearer values, tokens, session identifiers, or post bodies are stored in durable diagnostics, sidecars, logs, notifications, or Room metadata.
7. Phase 33 regression protections remain present: safe `secret-safe` labels, redacted Cookie/Bearer/token/session strings, duplicate-live grouping, direct-compatible native/aria2 lane summaries, `rcN` metadata acceptance, and the existing Diagnostics route.
8. Phase 34 release handoff remains present and records the landed Phase 33 result.

No-ship is required if any one of those checks fails.

## Artifact naming

The Phase 35 overlay artifact is:

`xdm_android_phase35_release_candidate_polish_overlay.zip`

Release-candidate APK outputs continue to be created by `tools/build-release-artifacts.sh`, which writes a `.sha256` file beside each APK.

## Validation files

- `tools/validate-phase-35-release-candidate-polish.py`
- `tools/run-final-release-gate.sh`
- `.github/workflows/android.yml`
- `app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt`

## Outcome

After Phase 35 lands, the Android tree has an explicit release-candidate checklist without changing app behavior. The next overlay can safely focus on actual packaging or release publication details only if validation remains green.
