# XDM Android XAR13 — Downloads truthful actions and fresh-redownload semantics

Merged roadmap position: **overlay 4 of 8**. Android roadmap position: **XAR13, overlay 13 of 17**.

## Scope
XAR13 closes the 21 S13 Downloads UI/action findings by making visible actions execute against durable current state rather than stale Compose snapshots.

## Implemented contracts
- Download rows now show indeterminate progress for Native HLS/media phases with no trustworthy fraction instead of looking frozen.
- Verification and checksum evidence shown in Downloads is filtered to the current `attemptGeneration`; older evidence can no longer label a newer attempt.
- Completed state is only presented as ready when committed artifact URI/generation/byte metadata matches the current attempt.
- Downloads rows retain backend/owner labels so the UI explains Native/Native HLS/Aria2 ownership instead of hiding the backend.
- Start Now reloads the current row and derives queue-policy override from durable current state.
- Resume, pause, and bulk actions reload current rows before dispatching runtime work.
- Delete/restart routes Native HLS-owned active work through the Native HLS manager instead of retiring only generic transfer state.
- Archive selected refuses to hide current active rows and logs per-item retention instead of silently masking live work.
- Replace source URL saves the current row first; the exact handoff is rebound only after persistence accepts the mutation.
- Fresh redownload clones or creates the exact request handoff before queueing the replacement; failed persistence forgets the provisional handoff.
- Exact-URL approvals are preserved only when the cloned handoff targets the same exact URL.
- Tag chips now support unassign; mixed selections assign missing rows first and a fully selected tag removes it from all selected rows.
- The batch action planner now exposes only actions implemented by the Organize sheet.

## Canonical findings closed
`S13-01`, `S13-02`, `S13-04`, `S13-05`, `S13-07`, `S13-08`, `S13-09`, `S13-10`, `S13-11`, `S13-12`, `S13-13`, `S13-14`, `S13-15`, `DS7-S13-01`, `DS7-S13-02`, `DS7-S13-03`, `DS7-S13-04`, `DS7-S13-05`, `DS7-S13-06`, `RERUN67-S13-01`, `RERUN7R-S13-01`.

## Firefox extension invariant
XAR13 does not modify `app/XDM.Android/browser-extension/src/main/extension/xdm-firefox`. The XFE01 Android-owned Firefox extension remains canonical.

## Validation
- `tools/validate-xar13-downloads-truthful-actions.py`
- Gradle task: `verifyXar13DownloadsTruthfulActions`
- Final gate inclusion: `tools/run-final-release-gate.sh`

Intermediate apply mode remains `--no-validate`; the final release gates execute the accumulated validation.
