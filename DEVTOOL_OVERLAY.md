# XDM Android master remediation Overlay 06+07 v3: backend resume + storage finalization

Target: `xdm_android`.

This corrected v3 intermediate schema-v2 devtool artifact supersedes v1/v2, is rebased on the actually applied Phase 04+05 state / commit `5c64cc6c`, and combines the next two dependency-ordered remediation phases:

1. backend resume / migration integrity
2. storage / finalization / completed-artifact integrity

## Manifest-controlled defaults

- `target`: `xdm_android`
- retained final validation tasks:
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `validation.allow_deferred`: `true`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.commit.message`: `Apply XDM Android backend resume and storage finalization`

Campaign validation is intentionally deferred. Apply this intermediate overlay with `--no-validate`; the declared tasks remain the final-campaign validation contract.

## Phase 06 — backend resume / migration

- adds a durable one-active-migration Room claim and keeps process mutexes as local serialization only;
- reconstructs the exact encrypted request envelope and reruns the app request-security guard before source mutation;
- quiesces but retains source backend evidence until target ownership/task attachment is durable, then performs final source retirement;
- binds native resume to hashed source/effective representation identity, a strong `If-Range` validator and SHA-256 evidence for persisted partial ranges;
- fails old/weak checkpoint evidence closed and requires checkpoint flush success for a safe pause;
- makes selective repair require exact request context, security validation, strong validator continuity and trusted block checksums, retaining a verified original backup until atomic replacement succeeds;
- makes aria2 status parsing strict, terminal transitions non-regressive and migration retirement preserve its GID mapping until target proof;
- persists aria2 runtime lease/ownership metadata with `AtomicFile`;
- stops advertising proxy/torrent/metalink capabilities that the current wrapper cannot execute truthfully.

## Phase 07 — storage / finalization / publication

- advances Room 18 -> 19 with generation-bound completed-artifact identity and a unique active migration claim;
- retains the fsynced storage publication journal across the backend→Room handoff, records the exact commit target before filesystem/provider commit, imports surviving evidence during startup recovery if Room finalization was never created, and converts a post-atomic-move crash into committed-artifact recovery rather than a missing-staging diagnosis;
- wires `AtomicFinalizationCoordinator` before checksum verification, persists `Verifying` durably, quarantines pause/cancel races after destination commit, reconciles an incomplete journal from already-durable `Completed` metadata, and never downgrades generation-bound `Completed` truth merely because journal closure or backend cleanup needs later reconciliation;
- keeps configured `destinationUri` as a destination specification and persists the committed artifact separately as `completedArtifactUri` + generation + byte count;
- supports content-URI completion verification/checksum streaming and requires a provable committed provider byte count before completion/open/share;
- fails SAF/direct-document in-place overwrite closed when the provider cannot prove atomic replacement;
- publishes MediaStore through a fresh pending item and retires the prior item only after successful publication;
- uses the actual attempt generation in publication journals and includes provider-copy capacity in planning;
- routes notification detail/recovery actions on fresh launch / `onNewIntent` without recreation replay;
- fixes terminal-notification idempotency comparison;
- makes completed terminal events/open/share/media playback/post-processing require the generation-bound committed artifact;
- dispatches Recovery by its typed recommended action and keeps unsupported automatic repair/adoption/locate flows quarantined with truthful review labels;
- persists a fresh restart replacement before retiring the old attempt's backend/finalization recovery evidence;
- revalidates saved document-provider destinations before offering/selection, centralizes live writability through `DestinationProvider.canWrite`, makes Storage Doctor probe the selected destination type, reconciles stale scanner-owned recovery warnings, and confirms manual warning dismissal.

## Apply

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlay \
  "/sdcard/Download/xdm_android_backend_resume_storage_finalization_overlay_v3.zip" \
  --no-validate
```

Do not start Phase 08 until this artifact applies cleanly. Campaign validation remains deferred until the final remediation overlay.
