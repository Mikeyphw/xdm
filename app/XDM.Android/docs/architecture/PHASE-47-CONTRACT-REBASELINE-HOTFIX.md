# Phase 47 Contract Rebaseline Hotfix

This hotfix updates stale test-only contracts after the browser-removal and Firefox bridge stacks converged.

## Scope

- Preserve the historical Phase 33 through Phase 36 manifest records without requiring `current_overlay` to still point at a browser-removal overlay.
- Treat the retained `browser_removal_phase0_1`, `browser_removal_phase4`, and `browser_removal_phase7` manifest entries as the browser-removal lineage proof.
- Fix `BrowserSchemePhase37ContractTest` so custom-scheme hosts are accumulated across the two source manifest intent filters instead of letting the second filter overwrite the first.

## Non-goals

- No runtime UI behavior change.
- No route changes.
- No manifest receiver ownership change.
- No browser runtime restoration.
