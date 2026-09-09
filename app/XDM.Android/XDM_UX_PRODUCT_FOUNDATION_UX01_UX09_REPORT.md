# XDM Android UX Product Foundation — UX01 + UX09

## UX01 — destination storage truth

- Queue conditions no longer parse destination URI schemes independently.
- The same `AndroidDestinationWriter` instance used by transfer execution is injected into queue intelligence.
- Queue capacity has explicit `Known`, `Unknown`, and `Unavailable` states.
- A writable SAF/provider destination with unknown capacity is no longer falsely treated as unavailable.
- Canonical `xdm://filesystem/downloads` now resolves through the writer health path used by Storage Doctor.
- Truly inaccessible/read-only/permission-missing destinations remain fail-closed.

## UX09 — diagnostics / developer consolidation

Settings now exposes only two concepts:

1. **Diagnostics & support** — safe local tests, health, redacted support report, history.
2. **Developer mode** — a gate that reveals one **Developer Center**.

The former nested `Advanced Debug Workbench` surface is removed. Its read-only diagnostic cards live under the Health tab of Diagnostics & support. The old Developer tools UI is presented as Developer Center, and release-readiness is rendered only under Validation & release rather than repeated across unrelated developer sections.

## Additional UI corrections included

- Quick diagnostics presets use a wrapping action row rather than a clipping fixed row.
- Developer section chips wrap instead of being partially cut off at phone widths.
- Public labels use Developer mode / Developer Center consistently.
- Existing enum identifiers remain stable so persisted navigation from older builds can migrate without a Room or preferences schema change.


## Rebase note

Rebased post-v4: the current source already contains the cumulative wire/recovery/Live Locator lint hotfix, so v2 carries only the UX01/UX09 deltas and does not reapply those files.
