# XDM Android master remediation combined Overlay 11+12: Termux security + UI truth

Target: `xdm_android`.

This intermediate schema-v2 artifact is based on the successfully applied Overlay 10 v2 tree at commit `81a429ed` and combines the next two dependency-ordered roadmap stages into one artifact.

## Manifest-controlled defaults

- `target`: `xdm_android`
- retained final validation tasks:
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `validation.allow_deferred`: `true`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.commit.message`: `Apply XDM Android Termux security and UI truth remediation`

Campaign Gradle validation remains intentionally deferred. Apply this intermediate overlay with `--no-validate`; the declared tasks remain part of the final Overlay-13 validation contract.

## Overlay 11 promises

- **M-012:** Termux result callbacks require the exact durable process token; control signals are never emitted after a failed control-state CAS; a launched process whose attach CAS loses is immediately force-cancelled through its exact token-bound owner; root filesystem actions are authorized from canonical Android paths rather than shell substring matches.
- **post-processing M-020:** graph deletion refuses non-terminal/recovery-owned post-processing, cleans terminal bridge artifacts before detaching the graph, and clears every persisted bridge URI including the payload bridge field.
- **Termux M-032:** every capture-backed yt-dlp path is externally owned. It does not own or mutate a normal app `Download`, including legacy capture links and automated post-processing paths.
- **Termux-filesystem M-046:** transient yt-dlp URL/header data is materialized only inside a 0700 Termux-private run directory, with 0600 config/URL-list files and a FIFO-managed payload; raw yt-dlp command templates fail closed; automatic/manual privacy audit scans the real Termux-private bridge filesystem plus the shared `.xdm-*` staging surface, including stale FIFO/non-regular nodes and abandoned run directories, and reports only bounded counts/status.

## Overlay 12 promises

- **M-037:** Add remains ephemeral/non-restorable; route restoration keeps activity/settings subpanels, download detail targets, and recovery target/action state; ordinary Downloads detail selection is persisted rather than only notification-opened targets.
- **M-038:** Downloads two-pane eligibility uses the measured content pane, fold orientation/separation, and the actual hinge rectangle/position from `FoldingFeature.bounds`; tabletop/horizontal separation avoids side-by-side panes and adaptive sheets follow the same window profile.
- **M-039:** every Add dismissal path clears the external in-memory review draft and rejects any still-owned durable automation command as user-declined.
- **M-040:** completed artifact health is generation/provider/readability/size/verification-aware; verification failure wins over stale pass evidence; destructive confirmation re-plans against current state; completed rename/delete/inspection reload the current Download before touching storage; bulk controls come from the real action planner.
- **M-041:** host matching uses domain boundaries, fallback destination rules cannot shadow specific rules, rule destinations are explicit, and saved-search Apply restores query/filter/archive state.
- **M-052:** the active accessibility/adaptive validator now covers behavior (including `performClick` instrumentation), exact hinge-position/measured-pane contracts, unique stable review-surface tags, human-readable destination types, and Add-owned `imePadding()` rather than relying on static labels alone.

## Security and ownership details

- Durable capture-backed yt-dlp specs store only an opaque `https://xdm.invalid/media-session/<hash>` identity plus selected variant IDs/format selector. Exact URLs and headers are recovered from the encrypted handoff at launch; an expired session-bound handoff fails closed rather than falling back to a sanitized URL.
- Managed yt-dlp uses `--config-locations` and `--batch-file` against private transient files and never places Cookie/Authorization/signed URL data in the child argv. Old raw yt-dlp entry points return a managed-session-required failure.
- Post-processing execution claim identity includes input, action, format selector, selected variant identity, and safe extra arguments so materially different output selections cannot collapse into one claim.
- Privacy/root diagnostics remain redacted; no raw credential values are copied into durable job metadata or privacy-audit output.

## Second promise-audit corrections

- Off-center separating hinges are now represented by their exact window-space rectangle. Downloads derives left/detail pane extents from the hinge edges, and adaptive sheets choose a physical fold-safe pane instead of centering across the fold.
- The privacy audit now treats private FIFOs/sockets/symlinks, abandoned per-run directories, and shared `.xdm-*` staging artifacts as auditable surfaces. Managed yt-dlp metadata uses a restricted `--print` object projection instead of raw `-J`, so signed URLs/header dictionaries are never staged in shared storage.
- Delayed result/control callbacks are terminal-safe: DAO reconciliation cannot mutate Completed/Failed/Cancelled/TimedOut jobs back into an active/recovery state. PID-only root process kills are rejected because a numeric PID is not durable ownership evidence.
- Browser Session Health, Engine Escalation, and Add Review have distinct stable semantics tags. Saved destination chips use explicit `DestinationType.uiLabel()` values rather than raw enum names.
- Historical unit/release-seal assertions now require retention of the schema-18 generation-integrity floor rather than freezing the repository at schema 18; the current schema remains 20.

## Validation posture

Targeted non-Gradle checks for this artifact include the active Phase-7 Termux validator, active Phase-9 accessibility/adaptive validator, the combined Phase-11/12 contract, pure core-model compilation and policy probes, and final artifact hash/preimage/apply simulation. The old Phase-8 umbrella validator still invokes two pre-existing final-gate harmony validators (Phase-61 overlay pointer and Phase-49 media-probe default-header expectation); those belong to Overlay 13's M-048/final M-001 gate-harmony scope and are not represented as Overlay-11/12 failures.

## Apply

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlay \
  "/sdcard/Download/xdm_android_termux_postprocessing_ui_navigation_truth_overlay_v2.zip" \
  --no-validate
```

After successful application, continue with Overlay 13 — privacy/quality/final gate — and run the campaign Gradle/unit/lint validation there.
