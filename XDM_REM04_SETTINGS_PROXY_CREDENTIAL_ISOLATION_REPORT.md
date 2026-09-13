# XDM Desktop REM04 — Settings, proxy/network policy, and credential isolation

## Scope

Closes the REM04 ownership set from the frozen S00–S16 audit: S11-01 through S11-08 and S11-13.

## Implementation

- Settings loading now has an explicit operational/degraded state. A corrupt/incompatible prior settings file with no valid backup no longer becomes first-run defaults; the shared HTTP client is fail-closed while settings are degraded.
- Explicit proxy modes are preserved during normalization. Invalid manual/PAC configuration cannot silently normalize to `None` or `System`.
- PAC evaluation throws when a script has no usable DIRECT/HTTP proxy directive instead of falling back to DIRECT. PAC mode now honors `BypassLocal` and the configured bypass list.
- Saved server credentials are resolved from committed settings, not mutable editor drafts. Restored transfers re-resolve credentials, and every source transition (refresh, checkpoint restore, clean restart, mirror failover) enforces credential-origin isolation.
- Manually supplied credentials remain usable only while the transfer stays on the same URI origin; changing origin drops them and re-resolves a matching saved credential for the new source.
- New transfers use the persisted default connection count and persisted automatic-category policy. Unsaved Settings editor values no longer leak into those runtime decisions.
- Settings export serializes `_settingsService.Current`, so exports represent committed state rather than a hybrid of saved and unsaved editor values.
- Network editor ranges now exactly match persisted normalization (timeouts, retries, connections, segmented size), and the save validator rejects out-of-range values rather than silently clamping them.
- Settings save/export filesystem/serialization failures remain inside the UI command boundary and are reported through status text.

## Regression coverage

- Corrupt saved settings without a valid backup fail closed.
- Settings service exposes degraded state and clears it after a successful save.
- Degraded settings produce a fail-closed HTTP client.
- PAC unsupported results fail closed; PAC bypass-local/bypass-list behavior is covered.
- Credential matching and URI-origin isolation are covered.

## Validation policy

REM04 is an intermediate remediation overlay and is applied with `--no-validate`. The final REM18 seal owns the complete cross-overlay validation gate.
