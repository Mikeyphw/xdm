# Phase 33: Media Final Validation Gate

Phase 33 closes the XDM Android media stack after the `--no-validate` build runway. It does not add a feature route, worker, Room migration, downloader backend, or DRM bypass. It restores the final gate that must run before release candidates are treated as shippable.

## Scope

- Reconcile the media phase ledger through Phase 33.
- Keep the browser, resolver, queue, library, player, capture quality, privacy audit, and mobile polish surfaces inside the existing Media route.
- Run every static validator from the original release gate plus the media validators added in Phases 18-33.
- Restore full Gradle build/test/lint expectations for the final phase.
- Keep warning count at zero. Warnings are patch-worthy, not acceptable residue.
- Preserve Termux/chroot native strip protection with `jniLibs.keepDebugSymbols += "**/*.so"`.
- Scan known Kotlin tripwires that previously broke overlays: unsafe cross-module smart casts, nullable `in` checks, redundant `!!`, raw `buildList`/`buildMap` helper drift, callable-reference count helpers, and untyped shell rendering.
- Scan durable and transient surfaces for cookie/header/token leaks.

## Final command ledger

The full gate is represented by `tools/run-final-release-gate.sh`. It runs the static validators through Phase 33 and, outside `--ci`, prints the full Gradle command that must be run for the actual build/test/lint gate.

The last phase intentionally changes the workflow from `--no-validate` back to normal validation. The overlay is still safe to apply without validation if needed, but the release candidate should not be accepted until the final gate passes in the target Android/Termux/chroot environment.

## Security contract

- yt-dlp cookies use transient Netscape files only.
- aria2 input/session files are transient and cleanup-verified.
- Room, sidecars, logs, diagnostics, notifications, and command previews never persist cookies, authorization headers, bearer tokens, proxy credentials, passwords, signatures, or tokenized URLs.
- Protected media remains diagnostics-only. No DRM bypass is introduced.

## UI contract

Phase 33 exposes a small **Media final validation gate** card inside the existing Media route. It summarizes readiness, blockers, review items, warning-zero status, no-new-route status, and the command ledger. It does not create a Validation, Final, Release, or Media Gate top-level tab.

## Acceptance criteria

- `PROJECT_MANIFEST.json` includes Phase 33 in both phase ledgers and sets `next_phase` to `complete`.
- `tools/validate-media-final-validation-gate.py` passes.
- `bash tools/run-final-release-gate.sh --ci` passes.
- `./gradlew -Pxdm.requireAria2Runtime=true --stacktrace lintDebug lintBeta :media:test :transfer-api:test :storage:test :transfer-native:test :transfer-aria2:test :scheduler:test :persistence:testDebugUnitTest testDebugUnitTest assembleDebug assembleBeta` passes in the target environment.
- `devtool ... apply-overlay xdm_android_phase33_media_final_validation_gate_overlay.zip --validate` passes when validation is re-enabled.


## Redaction Detector Repair

The final gate verifies that status labels such as `secret-safe` remain allowed UI copy and are not treated as literal leaked secrets. Secret literal scanning still blocks test fixtures such as `secret-token`, `secret-cookie`, `secret-auth`, and `super-secret-token`.


## Rolled-back validation repair

The repaired overlay preserves existing media behavior while fixing final-gate regressions found during `:media:testDebugUnitTest`:

- aria2/direct dashboard summaries include a Direct native compatible family label so direct progressive transfers remain visible in the dispatch dashboard.
- duplicate browser captures are grouped before live-review classification, preventing repeated live manifest rows from bypassing duplicate counts.
- mobile polish uses value-sensitive secret detection so safe documentation copy like `authorization headers` does not disable `secretSafe`.
