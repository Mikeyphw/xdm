# XDM Android master remediation Overlay 02+03 v2: generation integrity + durable external review

Target: `xdm_android`.

This corrected intermediate schema-v2 devtool artifact is built on Overlay 01 / commit `c66ec424`. It supersedes `xdm_android_generation_room_integrity_durable_review_overlay_v1.zip` and combines the next two dependency-ordered remediation phases:

1. generation / Room graph integrity
2. external handoff durable review

## Manifest-controlled defaults

- `target`: `xdm_android`
- retained final validation tasks:
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.commit.message`: `Apply XDM Android generation integrity and durable review`

Validation is intentionally deferred for the remediation campaign. Apply this intermediate overlay with `--no-validate`; the declared task list remains in the artifact as the canonical final-validation contract.

## Phase 02 — generation / Room integrity

- advances Room 17 -> 18 and commits exported schema 18;
- carries attempt generation through downloads, backend ownership/migration, backend snapshots, native checkpoints, checksums, verification, trusted blocks, recovery and finalization;
- creates backend tasks paused and requires durable ownership attachment before native/aria2 activation;
- binds migrated target tasks to the newly transferred ownership generation rather than the source generation;
- rejects runtime snapshots whose generation or installation identity does not match durable ownership;
- adds Room foreign-key graph protection and transactional terminal graph deletion while preserving review/recovery history by detachment;
- blocks terminal graph deletion while post-processing is active;
- makes batch download upserts atomic with whole-batch stale-write preflight and requires strictly newer timestamps for same-generation writes;
- makes transfer/runtime persistence rejection visible and prevents queue/fake-data batch writers from silently ignoring rejected saves;
- supports explicit empty media-variant replacement;
- adds MigrationTestHelper coverage for 17 -> 18, 14 -> 18 and the oldest authentic exported-schema chain 4 -> 18; manual legacy 1 -> 4 migration tests remain because exported schemas 1-3 do not exist in repository history.

## Phase 03 — external durable review

- `ExternalAddDownloadActivity` is review-only and no longer subclasses `MainActivity`;
- accepted external requests persist their exact sensitive request material in the secure durable envelope before an executable Room command is exposed;
- `MainActivity` receives only a persisted internal command ID and does not replay the original external launch intent after recreation;
- command side effects require an exclusive durable Room claim;
- interrupted `Claimed`/`Executing` commands recover after process death; review commands reopen review and replay-safe executable commands reacquire the durable claim;
- direct external enqueue uses a deterministic download ID so replay converges instead of duplicating downloads;
- integration-token auto-execution is limited to public `EnqueueDownload`; higher-impact/private actions require user review;
- Android-observed caller identity is separated from caller-supplied origin/referrer diagnostics;
- reviewed Add Download remains `Executing` until a durable download row exists, while dismissal records `Rejected/UserDeclined`;
- download/destination/queue/browser-export business decisions read authoritative Room/DataStore state rather than `MainUiState` snapshots.

## Apply

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlays \
  "/sdcard/Download/xdm_android_generation_room_integrity_durable_review_overlay_v2.zip" \
  --no-validate
```

Do not apply v1. Do not start Overlay 04 until this corrected artifact applies cleanly. Campaign validation remains deferred until the final overlay.
