# XDM Desktop REM05 — Downloads UX, admission preview, history actions, and UI performance

Date: 2026-09-13
Target: `xdm_modern`
Depends on: REM02, REM03, REM04
Scope: 15 frozen REM05 findings

## Implemented remediation

- Removed synchronous `File.Exists` probes from high-frequency progress snapshot application and from filtered-list rebuilding. Completed-file presence now refreshes asynchronously on completion/destination changes and on explicit organization refresh.
- Progress-only snapshots update existing row view-models in place instead of rebuilding `FilteredDownloads`; removal also releases timeline/state/presence bookkeeping.
- Added lossless URL-token parsing so invalid, unsupported, and failed batch members remain in the Add form for correction; request metadata clears only after the whole submitted batch has been processed successfully.
- Corrected source identity to normalize scheme/host/default ports while comparing case-sensitive path/query data ordinally.
- Added shared auto-category routing with compound-extension suffix matching and category destination propagation to Add and browser-capture admission.
- Added engine-owned single/batch admission preview using the same filename sanitization, destination rules, duplicate behavior, active destination ownership, filesystem state, and sequential batch reservation semantics as actual admission.
- Queued downloads are pausable in the desktop UI, matching engine capability.
- History-only removal is now terminal-state-only in both UI and engine; live transfers are never cancelled by a history-cleanup action.
- Dropped-file processing reports parse/import outcomes per file, preserves errors, distinguishes unsupported inputs, and does not count empty text/shortcut imports as success.
- Bulk pause/resume/cancel/remove operations isolate expected per-item failures and report exact succeeded/skipped/failed counts.
- Added platform-aware destination-path identity and routed admission, auto-rename, relocation/relink, preview, and batch reservations through OS-appropriate case semantics.
- Settings retention editor limits now match model normalization (1–3650 days; 100–100000 entries).
- Download-list import distinguishes newly created downloads from duplicate-focus results by returned ID.
- Add/drop copy now consistently advertises HTTP, HTTPS, FTP, and FTPS.

## Regression coverage

- Parser tests cover case-distinct path/query identities and rejected-token preservation.
- Category tests cover compound-extension routing/category destination and platform path identity.
- Engine tests cover terminal-only history removal, batch preview reservation, and case-distinct URL admission.
- App tests cover partial bulk failure continuation/result accounting.

## Validation

- Localization JSON parses successfully.
- Modified Avalonia XAML parses successfully.
- Changed C# sources pass delimiter/static consistency checks in the artifact build environment.
- The artifact build environment does not provide the .NET SDK; compilation/xUnit execution remains deferred under the campaign's intermediate `--no-validate` policy.
