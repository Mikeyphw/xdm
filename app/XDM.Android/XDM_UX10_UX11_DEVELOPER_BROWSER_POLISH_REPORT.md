# XDM Android UX10 + UX11 implementation report

Date: 2026-09-09

## UX10 — Developer diagnostics and workspace refinement

- Completed diagnostic runs now show an explicit run outcome instead of a success-like progress bar.
- Failed/warning checks surface a concise first-line explanation and recommended action before raw detail.
- Error codes, full multiline summaries, detail maps, and internal group identifiers live behind **Technical details**.
- Individual technical results can be copied as a redacted report.
- Roadmap phase-number prose was removed from runtime Developer Center cards while preserving the underlying diagnostic planners and historical contract evidence.
- Developer status labels were humanized (for example redaction safety, cleanup readiness, and ready-to-launch counts).
- Release readiness remains isolated to Validation and release.

## UX11 — Browser integration and external-surface polish

- Browser Integration is product-first: **Firefox extension**, Connected/Needs attention, **Test connection**, and **Install / Update**.
- Scheme, contract, detector, checksum, variant, and bridge diagnostics are hidden behind **Technical details**.
- SAF export-folder identifiers are rendered as human-readable storage paths when Android exposes a tree document id.
- Extension theme has a compact preview.
- Optional **Regenerate when app theme changes** is persisted in DataStore and only runs after an existing verified package and retained export folder exist.
- Main Settings no longer leads with SHA-256/package internals.
- External Tools gets a concise integration-health summary with direct Termux check/open actions.

## Migration and safety

- No Room schema change (schema remains 21).
- No new top-level app route.
- No automatic upload.
- Auto-regeneration is opt-in and limited to an already configured browser-extension export workflow.
- This is an intermediate overlay; Gradle/lint/full validation is intentionally deferred to the final roadmap overlay.
