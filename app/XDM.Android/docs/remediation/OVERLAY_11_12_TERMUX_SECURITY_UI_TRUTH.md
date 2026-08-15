# Remediation Overlays 11+12 — Termux security and UI/navigation truth

This combined campaign phase follows Overlay 10 v2 at `81a429ed`.

## Overlay 11

M-012 is closed by strict owner-token result acceptance, durable-control-before-signal ordering, exact-owner cancellation after attach CAS loss, and canonical Android-side root path authorization. Post-processing M-020 cleans terminal bridges before graph detachment and refuses deletion while any non-terminal/recovery job owns the graph. Termux M-032 makes all capture-backed yt-dlp paths externally owned. Termux M-046 adds a real private/shared bridge filesystem audit and moves transient media session data into per-run 0600 files/FIFO transport. The audit includes stale non-regular nodes and abandoned run directories; yt-dlp shared metadata is a restricted projection, never raw `-J` output.

For capture-backed yt-dlp, durable specs store an opaque hashed media-session URL and variant/format identity only. At launch, the exact URL and allowed headers are recovered from the encrypted request handoff; if that handoff has expired, execution fails closed. Raw yt-dlp shell entry points are denied. Delayed control/result callbacks cannot resurrect terminal Termux jobs, and PID-only root process kills are rejected because a PID alone is not durable owner evidence.

## Overlay 12

M-037 persists real nested navigation targets while keeping Add ephemeral. M-038 derives Downloads pane behavior from measured content width plus the exact current hinge rectangle/position and keeps adaptive sheets inside a physical fold-safe pane. M-039 clears/rejects external Add review ownership on every dismiss path. M-040 uses current artifact state for health/actions and re-plans destructive confirmation immediately before execution. M-041 fixes destination-domain/fallback matching and makes saved searches actionable. M-052 strengthens active accessibility/adaptive validation with behavioral semantics, unique stable review-surface tags, human-readable destination labels, Add IME coverage, and historical release-seal assertions that retain migration guarantees without freezing the current Room version.

## Deferred validation

This is an intermediate overlay. The artifact retains `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and `:app:lintDebug` as required final tasks but is intended to be applied with `--no-validate`. Full campaign validation and final historical-validator harmony are Overlay 13 responsibilities.
