# XDM Android combined remediation Overlay 11+12 v2 report

Baseline: Devtool commit `81a429ed` (Phase 08+09 v3 + Overlay 10 v2 applied).

Scope: roadmap Overlay 11 (M-012, post-processing M-020, Termux M-032, Termux-filesystem M-046) plus Overlay 12 (M-037 through M-041, M-052).

## Termux/post-processing closure

- Result ownership now fails closed when a durable process token is absent or mismatched.
- Pause/resume/cancel/force-cancel signals are sent only after the corresponding control request is durably accepted.
- If RUN_COMMAND starts a child but `attachRun` loses its ownership CAS, XDM immediately issues a token-bound force-cancel before recording recovery-required state.
- Root filesystem actions are normalized and canonicalized in Android; only exact bridge-verified artifacts or explicit app/shared-media roots can cross the root boundary.
- Capture-backed yt-dlp jobs never own an app `Download`, including automated media rules and reconciliation of legacy rows.
- Capture-backed yt-dlp durable specs contain an opaque media-session identity, format selector, and selected variant IDs—not the exact signed URL or request headers. Session-bound launch fails closed if the encrypted handoff is unavailable.
- yt-dlp secrets are written only to private 0600 per-run config/URL-list files and consumed via `--config-locations`/`--batch-file`; the managed shell payload is fed through a private FIFO instead of a persistent payload script.
- Raw yt-dlp templates are intentionally denied so credentials cannot bypass the managed transient-session bridge.
- Terminal post-processing bridges are cleaned before download-graph detachment; any non-terminal/recovery-owned job blocks deletion.
- The privacy audit scans the real Termux-private `xdm-post` filesystem for credential/signed-query residues, stale regular files, non-regular nodes such as the managed FIFO, and abandoned per-run directories. It also scans shared `.xdm-*` metadata staging files, returning bounded counts/status only. It runs after fresh tool probing and is also manually available in Developer Tools.
- yt-dlp metadata written to the shared bridge is a restricted `--print` JSON projection (title/container/duration/codec/format descriptors only), never raw `-J` output containing signed URLs or header dictionaries.
- DAO result/control reconciliation is terminal-safe; delayed callbacks cannot resurrect Completed/Failed/Cancelled/TimedOut Termux jobs.

## UI/navigation/accessibility closure

- `Add` restores to Downloads and is never written as the durable last route.
- Activity/Settings subpanel state, Downloads detail target, and Recovery download/action target are persisted. Normal Downloads detail selection now updates that durable target too.
- Dismissing Add clears the external review draft and moves a still-claimed/executing automation command to durable Rejected/UserDeclined state.
- Add content owns `imePadding()` and resets draft-specific backend/checksum/review state when a different external draft arrives.
- Fold layout captures the exact `FoldingFeature.bounds` rectangle, orientation, and separating state. Downloads derives physical list/detail pane edges from the hinge position, so an off-center hinge is never crossed; tabletop/horizontal separation disables side-by-side Downloads.
- Adaptive sheets use the same current window/fold profile and choose a physical fold-safe pane, instead of centering a dialog across a separating hinge.
- Completed UI health distinguishes provider change, lost permission, missing artifact, size mismatch, verification failure, and verified presence. Negative/over-total progress is bounded.
- Confirmation sheets re-plan the requested destructive action against current state before execution, and completed artifact inspect/rename/delete reload the current Download before provider mutation.
- Bulk Pause/Resume visibility comes from the actual batch action planner.
- Destination host rules require an exact domain or dot-boundary subdomain; fallback rules are evaluated only after specific rules. Rule destinations are explicit rather than inherited invisibly from the global destination.
- Saved-search Apply restores query, state filter, and archive inclusion.
- Browser Session Health, Engine Escalation, and Add Review use separate stable semantics tags; saved destination chips render explicit human-readable destination-type labels rather than raw enum names.
- Active Phase-9/UIX-R6 validation includes Add `imePadding`, exact fold-position geometry, measured Downloads panes, focus/semantics instrumentation with `performClick`, and raw-enum/static behavior checks.

## Second promise-audit findings fixed before delivery

The first combined artifact was not delivered as final after a deeper audit found four implementation gaps and one static/accessibility drift: off-center hinge position was discarded, the private privacy scan ignored FIFOs/abandoned run directories, raw yt-dlp `-J` metadata could temporarily reach shared staging, delayed control callbacks could resurrect terminal jobs, and multiple review surfaces shared one semantics tag. A final UIX-R6 run also found raw `DestinationType.name` rendering plus historical schema-18 assertions that would contradict the already-applied schema-20 migration. All are corrected in v2.

## Validation performed without campaign Gradle tasks

- `tools/validate-bug-hunt-phase7-post-processing-termux.py`: PASS after updating stale schema-18/raw-yt-dlp assumptions to the current schema-20 managed-session model.
- `tools/validate-bug-hunt-phase9-accessibility-adaptive-layout.py`: PASS.
- `tools/validate-uix-r6-accessibility-performance-release-seal.py`: PASS.
- `tools/validate-remediation-phase11-12.py`: PASS.
- all `core-model/src/main` Kotlin sources compile with standalone `kotlinc`.
- standalone policy probe verifies domain-boundary matching, fallback ordering, and verification-failure precedence.
- The Phase-8 umbrella validator's direct Phase-8 source checks are updated for the current artifact model; it still reports the two pre-existing Phase-61/Phase-49 final-harmony validator failures, intentionally reserved for Overlay 13.

No Gradle compile, unit-test, lint, or final release-gate campaign was run for this intermediate artifact.
