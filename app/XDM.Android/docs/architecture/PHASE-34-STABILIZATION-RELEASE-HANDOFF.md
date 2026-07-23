# Phase 34: Stabilization Release Handoff

Phase 34 is the release-handoff layer after the Phase 33 media final validation gate passed devtool validation.

## Status locked by this handoff

- Phase 33 overlay: `xdm_android_phase33_media_final_validation_gate_overlay.zip`.
- Devtool target: `xdm_android`.
- Validation result: 149 passed, 0 failed, 0 skipped.
- Diagnostics result: 0 warnings, 0 errors.
- Phase 33 is landed and must not be treated as pending.

## Scope

Phase 34 does not add runtime behavior. It records the landed state, keeps the release gate visible, and protects the exact regression repairs discovered during the Phase 33 rollback cycle.

Included:

- manifest handoff ledger;
- architecture handoff document;
- static validator for release-handoff invariants;
- CI and final release gate inclusion;
- app architecture contract coverage.

Not included:

- no new top-level route;
- no Room migration;
- no app version bump;
- no new worker, service, shell, or download execution path;
- no DRM bypass;
- no durable cookie, header, token, or session persistence.

## Phase 33 repairs that must stay locked

The final Phase 33 apply succeeded only after these defects were fixed. Future overlays must not reopen them.

1. Status labels are not secrets.
   - `secret-safe`, `secret-bearing`, and `secret-free` are safe UI/status vocabulary.
   - Literal leak fixtures such as `secret-token`, `secret-cookie`, `secret-auth`, `secret-csrf`, `secret-session`, and `super-secret-token` must still be blocked.

2. Already-redacted surfaces are safe.
   - `Cookie: <redacted-cookie>` is not a leak.
   - `Bearer <redacted>` is not a leak.
   - `token=<redacted>` is not a leak.
   - `session=referer=...` and aria2 `save-session=...` are not raw session-token persistence.

3. Browser capture quality grouping happens before live-review disposition.
   - Duplicate live captures must group with the existing capture instead of being sent to live review first.

4. Direct execution lanes stay discoverable.
   - `DirectNative` and `Aria2Segmented` must remain visible as direct-native compatible lanes in dashboards and summaries.

5. App contract tests track the actual release metadata shape.
   - Version checks must accept `rcN` release-candidate names such as `0.20.0-rc08`.
   - The existing `Diagnostics` top-level route is allowed; player diagnostics must not add `Player` or `Playback` top-level routes.

## Documentation research anchors

The Phase 18 through 33 media work remains aligned to these externally checked Android/Kotlin contracts:

- Android user-initiated data transfer jobs for user-started long transfers.
- Foreground service and WorkManager fallback rules for older or constrained execution paths.
- Android Gradle Plugin `jniLibs.keepDebugSymbols` for Termux/chroot native library strip protection.
- AndroidX Media3 track-selection APIs for real track and subtitle model seams.
- Kotlin regular-expression and string-literal behavior, especially substring matching and escaped JSON snippets in tests.

## Release handoff checklist

Before cutting or advertising a release candidate:

1. Run `bash tools/run-final-release-gate.sh --ci`.
2. Run the devtool Android target validation on the packed overlay, not a shadow working tree.
3. Confirm the manifest keeps `next_phase` as `complete`.
4. Confirm the Android app version remains `0.20.0-rc08` unless a separate release-version overlay intentionally bumps it.
5. Confirm Room schema remains 14.
6. Confirm no new top-level route appears beyond Downloads, Add, Queues, Scheduler, Media, Recovery, Diagnostics, and Settings.
7. Confirm all secret detectors allow redacted/status surfaces while blocking real leak fixtures.

## Next-phase guidance

The next overlay should be a release-candidate packaging or device-certification overlay only if it has the current source snapshot and exact target command. Avoid feature work in this handoff layer.
