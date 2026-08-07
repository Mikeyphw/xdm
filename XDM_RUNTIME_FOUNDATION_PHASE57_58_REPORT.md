# XDM Runtime Foundation Phases 57–58 r2 Promise Closure

## Baseline

This cumulative overlay targets the current landed Phase 55–56 tree reconstructed from `xdm-20260807-110547.tar.gz`, Phase 55–56 r2, and the manual validation fixes that produced **445 passing Android tests**. It supersedes the first Phase 57–58 overlay.

## Promise audit findings

The first Phase 57–58 artifact was not a complete closure. A second implementation audit found these gaps:

1. SAF/MediaStore could still fail while opening or creating the destination before the publication exception wrapper began.
2. Native `RecoveryRequired` final-save state was displayed as retryable, but the execution runtime could observe it as a run-end state without invoking a finalization-only backend retry.
3. Final-save recovery had no explicit `Retry save` user action in the main action planner / media queued row.
4. Manual/external inspection and Check again still had unguarded or silent branches.
5. A successful native destination commit could be mislabeled failed if checkpoint cleanup subsequently threw.
6. A successful aria2 publication, or a recoverable aria2 publication failure, could have its truthful state obscured by a later mapping-metadata write failure.
7. Firefox handoff authentication/planning could throw before visible intake feedback was published.

## Phase 57 closure

- `DestinationPublicationException` is the final-save failure boundary and records whether completed staging survives.
- SAF/MediaStore open/create, provider copy/finalize, and destination size verification failures are mapped into recoverable publication failure.
- Incomplete provider output is rolled back best-effort while app-private completed staging remains available for retry.
- Direct filesystem publication attempts to restore the just-moved file back to staging when a new target fails post-move verification. Existing pre-publication targets are never blindly overwritten during rollback.
- Native backend exposes a finalization-only retry path. It calls `PreparedDestination.promote()` directly and does not re-probe or re-download the origin.
- Regression coverage shuts down the local HTTP server before Retry save and still requires successful completion.
- Native post-commit checkpoint cleanup is best-effort so a saved file cannot become `Failed` solely because stale checkpoint cleanup failed.
- aria2 keeps finalization failures as `RecoveryRequired` with `DESTINATION_PUBLICATION`, and mapping bookkeeping after publication is best-effort so it cannot replace the truthful saved/recoverable state.
- `DownloadActionPlanner` presents `Retry save` as the primary action for final-save recovery, and Media recently-queued rows expose the same action.
- `content://` remains ContentResolver-backed; no `File(uri.path)` coercion is introduced.

## Phase 58 closure

All user-triggered media intake paths now publish explicit state rather than disappearing silently:

- pasted page/media URL
- shared page text / URL
- Firefox extension handoff authentication and candidate planning
- batch input
- manual media inspection
- external media review
- playlist `Check again`

The shared feedback model exposes `Working`, `Found`, `NoMediaFound`, `NeedsBrowserCapture`, `AuthenticationRequired`, `Unsupported`, and `Failed`. Titles, details, and diagnostics pass through `BrowserBridgeDiagnosticsRedactor` and are bounded before reaching UI state.

Static page probes still intentionally do not execute page JavaScript; when that boundary explains an empty result, the UI recommends browser capture rather than pretending nothing happened.

## Warning cleanup

The Phase 55–56 test uses `System.getProperty("user.dir") ?: "."`, eliminating the nullable Java-platform-type warning previously reported by Devtool.

## Schema and product boundaries

- Room schema remains **17**.
- No top-level route was added.
- No DRM bypass behavior was added.
- Firefox remains the browser observation surface; the deeper capture-session protocol belongs to later roadmap phases.

### r3 navigation regression hotfix
- Restores `navigate(AppRoute.Media)` after successful explicit external/manual media inspection.
- Preserves Phase 58 visible intake feedback while retaining the pre-existing Phase 3 resolver navigation contract.
- Adds artifact validation so successful media review seeding cannot become a dead-end screen again.

